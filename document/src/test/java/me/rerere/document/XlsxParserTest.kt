package me.rerere.document

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxParserTest {
    @Test
    fun `extracts shared strings and numeric cells`() {
        val file = File.createTempFile("xlsx-parser", ".xlsx")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
                zip.write("""
                    <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <si><t>الاسم</t></si><si><t>معتز</t></si>
                    </sst>
                """.trimIndent().toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                zip.write("""
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                      <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1"><v>42</v></c></row>
                      <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2" t="inlineStr"><is><t>فعال</t></is></c></row>
                    </sheetData></worksheet>
                """.trimIndent().toByteArray())
                zip.closeEntry()
            }
            val text = XlsxParser.parse(file)
            assertTrue(text.contains("## Sheet 1"))
            assertTrue(text.contains("الاسم\t42"))
            assertTrue(text.contains("معتز\tفعال"))
        } finally {
            file.delete()
        }
    }
}
