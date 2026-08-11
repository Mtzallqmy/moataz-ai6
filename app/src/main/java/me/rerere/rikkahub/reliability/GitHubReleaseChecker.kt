package me.rerere.rikkahub.reliability

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "GHReleaseChecker"

/**
 * Checks Moataz AI GitHub Releases and compares the latest semantic-version tag against
 * the locally installed [BuildConfig.VERSION_NAME]. Pure HTTP; callers decide when to run it.
 * Release tags use the Moataz AI lineage: `vMAJOR.MINOR.PATCH`.
 */
class GitHubReleaseChecker(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Release(
        val tag_name: String = "",
        val name: String = "",
        val html_url: String = "",
        val published_at: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val body: String = "",
    )

    sealed class CheckResult {
        data class Available(val current: String, val latest: Release) : CheckResult()
        data class UpToDate(val current: String, val latest: Release) : CheckResult()
        data class Failed(val message: String) : CheckResult()
    }

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(LATEST_URL)
            .get()
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .addHeader("User-Agent", "moataz-ai/${BuildConfig.VERSION_NAME}")
            .build()
        val response = try {
            client.newCall(req).execute()
        } catch (t: Throwable) {
            Log.w(TAG, "GitHub release fetch failed", t)
            return@withContext CheckResult.Failed("network error: ${t.message ?: t.javaClass.simpleName}")
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                return@withContext CheckResult.Failed("github responded ${resp.code}")
            }
            val body = resp.body.string()
            val release = try {
                json.decodeFromString<Release>(body)
            } catch (t: Throwable) {
                return@withContext CheckResult.Failed("could not parse github response: ${t.message ?: t.javaClass.simpleName}")
            }
            val current = BuildConfig.VERSION_NAME
            val latest = release.tag_name.removePrefix("v")
            return@withContext if (isNewer(latest, current)) {
                CheckResult.Available(current, release)
            } else {
                CheckResult.UpToDate(current, release)
            }
        }
    }

    /** True when [latestRaw] is a strictly newer plain semantic version than [currentRaw]. */
    fun isNewer(latestRaw: String, currentRaw: String): Boolean {
        val latest = parseSemVer(latestRaw) ?: return false
        val current = parseSemVer(currentRaw) ?: return false
        return compareLists(latest, current) > 0
    }

    private fun parseSemVer(raw: String): IntArray? {
        val cleaned = raw.removePrefix("v").trim()
        val core = cleaned.substringBefore('-').split('.')
        if (core.size != 3) return null
        val numbers = core.map { it.toIntOrNull() ?: return null }
        return numbers.toIntArray()
    }

    private fun compareLists(a: IntArray, b: IntArray): Int {
        for (i in a.indices) {
            val cmp = a[i].compareTo(b[i])
            if (cmp != 0) return cmp
        }
        return 0
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/Mtzallqmy/moataz-ai6/releases/latest"
    }
}
