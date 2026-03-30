package observer

import parser.UiComponent
import parser.UiTreeParser
import profile.ApplicationProfile
import profile.ComponentRole

/**
 * UI Observer - Captures and describes the current UI state for LLM consumption.
 *
 * This component is responsible for:
 * 1. Fetching the UI tree from the IDE
 * 2. Building semantic descriptions of UI elements
 * 3. Detecting dialogs, popups, inline widgets, context menus
 * 4. Identifying interactive elements with their states
 *
 * The output is a structured observation that the LLM can use to make decisions.
 */
class UIObserver(private val profile: ApplicationProfile) {
    /**
     * Observation of the current UI state.
     */
    data class UIObservation(
        val windows: List<WindowInfo>,
        val focusedElement: ElementInfo?,
        val availableActions: List<ActionInfo>,
        val contextDescription: String,
        val rawUiTree: List<UiComponent>? = null,
    )

    /**
     * Information about a window (dialog, popup, editor, etc.)
     */
    data class WindowInfo(
        val type: WindowType,
        val title: String,
        val elements: List<ElementInfo>,
        val bounds: Bounds? = null,
    )

    /**
     * Type of window detected in the UI.
     */
    enum class WindowType {
        MAIN_EDITOR, // Main editor window
        DIALOG, // Modal dialog
        POPUP, // Popup menu or chooser
        INLINE_WIDGET, // Inline widget (e.g., rename popup in editor)
        CONTEXT_MENU, // Right-click context menu
        UNKNOWN, // Unrecognized window type
    }

    /**
     * Information about an interactive element.
     */
    data class ElementInfo(
        val role: ElementRole,
        val label: String, // Visible text or accessible name
        val value: String? = null, // Current value for inputs
        val enabled: Boolean = true,
        val focused: Boolean = false,
        val bounds: Bounds? = null,
        val options: List<String>? = null, // For dropdowns
        val isChecked: Boolean? = null, // For checkboxes
    )

    /**
     * Role of an interactive element.
     */
    enum class ElementRole {
        BUTTON,
        TEXT_FIELD,
        TEXT_AREA,
        DROPDOWN,
        CHECKBOX,
        LABEL,
        TREE,
        TABLE,
        LIST,
        MENU_ITEM,
        TAB,
        ICON,
        UNKNOWN,
    }

    /**
     * Bounds of a UI element.
     */
    data class Bounds(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * Information about an available action.
     */
    data class ActionInfo(
        val name: String,
        val description: String,
        val shortcut: String? = null,
    )

    /**
     * Observe the current UI state from a UI tree.
     *
     * @param uiTree The raw UI tree from the IDE
     * @return Structured observation for LLM consumption
     */
    fun observe(uiTree: List<UiComponent>): UIObservation {
        val windows = detectWindows(uiTree)
        val focusedElement = findFocusedElement(uiTree)
        val availableActions = extractAvailableActions(uiTree)
        val contextDescription = buildContextDescription(windows, focusedElement)

        return UIObservation(
            windows = windows,
            focusedElement = focusedElement,
            availableActions = availableActions,
            contextDescription = contextDescription,
            rawUiTree = uiTree,
        )
    }

    /**
     * Detect windows (dialogs, popups, editors) from the UI tree.
     * Uses flattened search to find dialogs/popups at any depth in the tree.
     */
    private fun detectWindows(uiTree: List<UiComponent>): List<WindowInfo> {
        val windows = mutableListOf<WindowInfo>()

        // First, check top-level components (existing behavior)
        for (component in uiTree) {
            println("    [UIObserver] Checking component: ${component.cls.take(50)}")

            val window = detectWindow(component)
            if (window != null) {
                println("    [UIObserver] Detected window: ${window.type} - ${window.title}")
                windows.add(window)
            }
        }

        // Also search ALL components recursively for dialogs/popups that might be nested
        // This is important for IntelliJ where dialogs can be embedded in the frame
        if (windows.isEmpty() || windows.all { it.type == WindowType.MAIN_EDITOR }) {
            val allComponents = UiTreeParser.flatten(uiTree)
            println("    [UIObserver] Searching ${allComponents.size} flattened components for dialogs/popups...")

            for (component in allComponents) {
                val className = component.cls

                // Check for dialog using profile
                if (profile.isDialog(className)) {
                    val title =
                        component.accessibleName.ifBlank {
                            component.text.ifBlank { "Dialog" }
                        }
                    // Avoid duplicates
                    if (windows.none { it.title == title && it.type == WindowType.DIALOG }) {
                        println("    [UIObserver] Found nested DIALOG: $title")
                        windows.add(
                            WindowInfo(
                                type = WindowType.DIALOG,
                                title = title,
                                elements = extractElements(component.children),
                            ),
                        )
                    }
                }

                // Check for popup using profile
                if (profile.isPopupWindow(className)) {
                    val title = component.accessibleName.ifBlank { "Popup" }
                    if (windows.none { it.title == title && it.type == WindowType.POPUP }) {
                        println("    [UIObserver] Found nested POPUP: $title")
                        windows.add(
                            WindowInfo(
                                type = WindowType.POPUP,
                                title = title,
                                elements = extractElements(component.children),
                            ),
                        )
                    }
                }
            }
        }

        // If no windows detected, treat the whole tree as main editor
        if (windows.isEmpty() && uiTree.isNotEmpty()) {
            println("    [UIObserver] No windows detected, treating as main editor")
            windows.add(
                WindowInfo(
                    type = WindowType.MAIN_EDITOR,
                    title = "Editor",
                    elements = extractElements(uiTree),
                ),
            )
        }

        return windows
    }

    /**
     * Detect a window from a UI component.
     */
    private fun detectWindow(component: UiComponent): WindowInfo? {
        val className = component.cls

        return when {
            profile.isDialog(className) -> {
                WindowInfo(
                    type = WindowType.DIALOG,
                    title = component.accessibleName.ifBlank { component.text.ifBlank { "Dialog" } },
                    elements = extractElements(component.children),
                )
            }
            profile.isPopupWindow(className) -> {
                // Check if it's a context menu
                val isContextMenu = hasMenuItems(component)
                WindowInfo(
                    type = if (isContextMenu) WindowType.CONTEXT_MENU else WindowType.POPUP,
                    title = component.accessibleName.ifBlank { "Popup" },
                    elements = extractElements(component.children),
                )
            }
            isInlineWidget(component) -> {
                WindowInfo(
                    type = WindowType.INLINE_WIDGET,
                    title = "Inline Widget",
                    elements = extractElements(listOf(component)),
                )
            }
            profile.isEditor(className) -> {
                WindowInfo(
                    type = WindowType.MAIN_EDITOR,
                    title = "Editor",
                    elements = extractElements(component.children),
                )
            }
            else -> null
        }
    }

    /**
     * Check if a component is an inline widget (e.g., rename popup in editor).
     *
     * CORRECT DETECTION: An inline widget is a text input inside a popup window.
     * - Popup container + text input = inline widget
     * - Text input alone (without popup) = regular text field in dialog
     *
     * This method checks if the component is a popup window that contains a text input,
     * which is the pattern for inline editors like the rename refactoring popup.
     */
    private fun isInlineWidget(component: UiComponent): Boolean {
        val className = component.cls

        // Only popup windows can be inline widgets
        if (!profile.isPopupWindow(className)) {
            return false
        }

        // Check if this popup contains a text input (the inline editor)
        val hasTextInput =
            component.children.any { child ->
                profile.isTextInput(child.cls) ||
                    profile.roleOf(child.cls) in setOf(ComponentRole.TEXT_FIELD, ComponentRole.TEXT_AREA)
            }

        // Also check nested children (popup -> panel -> text input)
        val hasNestedTextInput =
            component.children.any { child ->
                child.children.any { grandchild ->
                    profile.isTextInput(grandchild.cls) ||
                        profile.roleOf(grandchild.cls) in setOf(ComponentRole.TEXT_FIELD, ComponentRole.TEXT_AREA)
                }
            }

        return hasTextInput || hasNestedTextInput
    }

    /**
     * Check if a component has menu items (indicating it's a context menu).
     */
    private fun hasMenuItems(component: UiComponent): Boolean {
        return component.children.any { child ->
            val childClass = child.cls
            childClass.contains("MenuItem") || childClass.contains("Action")
        }
    }

    /**
     * Extract interactive elements from UI components.
     */
    private fun extractElements(components: List<UiComponent>): List<ElementInfo> {
        val elements = mutableListOf<ElementInfo>()
        for (component in components) {
            extractElementsRecursive(component, elements)
        }
        return elements
    }

    /**
     * Recursively extract elements from a component tree.
     */
    private fun extractElementsRecursive(
        component: UiComponent,
        elements: MutableList<ElementInfo>,
    ) {
        val element = extractElement(component)
        if (element != null) {
            elements.add(element)
        }

        for (child in component.children) {
            extractElementsRecursive(child, elements)
        }
    }

    /**
     * Extract an element from a UI component.
     * For buttons, uses text as fallback since buttons often have text but no label.
     */
    private fun extractElement(component: UiComponent): ElementInfo? {
        val className = component.cls
        val role = determineRole(className, component)
        if (role == ElementRole.UNKNOWN) return null

        // For buttons, be more lenient - use text as fallback
        // Buttons in IntelliJ often have text but no accessibleName/label
        val label =
            when (role) {
                ElementRole.BUTTON -> component.label.ifBlank { component.text }
                ElementRole.MENU_ITEM -> component.label.ifBlank { component.text }
                else -> component.label
            }

        if (label.isBlank() && role != ElementRole.ICON) return null

        return ElementInfo(
            role = role,
            label = label,
            value = component.text.ifBlank { null },
            enabled = component.enabled,
            focused = false, // UiComponent doesn't have focused state
            bounds = null, // UiComponent doesn't have bounds
            options = extractOptions(component, role),
            isChecked = null,
        )
    }

    /**
     * Determine the role of an element from its class name.
     */
    private fun determineRole(
        className: String,
        component: UiComponent,
    ): ElementRole {
        return when {
            profile.isButton(className) -> ElementRole.BUTTON
            profile.isTextInput(className) -> ElementRole.TEXT_FIELD
            profile.isDropdown(className) -> ElementRole.DROPDOWN
            profile.isCheckbox(className) -> ElementRole.CHECKBOX
            profile.isTree(className) -> ElementRole.TREE
            profile.isTable(className) -> ElementRole.TABLE
            profile.isList(className) -> ElementRole.LIST
            className.contains("MenuItem") || className.contains("Action") -> ElementRole.MENU_ITEM
            className.contains("Tab") -> ElementRole.TAB
            profile.roleOf(className) == ComponentRole.LABEL -> ElementRole.LABEL
            else -> ElementRole.UNKNOWN
        }
    }

    /**
     * Extract bounds from a component.
     */
    private fun extractBounds(component: UiComponent): Bounds? {
        // UiComponent doesn't have bounds info
        return null
    }

    /**
     * Extract options from a dropdown component.
     */
    private fun extractOptions(
        component: UiComponent,
        role: ElementRole,
    ): List<String>? {
        if (role != ElementRole.DROPDOWN) return null
        // Look for child items with text
        return component.children.mapNotNull { child ->
            child.label.ifBlank { null }
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * Find the currently focused element.
     */
    private fun findFocusedElement(uiTree: List<UiComponent>): ElementInfo? {
        // UiComponent doesn't track focus, return first interactive element
        for (component in uiTree) {
            val element = extractElement(component)
            if (element != null && element.enabled) {
                return element
            }
            val childElement = findFocusedElement(component.children)
            if (childElement != null) return childElement
        }
        return null
    }

    /**
     * Recursively find the focused element.
     */
    private fun findFocusedElementRecursive(component: UiComponent): ElementInfo? {
        val element = extractElement(component)
        if (element != null && element.enabled) {
            return element
        }
        for (child in component.children) {
            val focused = findFocusedElementRecursive(child)
            if (focused != null) return focused
        }
        return null
    }

    /**
     * Extract available actions from the UI tree.
     */
    private fun extractAvailableActions(uiTree: List<UiComponent>): List<ActionInfo> {
        // This would extract actions like menu items, buttons, etc.
        // For now, return empty list - can be enhanced later
        return emptyList()
    }

    /**
     * Build a human-readable context description for the LLM.
     */
    private fun buildContextDescription(
        windows: List<WindowInfo>,
        focusedElement: ElementInfo?,
    ): String {
        val sb = StringBuilder()

        when {
            windows.isEmpty() -> sb.append("No windows detected. ")
            windows.size == 1 -> {
                val window = windows[0]
                sb.append("Single ${window.type.name.lowercase().replace("_", " ")} visible: ${window.title}. ")
                if (window.elements.isNotEmpty()) {
                    sb.append("Contains ${window.elements.size} interactive elements. ")
                }
            }
            else -> {
                sb.append("${windows.size} windows detected: ")
                sb.append(windows.joinToString(", ") { "${it.title} (${it.type.name.lowercase()})" })
                sb.append(". ")
            }
        }

        focusedElement?.let { elem ->
            sb.append("Focused element: ${elem.role.name.lowercase().replace("_", " ")}")
            if (elem.label.isNotBlank()) {
                sb.append(" '${elem.label}'")
            }
            if (!elem.value.isNullOrBlank()) {
                sb.append(" with value '${elem.value}'")
            }
            sb.append(". ")
        }

        return sb.toString().trim()
    }

    /**
     * Format the observation for LLM consumption.
     */
    fun formatForLLM(observation: UIObservation): String {
        val sb = StringBuilder()

        sb.append("## Current UI State\n\n")
        sb.append(observation.contextDescription).append("\n\n")

        if (observation.windows.isNotEmpty()) {
            sb.append("### Windows\n\n")
            for (window in observation.windows) {
                sb.append("**${window.type.name}**: ${window.title}\n")
                if (window.elements.isNotEmpty()) {
                    sb.append("Elements:\n")
                    for (elem in window.elements.take(20)) { // Limit to avoid token limits
                        val state =
                            when {
                                !elem.enabled -> " (disabled)"
                                elem.focused -> " (focused)"
                                else -> ""
                            }
                        val value = elem.value?.let { " = '$it'" } ?: ""
                        sb.append("  - ${elem.role.name.lowercase()}: '${elem.label}'$value$state\n")
                    }
                    if (window.elements.size > 20) {
                        sb.append("  ... and ${window.elements.size - 20} more elements\n")
                    }
                }
                sb.append("\n")
            }
        }

        observation.focusedElement?.let { elem ->
            sb.append("### Focused Element\n\n")
            sb.append("- Role: ${elem.role.name}\n")
            sb.append("- Label: ${elem.label}\n")
            elem.value?.let { sb.append("- Value: $it\n") }
            sb.append("- Enabled: ${elem.enabled}\n")
            sb.append("\n")
        }

        return sb.toString()
    }
}
