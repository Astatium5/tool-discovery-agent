package perception

import agent.AgentConfig.focusedTreeMaxDepth
import agent.AgentConfig.focusedTreeMaxElements
import agent.AgentConfig.uiTreeMaxDepth
import agent.AgentConfig.uiTreeMaxElements
import perception.tree.UiComponent
import perception.tree.UiTreeParser
import profile.ApplicationProfile
import profile.ComponentRole

/**
 * Formats UiComponent trees directly into LLM prompt strings.
 *
 * This eliminates the double transformation:
 * - Before: UiComponent → UIObserver.UIObservation → prompt string
 * - After: UiComponent → prompt string
 *
 * The formatter preserves all relevant information:
 * - Component class (cls)
 * - Accessible name
 * - Text content
 * - Enabled state
 * - Hierarchy depth (via indentation)
 */
object UiTreeFormatter {
    /**
     * Format a UI tree for LLM consumption.
     * @param uiTree The raw UI component tree
     * @param profile Application profile for role detection
     * @param maxDepth Maximum depth to traverse (default from AgentConfig)
     * @param maxElements Maximum elements to include (default from AgentConfig)
     * @return Formatted string for LLM
     */
    fun format(
        uiTree: List<UiComponent>,
        profile: ApplicationProfile,
        maxDepth: Int = uiTreeMaxDepth,
        maxElements: Int = uiTreeMaxElements,
    ): String {
        val sb = StringBuilder()
        val elementCount = intArrayOf(0)

        sb.append("## Current UI State\n\n")

        // Detect window types
        val windowSummary = detectWindows(uiTree, profile)
        sb.append(windowSummary.summary).append("\n\n")

        // Format components
        sb.append("### UI Components\n\n")
        sb.append("```\n")

        for (component in uiTree) {
            if (elementCount[0] >= maxElements) break
            formatComponent(component, profile, 0, maxDepth, sb, elementCount, maxElements)
        }

        if (elementCount[0] >= maxElements) {
            sb.append("... (truncated, ${elementCount[0]} elements)\n")
        }

        sb.append("```\n")

        return sb.toString()
    }

    /**
     * Format UI tree with focus on active dialog/popup if present.
     * When a dialog or popup is open, only that component is formatted.
     * This reduces noise and helps the LLM focus on the relevant UI.
     */
    fun formatFocused(
        uiTree: List<UiComponent>,
        profile: ApplicationProfile,
        maxDepth: Int = focusedTreeMaxDepth,
        maxElements: Int = focusedTreeMaxElements,
    ): String {
        val sb = StringBuilder()

        sb.append("## Current UI State\n\n")

        // Check for focused component (dialog/popup)
        val focusedComponent = extractFocusedComponent(uiTree, profile)

        if (focusedComponent != null) {
            // Format only the focused component
            sb.append("### Active ${focusedComponent.type}\n\n")
            sb.append("```\n")
            formatComponent(focusedComponent.component, profile, 0, maxDepth, sb, intArrayOf(0), maxElements)
            sb.append("```\n")
        } else {
            // Format full tree (existing behavior)
            val windowSummary = detectWindows(uiTree, profile)
            sb.append(windowSummary.summary).append("\n\n")
            sb.append("### UI Components\n\n")
            sb.append("```\n")
            for (component in uiTree) {
                formatComponent(component, profile, 0, maxDepth, sb, intArrayOf(0), maxElements)
            }
            sb.append("```\n")
        }

        return sb.toString()
    }

    /**
     * Data class to hold focused component information.
     */
    private data class FocusedComponent(
        val type: String,
        val component: UiComponent,
    )

    /**
     * Extract the focused component (dialog/popup) from the UI tree.
     * Priority: Dialog > Inline widget (popup with text input) > Popup menu
     */
    private fun extractFocusedComponent(
        uiTree: List<UiComponent>,
        profile: ApplicationProfile,
    ): FocusedComponent? {
        val allComponents = UiTreeParser.flatten(uiTree)

        // Helper functions with fallback for known IntelliJ classes
        fun isPopupWindow(cls: String): Boolean = profile.isPopupWindow(cls) || cls == "HeavyWeightWindow"

        fun isDialog(cls: String): Boolean = profile.isDialog(cls) || cls == "DialogRootPane" || cls == "MyDialog"

        // Priority 1: Dialog
        val dialog = allComponents.firstOrNull { isDialog(it.cls) }
        if (dialog != null) {
            return FocusedComponent("Dialog", dialog)
        }

        // Priority 2: Inline widget (popup with text input)
        val inlineWidget =
            allComponents.firstOrNull { component ->
                isPopupWindow(component.cls) &&
                    component.children.any { child ->
                        profile.isTextInput(child.cls) ||
                            profile.roleOf(child.cls) in setOf(ComponentRole.TEXT_FIELD, ComponentRole.TEXT_AREA)
                    }
            }
        if (inlineWidget != null) {
            return FocusedComponent("Inline Widget", inlineWidget)
        }

        // Priority 3: Popup menu
        val popup = allComponents.firstOrNull { isPopupWindow(it.cls) }
        if (popup != null) {
            return FocusedComponent("Popup", popup)
        }

        return null
    }

    /**
     * Detect window types from the UI tree.
     */
    private fun detectWindows(
        uiTree: List<UiComponent>,
        profile: ApplicationProfile,
    ): WindowSummary {
        val windows = mutableListOf<WindowInfo>()
        val allComponents = UiTreeParser.flatten(uiTree)

        for (component in allComponents) {
            val cls = component.cls

            when {
                profile.isDialog(cls) -> {
                    val title =
                        component.accessibleName.ifBlank {
                            component.text.ifBlank { "Dialog" }
                        }
                    if (windows.none { it.title == title && it.type == "dialog" }) {
                        windows.add(WindowInfo("dialog", title))
                    }
                }
                profile.isPopupWindow(cls) -> {
                    val title = component.accessibleName.ifBlank { "Popup" }
                    if (windows.none { it.title == title && it.type == "popup" }) {
                        // Check if it's an inline widget (popup with text input)
                        val hasTextInput =
                            component.children.any { child ->
                                profile.isTextInput(child.cls) ||
                                    profile.roleOf(child.cls) in setOf(ComponentRole.TEXT_FIELD, ComponentRole.TEXT_AREA)
                            }
                        val type = if (hasTextInput) "inline_widget" else "popup"
                        windows.add(WindowInfo(type, title))
                    }
                }
                profile.isEditor(cls) -> {
                    if (windows.none { it.type == "editor" }) {
                        windows.add(WindowInfo("editor", "Editor"))
                    }
                }
            }
        }

        // Default to editor if nothing detected
        if (windows.isEmpty()) {
            windows.add(WindowInfo("editor", "Main Window"))
        }

        return WindowSummary(windows)
    }

    /**
     * Format a single component and its children.
     */
    private fun formatComponent(
        component: UiComponent,
        profile: ApplicationProfile,
        depth: Int,
        maxDepth: Int,
        sb: StringBuilder,
        elementCount: IntArray,
        maxElements: Int,
    ) {
        if (depth > maxDepth) return
        if (elementCount[0] >= maxElements) return

        elementCount[0]++
        val indent = "  ".repeat(depth)

        // Format this component
        val role = determineRole(component.cls, profile)
        val name = component.accessibleName.ifBlank { component.text }
        val disabled = if (!component.enabled) " [disabled]" else ""

        // Build the line
        val clsShort = component.cls.substringAfterLast(".")
        sb.append(indent).append(clsShort)

        if (name.isNotBlank()) {
            sb.append(" '").append(name).append("'")
        }

        if (role != "unknown") {
            sb.append(" (").append(role).append(")")
        }

        sb.append(disabled).append("\n")

        // Format children
        for (child in component.children) {
            if (elementCount[0] >= maxElements) break
            formatComponent(child, profile, depth + 1, maxDepth, sb, elementCount, maxElements)
        }
    }

    /**
     * Determine the role of a component.
     */
    private fun determineRole(
        cls: String,
        profile: ApplicationProfile,
    ): String {
        return when {
            profile.isButton(cls) -> "button"
            profile.isTextInput(cls) -> "text_input"
            profile.isDropdown(cls) -> "dropdown"
            profile.isCheckbox(cls) -> "checkbox"
            profile.isTree(cls) -> "tree"
            profile.isTable(cls) -> "table"
            profile.isList(cls) -> "list"
            cls.contains("MenuItem") || cls.contains("Action") -> "menu_item"
            cls.contains("Tab") -> "tab"
            profile.roleOf(cls) == ComponentRole.LABEL -> "label"
            else -> "unknown"
        }
    }

    /**
     * Summary of detected windows.
     */
    private data class WindowSummary(
        val windows: List<WindowInfo>,
    ) {
        val summary: String
            get() =
                when {
                    windows.isEmpty() -> "No windows detected."
                    windows.size == 1 -> {
                        val w = windows[0]
                        "Single ${w.type.replace("_", " ")} visible: ${w.title}"
                    }
                    else -> {
                        "${windows.size} windows: " +
                            windows.joinToString(", ") { "${it.title} (${it.type})" }
                    }
                }
    }

    /**
     * Information about a detected window.
     */
    private data class WindowInfo(
        val type: String,
        val title: String,
    )
}
