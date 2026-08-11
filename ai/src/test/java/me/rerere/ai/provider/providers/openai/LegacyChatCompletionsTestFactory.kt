package me.rerere.ai.provider.providers.openai

import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient

/**
 * Test-only compatibility factory for upstream tests that still pass KeyRoulette.
 * Production requests use OpenAIApiKeyPool; these tests exercise request/message
 * serialization and do not perform credential selection.
 */
@Suppress("UNUSED_PARAMETER")
internal fun ChatCompletionsAPI(
    client: OkHttpClient,
    legacyKeyRoulette: KeyRoulette,
): ChatCompletionsAPI = ChatCompletionsAPI(client)
