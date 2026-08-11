package me.rerere.rikkahub.reliability

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the version comparator. We don't fire real HTTP — the [check]
 * method is reach-out + parse, exercised end-to-end by manual invocation via the
 * `check_app_updates` LLM tool. The comparator is the part with non-trivial logic
 * worth pinning.
 */
class GitHubReleaseCheckerTest {

    private val checker = GitHubReleaseChecker(OkHttpClient())

    @Test
    fun `patch version is newer`() {
        assertTrue(checker.isNewer("0.1.1", "0.1.0"))
        assertTrue(checker.isNewer("v0.1.1", "0.1.0"))
    }

    @Test
    fun `minor and major versions compare correctly`() {
        assertTrue(checker.isNewer("0.2.0", "0.1.99"))
        assertTrue(checker.isNewer("1.0.0", "0.99.999"))
    }

    @Test
    fun `same or older version is not newer`() {
        assertFalse(checker.isNewer("0.1.0", "0.1.0"))
        assertFalse(checker.isNewer("v0.1.0", "0.1.0"))
        assertFalse(checker.isNewer("0.0.99", "0.1.0"))
    }

    @Test
    fun `invalid or incomplete versions fail safe`() {
        assertFalse(checker.isNewer("totally bogus", "0.1.0"))
        assertFalse(checker.isNewer("0.1.0", "totally bogus"))
        assertFalse(checker.isNewer("0.1", "0.1.0"))
    }

    @Test
    fun `prerelease suffix compares using stable numeric core`() {
        assertTrue(checker.isNewer("0.2.0-rc1", "0.1.9"))
        assertFalse(checker.isNewer("0.1.0-rc1", "0.1.0"))
    }
}
