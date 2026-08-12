package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.source.document.LocalDocumentSource
import java.security.MessageDigest

internal data class RoutedDocument(
    val id: String,
    val part: UIMessagePart.Document,
)

internal fun routedDocuments(messages: List<UIMessage>): List<RoutedDocument> = messages
    .takeLast(12)
    .flatMap { it.parts }
    .filterIsInstance<UIMessagePart.Document>()
    .distinctBy { it.url }
    .map { part -> RoutedDocument(id = documentRouteId(part), part = part) }

internal fun documentRouteId(part: UIMessagePart.Document): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(part.url.toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "doc_$digest"
}

internal fun createLocalDocumentTools(
    source: LocalDocumentSource,
    documents: List<RoutedDocument>,
): List<Tool> {
    if (documents.isEmpty()) return emptyList()
    val byId = documents.associateBy { it.id }
    return listOf(
        Tool(
            name = "documents_list",
            description = "List documents attached in the recent conversation with stable document_id values. Read-only. Use document_read for bounded text windows.",
            parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
            execute = {
                sourceToolResult {
                    buildJsonObject {
                        put("documents", buildJsonArray {
                            documents.forEach { routed ->
                                val inspection = source.inspect(routed.part)
                                add(buildJsonObject {
                                    put("document_id", routed.id)
                                    put("file_name", inspection.fileName)
                                    put("mime", inspection.mime)
                                    put("size", inspection.size)
                                    put("total_lines", inspection.totalLines)
                                    put("total_characters", inspection.totalCharacters)
                                    put("text_available", inspection.textAvailable)
                                })
                            }
                        })
                    }
                }
            },
        ),
        Tool(
            name = "document_search",
            description = "Search an attached document and return bounded matching excerpts. Arabic search ignores diacritics, tatweel, and common Alef variants. Read-only.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("document_id", buildJsonObject { put("type", "string"); put("description", "ID returned by documents_list") })
                        put("query", buildJsonObject { put("type", "string"); put("description", "Text to find in the document") })
                        put("limit", buildJsonObject { put("type", "integer"); put("description", "Optional result limit from 1 to 20") })
                    },
                    required = listOf("document_id", "query"),
                )
            },
            execute = { input ->
                sourceToolResult {
                    val obj = input.jsonObject
                    val id = obj["document_id"]?.jsonPrimitive?.content?.trim().orEmpty()
                    val document = byId[id]?.part ?: throw IllegalArgumentException("Unknown document_id '$id'")
                    val query = obj["query"]?.jsonPrimitive?.content?.trim().orEmpty()
                    val result = source.search(
                        document = document,
                        query = query,
                        limit = obj["limit"]?.jsonPrimitive?.intOrNull ?: LocalDocumentSource.DEFAULT_SEARCH_LIMIT,
                    )
                    buildJsonObject {
                        put("document_id", id)
                        put("file_name", result.fileName)
                        put("total_lines", result.totalLines)
                        put("query", result.query)
                        put("truncated", result.truncated)
                        put("hits", buildJsonArray {
                            result.hits.forEach { hit ->
                                add(buildJsonObject {
                                    put("line", hit.line)
                                    put("excerpt", hit.excerpt)
                                })
                            }
                        })
                    }
                }
            },
        ),
        Tool(
            name = "document_read",
            description = "Read a bounded line window from an attached document. Supports PDF, DOCX, PPTX, XLSX, EPUB, CSV, JSON, Markdown, source code, and text. Read-only; use additional windows instead of loading a large document at once.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("document_id", buildJsonObject { put("type", "string"); put("description", "ID returned by documents_list, for example doc_1") })
                        put("start_line", buildJsonObject { put("type", "integer"); put("description", "Optional 1-based first line") })
                        put("end_line", buildJsonObject { put("type", "integer"); put("description", "Optional last line; maximum 500 lines per call") })
                    },
                    required = listOf("document_id"),
                )
            },
            execute = { input ->
                sourceToolResult {
                    val obj = input.jsonObject
                    val id = obj["document_id"]?.jsonPrimitive?.content?.trim().orEmpty()
                    val document = byId[id]?.part ?: throw IllegalArgumentException("Unknown document_id '$id'")
                    val window = source.readWindow(
                        document = document,
                        startLine = obj["start_line"]?.jsonPrimitive?.intOrNull,
                        endLine = obj["end_line"]?.jsonPrimitive?.intOrNull,
                    )
                    buildJsonObject {
                        put("document_id", id)
                        put("file_name", window.fileName)
                        put("mime", window.mime)
                        put("size", window.size)
                        put("total_lines", window.totalLines)
                        put("start_line", window.startLine)
                        put("end_line", window.endLine)
                        put("truncated", window.truncated)
                        put("text", window.text)
                    }
                }
            },
        ),
    )
}

private suspend fun sourceToolResult(block: suspend () -> kotlinx.serialization.json.JsonObject): List<UIMessagePart> = try {
    listOf(UIMessagePart.Text(block().toString()))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    listOf(UIMessagePart.Text(buildJsonObject {
        put("error", "document_source_failed")
        put("detail", e.message?.take(500) ?: e::class.simpleName ?: "unknown error")
    }.toString()))
}
