package graph

import parser.UiComponent

/**
 * Represents a snapshot of the UI at a specific moment.
 *
 * This is the output of the perception step - it captures the current UI state
 * in a structured way that can be fed to the LLM and used to update the knowledge graph.
 */
data class PageState(
    val pageId: String,                 // e.g. "editor_idle", "context_menu", "rename_dialog"
    val description: String,            // human-readable description of this UI state
    val elements: List<UiComponent>,    // interactive elements in this state
    val rawHtml: String                 // original HTML for debugging
) {
    /**
     * Format this PageState for inclusion in an LLM prompt.
     *
     * Returns a concise, structured description that helps the LLM understand:
     * - What UI state we're in
     * - What actions are available
     * - How to reference each element
     */
    fun toPromptString(): String = buildString {
        appendLine("## Current UI: $pageId")
        appendLine(description)
        appendLine()

        if (elements.isEmpty()) {
            appendLine("(No interactive elements detected)")
        } else {
            appendLine("Available actions:")
            elements.forEachIndexed { index, element ->
                val status = if (!element.enabled) " (disabled)" else ""
                val submenu = if (element.hasSubmenu) " ->" else ""
                appendLine("  ${index + 1}. \"${element.label}\"$status$submenu")
                appendLine("     XPath: ${element.xpath}")
            }
        }
    }
}
