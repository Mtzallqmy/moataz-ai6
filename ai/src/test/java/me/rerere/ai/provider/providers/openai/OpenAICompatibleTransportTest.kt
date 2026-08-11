package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OpenAICompatiblePreset
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.openAICompatiblePreset
import me.rerere.ai.provider.providers.OpenAIProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class OpenAICompatibleTransportTest {
    private fun clientFor(body: String, contentType: String = "application/json", code: Int = 200, capture: AtomicReference<Request>? = null): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            capture?.set(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(body.toResponseBody(contentType.toMediaType()))
                .build()
        }.build()

    private fun streamingProvider(preset: OpenAICompatiblePreset): ProviderSetting.OpenAI =
        openAICompatiblePreset(preset).copy(
            baseUrl = "https://mock.example/v1",
            apiKey = "test-key",
            models = listOf(Model(modelId = "model-id", displayName = "model-id")),
        )

    private fun assertStreaming(preset: OpenAICompatiblePreset) = runBlocking {
        val sse = """
            data: {"id":"c1","model":"model-id","choices":[{"index":0,"delta":{"content":"hel"},"finish_reason":null}]}

            data: {"id":"c1","model":"model-id","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()
        val provider = streamingProvider(preset)
        val chunks = OpenAIProvider(clientFor(sse, "text/event-stream")).streamText(
            provider,
            listOf(UIMessage.user("hello")),
            TextGenerationParams(model = provider.models.first()),
        ).toList()
        val text = chunks.flatMap { it.choices }.mapNotNull { it.delta }.flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        assertEquals("hello", text)
    }

    @Test fun `generic OpenAI compatible SSE streaming`() = assertStreaming(OpenAICompatiblePreset.CUSTOM)
    @Test fun `NVIDIA preset SSE streaming uses shared transport`() = assertStreaming(OpenAICompatiblePreset.NVIDIA)
    @Test fun `Hugging Face preset SSE streaming uses shared transport`() = assertStreaming(OpenAICompatiblePreset.HUGGING_FACE)

    @Test
    fun `chat completion sends model authorization and tools and parses tool call`() = runBlocking {
        val capture = AtomicReference<Request>()
        val response = """{"id":"c1","model":"tool-model","choices":[{"index":0,"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_1","type":"function","function":{"name":"clock","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        val client = clientFor(response, capture = capture)
        val model = Model(modelId = "vendor/tool-model", displayName = "tool-model", abilities = listOf(ModelAbility.TOOL))
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://mock.example/",
            apiKey = "secret",
            compatibilityPreset = OpenAICompatiblePreset.CUSTOM,
            models = listOf(model),
        )
        val chunk = OpenAIProvider(client).generateText(
            provider,
            listOf(UIMessage.user("use clock")),
            TextGenerationParams(
                model = model,
                tools = listOf(Tool(name = "clock", description = "clock", execute = { emptyList() })),
            )
        )
        val request = capture.get()
        assertEquals("Bearer secret", request.header("Authorization"))
        assertEquals("/v1/chat/completions", request.url.encodedPath)
        val sent = request.body.toString()
        val buffered = okio.Buffer().also { request.body.writeTo(it) }.readUtf8()
        assertTrue(buffered.contains("\"model\":\"vendor/tool-model\""))
        assertTrue(buffered.contains("\"tools\""))
        val tool = chunk.choices.first().message!!.parts.filterIsInstance<UIMessagePart.Tool>().first()
        assertEquals("clock", tool.toolName)
        assertEquals("call_1", tool.toolCallId)
    }

    @Test
    fun `model discovery failure does not prevent manual model configuration`() = runBlocking {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://mock.example/v1",
            apiKey = "bad",
            models = listOf(Model(modelId = "manual/model", displayName = "manual/model")),
        )
        val result = runCatching { OpenAIProvider(clientFor("{\"error\":{\"message\":\"no models\"}}", code = 404)).listModels(provider) }
        assertTrue(result.exceptionOrNull() is OpenAICompatibleHttpException)
        assertEquals("manual/model", provider.models.first().modelId)
    }
}
