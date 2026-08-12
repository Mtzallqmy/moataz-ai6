package me.rerere.rikkahub.data.source.document

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.document.XlsxParser
import java.io.File
import java.net.URI
import java.util.LinkedHashMap

/**
 * Bounded, cached reader for local conversation documents.
 *
 * Parsing happens below the model/tool boundary and is cached by stable file facts. Large files are
 * never injected wholesale into prompts: callers receive a small preview, bounded line windows, or
 * ranked text-search hits. Local paths remain private to this layer.
 */
internal object LocalDocumentSource {
    private val cache = object : LinkedHashMap<String, ParsedDocument>(CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedDocument>?): Boolean =
            size > CACHE_ENTRIES
    }

    suspend fun inspect(document: UIMessagePart.Document): LocalDocumentInspection = withContext(Dispatchers.IO) {
        val file = resolveFile(document)
        val parsed = parseCached(document, file)
        LocalDocumentInspection(
            fileName = document.fileName,
            mime = document.mime,
            size = file.length(),
            totalLines = parsed.lines.size,
            totalCharacters = parsed.text.length,
            textAvailable = parsed.text.isNotBlank(),
            preview = parsed.text.take(PROMPT_PREVIEW_CHARS),
            previewTruncated = parsed.text.length > PROMPT_PREVIEW_CHARS,
        )
    }

    suspend fun readWindow(
        document: UIMessagePart.Document,
        startLine: Int? = null,
        endLine: Int? = null,
    ): LocalDocumentWindow = withContext(Dispatchers.IO) {
        val file = resolveFile(document)
        val parsed = parseCached(document, file)
        val total = parsed.lines.size
        if (total == 0) {
            return@withContext LocalDocumentWindow(
                fileName = document.fileName,
                mime = document.mime,
                size = file.length(),
                totalLines = 0,
                startLine = 0,
                endLine = 0,
                truncated = false,
                text = "",
            )
        }

        val start = (startLine ?: 1).coerceAtLeast(1)
        require(start <= total) { "start_line exceeds document length" }
        val requestedEnd = endLine ?: (start + DEFAULT_WINDOW_LINES - 1)
        require(requestedEnd >= start) { "end_line must be greater than or equal to start_line" }
        val end = minOf(requestedEnd, start + MAX_WINDOW_LINES - 1, total)
        LocalDocumentWindow(
            fileName = document.fileName,
            mime = document.mime,
            size = file.length(),
            totalLines = total,
            startLine = start,
            endLine = end,
            truncated = end < total,
            text = parsed.lines.subList(start - 1, end).joinToString("\n"),
        )
    }

    suspend fun search(
        document: UIMessagePart.Document,
        query: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): LocalDocumentSearch = withContext(Dispatchers.IO) {
        val cleanQuery = normalizeForSearch(query)
        require(cleanQuery.length >= 2) { "query must contain at least 2 searchable characters" }
        val file = resolveFile(document)
        val parsed = parseCached(document, file)
        val boundedLimit = limit.coerceIn(1, MAX_SEARCH_LIMIT)
        val hits = ArrayList<LocalDocumentSearchHit>(boundedLimit)

        parsed.lines.forEachIndexed { index, line ->
            if (hits.size >= boundedLimit) return@forEachIndexed
            if (normalizeForSearch(line).contains(cleanQuery)) {
                val from = (index - SEARCH_CONTEXT_LINES).coerceAtLeast(0)
                val toExclusive = (index + SEARCH_CONTEXT_LINES + 1).coerceAtMost(parsed.lines.size)
                hits += LocalDocumentSearchHit(
                    line = index + 1,
                    excerpt = parsed.lines.subList(from, toExclusive).joinToString("\n"),
                )
            }
        }

        LocalDocumentSearch(
            fileName = document.fileName,
            mime = document.mime,
            totalLines = parsed.lines.size,
            query = query,
            hits = hits,
            truncated = hits.size >= boundedLimit,
        )
    }

    suspend fun promptPreview(document: UIMessagePart.Document): LocalDocumentPrompt = withContext(Dispatchers.IO) {
        val file = resolveFile(document)
        val parsed = parseCached(document, file)
        val fullFits = parsed.text.length <= INLINE_FULL_TEXT_CHARS
        LocalDocumentPrompt(
            content = if (fullFits) parsed.text else parsed.text.take(PROMPT_PREVIEW_CHARS),
            totalLines = parsed.lines.size,
            totalCharacters = parsed.text.length,
            truncated = !fullFits,
        )
    }

    private fun resolveFile(document: UIMessagePart.Document): File {
        val file = runCatching {
            val uri = URI(document.url)
            require(uri.scheme.equals("file", ignoreCase = true)) { "Only local file URIs are supported" }
            File(uri)
        }.getOrNull() ?: throw IllegalArgumentException("Invalid local file URI: ${document.fileName}")
        require(file.exists() && file.isFile) { "File not found: ${document.fileName}" }
        require(file.length() <= MAX_SOURCE_FILE_BYTES) {
            "File is too large to parse safely (${file.length()} bytes; max $MAX_SOURCE_FILE_BYTES)"
        }
        return file
    }

    private fun parseCached(document: UIMessagePart.Document, file: File): ParsedDocument {
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}|${document.mime}"
        synchronized(cache) { cache[key]?.let { return it } }
        val text = extractText(document, file)
            .replace("\u0000", "")
            .let { if (it.length > MAX_PARSED_CHARS) it.take(MAX_PARSED_CHARS) + "\n[Document text truncated]" else it }
        val lines = if (text.isEmpty()) emptyList() else text.lineSequence().toList()
        return ParsedDocument(text = text, lines = lines).also { parsed ->
            synchronized(cache) { cache[key] = parsed }
        }
    }

    private fun extractText(document: UIMessagePart.Document, file: File): String {
        val extension = document.fileName.substringAfterLast('.', "").lowercase()
        return when {
            document.mime == "application/pdf" || extension == "pdf" -> PdfParser.parserPdf(file)
            document.mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || extension == "docx" -> DocxParser.parse(file)
            document.mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" || extension == "pptx" -> PptxParser.parse(file)
            document.mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" || extension == "xlsx" -> XlsxParser.parse(file)
            document.mime == "application/epub+zip" || extension == "epub" -> EpubParser.parse(file)
            document.mime == "application/msword" || extension == "doc" -> "[Legacy .doc files are not supported for direct text extraction]"
            document.mime == "application/vnd.ms-excel" || extension == "xls" -> "[Legacy .xls files are not supported for direct text extraction; save as .xlsx or .csv]"
            isTextDocument(document.mime, extension) -> file.readText(Charsets.UTF_8)
            else -> throw IllegalArgumentException("Unsupported document format: ${document.mime.ifBlank { extension }}")
        }
    }

    private fun isTextDocument(mime: String, extension: String): Boolean =
        mime.startsWith("text/") || mime in TEXT_MIME_TYPES || extension in TEXT_EXTENSIONS

    private fun normalizeForSearch(value: String): String = buildString(value.length) {
        value.lowercase().forEach { char ->
            when (char) {
                '\u0640' -> Unit
                in '\u064B'..'\u065F', '\u0670' -> Unit
                'أ', 'إ', 'آ', 'ٱ' -> append('ا')
                'ى' -> append('ي')
                else -> append(char)
            }
        }
    }.trim()

    private data class ParsedDocument(val text: String, val lines: List<String>)

    companion object {
        const val DEFAULT_WINDOW_LINES = 300
        const val MAX_WINDOW_LINES = 500
        const val PROMPT_PREVIEW_CHARS = 12_000
        const val INLINE_FULL_TEXT_CHARS = 24_000
        const val MAX_PARSED_CHARS = 2_000_000
        const val MAX_SOURCE_FILE_BYTES = 64L * 1024 * 1024
        const val DEFAULT_SEARCH_LIMIT = 8
        const val MAX_SEARCH_LIMIT = 20
        private const val SEARCH_CONTEXT_LINES = 1
        private const val CACHE_ENTRIES = 12
        private val TEXT_MIME_TYPES = setOf(
            "application/json",
            "application/ld+json",
            "application/xml",
            "application/javascript",
            "application/x-javascript",
            "application/x-yaml",
            "application/toml",
        )
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "toml",
            "kt", "kts", "java", "js", "jsx", "ts", "tsx", "py", "rb", "go", "rs", "swift", "c", "h",
            "cpp", "hpp", "cs", "php", "sh", "bash", "zsh", "fish", "sql", "html", "htm", "css", "scss",
            "gradle", "properties", "ini", "conf", "log",
        )
    }
}

internal data class LocalDocumentInspection(
    val fileName: String,
    val mime: String,
    val size: Long,
    val totalLines: Int,
    val totalCharacters: Int,
    val textAvailable: Boolean,
    val preview: String,
    val previewTruncated: Boolean,
)

internal data class LocalDocumentWindow(
    val fileName: String,
    val mime: String,
    val size: Long,
    val totalLines: Int,
    val startLine: Int,
    val endLine: Int,
    val truncated: Boolean,
    val text: String,
)

internal data class LocalDocumentSearchHit(
    val line: Int,
    val excerpt: String,
)

internal data class LocalDocumentSearch(
    val fileName: String,
    val mime: String,
    val totalLines: Int,
    val query: String,
    val hits: List<LocalDocumentSearchHit>,
    val truncated: Boolean,
)

internal data class LocalDocumentPrompt(
    val content: String,
    val totalLines: Int,
    val totalCharacters: Int,
    val truncated: Boolean,
)
