package me.rerere.ai.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.providers.openai.OpenAICompatibleHttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException

class ApiKeyPoolTest {
    private fun key(id: String, value: String = id, enabled: Boolean = true, priority: Int = 0) =
        ApiKeyEntry(id = id, label = id, value = value, enabled = enabled, priority = priority, createdAt = priority.toLong())

    private fun provider(
        strategy: ApiKeyStrategy = ApiKeyStrategy.FAILOVER,
        keys: List<ApiKeyEntry> = listOf(key("A", priority = 0), key("B", priority = 1)),
        rateLimit: Boolean = false,
    ) = ProviderSetting.OpenAI(
        apiKey = keys.firstOrNull()?.value.orEmpty(),
        apiKeys = keys,
        apiKeyStrategy = strategy,
        primaryApiKeyId = keys.firstOrNull()?.id,
        manualApiKeyId = keys.firstOrNull()?.id,
        failoverOnRateLimit = rateLimit,
        allowEmptyApiKey = false,
    )

    @Test
    fun `legacy single and delimited apiKey migrate without loss`() {
        val migrated = ProviderSetting.OpenAI(apiKey = "one, two\nthree").migrateLegacyApiKeyPool()
        assertEquals(listOf("one", "two", "three"), migrated.apiKeys.map { it.value })
        assertEquals("one", migrated.apiKey)
        assertEquals(migrated.apiKeys.first().id, migrated.primaryApiKeyId)
    }

    @Test
    fun `primary only and manual choose one key`() {
        val pool = OpenAIApiKeyPool()
        assertEquals(listOf("A"), pool.candidates(provider(ApiKeyStrategy.PRIMARY_ONLY)).map { it.id })
        val manual = provider(ApiKeyStrategy.MANUAL).copy(manualApiKeyId = "B")
        assertEquals(listOf("B"), pool.candidates(manual).map { it.id })
    }

    @Test
    fun `disabled keys are excluded and keyless official provider has no candidate`() {
        val pool = OpenAIApiKeyPool()
        val p = provider(keys = listOf(key("A", enabled = false), key("B", priority = 1)))
        assertEquals(listOf("B"), pool.candidates(p).map { it.id })
        assertTrue(pool.candidates(ProviderSetting.OpenAI(apiKeys = emptyList(), apiKey = "", allowEmptyApiKey = false)).isEmpty())
    }

    @Test
    fun `keyless self hosted provider gets anonymous candidate`() {
        val candidates = OpenAIApiKeyPool().candidates(
            ProviderSetting.OpenAI(apiKeys = emptyList(), apiKey = "", allowEmptyApiKey = true)
        )
        assertEquals(1, candidates.size)
        assertEquals("", candidates.single().value)
    }

    @Test
    fun `round robin cycles deterministically`() {
        val pool = OpenAIApiKeyPool()
        val p = provider(ApiKeyStrategy.ROUND_ROBIN, listOf(key("A", priority = 0), key("B", priority = 1), key("C", priority = 2)))
        assertEquals(listOf("A", "B", "C", "A"), List(4) { pool.candidates(p).first().id })
    }

    @Test
    fun `round robin counter is safe under concurrent selection`() = runBlocking {
        val pool = OpenAIApiKeyPool()
        val p = provider(ApiKeyStrategy.ROUND_ROBIN, listOf(key("A", priority = 0), key("B", priority = 1), key("C", priority = 2)))
        val selected = List(90) {
            async(Dispatchers.Default) { pool.candidates(p).first().id }
        }.awaitAll()
        assertEquals(30, selected.count { it == "A" })
        assertEquals(30, selected.count { it == "B" })
        assertEquals(30, selected.count { it == "C" })
    }

    @Test
    fun `401 fails over but 400 and 404 do not`() = runBlocking {
        val pool = OpenAIApiKeyPool()
        val attempts401 = mutableListOf<String>()
        val result = pool.execute(provider()) { k ->
            attempts401 += k
            if (k == "A") throw OpenAICompatibleHttpException(401, "test", "auth")
            "ok-$k"
        }
        assertEquals("ok-B", result)
        assertEquals(listOf("A", "B"), attempts401)

        for (code in listOf(400, 404)) {
            val attempts = mutableListOf<String>()
            try {
                pool.execute(provider()) { k ->
                    attempts += k
                    throw OpenAICompatibleHttpException(code, "test", "bad")
                }
                fail("Expected HTTP $code")
            } catch (_: OpenAICompatibleHttpException) {
                assertEquals(listOf("A"), attempts)
            }
        }
    }

    @Test
    fun `429 failover is opt in`() = runBlocking {
        val pool = OpenAIApiKeyPool()
        val attemptsOff = mutableListOf<String>()
        try {
            pool.execute(provider(rateLimit = false)) { k ->
                attemptsOff += k
                throw OpenAICompatibleHttpException(429, "test", "rate")
            }
            fail("Expected 429")
        } catch (_: OpenAICompatibleHttpException) {
            assertEquals(listOf("A"), attemptsOff)
        }

        val attemptsOn = mutableListOf<String>()
        val result = pool.execute(provider(rateLimit = true)) { k ->
            attemptsOn += k
            if (k == "A") throw OpenAICompatibleHttpException(429, "test", "rate")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf("A", "B"), attemptsOn)
    }

    @Test
    fun `5xx and timeout may fail over`() = runBlocking {
        for (failure in listOf<Throwable>(
            OpenAICompatibleHttpException(503, "test", "temporary"),
            SocketTimeoutException("timeout"),
        )) {
            val attempts = mutableListOf<String>()
            val result = OpenAIApiKeyPool().execute(provider()) { k ->
                attempts += k
                if (k == "A") throw failure
                "ok"
            }
            assertEquals("ok", result)
            assertEquals(listOf("A", "B"), attempts)
        }
    }

    @Test
    fun `pool update keeps legacy primary synchronized and deletion safe`() {
        val p = provider().withApiKeyPool(listOf(key("B"), key("C", priority = 1)))
        assertEquals("B", p.apiKey)
        assertEquals("B", p.primaryApiKeyId)
        assertEquals(listOf("B", "C"), p.apiKeys.map { it.id })
    }

    @Test
    fun `structured key pool serializes and restores`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val original = provider(ApiKeyStrategy.ROUND_ROBIN).copy(failoverOnRateLimit = true)
        val encoded = json.encodeToString(original)
        val restored = json.decodeFromString<ProviderSetting.OpenAI>(encoded)
        assertEquals(original.apiKeys, restored.apiKeys)
        assertEquals(ApiKeyStrategy.ROUND_ROBIN, restored.apiKeyStrategy)
        assertTrue(restored.failoverOnRateLimit)
    }

}
