package perception.tree

import profile.ApplicationProfile
import profile.ComponentRole

/**
 * Phase-scoped UI snapshot builder.
 *
 * Instead of sending the full IDE state to the LLM, each method extracts
 * only the UI slice relevant to the current agent phase, keeping token
 * usage to ~200-500 per call.
 *
 * All component identification is done through [ApplicationProfile] role
 * lookups.  When no profile is set, built-in defaults are used so that
 * existing call-sites keep working.
 */
object ScopedSnapshotBuilder {
    /**
     * Active application profile.  Set this before calling any method.
     * When null, methods fall back to hardcoded defaults.
     */
    var profile: ApplicationProfile? = null

    // ── Fallback defaults (used only when profile == null) ──────────────────

    private val DEFAULT_DIALOG_COMPONENT_CLASSES =
        setOf(
            "ActionMenuItem", "ActionMenu", "JButton",
            "EditorComponentImpl", "JCheckBox", "ComboBox",
            "JTextField", "JBTextField", "JBTable", "JBList",
        )

    private val DEFAULT_MENU_ITEM_CLASSES =
        setOf(
            "ActionMenuItem",
            "ActionMenu",
            "JMenuItem",
            "JMenu",
            "Menu",
            "CheckboxMenuItem",
            "RadioButtonMenuItem",
        )

    // ── Profile-aware helpers ───────────────────────────────────────────────

    private fun isPopupWindow(cls: String): Boolean = profile?.isPopupWindow(cls) ?: (cls == "HeavyWeightWindow")

    private fun isDialog(cls: String): Boolean = profile?.isDialog(cls) ?: (cls == "DialogRootPane")

    private fun isPopupOrDialog(cls: String): Boolean = isPopupWindow(cls) || isDialog(cls)

    private fun isMenuItem(cls: String): Boolean {
        val p = profile
        return if (p != null) {
            ComponentRole.isMenu(p.roleOf(cls))
        } else {
            cls in DEFAULT_MENU_ITEM_CLASSES
        }
    }

    private fun isDialogInteractive(cls: String): Boolean {
        val p = profile
        return if (p != null) {
            ComponentRole.isDialogInteractive(p.roleOf(cls))
        } else {
            cls in DEFAULT_DIALOG_COMPONENT_CLASSES
        }
    }

    private fun isEditor(cls: String): Boolean = profile?.isEditor(cls) ?: (cls == "EditorComponentImpl")

    private fun isTextField(cls: String): Boolean {
        val p = profile
        return if (p != null) {
            p.roleOf(cls) in setOf(ComponentRole.TEXT_FIELD, ComponentRole.EDITOR, ComponentRole.DROPDOWN, ComponentRole.CHECKBOX)
        } else {
            cls in setOf("JTextField", "JBTextField", "EditorComponentImpl", "ComboBox", "JCheckBox")
        }
    }

    private fun isButton(cls: String): Boolean = profile?.isButton(cls) ?: (cls == "JButton")

    private fun isList(cls: String): Boolean {
        val p = profile
        return if (p != null) {
            p.roleOf(cls) in setOf(ComponentRole.LIST, ComponentRole.TABLE, ComponentRole.TREE)
        } else {
            cls in setOf("JBList", "JBTable", "Tree")
        }
    }

    private fun isTab(cls: String): Boolean = profile?.let { it.roleOf(cls) == ComponentRole.TAB } ?: (cls == "EditorTabLabel")

    // ── Public data ─────────────────────────────────────────────────────────

    data class MenuItemInfo(
        val label: String,
        val enabled: Boolean,
        val hasSubmenu: Boolean,
        val shortcutHint: String,
    )

    // ── Popup / menu queries ────────────────────────────────────────────────

    fun forActivePopupStructured(roots: List<UiComponent>): List<MenuItemInfo> {
        val all = UiTreeParser.flatten(roots)
        val popups = all.filter { isPopupWindow(it.cls) }
        if (popups.isEmpty()) return emptyList()

        val topmost = popups.last()
        return extractMenuItems(listOf(topmost))
    }

    fun forActivePopupStructured(
        roots: List<UiComponent>,
        p: ApplicationProfile,
    ): List<MenuItemInfo> {
        profile = p
        return forActivePopupStructured(roots)
    }

    fun hasMultiplePopups(roots: List<UiComponent>): Boolean {
        val all = UiTreeParser.flatten(roots)
        return all.count { isPopupWindow(it.cls) } >= 2
    }

    fun popupCount(roots: List<UiComponent>): Int {
        val all = UiTreeParser.flatten(roots)
        return all.count { isPopupWindow(it.cls) }
    }

    fun forAllPopupsStructured(roots: List<UiComponent>): List<MenuItemInfo> {
        val all = UiTreeParser.flatten(roots)
        val popups = all.filter { isPopupWindow(it.cls) }
        if (popups.isEmpty()) return emptyList()
        return popups.flatMap { extractMenuItems(listOf(it)) }
    }

    private fun extractMenuItems(scope: List<UiComponent>): List<MenuItemInfo> =
        UiTreeParser.flatten(scope)
            .filter { isMenuItem(it.cls) }
            .filter { it.label.isNotBlank() && it.label != it.cls }
            .map { item ->
                val shortcutHint =
                    item.tooltip.ifBlank {
                        item.text.replace(item.accessibleName, "").trim()
                    }.trim()
                MenuItemInfo(
                    label = item.label,
                    enabled = item.enabled,
                    hasSubmenu = item.hasSubmenu,
                    shortcutHint = shortcutHint,
                )
            }

    // ── Response-type detection ─────────────────────────────────────────────

    fun detectResponseType(roots: List<UiComponent>): Triple<Boolean, Boolean, Boolean> {
        val all = UiTreeParser.flatten(roots)

        val hasDialog = all.any { isDialog(it.cls) }
        val popups = all.filter { isPopupWindow(it.cls) }
        val lastWindow = popups.lastOrNull()

        var hasInlineEditor = false
        var hasPopupChooser = false

        if (lastWindow != null && !hasDialog) {
            val windowChildren = UiTreeParser.flatten(listOf(lastWindow))
            hasInlineEditor = windowChildren.any { isEditor(it.cls) }
            hasPopupChooser = !hasInlineEditor && windowChildren.any { isList(it.cls) }
        }

        return Triple(hasDialog, hasInlineEditor, hasPopupChooser)
    }

    // ── Text representations for LLM prompts ────────────────────────────────

    fun forActivePopup(roots: List<UiComponent>): String {
        val all = UiTreeParser.flatten(roots)
        val popups = all.filter { isPopupWindow(it.cls) }
        if (popups.isEmpty()) return "NO POPUP OPEN"

        val topmost = popups.last()
        val items =
            UiTreeParser.flatten(listOf(topmost))
                .filter { isMenuItem(it.cls) }
                .filter { it.label.isNotBlank() && it.label != it.cls }

        return buildString {
            appendLine("POPUP MENU ITEMS:")
            items.forEach { item ->
                val arrow = if (item.hasSubmenu) " ->" else ""
                val dis = if (!item.enabled) " (disabled)" else ""
                appendLine("  - \"${item.label}\"$arrow$dis")
            }
            appendLine("\nTotal: ${items.size} items")
        }
    }

    fun forActiveDialog(roots: List<UiComponent>): String {
        val all = UiTreeParser.flatten(roots)

        val dialog =
            all.lastOrNull { isDialog(it.cls) }
                ?: all.lastOrNull { isPopupWindow(it.cls) }
                ?: return "NO DIALOG OPEN"

        val components =
            UiTreeParser.flatten(listOf(dialog))
                .filter { isDialogInteractive(it.cls) }
                .filter { it.label.isNotBlank() && it.label != it.cls }

        val fields = components.filter { isTextField(it.cls) }
        val buttons = components.filter { isButton(it.cls) }
        val menuItems = components.filter { isMenuItem(it.cls) }

        return buildString {
            appendLine("DIALOG STATE:")

            if (fields.isNotEmpty()) {
                appendLine("\n  FIELDS:")
                fields.forEach { f ->
                    val role = profile?.roleOf(f.cls)?.name ?: f.cls
                    appendLine("    - [$role] \"${f.label}\"${if (!f.enabled) " (disabled)" else ""}")
                }
            }

            if (buttons.isNotEmpty()) {
                appendLine("\n  BUTTONS:")
                buttons.forEach { b ->
                    appendLine("    - \"${b.label}\"${if (!b.enabled) " (disabled)" else ""}")
                }
            }

            if (menuItems.isNotEmpty()) {
                appendLine("\n  MENU ITEMS:")
                menuItems.forEach { m ->
                    val arrow = if (m.hasSubmenu) " ->" else ""
                    appendLine("    - \"${m.label}\"$arrow${if (!m.enabled) " (disabled)" else ""}")
                }
            }
        }
    }

    fun forEditorState(roots: List<UiComponent>): String {
        val all = UiTreeParser.flatten(roots)

        val editors =
            all
                .filter { isEditor(it.cls) }
                .map {
                    EditorState(
                        file = it.accessibleName.removePrefix("Editor for").trim(),
                        focused = it.accessibleName.contains("focused", ignoreCase = true),
                    )
                }

        val tabs =
            all
                .filter { isTab(it.cls) }
                .map { it.label }
                .filter { it.isNotBlank() }
                .distinct()

        val hasPopup = all.any { isPopupOrDialog(it.cls) }

        return buildString {
            appendLine("EDITOR STATE:")
            if (hasPopup) appendLine("  WARNING: A popup/dialog is currently open")
            if (editors.isNotEmpty()) {
                editors.forEach { e ->
                    appendLine("  - ${e.file}${if (e.focused) " (focused)" else ""}")
                }
            } else {
                appendLine("  No editors open")
            }
            if (tabs.isNotEmpty()) {
                appendLine("  TABS: ${tabs.joinToString(", ")}")
            }
        }
    }

    // ── Utility ─────────────────────────────────────────────────────────────

    fun findByLabel(
        roots: List<UiComponent>,
        label: String,
    ): ClickableComponent? {
        return UiTreeParser.flatten(roots)
            .filter { it.label.isNotBlank() }
            .firstOrNull { it.label.equals(label, ignoreCase = true) || it.label.contains(label, ignoreCase = true) }
            ?.let { ClickableComponent(it.label, it.cls, it.hasSubmenu, it.enabled, it.xpath) }
    }
}
