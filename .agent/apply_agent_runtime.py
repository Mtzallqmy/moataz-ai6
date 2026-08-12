from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 exact match, found {count}")
    return text.replace(old, new, 1)


chat_path = Path("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")
chat = chat_path.read_text()

chat = replace_once(
    chat,
    "import me.rerere.rikkahub.data.ai.GenerationHandler\n",
    "import me.rerere.rikkahub.data.ai.GenerationHandler\n"
    "import me.rerere.rikkahub.data.ai.runtime.AgentRuntime\n"
    "import me.rerere.rikkahub.data.ai.runtime.AgentRuntimeRequest\n"
    "import me.rerere.rikkahub.data.ai.runtime.AgentRuntimeState\n"
    "import me.rerere.rikkahub.data.ai.runtime.InvalidMcpServerNameException\n",
    "runtime imports",
)

chat = replace_once(
    chat,
    "    private val localTools: LocalTools,\n"
    "    private val sourceToolRouter: SourceToolRouter,\n"
    "    val mcpManager: McpManager,\n"
    "    private val filesManager: FilesManager,\n"
    "    private val skillManager: SkillManager,\n",
    "    private val localTools: LocalTools,\n"
    "    private val agentRuntime: AgentRuntime,\n"
    "    val mcpManager: McpManager,\n"
    "    private val filesManager: FilesManager,\n",
    "ChatService constructor",
)

chat = replace_once(
    chat,
    "        sessions.values.forEach { it.cleanup() }\n"
    "        sessions.clear()\n",
    "        sessions.values.forEach { it.cleanup() }\n"
    "        sessions.keys.forEach(agentRuntime::clear)\n"
    "        sessions.clear()\n",
    "runtime cleanup",
)

chat = replace_once(
    chat,
    "    private val _generationDoneFlow = MutableSharedFlow<Uuid>()\n"
    "    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()\n",
    "    private val _generationDoneFlow = MutableSharedFlow<Uuid>()\n"
    "    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()\n\n"
    "    fun getAgentRuntimeState(conversationId: Uuid): StateFlow<AgentRuntimeState> =\n"
    "        agentRuntime.state(conversationId)\n",
    "runtime state exposure",
)

chat = replace_once(
    chat,
    "            generationHandler.generateText(\n",
    "            val runtimePlan = try {\n"
    "                agentRuntime.prepareTools(\n"
    "                    AgentRuntimeRequest(\n"
    "                        conversationId = conversationId,\n"
    "                        settings = settings,\n"
    "                        assistant = assistant,\n"
    "                        conversation = conversation,\n"
    "                        messages = messagesForGeneration,\n"
    "                        model = model,\n"
    "                    )\n"
    "                )\n"
    "            } catch (error: InvalidMcpServerNameException) {\n"
    "                throw IllegalStateException(\n"
    "                    context.getString(\n"
    "                        R.string.error_mcp_invalid_server_name,\n"
    "                        error.invalidNames.joinToString(\", \"),\n"
    "                    ),\n"
    "                    error,\n"
    "                )\n"
    "            }\n\n"
    "            generationHandler.generateText(\n",
    "runtime tool plan",
)

chat = replace_once(
    chat,
    "                onAfterToolExecution = { generatedMessages ->\n"
    "                    if (messageRange != null || !settings.enableAutoCompaction) {\n",
    "                onAfterToolExecution = { generatedMessages ->\n"
    "                    agentRuntime.markVerifying(conversationId)\n"
    "                    if (messageRange != null || !settings.enableAutoCompaction) {\n",
    "runtime verifying transition",
)

chat = replace_once(
    chat,
    "                onBeforeModelRequest = {\n"
    "                    awaitForegroundWorkReady()\n"
    "                },\n",
    "                onBeforeModelRequest = {\n"
    "                    agentRuntime.markExecuting(conversationId)\n"
    "                    awaitForegroundWorkReady()\n"
    "                },\n",
    "runtime executing transition",
)

pattern = re.compile(
    r"                tools = buildList \{\n.*?\n                \},\n            \)\.onCompletion",
    re.DOTALL,
)
chat, count = pattern.subn(
    "                tools = runtimePlan.tools,\n            ).onCompletion",
    chat,
    count=1,
)
if count != 1:
    raise SystemExit(f"tool assembly extraction: expected 1 match, found {count}")

chat = replace_once(
    chat,
    "        generationResult.onFailure {\n"
    "            // 取消 Live Update 通知\n",
    "        generationResult.onFailure {\n"
    "            if (it is CancellationException) {\n"
    "                agentRuntime.markCancelled(conversationId)\n"
    "            } else {\n"
    "                agentRuntime.markFailed(conversationId, it)\n"
    "            }\n"
    "            // 取消 Live Update 通知\n",
    "runtime failure transition",
)

chat = replace_once(
    chat,
    "        }.onSuccess {\n"
    "            val finalConversation = getConversationFlow(conversationId).value\n"
    "            saveConversation(conversationId, finalConversation)\n",
    "        }.onSuccess {\n"
    "            val finalConversation = getConversationFlow(conversationId).value\n"
    "            val waitingForApproval = finalConversation.currentMessages\n"
    "                .asSequence()\n"
    "                .flatMap { it.parts.asSequence() }\n"
    "                .filterIsInstance<UIMessagePart.Tool>()\n"
    "                .any { it.isPending }\n"
    "            if (waitingForApproval) {\n"
    "                agentRuntime.markWaitingApproval(conversationId)\n"
    "            } else {\n"
    "                agentRuntime.markCompleted(conversationId)\n"
    "            }\n"
    "            saveConversation(conversationId, finalConversation)\n",
    "runtime success transition",
)

helper_pattern = re.compile(
    r"\n    private suspend fun createWorkspaceToolsIfReady\(workspaceId: String\?, cwd: String\? = null\): List<Tool> \{.*?\n    \}\n\n    // ---- 检查无效消息 ----",
    re.DOTALL,
)
chat, count = helper_pattern.subn("\n\n    // ---- 检查无效消息 ----", chat, count=1)
if count != 1:
    raise SystemExit(f"workspace helper extraction: expected 1 match, found {count}")

chat_path.write_text(chat)

app_path = Path("app/src/main/java/me/rerere/rikkahub/di/AppModule.kt")
app = app_path.read_text()

app = replace_once(
    app,
    "    single {\n"
    "        ChatService(\n",
    "    single {\n"
    "        me.rerere.rikkahub.data.ai.runtime.AgentRuntime(\n"
    "            localTools = get(),\n"
    "            sourceToolRouter = get(),\n"
    "            mcpManager = get(),\n"
    "            skillManager = get(),\n"
    "            workspaceRepository = get(),\n"
    "        )\n"
    "    }\n\n"
    "    single {\n"
    "        ChatService(\n",
    "AgentRuntime DI",
)

app = replace_once(
    app,
    "            localTools = get(),\n"
    "            sourceToolRouter = get(),\n"
    "            mcpManager = get(),\n"
    "            filesManager = get(),\n"
    "            skillManager = get(),\n",
    "            localTools = get(),\n"
    "            agentRuntime = get(),\n"
    "            mcpManager = get(),\n"
    "            filesManager = get(),\n",
    "ChatService DI",
)

app_path.write_text(app)
