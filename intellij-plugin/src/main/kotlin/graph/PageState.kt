package graph

import parser.UiComponent

data class PageState(
    val pageId: String,
    val description: String,
    val elements: List<UiComponent>,
    val rawHtml: String,
) {
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
