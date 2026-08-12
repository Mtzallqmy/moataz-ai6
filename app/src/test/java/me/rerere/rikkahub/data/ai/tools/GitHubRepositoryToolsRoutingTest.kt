package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRepositoryToolsRoutingTest {
    @Test
    fun `GitHub tools are hidden from ordinary chat`() {
        assertFalse(
            shouldExposeGitHubRepositoryTools(
                listOf(userMessage("حلل هذا الكود واشرح لي المشكلة"))
            )
        )
    }

    @Test
    fun `GitHub tools are exposed when a repository URL is present`() {
        assertTrue(
            shouldExposeGitHubRepositoryTools(
                listOf(userMessage("حلل https://github.com/Mtzallqmy/moataz-ai6 من فضلك"))
            )
        )
    }

    @Test
    fun `GitHub tools remain available across the recent conversation window`() {
        val messages = buildList {
            add(userMessage("https://github.com/owner/repository"))
            repeat(10) { add(userMessage("تابع التحليل رقم $it")) }
        }
        assertTrue(shouldExposeGitHubRepositoryTools(messages))
    }

    private fun userMessage(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )
}
