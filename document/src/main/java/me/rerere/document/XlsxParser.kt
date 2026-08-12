package me.rerere.document

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/**
 * Lightweight XLSX text extractor with no spreadsheet runtime dependency.
 *
 * It intentionally extracts values rather than reproducing Excel formatting. The result is
 * Markdown-friendly TSV grouped by worksheet, which is substantially cheaper for an LLM to read.
 * Limits protect the app from pathological workbooks and zip-bomb-like entries.
 *
 * SAX is used instead of XmlPullParserFactory so the same parser works in Android runtime and in
 * plain JVM unit tests without relying on a platform-specific XmlPull implementation.
 */
object XlsxParser {
    private const val MAX_SHEETS = 100
    private const val MAX_ROWS_PER_SHEET = 20_000
    private const val MAX_ENTRY_BYTES = 24L * 1024 * 1024
    private const val MAX_OUTPUT_CHARS = 2_000_000

    fun parse(file: File): String = runCatching {
        ZipFile(file).use { zip ->
            val sharedStrings = zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
                requireSafeEntry(entry.size, entry.name)
                zip.getInputStream(entry).use(::parseSharedStrings)
            }.orEmpty()

            val worksheets = zip.entries().toList()
                .filter { it.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                .sortedBy { it.name.substringAfter("sheet").substringBefore(".xml").toIntOrNull() ?: Int.MAX_VALUE }
                .take(MAX_SHEETS)

            if (worksheets.isEmpty()) return@use "No worksheets found in XLSX file"

            val out = StringBuilder()
            worksheets.forEachIndexed { index, entry ->
                if (out.length >= MAX_OUTPUT_CHARS) return@forEachIndexed
                requireSafeEntry(entry.size, entry.name)
                if (out.isNotEmpty()) out.appendLine()
                out.appendLine("## Sheet ${index + 1}")
                out.appendLine()
                zip.getInputStream(entry).use { stream ->
                    parseWorksheet(stream, sharedStrings, out)
                }
            }
            if (worksheets.size >= MAX_SHEETS) {
                out.appendLine("\n[Workbook truncated to $MAX_SHEETS worksheets]")
            }
            if (out.length > MAX_OUTPUT_CHARS) {
                out.setLength(MAX_OUTPUT_CHARS)
                out.append("\n[Workbook text truncated]")
            }
            out.toString().trim()
        }
    }.getOrElse { "Error parsing XLSX file: ${it.message}" }

    private fun requireSafeEntry(size: Long, name: String) {
        require(size < 0 || size <= MAX_ENTRY_BYTES) { "XLSX entry is too large: $name" }
    }

    private fun parseSharedStrings(stream: InputStream): List<String> {
        val handler = SharedStringsHandler()
        parseXml(stream, handler)
        return handler.values
    }

    private fun parseWorksheet(stream: InputStream, sharedStrings: List<String>, out: StringBuilder) {
        val handler = WorksheetHandler(sharedStrings, out)
        try {
            parseXml(stream, handler)
        } catch (_: StopParsing) {
            // Expected bounded early-exit once row/output limits are reached.
        }
        if (handler.rows >= MAX_ROWS_PER_SHEET) {
            out.appendLine("[Worksheet truncated to $MAX_ROWS_PER_SHEET rows]")
        }
    }

    private fun parseXml(stream: InputStream, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val reader = factory.newSAXParser().xmlReader
        runCatching { reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { reader.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
        reader.contentHandler = handler
        reader.parse(InputSource(stream))
    }

    private class SharedStringsHandler : DefaultHandler() {
        val values = mutableListOf<String>()
        private var current: StringBuilder? = null
        private var readingText = false

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (nameOf(localName, qName)) {
                "si" -> current = StringBuilder()
                "t" -> if (current != null) readingText = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (readingText) current?.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (nameOf(localName, qName)) {
                "t" -> readingText = false
                "si" -> {
                    values += current?.toString().orEmpty()
                    current = null
                }
            }
        }
    }

    private class WorksheetHandler(
        private val sharedStrings: List<String>,
        private val out: StringBuilder,
    ) : DefaultHandler() {
        var rows: Int = 0
            private set

        private var cells = linkedMapOf<Int, String>()
        private var cellColumn = 0
        private var cellType: String? = null
        private val value = StringBuilder()
        private val inline = StringBuilder()
        private var readingValue = false
        private var readingInlineText = false

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (nameOf(localName, qName)) {
                "row" -> cells = linkedMapOf()
                "c" -> {
                    cellColumn = columnIndex(attributes?.getValue("r").orEmpty())
                    cellType = attributes?.getValue("t")
                    value.setLength(0)
                    inline.setLength(0)
                }
                "v" -> readingValue = true
                "t" -> if (cellType == "inlineStr") readingInlineText = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            when {
                readingValue -> value.append(ch, start, length)
                readingInlineText -> inline.append(ch, start, length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (nameOf(localName, qName)) {
                "v" -> readingValue = false
                "t" -> readingInlineText = false
                "c" -> cells[cellColumn] = resolveCell(cellType, value.toString(), inline.toString(), sharedStrings)
                "row" -> {
                    appendRow(cells, out)
                    rows++
                    if (rows >= MAX_ROWS_PER_SHEET || out.length >= MAX_OUTPUT_CHARS) throw StopParsing()
                }
            }
        }
    }

    private fun appendRow(cells: Map<Int, String>, out: StringBuilder) {
        if (cells.isEmpty()) {
            out.appendLine()
            return
        }
        val maxColumn = cells.keys.maxOrNull() ?: 0
        out.appendLine((0..maxColumn).joinToString("\t") { cells[it].orEmpty() })
    }

    private fun resolveCell(
        type: String?,
        value: String,
        inline: String,
        sharedStrings: List<String>,
    ): String = when (type) {
        "s" -> value.trim().toIntOrNull()?.let(sharedStrings::getOrNull).orEmpty()
        "b" -> if (value.trim() == "1") "TRUE" else "FALSE"
        "inlineStr" -> inline
        else -> value
    }.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

    private fun nameOf(localName: String?, qName: String?): String =
        localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')

    private fun columnIndex(cellReference: String): Int {
        var value = 0
        var found = false
        for (char in cellReference) {
            if (!char.isLetter()) break
            found = true
            value = value * 26 + (char.uppercaseChar() - 'A' + 1)
        }
        return if (found) (value - 1).coerceAtLeast(0) else 0
    }

    private class StopParsing : SAXException()
}
