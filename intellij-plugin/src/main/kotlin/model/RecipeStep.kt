package model

/**
 * RecipeStep represents a single step in a tool execution recipe.
 *
 * Steps are categorized by their purpose:
 * - Navigation: Move to a file/location
 * - Precondition: Set up the context (selection, caret position)
 * - Invocation: Open the tool (menu, shortcut)
 * - Dialog: Interact with dialog fields
 * - Field Navigation: Navigate to specific fields in dialogs (new in declarative model)
 */
sealed class RecipeStep {
    // ── Navigation steps ──────────────────────────────────────────────────────
    data class OpenFile(val path: String) : RecipeStep()

    // ── Precondition steps ──────────────────────────────────────────────────────
    data class MoveCaret(val toSymbol: String) : RecipeStep()

    data class SelectLines(val from: Int, val to: Int) : RecipeStep()

    /** Placeholder variant used in reusable recipes; the execution agent resolves the strings. */
    data class SelectLinesPlaceholder(val from: String, val to: String) : RecipeStep()

    object FocusEditor : RecipeStep()

    // ── Invocation steps ──────────────────────────────────────────────────────
    data class PressShortcut(val keys: String) : RecipeStep()

    object OpenContextMenu : RecipeStep()

    data class ClickMenu(val label: String) : RecipeStep()

    // ── Dialog steps (legacy - sequential typing) ──────────────────────────────
    data class TypeInDialog(val value: String) : RecipeStep()

    data class SelectDropdown(val value: String) : RecipeStep() // For dropdown selection

    data class ClickDialogButton(val label: String) : RecipeStep()

    data class PressKey(val key: String) : RecipeStep()

    object CancelDialog : RecipeStep()

    // ── Field Navigation steps (new in declarative model) ──────────────────────

    /**
     * Focus a specific field in a dialog by its label.
     * Used for declarative execution to navigate to specific fields.
     *
     * Example: FocusField("Name:") focuses the name input field.
     */
    data class FocusField(val fieldLabel: String) : RecipeStep()

    /**
     * Select a value from a specific dropdown field.
     * Combines field navigation with selection.
     *
     * Example: SelectDropdownField("Visibility:", "private")
     */
    data class SelectDropdownField(val fieldLabel: String, val value: String) : RecipeStep()

    /**
     * Set a checkbox to a specific state.
     *
     * Example: SetCheckbox("Declare final", true)
     */
    data class SetCheckbox(val fieldLabel: String, val checked: Boolean) : RecipeStep()

    /**
     * Perform an action on a table editor (Add, Remove, Up, Down).
     *
     * Example: TableRowAction("Add", null) - Add a new row
     * Example: TableRowAction("Up", 2) - Move row 2 up
     */
    data class TableRowAction(val action: String, val rowIndex: Int? = null) : RecipeStep()

    fun toJsonType(): String =
        when (this) {
            // Navigation
            is OpenFile -> "open_file"
            // Precondition
            is MoveCaret -> "move_caret"
            is SelectLines -> "select_lines"
            is SelectLinesPlaceholder -> "select_lines"
            is FocusEditor -> "focus_editor"
            // Invocation
            is PressShortcut -> "press_shortcut"
            is OpenContextMenu -> "right_click"
            is ClickMenu -> "click_menu"
            // Dialog (legacy)
            is TypeInDialog -> "type_in_dialog"
            is SelectDropdown -> "select_dropdown"
            is ClickDialogButton -> "click_button"
            is PressKey -> "press_key"
            is CancelDialog -> "cancel"
            // Field Navigation (new)
            is FocusField -> "focus_field"
            is SelectDropdownField -> "select_dropdown_field"
            is SetCheckbox -> "set_checkbox"
            is TableRowAction -> "table_row_action"
        }

    fun toJsonParams(): Map<String, String> =
        when (this) {
            // Navigation
            is OpenFile -> mapOf("path" to path)
            // Precondition
            is MoveCaret -> mapOf("symbol" to toSymbol)
            is SelectLines -> mapOf("from" to from.toString(), "to" to to.toString())
            is SelectLinesPlaceholder -> mapOf("from" to from, "to" to to)
            // Invocation
            is PressShortcut -> mapOf("keys" to keys)
            is ClickMenu -> mapOf("label" to label)
            // Dialog (legacy)
            is TypeInDialog -> mapOf("value" to value)
            is SelectDropdown -> mapOf("value" to value)
            is ClickDialogButton -> mapOf("label" to label)
            is PressKey -> mapOf("key" to key)
            // Field Navigation (new)
            is FocusField -> mapOf("field_label" to fieldLabel)
            is SelectDropdownField -> mapOf("field_label" to fieldLabel, "value" to value)
            is SetCheckbox -> mapOf("field_label" to fieldLabel, "checked" to checked.toString())
            is TableRowAction ->
                mapOf(
                    "action" to action,
                    "row_index" to (rowIndex?.toString() ?: ""),
                ).filterValues { it.isNotEmpty() }
            // Others with no params
            else -> emptyMap()
        }

    companion object {
        fun fromJson(
            type: String,
            params: Map<String, String>,
        ): RecipeStep =
            when (type) {
                // Navigation
                "open_file" -> OpenFile(params["path"] ?: "")
                // Precondition
                "move_caret" -> MoveCaret(params["symbol"] ?: "")
                "select_lines" -> {
                    val fromStr = params["from"] ?: "0"
                    val toStr = params["to"] ?: "0"
                    val fromInt = fromStr.toIntOrNull()
                    val toInt = toStr.toIntOrNull()
                    if (fromInt != null && toInt != null) {
                        SelectLines(fromInt, toInt)
                    } else {
                        SelectLinesPlaceholder(fromStr, toStr)
                    }
                }
                "focus_editor" -> FocusEditor
                // Invocation
                "press_shortcut" -> PressShortcut(params["keys"] ?: "")
                "right_click" -> OpenContextMenu
                "click_menu" -> ClickMenu(params["label"] ?: "")
                // Dialog (legacy)
                "type_in_dialog" -> TypeInDialog(params["value"] ?: "")
                "select_dropdown" -> SelectDropdown(params["value"] ?: "")
                "click_button" -> ClickDialogButton(params["label"] ?: "")
                "press_key" -> PressKey(params["key"] ?: "Enter")
                "cancel" -> CancelDialog
                // Field Navigation (new)
                "focus_field" -> FocusField(params["field_label"] ?: "")
                "select_dropdown_field" ->
                    SelectDropdownField(
                        params["field_label"] ?: "",
                        params["value"] ?: "",
                    )
                "set_checkbox" ->
                    SetCheckbox(
                        params["field_label"] ?: "",
                        params["checked"]?.toBoolean() ?: false,
                    )
                "table_row_action" ->
                    TableRowAction(
                        params["action"] ?: "",
                        params["row_index"]?.toIntOrNull(),
                    )
                else -> throw IllegalArgumentException("Unknown step type: $type")
            }

        fun describe(step: RecipeStep): String =
            when (step) {
                // Navigation
                is OpenFile -> "Open file '${step.path}'"
                // Precondition
                is MoveCaret -> "Move caret onto '${step.toSymbol}'"
                is SelectLines -> "Select lines ${step.from}-${step.to} in editor"
                is SelectLinesPlaceholder -> "Select lines ${step.from}-${step.to} in editor"
                is FocusEditor -> "Ensure editor is focused"
                // Invocation
                is PressShortcut -> "Press ${step.keys}"
                is OpenContextMenu -> "Right-click to open context menu"
                is ClickMenu -> "Click menu item '${step.label}'"
                // Dialog (legacy)
                is TypeInDialog -> "In dialog: type '${step.value}'"
                is SelectDropdown -> "In dialog: select '${step.value}' from dropdown"
                is ClickDialogButton -> "In dialog: click '${step.label}' button"
                is PressKey -> "Press '${step.key}' key"
                is CancelDialog -> "Press Escape to cancel"
                // Field Navigation (new)
                is FocusField -> "Focus field '${step.fieldLabel}'"
                is SelectDropdownField -> "In field '${step.fieldLabel}': select '${step.value}'"
                is SetCheckbox -> "Set checkbox '${step.fieldLabel}' to ${step.checked}"
                is TableRowAction ->
                    "Table action: ${step.action}" +
                        (step.rowIndex?.let { " on row $it" } ?: "")
            }
    }
}
