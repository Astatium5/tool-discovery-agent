package parser

import graph.PageState
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import profile.ApplicationProfile
import profile.ComponentRole

/**
 * Parses IntelliJ's Remote Robot HTML output into a structured UI component tree.
 *
 * This parser:
 * - Extracts interactive components from raw HTML
 * - Filters out layout containers to keep only meaningful elements
 * - Classifies pages (editor idle, dialogs, context menus, etc.)
 * - Supports an optional ApplicationProfile for customization
 *
 * When no profile is loaded, uses built-in defaults for IntelliJ IDEA.
 */
object UiTreeParser {
    /**
     * Optional application profile for customization.
     * When null, built-in defaults are used.
     */
    var profile: ApplicationProfile? = null

    /** Classes that represent layout containers (filtered out from final tree). */
    private val DEFAULT_LAYOUT_CLASSES = setOf(
        "JRootPane", "IdeRootPane", "JLayeredPane", "JBLayeredPane",
        "JPanel", "NonOpaquePanel", "MyNonOpaquePanel",
        "BorderLayoutPanel", "Wrapper", "Centerizer",
        "JScrollPane", "JBScrollPane", "MyScrollPane", "JBViewport",
        "SouthPanel", "ActionPanel", "StripeV2",
        "JBScrollBar", "InplaceButton",
        "MacToolbarFrameHeader", "MainToolbar",
        "MyActionToolbarImpl", "ActionToolbarImpl", "MySeparator",
    )

    /** Classes that represent interactive UI elements (kept in the tree). */
    private val DEFAULT_KEEP_CLASSES = setOf(
        "IdeFrameImpl", "HeavyWeightWindow", "DialogRootPane",
        "ActionMenuItem", "ActionMenu",
        "ActionButton", "ActionButtonWithText",
        "ToolbarComboButton", "CWMNewUIButton",
        "EditorComponentImpl", "ProjectViewTree", "MyProjectViewTree",
        "EditorTabLabel", "SimpleColoredComponent",
        "TextPanel", "NavBarItemComponent", "IdeStatusBarImpl",
        "SquareStripeButton",
        "JButton", "JTextField", "JBTextField",
        "ComboBox", "JCheckBox", "JBTable",
        "Tree", "JBList", "WithIconAndArrows",
    )

    /** Classes that indicate a menu item has a submenu. */
    private val DEFAULT_SUBMENU_CLASSES = setOf("ActionMenu")

    /** Classes that represent code editors. */
    private val DEFAULT_EDITOR_CLASSES = setOf("EditorComponentImpl")

    /** Element attributes that should always be dropped. */
    private val ALWAYS_DROP = setOf("hidden", "")

    private fun isLayoutClass(cls: String): Boolean =
        profile?.isLayoutContainer(cls) ?: (cls in DEFAULT_LAYOUT_CLASSES)

    private fun isKeepClass(cls: String): Boolean =
        profile?.isSignificantClass(cls) ?: (cls in DEFAULT_KEEP_CLASSES)

    private fun isSubmenuClass(cls: String): Boolean =
        profile?.hasSubmenuIndicator(cls) ?: (cls in DEFAULT_SUBMENU_CLASSES)

    private fun isEditorClass(cls: String): Boolean =
        profile?.isEditor(cls) ?: (cls in DEFAULT_EDITOR_CLASSES)

    private fun isPopupOrDialog(cls: String): Boolean {
        val p = profile
        return if (p != null) {
            p.isPopupWindow(cls) || p.isDialog(cls)
        } else {
            cls in setOf("HeavyWeightWindow", "DialogRootPane")
        }
    }

    private fun isPopupWindow(cls: String): Boolean =
        profile?.isPopupWindow(cls) ?: (cls == "HeavyWeightWindow")

    private fun isToolbarButton(cls: String): Boolean {
        val p = profile
        return if (p != null) {
            p.roleOf(cls) == ComponentRole.BUTTON || p.roleOf(cls) == ComponentRole.TOOLBAR
        } else {
            cls in setOf("ActionButton", "ActionButtonWithText", "ToolbarComboButton", "CWMNewUIButton")
        }
    }

    private fun isSidePanel(cls: String): Boolean {
        val p = profile
        return if (p != null) {
            p.roleOf(cls) == ComponentRole.BUTTON
        } else {
            cls == "SquareStripeButton"
        }
    }

    private fun isStatusBar(cls: String): Boolean {
        val p = profile
        return if (p != null) {
            p.roleOf(cls) == ComponentRole.STATUS_BAR || p.roleOf(cls) == ComponentRole.LABEL
        } else {
            cls in setOf("TextPanel", "IdeStatusBarImpl")
        }
    }

    private fun isTab(cls: String): Boolean {
        val p = profile
        return if (p != null) {
            p.roleOf(cls) == ComponentRole.TAB
        } else {
            cls == "EditorTabLabel"
        }
    }

    private fun isDialogInteractive(cls: String): Boolean {
        val p = profile
        return if (p != null) {
            ComponentRole.isDialogInteractive(p.roleOf(cls))
        } else {
            cls in setOf("ActionMenuItem", "ActionMenu", "JButton", "EditorComponentImpl", "JCheckBox", "ComboBox", "LookupList", "ActionButton")
        }
    }

    /**
     * Parse Remote Robot HTML into a UI component tree.
     *
     * @param html Raw HTML from Remote Robot endpoint
     * @return List of root-level UiComponent objects
     */
    fun parse(html: String): List<UiComponent> {
        val doc = Jsoup.parse(html)
        val selector = buildRootSelector()
        return doc.select(selector).mapNotNull { parseNode(it) }
    }

    private fun buildRootSelector(): String {
        val p = profile ?: return "div.IdeFrameImpl, div.HeavyWeightWindow"

        val rootClasses = mutableSetOf<String>()
        rootClasses.addAll(p.classesFor(ComponentRole.FRAME))
        rootClasses.addAll(p.classesFor(ComponentRole.POPUP_WINDOW))

        if (rootClasses.isEmpty()) return "div.IdeFrameImpl, div.HeavyWeightWindow"
        return rootClasses.joinToString(", ") { "div.$it" }
    }

    private fun parseNode(el: Element): UiComponent? {
        val cls = el.attr("class").trim()
        if (cls in ALWAYS_DROP) return null

        val accessibleName = el.attr("accessiblename").trim().take(80)
        val rawText = el.attr("text").trim().replace(Regex("<[^>]+>"), "").take(120)
        val tooltip = el.attr("tooltiptext").trim().take(80)
        val enabled = el.attr("enabled") != "false"
        val visible = el.attr("visible") != "false"

        if (!visible) return null

        val children = el.children().mapNotNull { parseNode(it) }

        if (isLayoutClass(cls)) {
            return when {
                children.size == 1 -> children.first()
                children.size > 1 -> UiComponent(
                    cls = cls,
                    text = "",
                    accessibleName = accessibleName,
                    tooltip = "",
                    enabled = enabled,
                    hasSubmenu = false,
                    children = children,
                )
                else -> null
            }
        }

        if (!isKeepClass(cls) && rawText.isBlank() && accessibleName.isBlank() && tooltip.isBlank() && children.isEmpty()) {
            return null
        }

        val displayText = if (isEditorClass(cls)) {
            "[editor: ${accessibleName.removePrefix("Editor for").trim()}]"
        } else {
            rawText
        }

        return UiComponent(
            cls = cls,
            text = displayText,
            accessibleName = accessibleName,
            tooltip = tooltip,
            enabled = enabled,
            hasSubmenu = isSubmenuClass(cls),
            children = children,
        )
    }

    /**
     * Create a high-level summary of the current IDE state.
     *
     * Extracts popups, open editors, toolbar actions, side panels, status bar,
     * and open tabs into a compact snapshot format.
     */
    fun toSnapshot(roots: List<UiComponent>): UiSnapshot {
        val all = flatten(roots)

        return UiSnapshot(
            popups = all
                .filter { isPopupOrDialog(it.cls) }
                .map { summarizePopup(it) }
                .filter { it.items.isNotEmpty() },
            editors = all
                .filter { isEditorClass(it.cls) }
                .map {
                    EditorState(
                        file = it.accessibleName.removePrefix("Editor for").trim(),
                        focused = it.accessibleName.contains("focused", ignoreCase = true),
                    )
                },
            toolbar = all
                .filter { isToolbarButton(it.cls) }
                .filter { it.enabled && it.label.isNotBlank() && it.label != it.cls }
                .map { toClickable(it) }
                .distinctBy { it.label },
            panels = all
                .filter { isSidePanel(it.cls) }
                .filter { it.label.isNotBlank() && it.label != it.cls }
                .map { toClickable(it) }
                .distinctBy { it.label },
            statusBar = all
                .filter { isStatusBar(it.cls) }
                .mapNotNull { it.accessibleName.ifBlank { it.text }.ifBlank { null } }
                .distinct(),
            tabs = all
                .filter { isTab(it.cls) }
                .map { it.label }
                .filter { it.isNotBlank() }
                .distinct(),
        )
    }

    private fun summarizePopup(popup: UiComponent): PopupSummary {
        val items = flatten(listOf(popup))
            .filter { isDialogInteractive(it.cls) }
            .map { toClickable(it) }
        return PopupSummary(popup.cls, items)
    }

    private fun toClickable(c: UiComponent) = ClickableComponent(
        label = c.label,
        cls = c.cls,
        hasSubmenu = c.hasSubmenu,
        enabled = c.enabled,
        xpath = c.xpath,
    )

    /**
     * Flatten a component tree into a single list.
     * Performs a depth-first traversal to collect all components.
     */
    fun flatten(nodes: List<UiComponent>): List<UiComponent> =
        nodes.flatMap { listOf(it) + flatten(it.children) }

    /**
     * Infer the current page state from a list of UI components.
     *
     * Analyzes the component tree to detect what kind of UI state we're in:
     * - Dialog: DialogRootPane detected
     * - Inline widget: HeavyWeightWindow with TextField
     * - Refactor submenu: HeavyWeightWindow with multiple popup menus
     * - Context menu: HeavyWeightWindow with single popup menu
     * - Editor idle: default state (no popups/dialogs)
     */
    fun inferPageState(
        components: List<UiComponent>,
        rawHtml: String = "",
    ): PageState {
        val allComponents = flatten(components)

        val dialogRootPane = allComponents.find {
            it.cls.contains("DialogRootPane", ignoreCase = true)
        }
        if (dialogRootPane != null) {
            val dialogName = dialogRootPane.accessibleName
                .takeIf { it.isNotBlank() }
                ?: dialogRootPane.text.takeIf { it.isNotBlank() }
                ?: "unknown_dialog"

            val interactiveElements = allComponents.filter { isDialogInteractive(it.cls) }
            return PageState(
                pageId = "dialog_${dialogName.lowercase().replace(" ", "_")}",
                description = "Dialog window: $dialogName",
                elements = interactiveElements,
                rawHtml = rawHtml,
            )
        }

        val heavyWeightWindows = allComponents.filter {
            it.cls.contains("HeavyWeightWindow", ignoreCase = true)
        }

        if (heavyWeightWindows.isNotEmpty()) {
            val hasInlineRenameWidget = heavyWeightWindows.any { window ->
                flatten(listOf(window)).any {
                    it.cls.contains("TextField", ignoreCase = true) ||
                        it.cls.contains("ComboBox", ignoreCase = true) ||
                        it.cls.contains("Lookup", ignoreCase = true)
                }
            }
            if (hasInlineRenameWidget) {
                val interactiveElements = allComponents.filter { isDialogInteractive(it.cls) }
                return PageState(
                    pageId = "inline_widget",
                    description = "Inline widget with input field",
                    elements = interactiveElements,
                    rawHtml = rawHtml,
                )
            }

            val popupCount = heavyWeightWindows.count { window ->
                flatten(listOf(window)).any {
                    it.cls.contains("ActionMenu", ignoreCase = true) ||
                        it.cls.contains("ActionMenuItem", ignoreCase = true)
                }
            }

            val interactiveElements = allComponents.filter { isDialogInteractive(it.cls) }
            return if (popupCount > 1) {
                PageState(
                    pageId = "refactor_submenu",
                    description = "Refactor submenu with multiple options",
                    elements = interactiveElements,
                    rawHtml = rawHtml,
                )
            } else {
                PageState(
                    pageId = "context_menu",
                    description = "Context menu with actions",
                    elements = interactiveElements,
                    rawHtml = rawHtml,
                )
            }
        }

        val interactiveElements = allComponents.filter {
            isToolbarButton(it.cls) || isSidePanel(it.cls) || isEditorClass(it.cls)
        }
        return PageState(
            pageId = "editor_idle",
            description = "Editor in idle state",
            elements = interactiveElements,
            rawHtml = rawHtml,
        )
    }
}
