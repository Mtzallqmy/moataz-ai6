package me.rerere.ai.provider.providers

import android.util.Log

/**
 * Small logging gate for provider diagnostics.
 *
 * Uses Android's runtime loggability instead of a build-time flag so release
 * builds do not emit provider diagnostics unless explicitly enabled for the tag.
 */
internal object Logging {
    fun isDebugLoggingEnabled(): Boolean = Log.isLoggable("OpenAIProvider", Log.DEBUG)
}
