package me.rerere.rikkahub.data.source.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRepositorySourceTest {
    @Test
    fun `parses canonical and nested GitHub URLs into repository identity`() {
        assertEquals(
            GitHubRepositoryRef("Mtzallqmy", "moataz-ai6"),
            GitHubRepositoryRef.parse("https://github.com/Mtzallqmy/moataz-ai6"),
        )
        assertEquals(
            GitHubRepositoryRef("owner", "repo"),
            GitHubRepositoryRef.parse("github.com/owner/repo.git"),
        )
        assertEquals(
            GitHubRepositoryRef("owner", "repo"),
            GitHubRepositoryRef.parse("https://github.com/owner/repo/blob/main/README.md"),
        )
    }

    @Test
    fun `rejects non GitHub or insecure repository URLs`() {
        assertNull(GitHubRepositoryRef.parse("https://example.com/owner/repo"))
        assertNull(GitHubRepositoryRef.parse("http://github.com/owner/repo"))
        assertNull(GitHubRepositoryRef.parse("git@github.com:owner/repo.git"))
    }

    @Test
    fun `normalizes repository paths and blocks traversal`() {
        assertEquals(
            "app/src/main.kt",
            GitHubRepositoryPolicy.normalizePath("/app/src/main.kt"),
        )

        var traversalRejected = false
        try {
            GitHubRepositoryPolicy.normalizePath("app/../secret")
        } catch (_: IllegalArgumentException) {
            traversalRejected = true
        }
        assertTrue(traversalRejected)

        var backslashRejected = false
        try {
            GitHubRepositoryPolicy.normalizePath("app\\..\\secret")
        } catch (_: IllegalArgumentException) {
            backslashRejected = true
        }
        assertTrue(backslashRejected)
    }

    @Test
    fun `important path ranking favors agent docs and build manifests`() {
        val paths = GitHubRepositorySource.rankImportantPaths(
            listOf(
                GitHubTreeEntry("src/main.kt", "blob", 10, "a"),
                GitHubTreeEntry("README.md", "blob", 10, "b"),
                GitHubTreeEntry("docs/AGENTS.md", "blob", 10, "c"),
                GitHubTreeEntry("build.gradle.kts", "blob", 10, "d"),
                GitHubTreeEntry(".github/workflows/android.yml", "blob", 10, "e"),
                GitHubTreeEntry("src", "tree", null, "f"),
            )
        )

        assertEquals("docs/AGENTS.md", paths.first())
        assertTrue("README.md" in paths)
        assertTrue("build.gradle.kts" in paths)
        assertTrue(".github/workflows/android.yml" in paths)
        assertFalse("src/main.kt" in paths)
    }
}
