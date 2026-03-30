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

    /**
     * Generate prompt string with only new/changed elements.
     * For first visit or no delta available, falls back to full listing.
     */
    fun toDeltaPromptString(hasVisitedBefore: Boolean, knownElements: List<ElementNode>): String = buildString {
        appendLine("## Current UI: $pageId")
        appendLine(description)
        appendLine()

        if (elements.isEmpty()) {
            appendLine("(No interactive elements detected)")
            return@buildString
        }

        val knownByLabel = knownElements.associateBy { it.label }

        if (hasVisitedBefore) {
            appendLine("This page has been visited before.")
            appendLine()

            val newElements = elements.filter { knownByLabel[it.label] == null }
            val changedElements = elements.filter { known ->
                val knownEl = knownByLabel[known.label]
                knownEl != null && (knownEl.cls != known.cls || knownEl.xpath != known.xpath)
            }
            val unchangedCount = elements.size - newElements.size - changedElements.size

            if (newElements.isNotEmpty()) {
                appendLine("### New elements since last visit:")
                newElements.forEachIndexed { index, element ->
                    val status = if (!element.enabled) " (disabled)" else ""
                    val submenu = if (element.hasSubmenu) " ->" else ""
                    appendLine("  ${index + 1}. \"${element.label}\"$status$submenu")
                    appendLine("     XPath: ${element.xpath}")
                }
                appendLine()
            }

            if (changedElements.isNotEmpty()) {
                appendLine("### Changed elements since last visit:")
                changedElements.forEachIndexed { index, element ->
                    val knownEl = knownByLabel[element.label]!!
                    val changes = mutableListOf<String>()
                    if (knownEl.cls != element.cls) changes += "class: ${knownEl.cls} → ${element.cls}"
                    if (knownEl.xpath != element.xpath) changes += "position changed"
                    appendLine("  - \"${element.label}\": ${changes.joinToString(", ")}")
                }
                appendLine()
            }

            appendLine("### Previously seen elements ($unchangedCount):")
            appendLine("(Not shown — stored in knowledge graph)")
        } else {
            appendLine("First visit to this page.")
            appendLine()
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
