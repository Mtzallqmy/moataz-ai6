package me.rerere.ai.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OpenAIApiKeyPool
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.buildOpenAICompatibleHeaders
import me.rerere.ai.provider.providers.openai.normalizeOpenAICompatibleBaseUrl
import me.rerere.ai.provider.providers.openai.openAICompatibleEndpoint
import me.rerere.ai.provider.providers.openai.openAICompatibleHttpException
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.openRouterModelFromJson
import me.rerere.ai.provider.providers.openai.parseImageDataUri
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.common.http.await
import me.rerere.common.http.getByKey
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "OpenAIProvider"

// Same shape as ClaudeProvider.MINIMAX_FALLBACK_MODELS — see comment there for
// why a fallback is needed (Minimax's /v1/models returns `{"object":"","data":null}`
// even with a valid key, despite their published OpenAPI spec). Duplicated rather
// than shared because both providers are otherwise self-contained and a util
// module just for this would be overkill.
private val MINIMAX_FALLBACK_MODELS = listOf(
    "MiniMax-M2.7",
    "MiniMax-M2.7-highspeed",
    "MiniMax-M2.5",
    "MiniMax-M2.5-highspeed",
    "MiniMax-M2.1",
    "MiniMax-M2.1-highspeed",
    "MiniMax-M2",
).map { Model(modelId = it, displayName = it) }

class OpenAIProvider(
    private val client: OkHttpClient,
    @Suppress("UNUSED_PARAMETER") context: Context? = null,
) : Provider<ProviderSetting.OpenAI> {
    private val apiKeyPool = OpenAIApiKeyPool()

    private val chatCompletionsAPI = ChatCompletionsAPI(client = client, apiKeyPool = apiKeyPool)
    private val responseAPI = ResponseAPI(client = client, apiKeyPool = apiKeyPool)


    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        withContext(Dispatchers.IO) {
            apiKeyPool.execute(providerSetting) { key ->
                val request = Request.Builder()
                    .url(openAICompatibleEndpoint(providerSetting.baseUrl, "/models"))
                    .headers(buildOpenAICompatibleHeaders(providerSetting, apiKey = key))
                    .get()
                    .build()

                val response = client.newCall(request).await()
                val bodyStr = response.body.string()
                if (!response.isSuccessful) {
                    throw openAICompatibleHttpException("Model discovery", response.code, bodyStr)
                }

                val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
                val data = bodyJson["data"] as? JsonArray
                if (data == null) {
                    val baseResp = bodyJson["base_resp"] as? JsonObject
                    val statusCode = baseResp?.get("status_code")?.jsonPrimitive?.intOrNull
                    if (statusCode != null && statusCode != 0) {
                        val msg = baseResp["status_msg"]?.jsonPrimitive?.contentOrNull
                        error("Failed to get models: ${msg ?: "status_code=$statusCode"}")
                    }
                    val errMsg = (bodyJson["error"] as? JsonObject)?.get("message")
                        ?.jsonPrimitive?.contentOrNull
                    if (errMsg != null) error("Failed to get models: $errMsg")
                    if (providerSetting.baseUrl.contains("api.minimax.io", ignoreCase = true)) {
                        return@execute MINIMAX_FALLBACK_MODELS
                    }
                    error("Failed to get models: response has no `data` field")
                }

                val isOpenRouter = providerSetting.baseUrl.contains("openrouter.ai", ignoreCase = true)
                data.mapNotNull { modelJson ->
                    val modelObj = modelJson.jsonObject
                    if (isOpenRouter) {
                        openRouterModelFromJson(modelObj)
                    } else {
                        val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        Model(modelId = id, displayName = id)
                    }
                }
            }
        }

    override suspend fun getBalance(providerSetting: ProviderSetting.OpenAI): String = withContext(Dispatchers.IO) {
        apiKeyPool.execute(providerSetting) { key ->
            val url = if (providerSetting.balanceOption.apiPath.startsWith("http")) {
                providerSetting.balanceOption.apiPath
            } else {
                openAICompatibleEndpoint(providerSetting.baseUrl, providerSetting.balanceOption.apiPath)
            }
            val request = Request.Builder()
                .url(url)
                .headers(buildOpenAICompatibleHeaders(providerSetting, apiKey = key))
                .get()
                .build()
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                throw openAICompatibleHttpException("Balance", response.code, response.body.string())
            }

            val bodyStr = response.body.string()
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val value = bodyJson.getByKey(providerSetting.balanceOption.resultPath)
            val digitalValue = value.toFloatOrNull()
            if (digitalValue != null) "%.2f".format(digitalValue) else value
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> {
        if (!providerSetting.compatibilityCapabilities.streaming) {
            return flow { emit(generateText(providerSetting, messages, params)) }
        }
        return if (providerSetting.useResponseApi && providerSetting.compatibilityCapabilities.responsesApi) {
            responseAPI.streamText(providerSetting = providerSetting, messages = messages, params = params)
        } else {
            chatCompletionsAPI.streamText(providerSetting = providerSetting, messages = messages, params = params)
        }
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = if (providerSetting.useResponseApi && providerSetting.compatibilityCapabilities.responsesApi) {
        responseAPI.generateText(providerSetting = providerSetting, messages = messages, params = params)
    } else {
        chatCompletionsAPI.generateText(providerSetting = providerSetting, messages = messages, params = params)
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        params: EmbeddingGenerationParams
    ): EmbeddingGenerationResult = withContext(Dispatchers.IO) {
        require(params.input.isNotEmpty()) { "Embedding input cannot be empty" }

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                if (params.input.size == 1) {
                    put("input", params.input.first())
                } else {
                    putJsonArray("input") { params.input.forEach { add(JsonPrimitive(it)) } }
                }
                params.dimensions?.let { put("dimensions", it) }
            }.mergeCustomBody(params.customBody)
        )

        apiKeyPool.execute(providerSetting) { key ->
            val request = Request.Builder()
                .url(openAICompatibleEndpoint(providerSetting.baseUrl, "/embeddings"))
                .headers(buildOpenAICompatibleHeaders(providerSetting, params.customHeaders, key))
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                throw openAICompatibleHttpException("Embedding", response.code, response.body.string())
            }

            val bodyJson = json.parseToJsonElement(response.body.string()).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: error("No data in response")
            val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: params.model.modelId
            val embeddings = data.map { embeddingJson ->
                val embeddingArray = embeddingJson.jsonObject["embedding"]?.jsonArray
                    ?: error("No embedding in response")
                embeddingArray.map { it.jsonPrimitive.content.toFloat() }
            }
            EmbeddingGenerationResult(model = model, embeddings = embeddings)
        }
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) { "Expected OpenAI provider setting" }

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)
                val isGrok = providerSetting.baseUrl.contains("x.ai", ignoreCase = true) ||
                    params.model.modelId.contains("grok", ignoreCase = true)
                if (!isGrok) {
                    put(
                        "size", when (params.aspectRatio) {
                            ImageAspectRatio.SQUARE -> "1024x1024"
                            ImageAspectRatio.LANDSCAPE -> "1536x1024"
                            ImageAspectRatio.PORTRAIT -> "1024x1536"
                        }
                    )
                }
            }.mergeCustomBody(params.customBody)
        )

        if (Logging.isDebugLoggingEnabled()) Log.i(TAG, "generateImage request prepared")
        val items = apiKeyPool.execute(providerSetting) { key ->
            if (providerSetting.baseUrl.contains("openrouter.ai", ignoreCase = true)) {
                generateImageViaChatCompletions(providerSetting, params, key)
            } else {
                val request = Request.Builder()
                    .url(openAICompatibleEndpoint(providerSetting.baseUrl, "/images/generations"))
                    .headers(buildOpenAICompatibleHeaders(providerSetting, params.customHeaders, key))
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .configureReferHeaders(providerSetting.baseUrl)
                    .build()
                withContext(Dispatchers.IO) {
                    val response = client.newCall(request).await()
                    if (!response.isSuccessful) {
                        throw openAICompatibleHttpException("Image generation", response.code, response.body?.string().orEmpty())
                    }
                    parseImageResponse(response.body.string())
                }
            }
        }
        items.forEach { emit(it) }
    }

    /** OpenRouter image generation via /chat/completions with modalities:["image","text"]. */
    private suspend fun generateImageViaChatCompletions(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageGenerationParams,
        key: String,
    ): List<ImageGenerationItem> {
        val body = buildJsonObject {
            put("model", params.model.modelId)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", params.prompt)
                })
            }
            putJsonArray("modalities") {
                add("image")
                add("text")
            }
            put("image_config", buildJsonObject {
                put(
                    "aspect_ratio", when (params.aspectRatio) {
                        ImageAspectRatio.SQUARE -> "1:1"
                        ImageAspectRatio.LANDSCAPE -> "16:9"
                        ImageAspectRatio.PORTRAIT -> "9:16"
                    }
                )
            })
        }.mergeCustomBody(params.customBody)

        val request = Request.Builder()
            .url(openAICompatibleEndpoint(providerSetting.baseUrl, providerSetting.chatCompletionsPath))
            .headers(buildOpenAICompatibleHeaders(providerSetting, params.customHeaders, key))
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).await()
        val bodyStr = response.body.string()
        if (!response.isSuccessful) {
            throw openAICompatibleHttpException("Image generation", response.code, bodyStr)
        }
        val message = json.parseToJsonElement(bodyStr).jsonObject["choices"]?.jsonArray
            ?.getOrNull(0)?.jsonObject?.get("message")?.jsonObject
            ?: error("No choices in image response")
        val images = message["images"]?.jsonArray ?: JsonArray(emptyList())
        val items = images.mapNotNull { img ->
            val url = img.jsonObject["image_url"]?.jsonObject?.get("url")
                ?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val parsed = parseImageDataUri(url) ?: return@mapNotNull null
            ImageGenerationItem(data = parsed.base64, mimeType = parsed.mime)
        }
        if (items.isEmpty()) {
            val text = message["content"]?.jsonPrimitive?.contentOrNull
            error(
                "No image returned. The model may not support image output or returned text only." +
                    (text?.takeIf { it.isNotBlank() }?.let { " Model said: $it" } ?: "")
            )
        }
        return items
    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) { "Expected OpenAI provider setting" }
        require(params.images.isNotEmpty()) { "At least one image is required" }

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", params.model.modelId)
            .addFormDataPart("prompt", params.prompt)
            .addFormDataPart("n", params.numOfImages.toString())
        bodyBuilder.addFormDataPart(
            "size", when (params.aspectRatio) {
                ImageAspectRatio.SQUARE -> "1024x1024"
                ImageAspectRatio.LANDSCAPE -> "1536x1024"
                ImageAspectRatio.PORTRAIT -> "1024x1536"
            }
        )

        val imageFieldName = if (params.images.size == 1) "image" else "image[]"
        params.images.forEach { path ->
            val imageFile = File(path)
            require(imageFile.exists()) { "Image file does not exist: $path" }
            require(imageFile.extension.lowercase() in SUPPORTED_EDIT_IMAGE_EXTENSIONS) {
                "Unsupported image file type for OpenAI edit: ${imageFile.extension}"
            }
            bodyBuilder.addFormDataPart(
                imageFieldName,
                imageFile.name,
                imageFile.asRequestBody(imageFile.imageMediaType().toMediaType()),
            )
        }
        params.customBody.forEach { customBody ->
            val value = when (val element = customBody.value) {
                is JsonPrimitive -> element.contentOrNull ?: element.toString()
                else -> element.toString()
            }
            bodyBuilder.addFormDataPart(customBody.key, value)
        }
        val body = bodyBuilder.build()

        val items = apiKeyPool.execute(providerSetting) { key ->
            val request = Request.Builder()
                .url(openAICompatibleEndpoint(providerSetting.baseUrl, "/images/edits"))
                .headers(buildOpenAICompatibleHeaders(providerSetting, params.customHeaders, key))
                .post(body)
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
            withContext(Dispatchers.IO) {
                val response = client.newCall(request).await()
                if (!response.isSuccessful) {
                    throw openAICompatibleHttpException("Image edit", response.code, response.body?.string().orEmpty())
                }
                parseImageResponse(response.body.string())
            }
        }
        items.forEach { emit(it) }
    }

    private suspend fun parseImageResponse(bodyStr: String): List<ImageGenerationItem> {
        val body = json.parseToJsonElement(bodyStr).jsonObject
        val defaultFormat = body["output_format"]?.jsonPrimitive?.contentOrNull ?: "png"
        val data = body["data"]?.jsonArray ?: error("No data in image response")
        return data.map { element ->
            val obj = element.jsonObject
            val b64Json = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            if (b64Json != null) {
                val outputFormat = obj["output_format"]?.jsonPrimitive?.contentOrNull ?: defaultFormat
                ImageGenerationItem(
                    data = b64Json,
                    mimeType = outputFormat.toImageMimeType(),
                )
            } else {
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("No b64_json or url in image response")
                downloadImageAsBase64(url)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadImageAsBase64(url: String): ImageGenerationItem {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to download generated image: ${response.code} ${response.body.string()}")
        }

        val body = response.body
        val mimeType = body.contentType()?.toString() ?: "image/png"
        val base64 = Base64.encode(body.bytes())

        return ImageGenerationItem(
            data = base64,
            mimeType = mimeType
        )
    }

    private fun File.imageMediaType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private fun String.toImageMimeType(): String = when (lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    companion object {
        private val SUPPORTED_EDIT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}
