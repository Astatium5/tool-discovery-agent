package perception.parser

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

    // ── Profile-aware helpers (with explicit profile parameter support) ──────

    // Name-based fallbacks used when the profile has not classified a class.
    // Catches the common IntelliJ / Swing class names so the snapshot stays
    // informative even when `app-profile.json` is stale or minimal — the
    // previous behaviour produced an empty snapshot that starved the LLM.

    private fun looksLikePopupByName(cls: String): Boolean =
        cls == "HeavyWeightWindow" ||
            cls.endsWith("Popup") ||
            cls.endsWith("PopupWindow") ||
            cls.contains("JBPopup") ||
            cls.contains("LightweightHint")

    /**
     * Classes that represent the actual popup CONTENT (as opposed to the
     * lightweight host window that Swing wraps every popup in). Used to
     * decide when a `HeavyWeightWindow` is redundant with a popup content
     * node nested inside it.
     */
    private fun isPopupContent(cls: String): Boolean =
        cls.endsWith("Popup") ||
            cls.endsWith("PopupWindow") ||
            cls.contains("JBPopup") ||
            cls.contains("LightweightHint")

    /**
     * Drop every `HeavyWeightWindow` whose subtree already contains a popup
     * content node — that node will be counted on its own. This brings the
     * popup count from "one entry per host + one per content" (2 per logical
     * popup) down to one entry per logical popup.
     */
    private fun dedupeHostWithContent(raw: List<UiComponent>): List<UiComponent> {
        fun hasPopupContent(n: UiComponent): Boolean {
            if (isPopupContent(n.cls)) return true
            for (c in n.children) if (hasPopupContent(c)) return true
            return false
        }
        return raw.filterNot { n ->
            n.cls == "HeavyWeightWindow" && hasPopupContent(n)
        }
    }

    private fun looksLikeDialogByName(cls: String): Boolean =
        cls == "DialogRootPane" ||
            cls == "MyDialog" ||
            cls == "JDialog" ||
            cls.endsWith("Dialog") ||
            cls.endsWith("DialogRootPane")

    private fun looksLikeMenuItemByName(cls: String): Boolean =
        cls in DEFAULT_MENU_ITEM_CLASSES ||
            cls.endsWith("MenuItem") ||
            cls == "ActionMenu"

    private fun looksLikeButtonByName(cls: String): Boolean =
        cls == "JButton" ||
            cls == "JBOptionButton" ||
            cls.endsWith("Button") && !cls.endsWith("StripeButton") && !cls.endsWith("LinkButton")

    private fun looksLikeTextFieldByName(cls: String): Boolean =
        cls in setOf("JTextField", "JBTextField", "EditorComponentImpl", "ComboBox", "JCheckBox")

    private fun looksLikeEditorByName(cls: String): Boolean =
        cls == "EditorComponentImpl" ||
            cls == "EditorImpl" ||
            cls == "EditorTextField" ||
            cls.endsWith("EditorComponentImpl") ||
            cls.endsWith("EditorImpl") ||
            cls.endsWith("EditorTextField")

    private fun looksLikeTabByName(cls: String): Boolean =
        cls == "EditorTabLabel" ||
            cls == "SingleHeightLabel" ||
            cls.endsWith("TabLabel")

    /**
     * IntelliJ exposes the file-path breadcrumb as a row of `NavBarItemComponent`.
     * We use their labels to identify the open file even when no profile entry
     * exists for the editor component.
     */
    private fun looksLikeBreadcrumbByName(cls: String): Boolean =
        cls == "NavBarItemComponent" ||
            cls.endsWith("NavBarItemComponent") ||
            cls == "NavBarPanel"

    private fun isPopupWindow(
        cls: String,
        p: ApplicationProfile? = profile,
    ): Boolean {
        val profileSays = p?.isPopupWindow(cls) ?: false
        return profileSays || looksLikePopupByName(cls)
    }

    private fun isDialog(
        cls: String,
        p: ApplicationProfile? = profile,
    ): Boolean {
        val profileSays = p?.isDialog(cls) ?: false
        return profileSays || looksLikeDialogByName(cls)
    }

    private fun isPopupOrDialog(
        cls: String,
        p: ApplicationProfile? = profile,
    ): Boolean = isPopupWindow(cls, p) || isDialog(cls, p)

    /**
     * Lightweight tree-only probe: is any visible dialog present anywhere
     * in this raw UI tree?
     *
     * Used by callers that need to decide whether it's safe to invoke
     * Remote Robot's `callJs` — heavy refactor dialogs (Change Signature,
     * Find Usages preview) enter a modal event loop that on macOS does
     * not reliably pump non-EDT `invokeAndWait` requests. A `callJs`
     * fired into that state times out client-side but leaves the
     * request stuck server-side, poisoning Remote Robot's dispatch
     * queue so that **every** subsequent `/api/tree` and
     * `/api/component/...` call hangs forever. Checking the raw tree
     * first (no JS round-trip) lets us bail before we create that
     * wedge.
     */
    fun containsDialog(
        roots: List<UiComponent>,
        p: ApplicationProfile? = null,
    ): Boolean {
        fun walk(n: UiComponent): Boolean {
            if (isDialog(n.cls, p)) return true
            return n.children.any { walk(it) }
        }
        return roots.any { walk(it) }
    }

    private fun isMenuItem(
        cls: String,
        p: ApplicationProfile? = profile,
    ): Boolean {
        val profileSays = p?.let { ComponentRole.isMenu(it.roleOf(cls)) } ?: false
        return profileSays || looksLikeMenuItemByName(cls)
    }

    private fun isDialogInteractive(
        cls: String,
        p: ApplicationProfile? = profile,
    ): Boolean {
        return if (p != null) {
            ComponentRole.isDialogInteractive(p.roleOf(cls))
        } else {
            cls in DEFAULT_DIALOG_COMPONENT_CLASSES
        }
    }

    private fun isEditor(
        cls: String,
        p: ApplicationProfile? = profile,
    ): Boolean {
        val profileSays = p?.isEditor(cls) ?: false
        return profileSays || looksLikeEditorByName(cls)
    }

    private fun isBreadcrumb(cls: String): Boolean = looksLikeBreadcrumbByName(cls)

    private fun isTextField(
        cls: String,
        p: ApplicationProfile? = profile,
    ): Boolean {
        val profileSays =
            p?.let {
                it.roleOf(cls) in
                    setOf(ComponentRole.TEXT_FIELD, ComponentRole.EDITOR, ComponentRole.DROPDOWN, ComponentRole.CHECKBOX)
            } ?: false
        return profileSays || looksLikeTextFieldByName(cls)
    }

    private fun isButton(
        cls: String,
        p: ApplicationProfile? = profile,
    ): Boolean {
        val profileSays = p?.isButton(cls) ?: false
        return profileSays || looksLikeButtonByName(cls)
    }

    private fun isList(
        cls: String,
        p: ApplicationProfile? = profile,
    ): Boolean {
        return if (p != null) {
            p.roleOf(cls) in setOf(ComponentRole.LIST, ComponentRole.TABLE, ComponentRole.TREE)
        } else {
            cls in setOf("JBList", "JBTable", "Tree")
        }
    }

    private fun isTab(
        cls: String,
        p: ApplicationProfile? = profile,
    ): Boolean {
        val profileSays = p?.let { it.roleOf(cls) == ComponentRole.TAB } ?: false
        return profileSays || looksLikeTabByName(cls)
    }

    // ── Public data ─────────────────────────────────────────────────────────

    data class MenuItemInfo(
        val label: String,
        val enabled: Boolean,
        val hasSubmenu: Boolean,
        val shortcutHint: String,
    )

    /**
     * Semantic classification of the current foreground UI.
     *
     * Derived deterministically from [detectResponseType] + popup heuristics.
     */
    enum class ActiveContext {
        EDITOR,
        POPUP_MENU,
        POPUP_CHOOSER,
        INLINE_WIDGET,
        DIALOG,
    }

    /**
     * One entry on the window stack. The topmost entry is the active window.
     * Background windows are listed by title + type only (no children) to keep
     * tokens tight.
     */
    data class WindowRef(
        val type: ActiveContext,
        val title: String,
    )

    /** Focused interactive component (if any). */
    data class FocusedItem(
        val role: String,
        val label: String,
        val cls: String,
    )

    /**
     * Source + caret details fetched from the live editor. Optional — only
     * populated when the caller pipes live data through
     * [buildCompactSnapshot]. When absent the snapshot still renders the
     * file / tabs / breadcrumb, just without code-level context.
     */
    data class EditorCode(
        val caretLine: Int,
        val caretColumn: Int,
        val totalLines: Int,
        val symbolUnderCaret: String,
        val selectedText: String,
        /** 0-indexed. Inclusive of the last rendered line. */
        val windowStartLine: Int,
        val windowEndLine: Int,
        val visibleText: String,
    )

    /** Compact view of the editor area. */
    data class EditorView(
        val file: String,
        val focused: Boolean,
        val tabs: List<String>,
        /**
         * Nav-bar breadcrumb, top-down (e.g. `project / src / foo / Bar.kt`).
         * Empty when no `NavBarItemComponent` could be found.
         */
        val breadcrumb: List<String> = emptyList(),
        /** Live caret + visible source window. `null` when unavailable. */
        val code: EditorCode? = null,
    )

    /**
     * Details of an in-place editing template — the state IntelliJ enters
     * after "Rename...", "Introduce Variable", "Extract Method", etc. The
     * old identifier is already selected in the editor; typing replaces it.
     *
     * Detection relies on tree signals (focused editor + suggestion list +
     * hint popup) rather than the profile so it works out of the box.
     */
    data class InlineWidget(
        val kind: String = "rename",
        /** Best guess at the identifier being renamed (from focused label). */
        val oldIdentifier: String,
        /** Name suggestions from the popup, if any. */
        val suggestions: List<String>,
        /** Human-readable hint surfaced by the IDE (e.g. "Press ↵ to replace"). */
        val hint: String,
    )

    /** Interactive element inside the active window. */
    data class InteractiveItem(
        val role: String,
        val label: String,
        val enabled: Boolean,
        val shortcutHint: String? = null,
    )

    /** Enumerated content of the topmost window. */
    data class ActiveWindowView(
        val title: String,
        val type: ActiveContext,
        val fields: List<InteractiveItem>,
        val buttons: List<InteractiveItem>,
        val menuItems: List<InteractiveItem>,
        val truncated: Int = 0,
    )

    /**
     * A small, deterministic LLM-facing view of the UI.
     *
     * - Only the topmost window's interactive items are enumerated.
     * - Every field is bounded; long sections report a truncated count.
     * - [fingerprint] is a stable hash used for delta detection / stagnation.
     */
    data class CompactSnapshot(
        val activeContext: ActiveContext,
        val windowStack: List<WindowRef>,
        val focused: FocusedItem?,
        val editor: EditorView?,
        val activeWindow: ActiveWindowView,
        val fingerprint: String,
        /**
         * Cheap proxy for "how large is the raw UI tree right now". We use
         * the number of flattened components rather than re-serialising HTML,
         * so the signal is free but still correlates with byte size. Consumers
         * (UiDelta) use it to detect "tree grew even though fingerprint is
         * unchanged" — the classic "file loaded but our compact view can't
         * represent it yet" case.
         */
        val treeSize: Int = 0,
        /**
         * Populated when the agent is inside an inline template (in-place
         * rename, extract, introduce, etc.). When non-null, [activeContext]
         * is [ActiveContext.INLINE_WIDGET] and the LLM should type + Enter.
         */
        val inlineWidget: InlineWidget? = null,
    )

    /** Max items rendered per section of the active window. */
    private const val MAX_ITEMS_PER_SECTION = 30

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

    fun detectResponseType(
        roots: List<UiComponent>,
        p: ApplicationProfile,
    ): Triple<Boolean, Boolean, Boolean> {
        profile = p
        return detectResponseType(roots)
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

    fun forActivePopup(
        roots: List<UiComponent>,
        p: ApplicationProfile,
    ): String {
        profile = p
        return forActivePopup(roots)
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

    fun forActiveDialog(
        roots: List<UiComponent>,
        p: ApplicationProfile,
    ): String {
        profile = p
        return forActiveDialog(roots)
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

    fun forEditorState(
        roots: List<UiComponent>,
        p: ApplicationProfile,
    ): String {
        profile = p
        return forEditorState(roots)
    }

    fun forInlineEditorState(roots: List<UiComponent>): String {
        val popupView = forActivePopup(roots)
        val editorView = forEditorState(roots)
        return "$popupView\n\n$editorView"
    }

    fun forInlineEditorState(
        roots: List<UiComponent>,
        p: ApplicationProfile,
    ): String {
        profile = p
        return forInlineEditorState(roots)
    }

    // ── Compact snapshot (token-efficient, delta-friendly) ──────────────────

    /**
     * Build a [CompactSnapshot] from the raw UI tree.
     *
     * The snapshot is the single source of truth for the LLM prompt and
     * downstream delta computation. It is intentionally small: only the
     * topmost window's interactive content is enumerated, and background
     * windows are referenced by title only.
     */
    fun buildCompactSnapshot(
        roots: List<UiComponent>,
        p: ApplicationProfile,
    ): CompactSnapshot = buildCompactSnapshot(roots, p, null)

    /**
     * [buildCompactSnapshot] with live editor state piped in.
     *
     * The [editorCode] parameter is supplied by callers that have an
     * executor handle (i.e. the agent loop); pure tests and synthetic
     * scenarios can keep using the no-arg overload.
     *
     * When [editorCode] is provided we use it to (a) seed [EditorView.code]
     * and (b) fold the file path / caret into the fingerprint so typing
     * that only changes the document byte stream (but not the UI tree)
     * still produces a fresh fingerprint and breaks stagnation loops.
     */
    fun buildCompactSnapshot(
        roots: List<UiComponent>,
        p: ApplicationProfile,
        editorCode: EditorCode?,
    ): CompactSnapshot {
        profile = p
        val all = UiTreeParser.flatten(roots)

        // ── Window stack ────────────────────────────────────────────────
        val dialogs = all.filter { isDialog(it.cls, p) }
        val popupsRaw = all.filter { isPopupWindow(it.cls, p) }

        // Dedupe HeavyWeightWindow hosts that already contain a popup content
        // node (JBPopupMenu / *Popup / JBPopup*). Every IntelliJ popup renders
        // as `HeavyWeightWindow > JBPopupMenu`, so without this step a 2-level
        // menu (context menu + Refactor submenu) appears as 4 popups and a
        // deeply nested Refactor submenu as 10. That bogus depth then makes
        // the LLM spam CloseAllPopups trying to escape a fake dead-end.
        val popups = dedupeHostWithContent(popupsRaw)

        val stack = mutableListOf<WindowRef>()
        // Dialogs take precedence as "active" when present; popups stack below.
        popups.forEach { stack.add(WindowRef(classifyWindow(it, p), windowTitleOf(it))) }
        dialogs.forEach { stack.add(WindowRef(ActiveContext.DIALOG, windowTitleOf(it))) }

        // ── Focused item ────────────────────────────────────────────────
        val focusedComponent = all.firstOrNull { it.focused && it.label.isNotBlank() }
        val focused =
            focusedComponent?.let {
                FocusedItem(
                    role = roleName(it.cls, p),
                    label = it.label,
                    cls = it.cls,
                )
            }

        // ── Inline widget (in-place rename / extract / introduce) ──────
        // Detect BEFORE choosing activeContext so inline templates trump the
        // default popup classification. The LLM needs to know "type the new
        // name + press Enter" — not "click a menu item".
        val inlineWidget = detectInlineWidget(all, popups, dialogs, focusedComponent, p, editorCode)

        // ── Active context ──────────────────────────────────────────────
        val hasDialog = dialogs.isNotEmpty()
        val activeContext =
            when {
                hasDialog -> ActiveContext.DIALOG
                inlineWidget != null -> ActiveContext.INLINE_WIDGET
                popups.isNotEmpty() -> classifyWindow(popups.last(), p)
                else -> ActiveContext.EDITOR
            }

        // Topmost window: dialog (if any) else the last popup else null.
        val topWindow: UiComponent? =
            when {
                hasDialog -> dialogs.last()
                popups.isNotEmpty() -> popups.last()
                else -> null
            }

        // ── Editor view ─────────────────────────────────────────────────
        // Primary: class-level match (profile or name fallback).
        // Secondary: accessibleName like "Editor for Foo.kt" (some IntelliJ
        //            versions don't expose the editor class we know).
        val editorComponent =
            all.firstOrNull { isEditor(it.cls, p) }
                ?: all.firstOrNull { it.accessibleName.startsWith("Editor for", ignoreCase = true) }
        val tabs =
            all
                .filter { isTab(it.cls, p) }
                .map { it.label }
                .filter { it.isNotBlank() }
                .distinct()
        val breadcrumb =
            all
                .filter { isBreadcrumb(it.cls) }
                .filter { it.label.isNotBlank() && it.label != it.cls }
                .map { it.label }
                .distinct()
        val inferredFile =
            editorComponent?.accessibleName?.removePrefix("Editor for")?.trim().orEmpty()
                .ifBlank { tabs.firstOrNull().orEmpty() }
                .ifBlank { breadcrumb.lastOrNull().orEmpty() }
        // If Remote Robot didn't expose a tab or an editor accessibleName, the
        // live editor context (when provided) still knows the filename.
        val finalFile = inferredFile
        val editor =
            if (editorComponent != null || tabs.isNotEmpty() || breadcrumb.isNotEmpty() || editorCode != null) {
                EditorView(
                    file = finalFile,
                    focused =
                        editorComponent?.focused == true ||
                            editorComponent?.accessibleName?.contains("focused", ignoreCase = true) == true,
                    tabs = tabs,
                    breadcrumb = breadcrumb,
                    code = editorCode,
                )
            } else {
                null
            }

        // ── Active window view ──────────────────────────────────────────
        // In editor mode we still want *something* informative so the LLM
        // doesn't see an empty window and default to Observe forever.
        val activeWindow =
            when {
                inlineWidget != null ->
                    buildInlineWidgetActiveWindowView(inlineWidget, editor?.file)
                topWindow != null -> buildActiveWindowView(topWindow, activeContext, p)
                else -> buildEditorActiveWindowView(editor, all, p)
            }

        // Tree-wide signals — included in the fingerprint even when the active
        // window view ended up empty (e.g. profile missed the popup class).
        // Without these, a just-opened context menu is indistinguishable from
        // an idle editor, which is exactly how the v1 snapshot silently
        // reported "no change" for 14 iterations.
        val menuItemCount = all.count { looksLikeMenuItemByName(it.cls) }
        val popupLike = all.any { looksLikePopupByName(it.cls) }
        val dialogLike = all.any { looksLikeDialogByName(it.cls) }
        val focusedAccName = all.firstOrNull { it.focused }?.accessibleName ?: ""

        val fingerprint =
            fingerprintOf(
                ctx = activeContext,
                stack = stack,
                focused = focused,
                active = activeWindow,
                menuItemCount = menuItemCount,
                popupLike = popupLike,
                dialogLike = dialogLike,
                focusedAccName = focusedAccName,
                tabs = tabs,
                breadcrumb = breadcrumb,
                editorFile = finalFile,
                inlineWidget = inlineWidget,
                editorCode = editorCode,
            )

        return CompactSnapshot(
            activeContext = activeContext,
            windowStack = stack,
            focused = focused,
            editor = editor,
            activeWindow = activeWindow,
            fingerprint = fingerprint,
            treeSize = all.size,
            inlineWidget = inlineWidget,
        )
    }

    /**
     * Render an inline widget (in-place rename / extract / …) as an
     * [ActiveWindowView]. We shove the old identifier, suggestions, and the
     * IDE's own hint into the `fields` section so the LLM sees:
     *
     *   - [identifier] "<oldName>" (the target being renamed)
     *   - [suggestion] "<candidate>" × N
     *   - [hint] "Press Enter to commit, Escape to cancel"
     *
     * No buttons / menu items — the correct action is Type + Enter, which
     * is surfaced via the prompt rule rather than a clickable target.
     */
    private fun buildInlineWidgetActiveWindowView(
        widget: InlineWidget,
        file: String?,
    ): ActiveWindowView {
        val fields = mutableListOf<InteractiveItem>()
        if (widget.oldIdentifier.isNotBlank()) {
            fields += InteractiveItem("identifier", widget.oldIdentifier, true, "old name")
        }
        widget.suggestions.take(MAX_ITEMS_PER_SECTION).forEach { s ->
            fields += InteractiveItem("suggestion", s, true, null)
        }
        // Always surface the operational hint so the LLM knows the commit
        // keystroke even when the IDE's own hint popup is not in the tree.
        val hint =
            widget.hint.ifBlank {
                "Type the new name, then PressKey(\"Enter\") to commit " +
                    "(or PressKey(\"Escape\") to cancel)."
            }
        fields += InteractiveItem("hint", hint, true, null)

        val title =
            when {
                widget.oldIdentifier.isNotBlank() -> "Inline rename: ${widget.oldIdentifier}"
                !file.isNullOrBlank() -> "Inline widget in $file"
                else -> "Inline widget"
            }

        return ActiveWindowView(
            title = title,
            type = ActiveContext.INLINE_WIDGET,
            fields = fields,
            buttons = emptyList(),
            menuItems = emptyList(),
        )
    }

    /**
     * Build an [ActiveWindowView] for editor-only state so the LLM sees
     * something actionable (open file, tabs, breadcrumb) instead of an empty
     * pane.
     *
     * The items are marked with a synthetic role ("tab" / "breadcrumb" /
     * "editor") and placed in [ActiveWindowView.fields] so the existing
     * prompt layout stays intact.
     */
    private fun buildEditorActiveWindowView(
        editor: EditorView?,
        all: List<UiComponent>,
        p: ApplicationProfile,
    ): ActiveWindowView {
        val title = editor?.file?.ifBlank { null } ?: "Main Window"

        val fields = mutableListOf<InteractiveItem>()
        editor?.let { ev ->
            if (ev.file.isNotBlank()) {
                fields += InteractiveItem("editor", ev.file, true, if (ev.focused) "focused" else null)
            }
            ev.tabs.take(MAX_ITEMS_PER_SECTION).forEach { t ->
                fields += InteractiveItem("tab", t, true, null)
            }
            if (ev.breadcrumb.isNotEmpty()) {
                fields += InteractiveItem(
                    "breadcrumb",
                    ev.breadcrumb.joinToString(" / "),
                    true,
                    null,
                )
            }
        }

        // If we *still* have nothing, include the focused component's label
        // as a last-resort hint so the snapshot isn't entirely empty.
        if (fields.isEmpty()) {
            all.firstOrNull { it.focused && it.label.isNotBlank() }?.let { fc ->
                fields += InteractiveItem(roleName(fc.cls, p), fc.label, fc.enabled, null)
            }
        }

        return ActiveWindowView(
            title = title,
            type = ActiveContext.EDITOR,
            fields = fields,
            buttons = emptyList(),
            menuItems = emptyList(),
        )
    }

    /**
     * Render a [CompactSnapshot] as a small, deterministic string suitable for
     * direct inclusion in the LLM prompt.
     *
     * The shape is fixed; headings match the prompt contract so diffs between
     * runs are easy to spot.
     */
    fun formatCompactSnapshot(snap: CompactSnapshot): String {
        val sb = StringBuilder()

        sb.append("Active Context: ").append(snap.activeContext.name).append("\n")

        sb.append("Window Stack (top = active):\n")
        if (snap.windowStack.isEmpty()) {
            sb.append("  (none)\n")
        } else {
            // Last entry is the topmost. Render top-to-bottom for readability.
            for (w in snap.windowStack.asReversed()) {
                sb.append("  - [${w.type.name}] \"${w.title}\"\n")
            }
        }

        snap.focused?.let {
            sb.append("Focused: [${it.role}] \"${it.label}\"\n")
        } ?: sb.append("Focused: (none)\n")

        snap.editor?.let { e ->
            val f = if (e.focused) " (focused)" else ""
            val tabs = if (e.tabs.isNotEmpty()) " | tabs: ${e.tabs.joinToString(", ")}" else ""
            sb.append("Editor: ").append(e.file.ifBlank { "(untitled)" }).append(f).append(tabs).append("\n")
            if (e.breadcrumb.isNotEmpty()) {
                sb.append("Breadcrumb: ").append(e.breadcrumb.joinToString(" / ")).append("\n")
            }
            e.code?.let { c ->
                // Caret + symbol-under-caret line. Both are tiny and give the
                // LLM concrete anchors for "where am I?" reasoning.
                sb.append("Caret: line ").append(c.caretLine + 1)
                    .append(", col ").append(c.caretColumn + 1)
                    .append(" of ").append(c.totalLines).append(" lines")
                if (c.symbolUnderCaret.isNotBlank()) {
                    sb.append(" | symbol under caret: \"").append(c.symbolUnderCaret).append("\"")
                }
                if (c.selectedText.isNotBlank()) {
                    val preview = c.selectedText.take(80).replace("\n", "⏎")
                    sb.append(" | selection: \"").append(preview)
                    if (c.selectedText.length > 80) sb.append("…")
                    sb.append("\"")
                }
                sb.append("\n")

                // Visible source window with line-numbered gutter — cheap,
                // bounded, and transforms empty-editor prompts into ones the
                // LLM can reason about symbolically.
                if (c.visibleText.isNotBlank()) {
                    sb.append("Visible Source (lines ")
                        .append(c.windowStartLine + 1)
                        .append('–')
                        .append(c.windowEndLine + 1)
                        .append("):\n")
                    val lines = c.visibleText.split('\n')
                    val gutterWidth = (c.windowEndLine + 1).toString().length
                    for ((idx, line) in lines.withIndex()) {
                        val lineNo = c.windowStartLine + idx + 1
                        val marker = if (lineNo == c.caretLine + 1) ">" else " "
                        sb.append("  ")
                            .append(marker)
                            .append(' ')
                            .append(lineNo.toString().padStart(gutterWidth))
                            .append(" | ")
                            .append(line)
                            .append('\n')
                    }
                }
            }
        }

        snap.inlineWidget?.let { w ->
            sb.append("Inline Widget: ").append(w.kind)
            if (w.oldIdentifier.isNotBlank()) sb.append(" | old: \"").append(w.oldIdentifier).append("\"")
            if (w.suggestions.isNotEmpty()) {
                sb.append(" | suggestions: ")
                    .append(w.suggestions.take(6).joinToString(", "))
                if (w.suggestions.size > 6) sb.append(", +").append(w.suggestions.size - 6).append(" more")
            }
            if (w.hint.isNotBlank()) sb.append(" | hint: ").append(w.hint)
            sb.append("\n")
        }

        sb.append("\nActive Window: [${snap.activeWindow.type.name}] \"${snap.activeWindow.title}\"\n")
        renderSection(sb, "Fields", snap.activeWindow.fields)
        renderSection(sb, "Buttons", snap.activeWindow.buttons)
        renderSection(sb, "Menu Items", snap.activeWindow.menuItems)
        if (snap.activeWindow.truncated > 0) {
            sb.append("  (+").append(snap.activeWindow.truncated).append(" more)\n")
        }

        return sb.toString()
    }

    private fun renderSection(
        sb: StringBuilder,
        title: String,
        items: List<InteractiveItem>,
    ) {
        if (items.isEmpty()) return
        sb.append("  ").append(title).append(":\n")
        for (it in items) {
            val dis = if (!it.enabled) " (disabled)" else ""
            val sc = it.shortcutHint?.let { s -> if (s.isNotBlank()) " [$s]" else "" } ?: ""
            sb.append("    - [${it.role}] \"${it.label}\"$dis$sc\n")
        }
    }

    private fun buildActiveWindowView(
        window: UiComponent,
        ctx: ActiveContext,
        p: ApplicationProfile,
    ): ActiveWindowView {
        val children =
            UiTreeParser.flatten(listOf(window))
                .filter { it.label.isNotBlank() && it.label != it.cls }

        val fields = mutableListOf<InteractiveItem>()
        val buttons = mutableListOf<InteractiveItem>()
        val menuItems = mutableListOf<InteractiveItem>()

        for (c in children) {
            val role = roleName(c.cls, p)
            val shortcut =
                c.tooltip.ifBlank {
                    c.text.replace(c.accessibleName, "").trim()
                }.trim().ifBlank { null }
            val item = InteractiveItem(role, c.label, c.enabled, shortcut)
            when {
                isTextField(c.cls, p) -> fields += item
                isButton(c.cls, p) -> buttons += item
                isMenuItem(c.cls, p) -> menuItems += item
                isList(c.cls, p) -> fields += item
            }
        }

        // Deduplicate by label while preserving order.
        val dedup: (List<InteractiveItem>) -> List<InteractiveItem> = { list ->
            list.distinctBy { it.label.lowercase() to it.role }
        }

        val dFields = dedup(fields)
        val dButtons = dedup(buttons)
        val dMenu = dedup(menuItems)

        val truncated =
            maxOf(0, dFields.size - MAX_ITEMS_PER_SECTION) +
                maxOf(0, dButtons.size - MAX_ITEMS_PER_SECTION) +
                maxOf(0, dMenu.size - MAX_ITEMS_PER_SECTION)

        return ActiveWindowView(
            title = windowTitleOf(window),
            type = ctx,
            fields = dFields.take(MAX_ITEMS_PER_SECTION),
            buttons = dButtons.take(MAX_ITEMS_PER_SECTION),
            menuItems = dMenu.take(MAX_ITEMS_PER_SECTION),
            truncated = truncated,
        )
    }

    /**
     * Heuristic detector for IntelliJ in-place templates (rename, extract,
     * introduce variable, rename file, …).
     *
     * Signature we look for:
     *  - A focused `EditorComponentImpl` in the main editor area (the
     *    template caret lives there), OR a focused component whose label
     *    looks like an identifier.
     *  - Optionally a suggestion popup (`HeavyWeightWindow` / `JBPopup`)
     *    containing identifier-like items (short camelCase / snake_case
     *    strings) or a hint string containing "Press" + "replace".
     *  - No `DialogRootPane` (that's a regular modal, not inline).
     *  - No enumerable menu items in the topmost popup (that's a context
     *    menu, not the suggestion list).
     *
     * Returns null when the signature does not match. When it matches we
     * return the old identifier + suggestion labels so the LLM can see
     * exactly what it's renaming.
     */
    private fun detectInlineWidget(
        all: List<UiComponent>,
        popups: List<UiComponent>,
        dialogs: List<UiComponent>,
        focusedComponent: UiComponent?,
        p: ApplicationProfile,
        editorCode: EditorCode? = null,
    ): InlineWidget? {
        if (dialogs.isNotEmpty()) return null

        // Signal 1 — focused editor with a non-blank accessible name/text.
        // IntelliJ re-uses the main `EditorComponentImpl` as the template
        // surface, so focus landing there + a recent refactor action is a
        // strong "template active" hint on its own.
        val focusedEditor =
            focusedComponent?.takeIf { isEditor(it.cls, p) }
                ?: all.firstOrNull { it.focused && isEditor(it.cls, p) }

        // Signal 2 — suggestion/hint popup: any popup whose content is NOT a
        // menu (no menu items, no buttons) but has list-like or label-like
        // descendants. The IntelliJ suggestion popup for rename uses
        // `JBList` of candidates; the keyboard-hint popup uses a label
        // containing "Press ↵ or → to replace".
        val topPopup = popups.lastOrNull()
        val topHasMenuItems =
            topPopup
                ?.let { UiTreeParser.flatten(listOf(it)) }
                ?.any { isMenuItem(it.cls, p) } ?: false
        val topHasButtons =
            topPopup
                ?.let { UiTreeParser.flatten(listOf(it)) }
                ?.any { isButton(it.cls, p) } ?: false

        // Suggestion list items: short, identifier-looking labels from any
        // popup (covers both JBList rows and plain labels). We de-duplicate
        // and cap to keep the prompt small. The popup *root* itself is
        // excluded so the HeavyWeightWindow's own title doesn't pollute.
        val suggestions =
            if (topPopup != null && !topHasMenuItems && !topHasButtons) {
                UiTreeParser.flatten(listOf(topPopup))
                    .asSequence()
                    .filter { it !== topPopup }
                    .filter { !looksLikePopupByName(it.cls) }
                    .filter { it.label.isNotBlank() && it.label != it.cls }
                    .map { it.label.trim() }
                    .filter { looksLikeIdentifier(it) }
                    .distinct()
                    .take(15)
                    .toList()
            } else {
                emptyList()
            }

        // Hint label anywhere in the popups (keyboard shortcut tip).
        val hint =
            popups.asSequence()
                .flatMap { UiTreeParser.flatten(listOf(it)).asSequence() }
                .map { it.label }
                .firstOrNull { it.contains("replace", ignoreCase = true) && it.contains("Press", ignoreCase = true) }
                .orEmpty()

        // Signal 3 — LIVE EDITOR STATE via `editorCode`: when IntelliJ invokes
        // the inline rename template it pre-selects the old identifier. We can
        // ask the editor directly whether (a) there's a selection that looks
        // like an identifier, AND (b) at least one popup is up. That covers
        // the case where both the focused-component lookup fails (editor has
        // blank accessibleName) and the suggestion JBList isn't yet in the
        // tree / is classified under a class we don't recognise. Session
        // 2026-04-21_19-13-20 iteration 6 was exactly that shape.
        val selectedIdentifier =
            editorCode?.selectedText
                ?.takeIf { it.isNotBlank() && looksLikeIdentifier(it) }
        val hasSelectionSignal =
            selectedIdentifier != null && popups.isNotEmpty()

        // Decision:
        //  - Strong signal: suggestion list, hint, or selected-identifier + popup.
        //  - Weaker signal: focused editor AND a popup that is not a menu.
        val hasStrongSignal =
            suggestions.isNotEmpty() || hint.isNotBlank() || hasSelectionSignal
        val hasWeakSignal =
            focusedEditor != null &&
                topPopup != null &&
                !topHasMenuItems &&
                !topHasButtons

        if (!hasStrongSignal && !hasWeakSignal) return null

        // Old identifier: prefer the live selection, then the focused editor's
        // label, then the focused component's label. The selection is the most
        // reliable source because IntelliJ pre-selects the old name whenever
        // the rename template is live.
        val oldId =
            selectedIdentifier
                ?: focusedEditor?.label?.takeIf { it.isNotBlank() && looksLikeIdentifier(it) }
                ?: focusedComponent?.label?.takeIf { looksLikeIdentifier(it) }
                ?: editorCode?.symbolUnderCaret?.takeIf { it.isNotBlank() && looksLikeIdentifier(it) }
                ?: ""

        return InlineWidget(
            kind = "rename",
            oldIdentifier = oldId,
            suggestions = suggestions,
            hint = hint,
        )
    }

    /**
     * True when [s] is short (≤ 64 chars) and contains only characters that
     * commonly appear in Java/Kotlin/JS identifiers. Used to filter popup
     * children down to likely rename suggestions.
     */
    private fun looksLikeIdentifier(s: String): Boolean {
        if (s.isBlank() || s.length > 64) return false
        // Allow letters, digits, underscore, dollar, and the dot used by
        // qualified suggestions. Reject anything with spaces, punctuation
        // that would only appear in full sentences (commas, colons, etc.).
        return s.all { it.isLetterOrDigit() || it == '_' || it == '$' || it == '.' }
    }

    private fun classifyWindow(
        window: UiComponent,
        p: ApplicationProfile,
    ): ActiveContext {
        if (isDialog(window.cls, p)) return ActiveContext.DIALOG
        val descendants = UiTreeParser.flatten(listOf(window))
        val hasEditor = descendants.any { isEditor(it.cls, p) }
        val hasTextField = descendants.any { isTextField(it.cls, p) && !isEditor(it.cls, p) }
        val hasListLike = descendants.any { isList(it.cls, p) }
        return when {
            hasEditor || hasTextField -> ActiveContext.INLINE_WIDGET
            hasListLike -> ActiveContext.POPUP_CHOOSER
            else -> ActiveContext.POPUP_MENU
        }
    }

    private fun windowTitleOf(window: UiComponent): String {
        val name = window.accessibleName.ifBlank { window.text }
        return name.ifBlank { window.cls }
    }

    private fun roleName(
        cls: String,
        p: ApplicationProfile,
    ): String = p.roleOf(cls).name.lowercase()

    /**
     * Stable hash of the perceivable state. Two snapshots with the same
     * fingerprint are treated as equivalent for stagnation detection.
     *
     * Tree-wide signals ([menuItemCount], [popupLike], [dialogLike],
     * [focusedAccName], [firstTab]) are included so the fingerprint still
     * moves when the profile hasn't classified a new popup/dialog — this is
     * the difference between "UI changed, try again" and a spurious
     * stagnation-fail.
     */
    private fun fingerprintOf(
        ctx: ActiveContext,
        stack: List<WindowRef>,
        focused: FocusedItem?,
        active: ActiveWindowView,
        menuItemCount: Int,
        popupLike: Boolean,
        dialogLike: Boolean,
        focusedAccName: String,
        tabs: List<String>,
        breadcrumb: List<String>,
        editorFile: String,
        inlineWidget: InlineWidget?,
        editorCode: EditorCode? = null,
    ): String {
        val parts = mutableListOf<String>()
        parts += "ctx=${ctx.name}"
        parts += "stack=${stack.joinToString("|") { "${it.type.name}:${it.title}" }}"
        parts += "focused=${focused?.let { "${it.role}:${it.label}" } ?: "-"}"
        parts += "title=${active.title}"
        parts += "fields=" + active.fields.map { it.label.lowercase() }.sorted().joinToString(",")
        parts += "buttons=" + active.buttons.map { it.label.lowercase() }.sorted().joinToString(",")
        parts += "menu=" + active.menuItems.map { it.label.lowercase() }.sorted().joinToString(",")
        // Tree-wide signals — change regardless of profile coverage.
        parts += "menuCount=$menuItemCount"
        parts += "popup=$popupLike"
        parts += "dialog=$dialogLike"
        parts += "focusedAcc=$focusedAccName"
        // Editor-area signals: these matter because a file-open event typically
        // doesn't touch the popup/dialog stack but does add tabs + breadcrumb.
        parts += "tabs=" + tabs.joinToString(",")
        parts += "crumbs=" + breadcrumb.joinToString("/")
        parts += "editorFile=$editorFile"
        // Inline-widget signals — drive stagnation away from "no change" once
        // the template state changes (e.g. the user types and the first
        // suggestion shifts or the old identifier is replaced).
        parts += "inlineKind=${inlineWidget?.kind ?: "-"}"
        parts += "inlineOld=${inlineWidget?.oldIdentifier.orEmpty()}"
        parts += "inlineSuggest=${inlineWidget?.suggestions?.take(3)?.joinToString(",").orEmpty()}"
        // Live editor signals — caret movement + symbol-under-caret + total
        // line count fold into the fingerprint so typing a single character
        // produces a fresh hash even when the UI tree doesn't visibly change.
        // Without this, "Type" into the editor was indistinguishable from
        // Observe in stagnation accounting.
        parts += "caret=${editorCode?.let { "${it.caretLine}:${it.caretColumn}" } ?: "-"}"
        parts += "symbol=${editorCode?.symbolUnderCaret.orEmpty()}"
        parts += "docLines=${editorCode?.totalLines ?: -1}"
        val digest =
            java.security.MessageDigest.getInstance("SHA-1")
                .digest(parts.joinToString("\n").toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
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
