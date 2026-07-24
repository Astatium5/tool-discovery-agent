package perception.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import profile.ApplicationProfile
import profile.ComponentRole

object UiTreeParser {
    /**
     * The active application profile.  Must be set before [parse] / [toSnapshot]
     * are called.  When null the parser falls back to built-in defaults so that
     * existing tests keep working until they supply a profile.
     */
    var profile: ApplicationProfile? = null

    // ── Fallback defaults (used only when no profile is loaded) ─────────────

    private val DEFAULT_LAYOUT_CLASSES =
        setOf(
            "JRootPane", "IdeRootPane", "JLayeredPane", "JBLayeredPane",
            "JPanel", "NonOpaquePanel", "MyNonOpaquePanel",
            "BorderLayoutPanel", "Wrapper", "Centerizer",
            "JScrollPane", "JBScrollPane", "MyScrollPane", "JBViewport",
            "SouthPanel", "ActionPanel", "StripeV2",
            "JBScrollBar", "InplaceButton",
            "MacToolbarFrameHeader", "MainToolbar",
            "MyActionToolbarImpl", "ActionToolbarImpl", "MySeparator",
        )

    private val DEFAULT_KEEP_CLASSES =
        setOf(
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

    private val DEFAULT_SUBMENU_CLASSES = setOf("ActionMenu")

    private val DEFAULT_EDITOR_CLASSES = setOf("EditorComponentImpl")

    private val ALWAYS_DROP = setOf("hidden", "")

    // ── Profile-aware helpers ───────────────────────────────────────────────

    private fun isLayoutClass(cls: String): Boolean = profile?.isLayoutContainer(cls) ?: (cls in DEFAULT_LAYOUT_CLASSES)

    private fun isKeepClass(cls: String): Boolean = profile?.isSignificantClass(cls) ?: (cls in DEFAULT_KEEP_CLASSES)

    private fun isSubmenuClass(cls: String): Boolean = profile?.hasSubmenuIndicator(cls) ?: (cls in DEFAULT_SUBMENU_CLASSES)

    private fun isEditorClass(cls: String): Boolean = profile?.isEditor(cls) ?: (cls in DEFAULT_EDITOR_CLASSES)

    // ── Parsing ─────────────────────────────────────────────────────────────

    fun parse(html: String): List<UiComponent> {
        val doc = Jsoup.parse(html)
        val selector = buildRootSelector()
        return doc.select(selector).mapNotNull { parseNode(it) }
    }

    /**
     * Build a CSS selector for root-level containers from the profile.
     * Falls back to the original hardcoded selector when no profile is loaded.
     */
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
        // Robot HTML may expose label either under @text or @visible_text; prefer
        // the richer one so buttons / labels with only visible_text aren't lost.
        val textAttr = el.attr("text").trim()
        val visibleTextAttr = el.attr("visible_text").trim()
        val rawText =
            when {
                textAttr.isNotBlank() -> textAttr
                visibleTextAttr.isNotBlank() -> visibleTextAttr
                else -> ""
            }.replace(Regex("<[^>]+>"), "").take(120)
        val tooltip = el.attr("tooltiptext").trim().take(80)
        val enabled = el.attr("enabled") != "false"
        val focused = el.attr("focused") == "true" || accessibleName.contains("focused", ignoreCase = true)
        val visible = el.attr("visible") != "false"

        if (!visible) return null

        val children = el.children().mapNotNull { parseNode(it) }

        if (isLayoutClass(cls)) {
            return when {
                children.size == 1 -> children.first()
                children.size > 1 ->
                    UiComponent(
                        cls = cls,
                        text = "",
                        accessibleName = accessibleName,
                        tooltip = "",
                        enabled = enabled,
                        hasSubmenu = false,
                        children = children,
                        focused = focused,
                    )
                else -> null
            }
        }

        if (!isKeepClass(cls) &&
            rawText.isBlank() && accessibleName.isBlank() &&
            tooltip.isBlank() && children.isEmpty()
        ) {
            return null
        }

        val displayText =
            when {
                isEditorClass(cls) ->
                    "[editor: ${accessibleName.removePrefix("Editor for").trim()}]"
                else -> rawText
            }

        return UiComponent(
            cls = cls,
            text = displayText,
            accessibleName = accessibleName,
            tooltip = tooltip,
            enabled = enabled,
            hasSubmenu = isSubmenuClass(cls),
            children = children,
            focused = focused,
        )
    }

    fun flatten(nodes: List<UiComponent>): List<UiComponent> = nodes.flatMap { listOf(it) + flatten(it.children) }
}
