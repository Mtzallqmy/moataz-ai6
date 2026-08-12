package me.rerere.rikkahub.data.ai.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.SourceToolRouter
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Runtime boundary for one agent turn.
 *
 * ChatService still owns conversation persistence and UI notifications. AgentRuntime owns the
 * execution-facing plan: which tools are exposed for this turn and the observable runtime stage.
 * This keeps providers, sources, local tools, workspaces, skills, and MCP behind one seam so future
 * model routing and budgets can evolve without growing ChatService again.
 */
class AgentRuntime internal constructor(
    private val localTools: LocalTools,
    private val sourceToolRouter: SourceToolRouter,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
) {
    private val states = ConcurrentHashMap<Uuid, MutableStateFlow<AgentRuntimeState>>()

    fun state(conversationId: Uuid): StateFlow<AgentRuntimeState> =
        stateFlow(conversationId).asStateFlow()

    suspend fun prepareTools(request: AgentRuntimeRequest): AgentToolPlan {
        transition(request.conversationId, AgentRuntimeStage.PREPARING, toolCount = 0)
        return try {
            val web = if (request.assistant.enableWebSearch) {
                createSearchTools(request.settings)
            } else {
                emptyList()
            }
            val sources = sourceToolRouter.toolsFor(request.messages)
            val invocationContext = ToolInvocationContext(
                callerAssistantId = request.assistant.id.toString(),
                callerConversationId = request.conversationId.toString(),
                isHeadless = HeadlessConversations.isHeadless(request.conversationId),
                modelCanSeeImages = Modality.IMAGE in request.model.inputModalities,
            )
            val local = localTools.getTools(request.assistant.localTools, invocationContext)
            val workspace = createWorkspaceToolsIfReady(
                workspaceId = request.assistant.workspaceId?.toString(),
                cwd = request.conversation.workspaceCwd,
            )
            val skills = if (request.assistant.enabledSkills.isNotEmpty()) {
                createSkillTools(
                    enabledSkills = request.assistant.enabledSkills,
                    allSkills = skillManager.listSkills(),
                    skillManager = skillManager,
                )
            } else {
                emptyList()
            }
            val mcp = createMcpTools()
            val tools = buildList {
                addAll(web)
                addAll(sources)
                addAll(local)
                addAll(workspace)
                addAll(skills)
                addAll(mcp)
            }
            val plan = AgentToolPlan(
                tools = tools,
                counts = AgentToolCounts(
                    web = web.size,
                    sources = sources.size,
                    local = local.size,
                    workspace = workspace.size,
                    skills = skills.size,
                    mcp = mcp.size,
                ),
            )
            transition(
                request.conversationId,
                AgentRuntimeStage.PLANNING,
                toolCount = tools.size,
            )
            plan
        } catch (error: Throwable) {
            markFailed(request.conversationId, error)
            throw error
        }
    }

    fun markExecuting(conversationId: Uuid) =
        transition(conversationId, AgentRuntimeStage.EXECUTING)

    fun markVerifying(conversationId: Uuid) =
        transition(conversationId, AgentRuntimeStage.VERIFYING)

    fun markWaitingApproval(conversationId: Uuid) =
        transition(conversationId, AgentRuntimeStage.WAITING_APPROVAL)

    fun markCompleted(conversationId: Uuid) =
        transition(conversationId, AgentRuntimeStage.COMPLETED)

    fun markCancelled(conversationId: Uuid) =
        transition(conversationId, AgentRuntimeStage.CANCELLED)

    fun markFailed(conversationId: Uuid, error: Throwable) =
        transition(
            conversationId,
            AgentRuntimeStage.FAILED,
            error = error.message ?: error::class.simpleName ?: "Unknown runtime failure",
        )

    fun clear(conversationId: Uuid) {
        states.remove(conversationId)
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String?): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return emptyList()
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }

    private suspend fun createMcpTools(): List<Tool> {
        val available = mcpManager.getAllAvailableTools()
        val invalidNames = available
            .map { it.second }
            .distinct()
            .filter { name ->
                name.isEmpty() || !name.all { char ->
                    char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'
                }
            }
        if (invalidNames.isNotEmpty()) throw InvalidMcpServerNameException(invalidNames)

        return available.map { (serverId, serverName, tool) ->
            val serverSlug = serverId.toString().take(8).replace("-", "")
            val modelFacingName = "mcp__${serverSlug}_${serverName}__${tool.name}"
            Tool(
                name = modelFacingName,
                description = tool.description ?: "",
                parameters = { tool.inputSchema },
                needsApproval = {
                    ToolApprovalDefaults.requiresApproval(modelFacingName) || tool.needsApproval
                },
                execute = { args ->
                    mcpManager.callTool(serverId, tool.name, args.jsonObject)
                },
            )
        }
    }

    private fun stateFlow(conversationId: Uuid): MutableStateFlow<AgentRuntimeState> =
        states.getOrPut(conversationId) {
            MutableStateFlow(AgentRuntimeState(conversationId = conversationId))
        }

    private fun transition(
        conversationId: Uuid,
        stage: AgentRuntimeStage,
        toolCount: Int? = null,
        error: String? = null,
    ) {
        val flow = stateFlow(conversationId)
        val previous = flow.value
        val now = System.currentTimeMillis()
        val startedAt = if (stage == AgentRuntimeStage.PREPARING || previous.startedAtMs == null) {
            now
        } else {
            previous.startedAtMs
        }
        flow.value = previous.copy(
            stage = stage,
            toolCount = toolCount ?: previous.toolCount,
            startedAtMs = startedAt,
            updatedAtMs = now,
            completedAtMs = if (stage.isTerminal) now else null,
            error = error,
        )
    }
}

data class AgentRuntimeRequest(
    val conversationId: Uuid,
    val settings: Settings,
    val assistant: Assistant,
    val conversation: Conversation,
    val messages: List<UIMessage>,
    val model: Model,
)

data class AgentToolPlan(
    val tools: List<Tool>,
    val counts: AgentToolCounts,
)

data class AgentToolCounts(
    val web: Int = 0,
    val sources: Int = 0,
    val local: Int = 0,
    val workspace: Int = 0,
    val skills: Int = 0,
    val mcp: Int = 0,
) {
    val total: Int
        get() = web + sources + local + workspace + skills + mcp
}

data class AgentRuntimeState(
    val conversationId: Uuid,
    val stage: AgentRuntimeStage = AgentRuntimeStage.IDLE,
    val toolCount: Int = 0,
    val startedAtMs: Long? = null,
    val updatedAtMs: Long? = null,
    val completedAtMs: Long? = null,
    val error: String? = null,
)

enum class AgentRuntimeStage(val isTerminal: Boolean = false) {
    IDLE,
    PREPARING,
    PLANNING,
    EXECUTING,
    VERIFYING,
    WAITING_APPROVAL,
    COMPLETED(true),
    FAILED(true),
    CANCELLED(true),
}

class InvalidMcpServerNameException(
    val invalidNames: List<String>,
) : IllegalStateException("Invalid MCP server names: ${invalidNames.joinToString(", ")}")
