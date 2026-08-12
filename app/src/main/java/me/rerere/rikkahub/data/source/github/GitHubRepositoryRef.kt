package me.rerere.rikkahub.data.source.github

import java.net.URI

/**
 * Canonical repository identity used by the GitHub source layer.
 *
 * User-provided URLs are parsed once at the boundary and only owner/repository are retained.
 * API requests are always rebuilt against api.github.com, so a pasted URL can never redirect
 * credentials to an arbitrary host.
 */
internal data class GitHubRepositoryRef(
    val owner: String,
    val repository: String,
) {
    val fullName: String get() = "$owner/$repository"

    companion object {
        private val OWNER = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
        private val REPOSITORY = Regex("^[A-Za-z0-9._-]{1,100}$")

        fun parse(input: String): GitHubRepositoryRef? {
            val raw = input.trim()
            if (raw.isEmpty()) return null

            val candidate = when {
                raw.startsWith("https://", ignoreCase = true) -> raw
                raw.startsWith("http://", ignoreCase = true) -> raw
                raw.startsWith("github.com/", ignoreCase = true) -> "https://$raw"
                else -> return null
            }

            val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            if (!uri.host.equals("github.com", ignoreCase = true)) return null

            val segments = uri.path.orEmpty()
                .split('/')
                .filter { it.isNotBlank() }
            if (segments.size < 2) return null

            val owner = segments[0]
            val repository = segments[1].removeSuffix(".git")
            if (!OWNER.matches(owner) || !REPOSITORY.matches(repository)) return null

            return GitHubRepositoryRef(owner = owner, repository = repository)
        }
    }
}

/** Input guards shared by the network client and model-facing tools. */
internal object GitHubRepositoryPolicy {
    const val MAX_PATH_LENGTH = 1_024
    const val MAX_REF_LENGTH = 256

    fun normalizePath(path: String, allowEmpty: Boolean = false): String {
        val normalized = path.trim().trimStart('/')
        require(normalized.length <= MAX_PATH_LENGTH) { "Repository path is too long" }
        require('\\' !in normalized) { "Repository paths must use '/' separators" }
        require(normalized.none { it.code < 0x20 }) { "Repository path contains control characters" }

        val segments = normalized.split('/').filter { it.isNotEmpty() }
        require(segments.none { it == "." || it == ".." }) { "Repository path traversal is not allowed" }
        if (!allowEmpty) require(segments.isNotEmpty()) { "Repository path is required" }
        return segments.joinToString("/")
    }

    fun normalizeRef(ref: String): String {
        val normalized = ref.trim()
        require(normalized.isNotEmpty()) { "Git ref is required" }
        require(normalized.length <= MAX_REF_LENGTH) { "Git ref is too long" }
        require(normalized.none { it.code < 0x20 }) { "Git ref contains control characters" }
        return normalized
    }
}
