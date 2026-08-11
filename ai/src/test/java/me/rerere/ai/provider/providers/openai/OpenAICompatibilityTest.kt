package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.HUGGING_FACE_DEFAULT_BASE_URL
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.NVIDIA_NIM_DEFAULT_BASE_URL
import me.rerere.ai.provider.OpenAICompatiblePreset
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.openAICompatiblePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAICompatibilityTest {
    @Test
    fun `base URL normalization appends v1 only to bare roots`() {
        assertEquals("https://example.com/v1", normalizeOpenAICompatibleBaseUrl("https://example.com"))
        assertEquals("https://example.com/v1", normalizeOpenAICompatibleBaseUrl("https://example.com/"))
        assertEquals("https://example.com/v1", normalizeOpenAICompatibleBaseUrl("https://example.com/v1/"))
        assertEquals("https://example.com/api/v1", normalizeOpenAICompatibleBaseUrl("https://example.com/api/v1/"))
        assertEquals(
            "https://example.com/v1/chat/completions",
            openAICompatibleEndpoint("https://example.com/v1/", "/chat/completions")
        )
        assertEquals(
            "https://example.com/v1/chat/completions",
            openAICompatibleEndpoint("https://example.com/v1/chat/completions?debug=1", "/chat/completions")
        )
    }

    @Test
    fun `authorization is generated once and explicit custom authorization wins`() {
        val provider = ProviderSetting.OpenAI(
            apiKey = "generated-key",
            customHeaders = listOf(
                CustomHeader("X-Provider-Key", "abc"),
                CustomHeader("Authorization", "Token custom"),
                CustomHeader("Content-Length", "999"),
            ),
        )
        val headers = buildOpenAICompatibleHeaders(
            provider,
            requestHeaders = listOf(CustomHeader("X-Provider-Key", "override")),
        )
        assertEquals("Token custom", headers["Authorization"])
        assertEquals("override", headers["X-Provider-Key"])
        assertNull(headers["Content-Length"])
        assertEquals(1, headers.values("Authorization").size)
    }

    @Test
    fun `blank API key produces no authorization header for self hosted NIM`() {
        val provider = ProviderSetting.OpenAI(apiKey = "")
        assertNull(buildOpenAICompatibleHeaders(provider)["Authorization"])
    }

    @Test
    fun `NVIDIA and Hugging Face presets use documented API roots`() {
        val nvidia = openAICompatiblePreset(OpenAICompatiblePreset.NVIDIA)
        val hf = openAICompatiblePreset(OpenAICompatiblePreset.HUGGING_FACE)
        assertEquals(NVIDIA_NIM_DEFAULT_BASE_URL, nvidia.baseUrl)
        assertEquals(HUGGING_FACE_DEFAULT_BASE_URL, hf.baseUrl)
        assertFalse(nvidia.compatibilityCapabilities.responsesApi)
        assertFalse(hf.compatibilityCapabilities.responsesApi)
    }

    @Test
    fun `Hugging Face model id suffix is preserved verbatim`() {
        val id = "openai/gpt-oss-120b:fastest"
        val model = Model(modelId = id, displayName = id)
        assertEquals(id, model.modelId)
    }

    @Test
    fun `HTTP status classification is stable`() {
        assertEquals(OpenAICompatibleErrorKind.BAD_REQUEST, classifyOpenAICompatibleStatus(400))
        assertEquals(OpenAICompatibleErrorKind.AUTHENTICATION, classifyOpenAICompatibleStatus(401))
        assertEquals(OpenAICompatibleErrorKind.AUTHENTICATION, classifyOpenAICompatibleStatus(403))
        assertEquals(OpenAICompatibleErrorKind.NOT_FOUND, classifyOpenAICompatibleStatus(404))
        assertEquals(OpenAICompatibleErrorKind.TIMEOUT, classifyOpenAICompatibleStatus(408))
        assertEquals(OpenAICompatibleErrorKind.RATE_LIMIT, classifyOpenAICompatibleStatus(429))
        assertEquals(OpenAICompatibleErrorKind.SERVER, classifyOpenAICompatibleStatus(503))
        assertTrue(openAICompatibleHttpException("test", 401, "token=secret").safeDetail.contains("***"))
    }
}
