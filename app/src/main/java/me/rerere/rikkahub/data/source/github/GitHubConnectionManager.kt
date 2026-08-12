package me.rerere.rikkahub.data.source.github

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class GitHubConnectionManager(
    private val httpClient: OkHttpClient,
    private val credentialStore: GitHubCredentialStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun hasSavedCredential(): Boolean = !credentialStore.read().accessToken.isNullOrBlank()

    suspend fun currentConnection(): GitHubConnectionStatus {
        val token = credentialStore.read().accessToken?.trim().orEmpty()
        if (token.isBlank()) return GitHubConnectionStatus.Disconnected
        return runCatching { validate(token) }
            .getOrElse { GitHubConnectionStatus.Invalid(it.message ?: "GitHub authentication failed") }
    }

    suspend fun connectFineGrainedPat(token: String): GitHubConnectionStatus {
        val clean = token.trim()
        require(clean.length >= 20) { "GitHub token looks incomplete" }
        val connected = validate(clean)
        credentialStore.write(
            GitHubCredentialState(
                accessToken = clean,
                kind = GitHubCredentialKind.FINE_GRAINED_PAT,
            )
        )
        return connected
    }

    fun disconnect() = credentialStore.clear()

    private suspend fun validate(token: String): GitHubConnectionStatus.Connected {
        val request = Request.Builder()
            .url("https://api.github.com/user")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val text = execute(request)
        val user = json.decodeFromString<ApiUser>(text)
        return GitHubConnectionStatus.Connected(login = user.login, name = user.name)
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCompleted) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body.string().take(MAX_RESPONSE_CHARS)
                    if (!it.isSuccessful) {
                        if (!continuation.isCompleted) {
                            continuation.resumeWithException(
                                GitHubRepositoryException(it.code, "GitHub authentication failed")
                            )
                        }
                        return
                    }
                    if (!continuation.isCompleted) continuation.resume(text)
                }
            }
        })
    }

    private companion object {
        const val API_VERSION = "2026-03-10"
        const val MAX_RESPONSE_CHARS = 256_000
    }
}

internal sealed interface GitHubConnectionStatus {
    data object Disconnected : GitHubConnectionStatus
    data class Connected(val login: String, val name: String?) : GitHubConnectionStatus
    data class Invalid(val detail: String) : GitHubConnectionStatus
}

@Serializable
private data class ApiUser(
    val login: String,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)
