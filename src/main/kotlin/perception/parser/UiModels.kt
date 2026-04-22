package perception.parser

data class UiComponent(
    val cls: String,
    val text: String,
    val accessibleName: String,
    val tooltip: String,
    val enabled: Boolean,
    val hasSubmenu: Boolean,
    val children: List<UiComponent>,
    val focused: Boolean = false,
) {
    val label get() =
        when {
            accessibleName.isNotBlank() -> accessibleName
            text.isNotBlank() -> text
            tooltip.isNotBlank() -> tooltip
            else -> cls
        }

    val xpath get() =
        when {
            accessibleName.isNotBlank() ->
                "//div[@class='$cls' and @accessiblename='${accessibleName.replace("'", "\\'")}']"
            text.isNotBlank() ->
                "//div[@class='$cls' and @text='${text.replace("'", "\\'")}']"
            tooltip.isNotBlank() ->
                "//div[@class='$cls' and @tooltiptext='${tooltip.replace("'", "\\'")}']"
            else -> "//div[@class='$cls']"
        }
}

data class UiSnapshot(
    val popups: List<PopupSummary>,
    val editors: List<EditorState>,
    val toolbar: List<ClickableComponent>,
    val panels: List<ClickableComponent>,
    val statusBar: List<String>,
    val tabs: List<String>,
) {
    fun hasPopup() = popups.isNotEmpty()

    fun toPromptString(): String =
        buildString {
            appendLine("=== CURRENT IDE STATE ===")

            if (popups.isNotEmpty()) {
                appendLine("\nACTIVE POPUPS/DIALOGS:")
                popups.forEach { popup ->
                    appendLine("  [${popup.type}]")
                    popup.items.forEach { item ->
                        val arrow = if (item.hasSubmenu) " ->" else ""
                        val dis = if (!item.enabled) " (disabled)" else ""
                        appendLine("    - \"${item.label}\"$arrow$dis")
                    }
                }
            } else {
                appendLine("\nNO POPUPS - editor is in focus")
            }

            if (editors.isNotEmpty()) {
                appendLine("\nOPEN EDITORS:")
                editors.forEach { appendLine("  - ${it.file}${if (it.focused) " (focused)" else ""}") }
            }

            if (tabs.isNotEmpty()) {
                appendLine("\nOPEN TABS: ${tabs.joinToString(", ")}")
            }

            if (toolbar.isNotEmpty()) {
                appendLine("\nTOOLBAR ACTIONS:")
                toolbar.forEach { appendLine("  - \"${it.label}\"") }
            }

            if (panels.isNotEmpty()) {
                appendLine("\nSIDE PANELS:")
                panels.forEach { appendLine("  - \"${it.label}\"") }
            }

            if (statusBar.isNotEmpty()) {
                appendLine("\nSTATUS: ${statusBar.joinToString(" | ")}")
            }
        }
}

data class PopupSummary(val type: String, val items: List<ClickableComponent>)

data class ClickableComponent(
    val label: String,
    val cls: String,
    val hasSubmenu: Boolean,
    val enabled: Boolean,
    val xpath: String,
)

data class EditorState(val file: String, val focused: Boolean)
