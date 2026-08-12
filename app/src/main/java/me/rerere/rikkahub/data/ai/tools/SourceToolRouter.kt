package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.source.document.LocalDocumentSource
import me.rerere.rikkahub.data.source.github.GitHubRepositorySource

/**
 * Chooses source-reading tools for the current model request.
 *
 * Source tools are demand-loaded instead of attached to every request, keeping schema/token
 * overhead out of ordinary chats. Authentication and local file paths stay below this boundary;
 * the model only sees stable source identifiers and bounded read operations.
 */
class SourceToolRouter internal constructor(
    private val gitHubRepositorySource: GitHubRepositorySource,
) {
    fun toolsFor(messages: List<UIMessage>): List<Tool> = buildList {
        if (shouldExposeGitHubRepositoryTools(messages)) {
            addAll(createGitHubRepositoryTools(gitHubRepositorySource))
        }
        val documents = routedDocuments(messages)
        if (documents.isNotEmpty()) {
            addAll(createLocalDocumentTools(LocalDocumentSource, documents))
        }
    }
}
