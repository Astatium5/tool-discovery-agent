package profile

import kotlinx.serialization.Serializable

/**
 * Application-agnostic semantic roles for UI components.
 *
 * Instead of hardcoding toolkit-specific class names (e.g., "HeavyWeightWindow",
 * "ActionMenuItem"), every component class discovered in the UI tree is mapped to
 * one of these roles. All downstream code—parser, snapshot builder, discovery
 * agent—queries by role rather than by raw class name.
 */
@Serializable
enum class ComponentRole {
    POPUP_WINDOW,
    DIALOG,
    MENU_ITEM,
    MENU_CONTAINER,
    BUTTON,
    TEXT_FIELD,
    TEXT_AREA,
    EDITOR,
    CHECKBOX,
    DROPDOWN,
    LIST,
    TABLE,
    TREE,
    LABEL,
    SEPARATOR,
    TOOLBAR,
    TAB,
    PANEL,
    SCROLL_PANE,
    STATUS_BAR,
    FRAME,
    UNKNOWN,
    ;

    companion object {
        private val LAYOUT_ROLES = setOf(PANEL, SCROLL_PANE)
        private val INTERACTIVE_ROLES =
            setOf(
                MENU_ITEM, MENU_CONTAINER, BUTTON, TEXT_FIELD, TEXT_AREA,
                EDITOR, CHECKBOX, DROPDOWN, LIST, TABLE, TREE,
            )
        private val TEXT_INPUT_ROLES = setOf(TEXT_FIELD, TEXT_AREA, EDITOR)
        private val MENU_ROLES = setOf(MENU_ITEM, MENU_CONTAINER)
        private val DIALOG_INTERACTIVE_ROLES =
            setOf(
                MENU_ITEM, MENU_CONTAINER, BUTTON, TEXT_FIELD, TEXT_AREA,
                EDITOR, CHECKBOX, DROPDOWN, TABLE, LIST,
            )

        fun isLayout(role: ComponentRole) = role in LAYOUT_ROLES

        fun isInteractive(role: ComponentRole) = role in INTERACTIVE_ROLES

        fun isTextInput(role: ComponentRole) = role in TEXT_INPUT_ROLES

        fun isMenu(role: ComponentRole) = role in MENU_ROLES

        fun isDialogInteractive(role: ComponentRole) = role in DIALOG_INTERACTIVE_ROLES

        fun fromString(s: String): ComponentRole = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: UNKNOWN
    }
}
