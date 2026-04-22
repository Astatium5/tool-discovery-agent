package model

/**
 * RecipeStep represents a single step in a tool execution recipe.
 *
 * Uses SEMANTIC IDENTIFIERS (labels, paths, symbols) - executor finds elements
 * by searching the UI tree via Remote Robot.
 *
 * Also supports VISION-BASED operations: ClickElement for coordinate clicking
 * when the element can't be found by xpath (toolbar buttons, etc).
 */
sealed class RecipeStep {
    // ── Navigation steps ──────────────────────────────────────────────────────
    data class OpenFile(val path: String) : RecipeStep()

    // ── Precondition steps ──────────────────────────────────────────────────────
    data class MoveCaret(val toSymbol: String) : RecipeStep()

    data class SelectLines(val from: Int, val to: Int) : RecipeStep()

    data class SelectLinesPlaceholder(val from: String, val to: String) : RecipeStep()

    object FocusEditor : RecipeStep()

    // ── Invocation steps ──────────────────────────────────────────────────────
    data class PressShortcut(val keys: String) : RecipeStep()

    object OpenContextMenu : RecipeStep()

    data class ClickMenu(val label: String) : RecipeStep()

    // ── Vision-based steps (for numeric overlay clicking) ──────────────────────
    /** Click on element by numeric ID from vision overlay */
    data class ClickElement(val elementId: Int) : RecipeStep()

    /** Double-click on element by numeric ID (for selecting text in editor) */
    data class DoubleClickElement(val elementId: Int) : RecipeStep()

    /** Right-click on element by numeric ID */
    data class RightClickElement(val elementId: Int) : RecipeStep()

    // ── Dialog steps ──────────────────────────────────────────────────────
    data class TypeInDialog(val value: String) : RecipeStep()

    data class SelectDropdown(val value: String) : RecipeStep()

    data class ClickDialogButton(val label: String) : RecipeStep()

    data class PressKey(val key: String) : RecipeStep()

    object CancelDialog : RecipeStep()

    // ── Field Navigation steps ──────────────────────────────────────────────────────

    data class FocusField(val fieldLabel: String) : RecipeStep()

    data class SelectDropdownField(val fieldLabel: String, val value: String) : RecipeStep()

    data class SetCheckbox(val fieldLabel: String, val checked: Boolean) : RecipeStep()

    data class TableRowAction(val action: String, val rowIndex: Int? = null) : RecipeStep()

    fun describe(): String =
        when (this) {
            is OpenFile -> "Open file '$path'"
            is MoveCaret -> "Move caret onto '$toSymbol'"
            is SelectLines -> "Select lines $from-$to in editor"
            is SelectLinesPlaceholder -> "Select lines $from-$to in editor"
            is FocusEditor -> "Ensure editor is focused"
            is PressShortcut -> "Press $keys"
            is OpenContextMenu -> "Right-click to open context menu"
            is ClickMenu -> "Click menu item '$label'"
            is ClickElement -> "Click element #$elementId"
            is DoubleClickElement -> "Double-click element #$elementId"
            is RightClickElement -> "Right-click element #$elementId"
            is TypeInDialog -> "In dialog: type '$value'"
            is SelectDropdown -> "In dialog: select '$value' from dropdown"
            is ClickDialogButton -> "In dialog: click '$label' button"
            is PressKey -> "Press '$key' key"
            is CancelDialog -> "Press Escape to cancel"
            is FocusField -> "Focus field '$fieldLabel'"
            is SelectDropdownField -> "In field '$fieldLabel': select '$value'"
            is SetCheckbox -> "Set checkbox '$fieldLabel' to $checked"
            is TableRowAction -> "Table action: $action" + (rowIndex?.let { " on row $it" } ?: "")
        }

    fun toJsonType(): String =
        when (this) {
            is OpenFile -> "open_file"
            is MoveCaret -> "move_caret"
            is SelectLines -> "select_lines"
            is SelectLinesPlaceholder -> "select_lines"
            is FocusEditor -> "focus_editor"
            is PressShortcut -> "press_shortcut"
            is OpenContextMenu -> "right_click"
            is ClickMenu -> "click_menu"
            is ClickElement -> "click_element"
            is DoubleClickElement -> "double_click_element"
            is RightClickElement -> "right_click_element"
            is TypeInDialog -> "type_in_dialog"
            is SelectDropdown -> "select_dropdown"
            is ClickDialogButton -> "click_button"
            is PressKey -> "press_key"
            is CancelDialog -> "cancel"
            is FocusField -> "focus_field"
            is SelectDropdownField -> "select_dropdown_field"
            is SetCheckbox -> "set_checkbox"
            is TableRowAction -> "table_row_action"
        }

    fun toJsonParams(): Map<String, String> =
        when (this) {
            is OpenFile -> mapOf("path" to path)
            is MoveCaret -> mapOf("symbol" to toSymbol)
            is SelectLines -> mapOf("from" to from.toString(), "to" to to.toString())
            is SelectLinesPlaceholder -> mapOf("from" to from, "to" to to)
            is PressShortcut -> mapOf("keys" to keys)
            is ClickMenu -> mapOf("label" to label)
            is ClickElement -> mapOf("element_id" to elementId.toString())
            is DoubleClickElement -> mapOf("element_id" to elementId.toString())
            is RightClickElement -> mapOf("element_id" to elementId.toString())
            is TypeInDialog -> mapOf("value" to value)
            is SelectDropdown -> mapOf("value" to value)
            is ClickDialogButton -> mapOf("label" to label)
            is PressKey -> mapOf("key" to key)
            is FocusField -> mapOf("field_label" to fieldLabel)
            is SelectDropdownField -> mapOf("field_label" to fieldLabel, "value" to value)
            is SetCheckbox -> mapOf("field_label" to fieldLabel, "checked" to checked.toString())
            is TableRowAction ->
                mapOf(
                    "action" to action,
                    "row_index" to (rowIndex?.toString() ?: ""),
                ).filterValues { it.isNotEmpty() }
            else -> emptyMap()
        }

    companion object {
        fun fromJson(type: String, params: Map<String, String>): RecipeStep =
            when (type) {
                "open_file" -> OpenFile(params["path"] ?: "")
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
                "press_shortcut" -> PressShortcut(params["keys"] ?: "")
                "right_click" -> OpenContextMenu
                "click_menu" -> ClickMenu(params["label"] ?: "")
                "click_element" -> ClickElement(params["element_id"]?.toIntOrNull() ?: 0)
                "double_click_element" -> DoubleClickElement(params["element_id"]?.toIntOrNull() ?: 0)
                "right_click_element" -> RightClickElement(params["element_id"]?.toIntOrNull() ?: 0)
                "type_in_dialog" -> TypeInDialog(params["value"] ?: "")
                "select_dropdown" -> SelectDropdown(params["value"] ?: "")
                "click_button" -> ClickDialogButton(params["label"] ?: "")
                "press_key" -> PressKey(params["key"] ?: "Enter")
                "cancel" -> CancelDialog
                "focus_field" -> FocusField(params["field_label"] ?: "")
                "select_dropdown_field" -> SelectDropdownField(params["field_label"] ?: "", params["value"] ?: "")
                "set_checkbox" -> SetCheckbox(params["field_label"] ?: "", params["checked"]?.toBoolean() ?: false)
                "table_row_action" -> TableRowAction(params["action"] ?: "", params["row_index"]?.toIntOrNull())
                else -> throw IllegalArgumentException("Unknown step type: $type")
            }
    }
}