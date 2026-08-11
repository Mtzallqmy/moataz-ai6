package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
private val BLOCKED_CUSTOM_HEADERS = setOf(
    "host",
    "content-length",
    "connection",
    "transfer-encoding",
)

/**
 * Contract for OpenAI-compatible providers in Moataz AI:
 * [raw] is the API root, not the chat-completions endpoint. If it has no path,
 * `/v1` is appended. Existing versioned/custom roots (for example `/api/v1` or
 * `/compatible-mode/v1`) are preserved. Trailing slashes are removed.
 */
fun normalizeOpenAICompatibleBaseUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val url = trimmed.toHttpUrlOrNull() ?: return trimmed.trimEnd('/')
    var currentPath = url.encodedPath.trimEnd('/')
    // Be forgiving when a user pastes a full endpoint instead of the documented API root.
    // The configurable request path is appended separately, so keeping it here would create
    // /chat/completions/chat/completions (or similar) URLs.
    listOf("/chat/completions", "/responses", "/models").firstOrNull { currentPath.endsWith(it) }
        ?.let { suffix -> currentPath = currentPath.removeSuffix(suffix).trimEnd('/') }

    val builder = url.newBuilder().query(null).fragment(null)
    if (currentPath.isBlank()) {
        builder.encodedPath("/v1")
    } else {
        builder.encodedPath(currentPath)
    }
    return builder.build().toString().trimEnd('/')
}

fun openAICompatibleEndpoint(baseUrl: String, path: String): String {
    val base = normalizeOpenAICompatibleBaseUrl(baseUrl)
    val suffix = path.trim().let { if (it.startsWith('/')) it else "/$it" }
    return "$base$suffix"
}

/**
 * Provider-wide headers are merged first, then per-model/per-request headers override them.
 * Explicit Authorization is respected; otherwise a Bearer header is generated only when
 * an API key is non-blank. Hop-by-hop/body-length headers are rejected because OkHttp owns
 * them and allowing overrides can create malformed requests.
 */
fun buildOpenAICompatibleHeaders(
    providerSetting: ProviderSetting.OpenAI,
    requestHeaders: List<CustomHeader> = emptyList(),
    apiKey: String = providerSetting.apiKey,
): Headers {
    val merged = linkedMapOf<String, CustomHeader>()
    (providerSetting.customHeaders + requestHeaders).forEach { header ->
        val key = header.name.trim()
        val value = header.value
        val lower = key.lowercase()
        if (
            key.isNotBlank() &&
            HEADER_NAME.matches(key) &&
            '\r' !in value && '\n' !in value &&
            lower !in BLOCKED_CUSTOM_HEADERS
        ) {
            merged[lower] = CustomHeader(key, value)
        }
    }

    if (providerSetting.organizationId.isNotBlank() && "openai-organization" !in merged) {
        merged["openai-organization"] = CustomHeader("OpenAI-Organization", providerSetting.organizationId.trim())
    }
    if (providerSetting.projectId.isNotBlank() && "openai-project" !in merged) {
        merged["openai-project"] = CustomHeader("OpenAI-Project", providerSetting.projectId.trim())
    }
    if (apiKey.isNotBlank() && "authorization" !in merged) {
        merged["authorization"] = CustomHeader("Authorization", "Bearer $apiKey")
    }

    return Headers.Builder().apply {
        merged.values.forEach { add(it.name, it.value) }
    }.build()
}


enum class OpenAICompatibleErrorKind {
    BAD_REQUEST,
    AUTHENTICATION,
    NOT_FOUND,
    TIMEOUT,
    RATE_LIMIT,
    SERVER,
    UNKNOWN,
}

class OpenAICompatibleHttpException(
    val statusCode: Int,
    val operation: String,
    val safeDetail: String,
) : java.io.IOException("$operation failed (HTTP $statusCode)${if (safeDetail.isBlank()) "" else ": $safeDetail"}")

class OpenAICompatibleStreamException(
    val statusCode: Int?,
    val receivedMeaningfulOutput: Boolean,
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)

fun classifyOpenAICompatibleStatus(statusCode: Int): OpenAICompatibleErrorKind = when (statusCode) {
    400 -> OpenAICompatibleErrorKind.BAD_REQUEST
    401, 403 -> OpenAICompatibleErrorKind.AUTHENTICATION
    404 -> OpenAICompatibleErrorKind.NOT_FOUND
    408 -> OpenAICompatibleErrorKind.TIMEOUT
    429 -> OpenAICompatibleErrorKind.RATE_LIMIT
    in 500..599 -> OpenAICompatibleErrorKind.SERVER
    else -> OpenAICompatibleErrorKind.UNKNOWN
}

fun openAICompatibleHttpException(
    operation: String,
    statusCode: Int,
    responseBody: String,
): OpenAICompatibleHttpException {
    // Provider error bodies are useful for diagnosis, but keep only a bounded, single-line
    // detail and mask common secret-looking fields before it can surface in UI/logs.
    val safe = responseBody
        .replace(Regex("(?i)(api[_-]?key|token|authorization)\\s*[:=]\\s*[^,}\\s]+"), "$1=***")
        .replace('\n', ' ')
        .replace('\r', ' ')
        .take(500)
    return OpenAICompatibleHttpException(statusCode, operation, safe)
}
