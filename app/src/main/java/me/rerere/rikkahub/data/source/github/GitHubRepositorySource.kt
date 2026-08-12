package me.rerere.rikkahub.data.source.github

/**
 * Context-bounded facade over the GitHub REST reader.
 *
 * The model never receives an entire repository by default. It first gets a compact inspection,
 * can list a bounded slice of the tree, then reads explicit files in bounded line windows.
 */
internal class GitHubRepositorySource(
    private val client: GitHubRepositoryClient,
) {
    suspend fun inspect(url: String, ref: String? = null): GitHubRepositoryInspection {
        val repository = parse(url)
        val metadata = client.getMetadata(repository)
        val resolvedRef = ref?.let(GitHubRepositoryPolicy::normalizeRef) ?: metadata.defaultBranch
        val tree = client.listTree(repository, resolvedRef)
        val topLevel = tree.entries
            .asSequence()
            .map { it.path.substringBefore('/') }
            .distinct()
            .sorted()
            .take(MAX_TOP_LEVEL_PATHS)
            .toList()
        val importantFiles = rankImportantPaths(tree.entries)
            .take(MAX_IMPORTANT_FILES)

        return GitHubRepositoryInspection(
            repository = repository,
            metadata = metadata,
            ref = resolvedRef,
            totalEntries = tree.entries.size,
            treeTruncatedByGitHub = tree.truncated,
            topLevelPaths = topLevel,
            importantFiles = importantFiles,
        )
    }

    suspend fun listTree(
        url: String,
        ref: String? = null,
        prefix: String? = null,
        limit: Int = DEFAULT_TREE_LIMIT,
    ): GitHubRepositoryTreeSlice {
        val repository = parse(url)
        val metadata = if (ref == null) client.getMetadata(repository) else null
        val resolvedRef = ref?.let(GitHubRepositoryPolicy::normalizeRef) ?: requireNotNull(metadata).defaultBranch
        val safePrefix = prefix?.let { GitHubRepositoryPolicy.normalizePath(it, allowEmpty = true) }.orEmpty()
        val safeLimit = limit.coerceIn(1, MAX_TREE_LIMIT)
        val all = client.listTree(repository, resolvedRef)
        val filtered = all.entries.asSequence()
            .filter { safePrefix.isEmpty() || it.path == safePrefix || it.path.startsWith("$safePrefix/") }
        val page = filtered.take(safeLimit + 1).toList()

        return GitHubRepositoryTreeSlice(
            repository = repository,
            ref = resolvedRef,
            prefix = safePrefix.ifBlank { null },
            entries = page.take(safeLimit),
            hasMore = page.size > safeLimit,
            treeTruncatedByGitHub = all.truncated,
        )
    }

    suspend fun readFile(
        url: String,
        path: String,
        ref: String? = null,
        startLine: Int? = null,
        endLine: Int? = null,
    ): GitHubFileWindow {
        val repository = parse(url)
        val metadata = if (ref == null) client.getMetadata(repository) else null
        val resolvedRef = ref?.let(GitHubRepositoryPolicy::normalizeRef) ?: requireNotNull(metadata).defaultBranch
        val file = client.readFile(repository, path, resolvedRef)
        val lines = file.text.split('\n')
        val totalLines = lines.size
        val requestedStart = (startLine ?: 1).coerceAtLeast(1)
        require(requestedStart <= maxOf(totalLines, 1)) {
            "start_line $requestedStart is beyond the file's $totalLines lines"
        }
        val requestedEnd = endLine ?: minOf(totalLines, requestedStart + DEFAULT_FILE_LINES - 1)
        require(requestedEnd >= requestedStart) { "end_line must be greater than or equal to start_line" }
        val boundedEnd = minOf(requestedEnd, totalLines, requestedStart + MAX_FILE_LINES - 1)
        val text = if (totalLines == 0) "" else lines
            .subList(requestedStart - 1, boundedEnd)
            .joinToString("\n")

        return GitHubFileWindow(
            repository = repository,
            path = file.path,
            ref = file.ref,
            sha = file.sha,
            size = file.size,
            totalLines = totalLines,
            startLine = requestedStart,
            endLine = boundedEnd,
            truncated = boundedEnd < totalLines || requestedEnd > boundedEnd,
            text = text,
        )
    }

    fun canHandle(input: String): Boolean = GitHubRepositoryRef.parse(input) != null

    private fun parse(url: String): GitHubRepositoryRef = GitHubRepositoryRef.parse(url)
        ?: throw IllegalArgumentException("Expected a GitHub repository URL such as https://github.com/owner/repo")

    internal companion object {
        const val DEFAULT_TREE_LIMIT = 200
        const val MAX_TREE_LIMIT = 500
        const val DEFAULT_FILE_LINES = 300
        const val MAX_FILE_LINES = 500
        const val MAX_TOP_LEVEL_PATHS = 100
        const val MAX_IMPORTANT_FILES = 80

        internal fun rankImportantPaths(entries: List<GitHubTreeEntry>): List<String> = entries
            .asSequence()
            .filter { it.type == "blob" }
            .map { it.path }
            .mapNotNull { path -> importance(path)?.let { score -> score to path } }
            .sortedWith(compareBy<Pair<Int, String>> { it.first }.thenBy { it.second })
            .map { it.second }
            .toList()

        private fun importance(path: String): Int? {
            val lower = path.lowercase()
            val name = lower.substringAfterLast('/')
            val depth = lower.count { it == '/' }
            return when {
                name == "agents.md" || name == "claude.md" -> depth
                name.startsWith("readme") -> 10 + depth
                name in setOf(
                    "settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle",
                    "package.json", "pnpm-workspace.yaml", "turbo.json", "pyproject.toml",
                    "requirements.txt", "pom.xml", "go.mod", "cargo.toml", "composer.json",
                    "gemfile", "dockerfile", "docker-compose.yml", "docker-compose.yaml",
                ) -> 20 + depth
                lower.startsWith(".github/workflows/") && (name.endsWith(".yml") || name.endsWith(".yaml")) -> 30 + depth
                else -> null
            }
        }
    }
}

internal data class GitHubRepositoryInspection(
    val repository: GitHubRepositoryRef,
    val metadata: GitHubRepositoryMetadata,
    val ref: String,
    val totalEntries: Int,
    val treeTruncatedByGitHub: Boolean,
    val topLevelPaths: List<String>,
    val importantFiles: List<String>,
)

internal data class GitHubRepositoryTreeSlice(
    val repository: GitHubRepositoryRef,
    val ref: String,
    val prefix: String?,
    val entries: List<GitHubTreeEntry>,
    val hasMore: Boolean,
    val treeTruncatedByGitHub: Boolean,
)

internal data class GitHubFileWindow(
    val repository: GitHubRepositoryRef,
    val path: String,
    val ref: String,
    val sha: String?,
    val size: Long,
    val totalLines: Int,
    val startLine: Int,
    val endLine: Int,
    val truncated: Boolean,
    val text: String,
)
