package me.rerere.rikkahub.data.source.document

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalDocumentSourceTest {
    @Test
    fun `reads bounded windows without dumping whole file`() = runBlocking {
        withTextDocument((1..900).joinToString("\n") { "line $it" }) { document ->
            val window = LocalDocumentSource.readWindow(document, startLine = 301, endLine = 900)
            assertEquals(301, window.startLine)
            assertEquals(800, window.endLine)
            assertTrue(window.truncated)
            assertTrue(window.text.startsWith("line 301"))
            assertTrue(window.text.endsWith("line 800"))
        }
    }

    @Test
    fun `arabic search ignores diacritics tatweel and alef variants`() = runBlocking {
        withTextDocument("مقدمة\nإِنَّ مُعْتَز يطوّر وكيلاً عربياً\nنهاية") { document ->
            val result = LocalDocumentSource.search(document, "ان معتز")
            assertFalse(result.hits.isEmpty())
            assertEquals(2, result.hits.first().line)
        }
    }

    @Test
    fun `large prompt uses compact preview`() = runBlocking {
        withTextDocument("س".repeat(LocalDocumentSource.INLINE_FULL_TEXT_CHARS + 100)) { document ->
            val preview = LocalDocumentSource.promptPreview(document)
            assertTrue(preview.truncated)
            assertEquals(LocalDocumentSource.PROMPT_PREVIEW_CHARS, preview.content.length)
        }
    }

    private suspend fun withTextDocument(content: String, block: suspend (UIMessagePart.Document) -> Unit) {
        val file = File.createTempFile("moataz-doc-source", ".txt")
        try {
            file.writeText(content)
            block(
                UIMessagePart.Document(
                    url = file.toURI().toString(),
                    fileName = "notes.txt",
                    mime = "text/plain",
                )
            )
        } finally {
            file.delete()
        }
    }
}
