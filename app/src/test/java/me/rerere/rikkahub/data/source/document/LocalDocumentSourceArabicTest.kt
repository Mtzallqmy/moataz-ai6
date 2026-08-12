package me.rerere.rikkahub.data.source.document

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalDocumentSourceArabicTest {
    @Test
    fun `search ignores Arabic diacritics and alif variants`() = runBlocking {
        val file = File.createTempFile("moataz-arabic-search", ".txt")
        try {
            file.writeText(
                "إِدَارَةُ الأَعْمَالِ تبدأ من هنا\n" +
                    "العمل عَلَى المصادر المحلية مهم\n",
                Charsets.UTF_8,
            )
            val document = UIMessagePart.Document(
                url = file.toURI().toString(),
                fileName = "arabic.txt",
                mime = "text/plain",
            )

            val alifResult = LocalDocumentSource.search(
                document = document,
                query = "ادارة الاعمال",
            )
            assertEquals(1, alifResult.hits.size)
            assertEquals(1, alifResult.hits.single().line)
            assertTrue(alifResult.hits.single().excerpt.contains("إِدَارَةُ الأَعْمَالِ"))

            val alefMaqsuraResult = LocalDocumentSource.search(
                document = document,
                query = "علي المصادر",
            )
            assertEquals(1, alefMaqsuraResult.hits.size)
            assertEquals(2, alefMaqsuraResult.hits.single().line)
        } finally {
            file.delete()
        }
    }
}
