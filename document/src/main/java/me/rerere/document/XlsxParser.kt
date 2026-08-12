package me.rerere.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Lightweight XLSX text extractor with no spreadsheet runtime dependency.
 *
 * It intentionally extracts values rather than reproducing Excel formatting. The result is
 * Markdown-friendly TSV grouped by worksheet, which is substantially cheaper for an LLM to read.
 * Limits protect the app from pathological workbooks and zip-bomb-like entries.
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

    private fun parser(stream: InputStream): XmlPullParser = XmlPullParserFactory.newInstance().run {
        isNamespaceAware = true
        newPullParser().also { it.setInput(stream, "UTF-8") }
    }

    private fun parseSharedStrings(stream: InputStream): List<String> {
        val parser = parser(stream)
        val strings = mutableListOf<String>()
        var current: StringBuilder? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> current = StringBuilder()
                    "t" -> current?.append(readText(parser))
                }
                XmlPullParser.END_TAG -> if (parser.name == "si") {
                    strings += current?.toString().orEmpty()
                    current = null
                }
            }
            parser.next()
        }
        return strings
    }

    private fun parseWorksheet(stream: InputStream, sharedStrings: List<String>, out: StringBuilder) {
        val parser = parser(stream)
        var rows = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT && rows < MAX_ROWS_PER_SHEET && out.length < MAX_OUTPUT_CHARS) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "row") {
                val cells = parseRow(parser, sharedStrings)
                if (cells.isNotEmpty()) {
                    val maxColumn = cells.keys.maxOrNull() ?: 0
                    out.appendLine((0..maxColumn).joinToString("\t") { cells[it].orEmpty() })
                } else {
                    out.appendLine()
                }
                rows++
            }
            parser.next()
        }
        if (rows >= MAX_ROWS_PER_SHEET) out.appendLine("[Worksheet truncated to $MAX_ROWS_PER_SHEET rows]")
    }

    private fun parseRow(parser: XmlPullParser, sharedStrings: List<String>): Map<Int, String> {
        val rowDepth = parser.depth
        val cells = linkedMapOf<Int, String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "c") {
                val ref = parser.getAttributeValue(null, "r").orEmpty()
                val type = parser.getAttributeValue(null, "t")
                cells[columnIndex(ref)] = parseCell(parser, type, sharedStrings)
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "row" && parser.depth == rowDepth) {
                break
            }
        }
        return cells
    }

    private fun parseCell(parser: XmlPullParser, type: String?, sharedStrings: List<String>): String {
        val cellDepth = parser.depth
        var value = ""
        val inline = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "v" -> value = readText(parser)
                    "t" -> if (type == "inlineStr") inline.append(readText(parser))
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "c" && parser.depth == cellDepth) {
                break
            }
        }
        return when (type) {
            "s" -> value.toIntOrNull()?.let(sharedStrings::getOrNull).orEmpty()
            "b" -> if (value == "1") "TRUE" else "FALSE"
            "inlineStr" -> inline.toString()
            else -> value
        }.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
    }

    private fun readText(parser: XmlPullParser): String =
        if (parser.next() == XmlPullParser.TEXT) parser.text.orEmpty() else ""

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
}
