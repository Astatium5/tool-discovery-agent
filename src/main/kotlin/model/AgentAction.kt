package model

import kotlinx.serialization.Serializable

@Serializable
sealed class AgentAction {
    @Serializable
    data class OpenFile(val path: String) : AgentAction()

    @Serializable
    data class MoveCaret(val symbol: String) : AgentAction()

    @Serializable
    data class SelectLines(val start: Int, val end: Int) : AgentAction()

    @Serializable
    data class Click(val target: String) : AgentAction()

    /**
     * Click a menu item (context menu, main menu, submenu) by its visible
     * label. Dedicated form of [Click] that skips button-fallback heuristics
     * and goes straight to the menu-item XPath cascade — preferred whenever
     * the Active Context is POPUP_MENU or POPUP_CHOOSER so the agent doesn't
     * waste time probing for a button.
     */
    @Serializable
    data class ClickMenuItem(val target: String) : AgentAction()

    /**
     * Click a button inside the topmost dialog by its visible label. Dedicated
     * form of [Click] for DIALOG context; skips menu-item probes.
     */
    @Serializable
    data class ClickButton(val target: String) : AgentAction()

    @Serializable
    data class Type(val text: String, val clearFirst: Boolean = true, val target: String? = null) : AgentAction()

    @Serializable
    data class PressKey(val key: String) : AgentAction()

    /**
     * Open the IDE context menu at the current caret/focus location. First-
     * class replacement for `PressKey("context_menu")`.
     */
    @Serializable
    data object OpenContextMenu : AgentAction()

    /**
     * Close every open popup and dialog by pressing Escape until the window
     * stack stops shrinking. Recovery primitive for when the agent is stuck
     * in a stacked-popup dead-end.
     */
    @Serializable
    data object CloseAllPopups : AgentAction()

    @Serializable
    data class SelectDropdown(val target: String, val value: String) : AgentAction()

    @Serializable
    data class Wait(val elementType: String, val timeoutMs: Long = 5000) : AgentAction()

    @Serializable
    data class UseRecipe(val recipeId: String, val params: Map<String, String> = emptyMap()) : AgentAction()

    /** Focus the active code editor (equivalent to clicking inside the editor). */
    @Serializable
    data object FocusEditor : AgentAction()

    /** Cancel the topmost dialog / popup by sending Escape and verifying it closed. */
    @Serializable
    data object CancelDialog : AgentAction()

    /** Toggle a checkbox in the active dialog to the requested state. */
    @Serializable
    data class SetCheckbox(val target: String, val checked: Boolean) : AgentAction()

    /**
     * Scroll inside the active window.
     *
     * [target] optionally names a list/tree/table to scroll; when blank the
     * currently focused scrollable is used. [direction] is "up" / "down" /
     * "page_up" / "page_down" / "home" / "end". [amount] is optional step count.
     */
    @Serializable
    data class Scroll(
        val direction: String,
        val target: String = "",
        val amount: Int = 1,
    ) : AgentAction()

    /**
     * Non-side-effecting assertion over the current [perception.parser.ScopedSnapshotBuilder.CompactSnapshot].
     *
     * Supported [predicate] forms (case-insensitive):
     *  - "dialog_open:<title>"       — any dialog whose title contains <title>
     *  - "popup_open"                — a popup window is visible
     *  - "no_popup"                  — no popup/dialog is visible
     *  - "context=<CTX>"             — activeContext equals CTX (e.g. DIALOG)
     *  - "button_enabled:<label>"    — button exists and is enabled
     *  - "field_present:<label>"     — field with label exists
     *  - "focused:<label>"           — focused label contains <label>
     */
    @Serializable
    data class Verify(val predicate: String) : AgentAction()

    @Serializable
    data object Observe : AgentAction()

    @Serializable
    data object Complete : AgentAction()

    @Serializable
    data object Fail : AgentAction()

    companion object {
        fun describe(action: AgentAction): String =
            when (action) {
                is OpenFile -> "OpenFile('${action.path}')"
                is MoveCaret -> "MoveCaret('${action.symbol}')"
                is SelectLines -> "SelectLines(${action.start}-${action.end})"
                is Click -> "Click('${action.target}')"
                is ClickMenuItem -> "ClickMenuItem('${action.target}')"
                is ClickButton -> "ClickButton('${action.target}')"
                is Type -> "Type('${action.text}', clearFirst=${action.clearFirst})"
                is PressKey -> "PressKey('${action.key}')"
                is OpenContextMenu -> "OpenContextMenu"
                is CloseAllPopups -> "CloseAllPopups"
                is SelectDropdown -> "SelectDropdown('${action.target}', '${action.value}')"
                is Wait -> "Wait('${action.elementType}')"
                is UseRecipe -> "UseRecipe('${action.recipeId}')"
                is FocusEditor -> "FocusEditor"
                is CancelDialog -> "CancelDialog"
                is SetCheckbox -> "SetCheckbox('${action.target}', ${action.checked})"
                is Scroll -> "Scroll('${action.direction}', target='${action.target}', amount=${action.amount})"
                is Verify -> "Verify('${action.predicate}')"
                is Observe -> "Observe"
                is Complete -> "Complete"
                is Fail -> "Fail"
            }
    }
}
