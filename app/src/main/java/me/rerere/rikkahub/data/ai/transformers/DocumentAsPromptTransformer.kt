package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.source.document.LocalDocumentSource
import me.rerere.rikkahub.data.ai.tools.documentRouteId

/**
 * Adds a compact document preview to the prompt while preserving the original attachment part.
 * Large documents are no longer dumped wholesale into every provider request; the model can use
 * `documents_list` / `document_read` for bounded windows through SourceToolRouter.
 */
object DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = withContext(Dispatchers.IO) {
        messages.map { message ->
            message.copy(
                parts = message.parts.toMutableList().apply {
                    val documents = filterIsInstance<UIMessagePart.Document>()
                    documents.forEach { document ->
                        val prompt = runCatching {
                            val preview = LocalDocumentSource.promptPreview(document)
                            val path = resolveWorkspacePath(document)
                            val pathAttr = path?.let { " path=\"$it\"" } ?: ""
                            val documentId = documentRouteId(document)
                            buildString {
                                append("<UploadFile name=\"")
                                append(document.fileName)
                                append("\" document_id=\"")
                                append(documentId)
                                append("\"")
                                append(pathAttr)
                                append(" total_lines=\"")
                                append(preview.totalLines)
                                append("\" total_characters=\"")
                                append(preview.totalCharacters)
                                appendLine("\">")
                                appendLine("```")
                                appendLine(preview.content)
                                appendLine("```")
                                if (preview.truncated) {
                                    appendLine("[Preview only. Use document_read with document_id=$documentId for additional line windows.]")
                                }
                                append("</UploadFile>")
                            }
                        }.getOrElse {
                            "<UploadFile name=\"${document.fileName}\">[ERROR, failed to read file: ${it.message}]</UploadFile>"
                        }
                        add(0, UIMessagePart.Text(prompt))
                    }
                }
            )
        }
    }

    // Uploaded files are mounted into workspaces at /upload. Preserve this hint so an agent with
    // workspace tools can still manipulate the original binary file when appropriate.
    private fun resolveWorkspacePath(document: UIMessagePart.Document): String? {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull() ?: return null
        if (file.parentFile?.name != "upload") return null
        return "/upload/${file.name}"
    }
}
