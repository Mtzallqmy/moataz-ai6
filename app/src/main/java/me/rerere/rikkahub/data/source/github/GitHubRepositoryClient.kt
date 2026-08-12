package me.rerere.rikkahub.data.source.github

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class GitHubRepositoryClient(
    private val httpClient: OkHttpClient,
    private val credentialProvider: GitHubCredentialProvider,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val apiBase: HttpUrl = "https://api.github.com".toHttpUrl()

    suspend fun getMetadata(repository: GitHubRepositoryRef): GitHubRepositoryMetadata {
        val payload = executeJson<ApiRepository>(
            repository = repository,
            url = repositoryUrl(repository),
        )
        return GitHubRepositoryMetadata(
            fullName = payload.fullName,
            description = payload.description,
            defaultBranch = payload.defaultBranch,
            isPrivate = payload.isPrivate,
            isArchived = payload.isArchived,
            isFork = payload.isFork,
            language = payload.language,
        )
    }

    suspend fun listTree(
        repository: GitHubRepositoryRef,
        ref: String,
    ): GitHubRepositoryTree {
        val safeRef = GitHubRepositoryPolicy.normalizeRef(ref)
        val url = repositoryUrl(repository)
            .newBuilder()
            .addPathSegments("git/trees")
            .addPathSegment(safeRef)
            .addQueryParameter("recursive", "1")
            .build()
        val payload = executeJson<ApiTree>(repository, url)
        return GitHubRepositoryTree(
            ref = safeRef,
            truncated = payload.truncated,
            entries = payload.tree.mapNotNull { item ->
                val path = item.path ?: return@mapNotNull null
                val type = item.type ?: return@mapNotNull null
                GitHubTreeEntry(
                    path = path,
                    type = type,
                    size = item.size,
                    sha = item.sha,
                )
            },
        )
    }

    suspend fun readFile(
        repository: GitHubRepositoryRef,
        path: String,
        ref: String,
    ): GitHubRawFile {
        val safePath = GitHubRepositoryPolicy.normalizePath(path)
        val safeRef = GitHubRepositoryPolicy.normalizeRef(ref)
        val builder = repositoryUrl(repository)
            .newBuilder()
            .addPathSegment("contents")
        safePath.split('/').forEach(builder::addPathSegment)
        val url = builder
            .addQueryParameter("ref", safeRef)
            .build()

        val payload = executeJson<ApiContent>(repository, url)
        require(payload.type == "file") { "GitHub path '$safePath' is not a file" }
        require(payload.size <= MAX_FILE_BYTES) {
            "GitHub file is too large to read safely (${payload.size} bytes; max $MAX_FILE_BYTES)"
        }
        require(payload.encoding.equals("base64", ignoreCase = true)) {
            "GitHub returned unsupported content encoding '${payload.encoding}'"
        }
        val encoded = payload.content ?: error("GitHub did not return file content")
        val bytes = runCatching { Base64.getMimeDecoder().decode(encoded) }
            .getOrElse { throw IllegalArgumentException("GitHub returned invalid base64 content", it) }
        require(bytes.size <= MAX_FILE_BYTES) { "Decoded GitHub file exceeds the safe size limit" }
        require(!looksBinary(bytes)) { "GitHub file appears to be binary and cannot be read as text" }

        return GitHubRawFile(
            path = payload.path ?: safePath,
            ref = safeRef,
            sha = payload.sha,
            size = bytes.size.toLong(),
            text = bytes.toString(Charsets.UTF_8),
        )
    }

    private fun repositoryUrl(repository: GitHubRepositoryRef): HttpUrl = apiBase.newBuilder()
        .addPathSegment("repos")
        .addPathSegment(repository.owner)
        .addPathSegment(repository.repository)
        .build()

    private suspend inline fun <reified T> executeJson(
        repository: GitHubRepositoryRef,
        url: HttpUrl,
    ): T {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)

        credentialProvider.tokenFor(repository)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { requestBuilder.header("Authorization", "Bearer $it") }

        val text = execute(requestBuilder.get().build())
        return json.decodeFromString(text)
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
                    val body = it.body
                    val source = body.source()
                    source.request(MAX_API_RESPONSE_BYTES + 1)
                    if (source.buffer.size > MAX_API_RESPONSE_BYTES) {
                        if (!continuation.isCompleted) {
                            continuation.resumeWithException(
                                IOException("GitHub response exceeded the safe ${MAX_API_RESPONSE_BYTES}-byte limit")
                            )
                        }
                        return
                    }
                    val text = source.buffer.readUtf8()
                    if (!it.isSuccessful) {
                        val message = runCatching {
                            json.decodeFromString<ApiError>(text).message
                        }.getOrNull()?.take(300)
                        if (!continuation.isCompleted) {
                            continuation.resumeWithException(
                                GitHubRepositoryException(
                                    statusCode = it.code,
                                    detail = message ?: "GitHub request failed",
                                )
                            )
                        }
                        return
                    }
                    if (!continuation.isCompleted) continuation.resume(text)
                }
            }
        })
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val sampleSize = minOf(bytes.size, 4_096)
        if (sampleSize == 0) return false
        var suspicious = 0
        for (index in 0 until sampleSize) {
            val value = bytes[index].toInt() and 0xff
            if (value == 0) return true
            val acceptableControl = value == 9 || value == 10 || value == 13
            if (value < 32 && !acceptableControl) suspicious++
        }
        return suspicious.toDouble() / sampleSize > 0.10
    }

    private companion object {
        const val API_VERSION = "2026-03-10"
        const val MAX_FILE_BYTES = 1_048_576L
        const val MAX_API_RESPONSE_BYTES = 8L * 1024 * 1024
    }
}

internal class GitHubRepositoryException(
    val statusCode: Int,
    detail: String,
) : IOException("GitHub API $statusCode: $detail")

internal data class GitHubRepositoryMetadata(
    val fullName: String,
    val description: String?,
    val defaultBranch: String,
    val isPrivate: Boolean,
    val isArchived: Boolean,
    val isFork: Boolean,
    val language: String?,
)

internal data class GitHubRepositoryTree(
    val ref: String,
    val truncated: Boolean,
    val entries: List<GitHubTreeEntry>,
)

internal data class GitHubTreeEntry(
    val path: String,
    val type: String,
    val size: Long?,
    val sha: String?,
)

internal data class GitHubRawFile(
    val path: String,
    val ref: String,
    val sha: String?,
    val size: Long,
    val text: String,
)

@Serializable
private data class ApiRepository(
    @SerialName("full_name") val fullName: String,
    val description: String? = null,
    @SerialName("default_branch") val defaultBranch: String,
    @SerialName("private") val isPrivate: Boolean = false,
    @SerialName("archived") val isArchived: Boolean = false,
    @SerialName("fork") val isFork: Boolean = false,
    val language: String? = null,
)

@Serializable
private data class ApiTree(
    val truncated: Boolean = false,
    val tree: List<ApiTreeEntry> = emptyList(),
)

@Serializable
private data class ApiTreeEntry(
    val path: String? = null,
    val type: String? = null,
    val size: Long? = null,
    val sha: String? = null,
)

@Serializable
private data class ApiContent(
    val type: String,
    val path: String? = null,
    val sha: String? = null,
    val size: Long = 0,
    val encoding: String? = null,
    val content: String? = null,
)

@Serializable
private data class ApiError(val message: String = "GitHub request failed")
