package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.source.github.GitHubRepositorySource

/**
 * Read-only GitHub tools. Authentication is intentionally absent from every schema: credentials
 * are resolved inside GitHubRepositorySource and therefore never enter model context.
 */
internal fun createGitHubRepositoryTools(source: GitHubRepositorySource): Set<Tool> = setOf(
    Tool(
        name = "github_repo_inspect",
        description = """
            Inspect a GitHub repository from a github.com URL. Read-only. Use this first to learn
            the default branch, top-level structure, and important project files before reading
            individual files. Public repositories require no setup. Never ask for or pass a token
            in tool arguments; authentication is handled privately by the app.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    stringProperty("url", "GitHub repository URL, for example https://github.com/owner/repo")
                    stringProperty("ref", "Optional branch, tag, or commit ref. Defaults to the repository default branch.")
                },
                required = listOf("url"),
            )
        },
        execute = { input ->
            repositoryToolResult {
                val args = input.jsonObject
                val inspection = source.inspect(
                    url = args.requiredString("url"),
                    ref = args.optionalString("ref"),
                )
                buildJsonObject {
                    put("repository", inspection.repository.fullName)
                    put("ref", inspection.ref)
                    put("description", inspection.metadata.description)
                    put("default_branch", inspection.metadata.defaultBranch)
                    put("private", inspection.metadata.isPrivate)
                    put("archived", inspection.metadata.isArchived)
                    put("fork", inspection.metadata.isFork)
                    inspection.metadata.language?.let { put("language", it) }
                    put("total_entries", inspection.totalEntries)
                    put("tree_truncated_by_github", inspection.treeTruncatedByGitHub)
                    put("top_level_paths", buildJsonArray {
                        inspection.topLevelPaths.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    })
                    put("important_files", buildJsonArray {
                        inspection.importantFiles.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    })
                }
            }
        },
    ),
    Tool(
        name = "github_repo_tree",
        description = """
            List a bounded slice of a GitHub repository tree. Read-only. Use prefix to explore one
            directory at a time instead of loading a whole large repository into context.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    stringProperty("url", "GitHub repository URL.")
                    stringProperty("ref", "Optional branch, tag, or commit ref.")
                    stringProperty("prefix", "Optional repository-relative directory or path prefix.")
                    integerProperty("limit", "Maximum entries to return. Default 200; hard maximum 500.")
                },
                required = listOf("url"),
            )
        },
        execute = { input ->
            repositoryToolResult {
                val args = input.jsonObject
                val tree = source.listTree(
                    url = args.requiredString("url"),
                    ref = args.optionalString("ref"),
                    prefix = args.optionalString("prefix"),
                    limit = args["limit"]?.jsonPrimitive?.intOrNull ?: GitHubRepositorySource.DEFAULT_TREE_LIMIT,
                )
                buildJsonObject {
                    put("repository", tree.repository.fullName)
                    put("ref", tree.ref)
                    tree.prefix?.let { put("prefix", it) }
                    put("has_more", tree.hasMore)
                    put("tree_truncated_by_github", tree.treeTruncatedByGitHub)
                    put("entries", buildJsonArray {
                        tree.entries.forEach { entry ->
                            add(buildJsonObject {
                                put("path", entry.path)
                                put("type", entry.type)
                                entry.size?.let { put("size", it) }
                                entry.sha?.let { put("sha", it) }
                            })
                        }
                    })
                }
            }
        },
    ),
    Tool(
        name = "github_file_read",
        description = """
            Read text from one explicit file in a GitHub repository. Read-only. Results are limited
            to a line window so large source files do not flood model context. Binary files and
            files over the safety limit are rejected. Use start_line/end_line to continue reading.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    stringProperty("url", "GitHub repository URL.")
                    stringProperty("path", "Repository-relative file path.")
                    stringProperty("ref", "Optional branch, tag, or commit ref.")
                    integerProperty("start_line", "Optional 1-based first line. Defaults to 1.")
                    integerProperty("end_line", "Optional 1-based final line. At most 500 lines are returned per call.")
                },
                required = listOf("url", "path"),
            )
        },
        execute = { input ->
            repositoryToolResult {
                val args = input.jsonObject
                val file = source.readFile(
                    url = args.requiredString("url"),
                    path = args.requiredString("path"),
                    ref = args.optionalString("ref"),
                    startLine = args["start_line"]?.jsonPrimitive?.intOrNull,
                    endLine = args["end_line"]?.jsonPrimitive?.intOrNull,
                )
                buildJsonObject {
                    put("repository", file.repository.fullName)
                    put("path", file.path)
                    put("ref", file.ref)
                    file.sha?.let { put("sha", it) }
                    put("size", file.size)
                    put("total_lines", file.totalLines)
                    put("start_line", file.startLine)
                    put("end_line", file.endLine)
                    put("truncated", file.truncated)
                    put("text", file.text)
                }
            }
        },
    ),
)

/** Avoid paying the schema/token cost unless the current conversation actually references GitHub. */
internal fun shouldExposeGitHubRepositoryTools(messages: List<UIMessage>): Boolean = messages
    .takeLast(12)
    .asSequence()
    .flatMap { it.parts.asSequence() }
    .filterIsInstance<UIMessagePart.Text>()
    .any { GITHUB_REPOSITORY_URL.containsMatchIn(it.text) }

private val GITHUB_REPOSITORY_URL = Regex(
    pattern = "(?:https://)?github\\.com/[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]+",
    option = RegexOption.IGNORE_CASE,
)

private suspend fun repositoryToolResult(block: suspend () -> JsonObject): List<UIMessagePart> = try {
    listOf(UIMessagePart.Text(block().toString()))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("error", "github_repository_read_failed")
                put("detail", e.message?.take(500) ?: e::class.simpleName ?: "unknown error")
            }.toString()
        )
    )
}

private fun JsonObject.requiredString(name: String): String = this[name]
    ?.jsonPrimitive
    ?.contentOrNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: throw IllegalArgumentException("Missing required argument '$name'")

private fun JsonObject.optionalString(name: String): String? = this[name]
    ?.jsonPrimitive
    ?.contentOrNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

private fun kotlinx.serialization.json.JsonObjectBuilder.stringProperty(name: String, description: String) {
    put(name, buildJsonObject {
        put("type", "string")
        put("description", description)
    })
}

private fun kotlinx.serialization.json.JsonObjectBuilder.integerProperty(name: String, description: String) {
    put(name, buildJsonObject {
        put("type", "integer")
        put("description", description)
    })
}
