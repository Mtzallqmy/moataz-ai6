package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.source.github.GitHubRepositorySource

/**
 * Chooses source-reading tools for the current model request.
 *
 * Source tools are intentionally demand-loaded instead of being attached to every request. This
 * keeps tool schemas and token overhead out of ordinary chats, while still making a pasted source
 * reference immediately actionable. Authentication remains below this boundary: the router only
 * exposes read operations and never accepts credentials from the model.
 *
 * This is the first narrow seam of the broader Auto runtime. Additional sources (local documents,
 * workspaces, remote files) should plug in here rather than adding more branches to ChatService.
 */
class SourceToolRouter(
    private val gitHubRepositorySource: GitHubRepositorySource,
) {
    fun toolsFor(messages: List<UIMessage>): List<Tool> = buildList {
        if (shouldExposeGitHubRepositoryTools(messages)) {
            addAll(createGitHubRepositoryTools(gitHubRepositorySource))
        }
    }
}
