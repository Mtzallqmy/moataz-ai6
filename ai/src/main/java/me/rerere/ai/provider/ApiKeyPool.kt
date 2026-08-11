package me.rerere.ai.provider

import me.rerere.ai.provider.providers.openai.OpenAICompatibleErrorKind
import me.rerere.ai.provider.providers.openai.OpenAICompatibleHttpException
import me.rerere.ai.provider.providers.openai.OpenAICompatibleStreamException
import me.rerere.ai.provider.providers.openai.ResponseStreamErrorException
import me.rerere.ai.provider.providers.openai.ResponseStreamFailureException
import me.rerere.ai.provider.providers.openai.classifyOpenAICompatibleStatus
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val LEGACY_API_KEY_SEPARATOR = "[\\s,]+".toRegex()
private const val MAX_API_KEY_ATTEMPTS = 3

/**
 * Resolve the structured credential pool without invalidating older settings/backups.
 * Older RikkaHub settings allowed several credentials to be typed into the single apiKey
 * field separated by whitespace or commas; preserve every one of those credentials.
 */
fun ProviderSetting.OpenAI.resolvedApiKeys(): List<ApiKeyEntry> {
    if (apiKeys.isNotEmpty()) return apiKeys
    val legacy = apiKey
        .split(LEGACY_API_KEY_SEPARATOR)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    return legacy.mapIndexed { index, value ->
        ApiKeyEntry(
            id = "legacy-${index + 1}",
            label = if (index == 0) "Main" else "Backup ${index + 1}",
            value = value,
            enabled = true,
            priority = index,
            createdAt = 0L,
        )
    }
}

/** Lazy, non-destructive migration used when settings are decoded. */
fun ProviderSetting.OpenAI.migrateLegacyApiKeyPool(): ProviderSetting.OpenAI {
    if (apiKeys.isNotEmpty()) return this
    val migrated = resolvedApiKeys()
    if (migrated.isEmpty()) return this
    return copy(
        apiKeys = migrated,
        primaryApiKeyId = primaryApiKeyId ?: migrated.first().id,
        manualApiKeyId = manualApiKeyId ?: migrated.first().id,
        // Retain a single legacy value so older builds can still use the provider.
        apiKey = migrated.first().value,
    )
}

/** Keep the legacy apiKey in sync with the selected primary credential. */
fun ProviderSetting.OpenAI.withApiKeyPool(entries: List<ApiKeyEntry>): ProviderSetting.OpenAI {
    val normalized = entries.mapIndexed { index, entry -> entry.copy(priority = index) }
    val enabled = normalized.filter { it.enabled }
    val requestedPrimary = primaryApiKeyId?.let { id -> normalized.firstOrNull { it.id == id } }
    val primary = requestedPrimary ?: enabled.firstOrNull() ?: normalized.firstOrNull()
    val manual = manualApiKeyId?.takeIf { id -> normalized.any { it.id == id && it.enabled } }
        ?: primary?.id
    return copy(
        apiKeys = normalized,
        primaryApiKeyId = primary?.id,
        manualApiKeyId = manual,
        apiKey = primary?.value.orEmpty(),
    )
}

/**
 * Thread-safe request-level selector. Selection is independent from conversation/tool state;
 * a caller receives an ordered list once and pins one key for the lifetime of each request.
 */
class OpenAIApiKeyPool {
    private val roundRobinCounters = ConcurrentHashMap<String, AtomicInteger>()

    fun candidates(provider: ProviderSetting.OpenAI): List<ApiKeyEntry> {
        val enabled = provider.resolvedApiKeys()
            .filter { it.enabled && it.value.isNotBlank() }
            .sortedWith(compareBy<ApiKeyEntry> { it.priority }.thenBy { it.createdAt }.thenBy { it.id })

        // Preserve keyless OpenAI-compatible endpoints only when the provider allows it.
        // Official OpenAI/Hugging Face presets disable this; NVIDIA/Custom keep it available
        // for self-hosted NIM, LM Studio, vLLM, LocalAI, Ollama-compatible endpoints, etc.
        if (enabled.isEmpty()) {
            return if (provider.allowEmptyApiKey) {
                listOf(ApiKeyEntry(id = "anonymous", label = "No API key", value = ""))
            } else {
                emptyList()
            }
        }

        val primary = provider.primaryApiKeyId
            ?.let { id -> enabled.firstOrNull { it.id == id } }
            ?: enabled.first()

        val ordered = when (provider.apiKeyStrategy) {
            ApiKeyStrategy.PRIMARY_ONLY -> listOf(primary)
            ApiKeyStrategy.MANUAL -> listOf(
                provider.manualApiKeyId?.let { id -> enabled.firstOrNull { it.id == id } } ?: primary
            )
            ApiKeyStrategy.FAILOVER -> listOf(primary) + enabled.filterNot { it.id == primary.id }
            ApiKeyStrategy.ROUND_ROBIN -> {
                val counter = roundRobinCounters.computeIfAbsent(provider.id.toString()) { AtomicInteger(0) }
                val start = Math.floorMod(counter.getAndIncrement(), enabled.size)
                List(enabled.size) { offset -> enabled[(start + offset) % enabled.size] }
            }
        }
        return ordered.take(MAX_API_KEY_ATTEMPTS)
    }

    suspend fun <T> execute(
        provider: ProviderSetting.OpenAI,
        block: suspend (apiKey: String) -> T,
    ): T {
        val candidates = candidates(provider)
        if (candidates.isEmpty()) throw NoApiKeyAvailableException()
        var lastFailure: Throwable? = null
        candidates.forEachIndexed { index, entry ->
            try {
                return block(entry.value)
            } catch (failure: Throwable) {
                lastFailure = failure
                val hasNext = index < candidates.lastIndex
                if (!hasNext || !shouldFailoverApiKey(failure, provider.failoverOnRateLimit)) {
                    throw failure
                }
            }
        }
        throw lastFailure ?: IllegalStateException("No API key candidate available")
    }
}

class NoApiKeyAvailableException : IllegalStateException("No enabled API key is configured for this provider")

fun shouldFailoverApiKey(failure: Throwable, allowRateLimitFailover: Boolean): Boolean {
    val statusCode = generateSequence(failure) { it.cause }
        .mapNotNull { cause ->
            when (cause) {
                is OpenAICompatibleHttpException -> cause.statusCode
                is OpenAICompatibleStreamException -> cause.statusCode
                is ResponseStreamFailureException -> cause.statusCode
                else -> null
            }
        }
        .firstOrNull()

    if (statusCode != null) {
        return when (classifyOpenAICompatibleStatus(statusCode)) {
            OpenAICompatibleErrorKind.AUTHENTICATION -> true
            OpenAICompatibleErrorKind.RATE_LIMIT -> allowRateLimitFailover
            OpenAICompatibleErrorKind.TIMEOUT, OpenAICompatibleErrorKind.SERVER -> true
            OpenAICompatibleErrorKind.BAD_REQUEST,
            OpenAICompatibleErrorKind.NOT_FOUND,
            OpenAICompatibleErrorKind.UNKNOWN -> false
        }
    }

    if (generateSequence(failure) { it.cause }.any { it is ResponseStreamErrorException }) return false
    if (generateSequence(failure) { it.cause }.any { it is SocketTimeoutException }) return true
    // A transport failure before an HTTP response may be retried with a different credential;
    // stream callers separately prevent failover after meaningful output has been emitted.
    return failure is IOException
}
