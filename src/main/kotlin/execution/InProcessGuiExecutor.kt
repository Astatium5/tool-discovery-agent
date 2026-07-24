package execution

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import java.awt.Component
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JRootPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * Executes IDE actions by manipulating Swing components directly — no HTTP,
 * no JS bridge, no Remote Robot server.
 *
 * Replaces the Remote-Robot-based action dispatch in [UiExecutor] with calls
 * that walk the Swing component tree (via [ComponentTreeWalker]) and invoke
 * native component methods (`doClick`, `setText`, `setSelected`, etc.).
 *
 * All public methods are EDT-safe: callers may invoke them from any thread.
 * The implementation dispatches to the EDT internally when needed and uses
 * `invokeAndWait` for operations whose result the caller depends on.
 *
 * UI tree perception (HTML fetching via Remote Robot) is unchanged — see
 * [perception.parser.HtmlUiTreeProvider].
 */
class InProcessGuiExecutor(
    private val project: Project,
) {
    private val log = logger<InProcessGuiExecutor>()
    private var activePopup: com.intellij.openapi.ui.popup.JBPopup? = null

    // ── EDT helpers ──────────────────────────────────────────────────────────

    fun isOnEdt(): Boolean = SwingUtilities.isEventDispatchThread()

    fun runOnEdt(block: () -> Unit) {
        if (isOnEdt()) block() else SwingUtilities.invokeLater(block)
    }

    fun <T> runOnEdtAndWait(block: () -> T): T {
        if (isOnEdt()) return block()
        val holder = arrayOfNulls<Any>(1)
        val throwable = arrayOfNulls<Throwable>(1)
        SwingUtilities.invokeAndWait {
            try {
                holder[0] = block()
            } catch (t: Throwable) {
                throwable[0] = t
            }
        }
        throwable[0]?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    // ── Window / frame access ────────────────────────────────────────────────

    fun findMainFrame(): JFrame? =
        runOnEdtAndWait {
            WindowManager.getInstance().getFrame(project)
        }

    fun findMenuBar(): JMenuBar? =
        runOnEdtAndWait {
            val frame = WindowManager.getInstance().getFrame(project)
            frame?.jMenuBar
        }

    fun findTopmostDialog(): JDialog? =
        runOnEdtAndWait {
            ComponentTreeWalker.getTopmostDialog()
        }

    /**
     * Open the editor's context menu at the current caret position, in-process
     * and headless. We use the IntelliJ platform action `EditorShowContextMenu`
     * rather than synthesising a right-click mouse event:
     *
     * 1. **No OS cursor movement** — AWT `Robot.mouseMove` would move the
     *    real pointer, which is hostile to a developer's machine and
     *    contradicts the "headless agent" promise of the in-process path.
     * 2. **No `TransactionGuard` violations** — raw `MouseEvent` dispatch
     *    runs outside a write-safe modality state and trips IntelliJ's
     *    TransactionGuard (the synthetic `mouseReleased` reaches
     *    `FloatingToolbar.canBeShownAtCurrentSelection` which calls
     *    `commitDocument`). The platform action path stays inside the
     *    correct write-safe context.
     *
     * Returns true if a popup is now visible (the action triggers the IDE's
     * normal context-menu path, which is async; we pump the EDT and poll).
     */
    fun openContextMenu(): Boolean =
        runOnEdtAndWait {
            openContextMenuOnEdt()
        }

    /**
     * Same as [openContextMenu] but assumes the caller is already on the EDT.
     * Split out so callers that are themselves inside an `invokeAndWait` (the
     * `runOnEdtAndWait` from [clickMenuItemByLabel] recovery path) can call
     * this without re-entering the EDT — that would deadlock.
     */
    private fun openContextMenuOnEdt(): Boolean {
        val editor = findEditorInternal() ?: return false
        val actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val group =
            actionManager.getAction("EditorPopupMenu") as? com.intellij.openapi.actionSystem.ActionGroup
                ?: return false
        val dataManager = com.intellij.ide.DataManager.getInstance()
        val dataContext = dataManager.getDataContext(editor.contentComponent)
        val popup =
            com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                .createActionGroupPopup(
                    null,
                    group!!,
                    dataContext,
                    com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.ALPHA_NUMBERING,
                    false,
                )
        (popup as com.intellij.openapi.ui.popup.JBPopup).showInBestPositionFor(dataContext)
        activePopup = popup as com.intellij.openapi.ui.popup.JBPopup
        return true
    }

    private fun findEditorInternal(): com.intellij.openapi.editor.Editor? = FileEditorManager.getInstance(project).selectedTextEditor

    /**
     * Click a menu item by its full path, e.g. `listOf("Refactor", "Rename...")`.
     * Opens each menu in turn, then invokes the final menu item via `doClick()`.
     */
    fun clickMenuItem(menuPath: List<String>) {
        if (menuPath.isEmpty()) {
            throw IllegalArgumentException("menuPath must not be empty")
        }
        runOnEdtAndWait {
            val menuBar =
                findMenuBar()
                    ?: throw IllegalStateException("No menu bar found in main frame")
            val topMenu =
                ComponentTreeWalker.findMenuByText(menuBar, menuPath.first())
                    ?: throw IllegalStateException("Top-level menu not found: ${menuPath.first()}")
            if (menuPath.size == 1) {
                topMenu.doClick()
                return@runOnEdtAndWait
            }
            topMenu.isSelected = true
            topMenu.setPopupMenuVisible(true)
            var current: JMenu? = topMenu
            for (i in 1 until menuPath.size - 1) {
                current = ComponentTreeWalker.findMenuItemByText(current, menuPath[i]) as? JMenu
                    ?: throw IllegalStateException("Submenu not found: ${menuPath[i]}")
                current.isSelected = true
                current.setPopupMenuVisible(true)
            }
            val last = menuPath.last()
            val target =
                ComponentTreeWalker.findMenuItemByText(current, last)
                    ?: throw IllegalStateException("Menu item not found: $last (path: $menuPath)")
            target.doClick()
        }
    }

    /**
     * Click a menu item by a single label. Used by the legacy
     * `UiExecutor.clickMenuItem(label)` path during migration.
     *
     * Search order, with rationales for each:
     *   1. Any visible `JPopupMenu` (context menu) — most context-menu items
     *      like "Rename..." or "Refactor → Rename..." only exist here, not in
     *      the main menu bar. The search is recursive into nested `JMenu`
     *      submenus so an unexpanded "Refactor" submenu still lets the agent
     *      reach its children.
     *   2. The main `JMenuBar` — for top-level items like "File → Save" that
     *      live in the application window.
     *
     * If the item is not found in either place, this throws with a clear hint
     * that names the missing prerequisite (`OpenContextMenu` for context-menu
     * items). The hint reaches the LLM via the action history and prompts
     * the next iteration to add the missing step.
     *
     * Earlier versions of this method also tried to synthesise a right-click
     * to open the context menu automatically when the agent skipped that
     * step. The earlier attempt used synthetic `MouseEvent` dispatch which
     * tripped `TransactionGuard`; this version uses the platform's
     * `EditorShowContextMenu` action via [openContextMenu] which is
     * headless, write-safe, and never reaches `FloatingToolbar`.
     */
    fun clickMenuItemByLabel(label: String) {
        val core =
            label
                .removeSuffix("\u2026")
                .removeSuffix("...")
                .trim()
        runOnEdtAndWait {
            val actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            val editor = findEditorInternal()
            val dataContext =
                if (editor != null) {
                    com.intellij.ide.DataManager.getInstance().getDataContext(editor.contentComponent)
                } else {
                    val frame = findMainFrame()
                    com.intellij.ide.DataManager.getInstance().getDataContext(frame?.contentPane)
                }

            val action = findActionByLabel(actionManager, "EditorPopupMenu", core)
            if (action != null) {
                if (action is com.intellij.openapi.actionSystem.DefaultActionGroup) {
                    val expanded = expandSubmenuViaList(core)
                    if (expanded) return@runOnEdtAndWait
                    activePopup?.cancel()
                    val subPopup =
                        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                            .createActionGroupPopup(
                                null,
                                action,
                                dataContext,
                                com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.ALPHA_NUMBERING,
                                false,
                            )
                    (subPopup as com.intellij.openapi.ui.popup.JBPopup).showInBestPositionFor(dataContext)
                    activePopup = subPopup
                    return@runOnEdtAndWait
                }
                val event =
                    com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(
                        action,
                        null,
                        "search",
                        dataContext,
                    )
                action.actionPerformed(event)
                return@runOnEdtAndWait
            }

            val menuBar = findMenuBar()
            if (menuBar != null) {
                val fromMenuBar = findMenuItemInMenuBar(menuBar, core)
                if (fromMenuBar != null) {
                    fromMenuBar.doClick()
                    return@runOnEdtAndWait
                }
            }

            throw IllegalStateException(
                "Menu item '$label' not found. " +
                    "If this is a context-menu item (e.g. Refactor → Rename...), " +
                    "call OpenContextMenu first to open the right-click menu.",
            )
        }
    }

    private fun findActionByLabel(
        actionManager: com.intellij.openapi.actionSystem.ActionManager,
        groupId: String,
        label: String,
    ): com.intellij.openapi.actionSystem.AnAction? {
        val group = actionManager.getAction(groupId) as? com.intellij.openapi.actionSystem.DefaultActionGroup
            ?: return null
        return findActionInGroup(group, label)
    }

    private fun findActionInGroup(
        group: com.intellij.openapi.actionSystem.DefaultActionGroup,
        label: String,
    ): com.intellij.openapi.actionSystem.AnAction? {
        val editor = findEditorInternal()
        val dataContext =
            if (editor != null) {
                com.intellij.ide.DataManager.getInstance().getDataContext(editor.contentComponent)
            } else {
                com.intellij.ide.DataManager.getInstance().getDataContext(findMainFrame()?.contentPane)
            }
        val placeEvent =
            com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(
                group,
                null,
                "search",
                dataContext,
            )
        val children = group.getChildren(placeEvent)
        for (child in children) {
            val presentation = child.templatePresentation
            val text = presentation.text?.removeSuffix("\u2026")?.removeSuffix("...")?.trim() ?: continue
            if (text.equals(label, ignoreCase = true) || text.contains(label, ignoreCase = true)) {
                return child
            }
            if (child is com.intellij.openapi.actionSystem.DefaultActionGroup) {
                val found = findActionInGroup(child, label)
                if (found != null) return found
            }
        }
        return null
    }

    private fun expandSubmenuViaList(label: String): Boolean {
        val popup = activePopup ?: return false

        val listMethod = popup.javaClass.getMethod("getList")
        val list = listMethod.invoke(popup) as? javax.swing.JList<*> ?: return fallbackToWindowSearch(label)

        val model = list.model
        for (i in 0 until model.size) {
            val value = model.getElementAt(i)
            val text = value?.toString() ?: continue
            val clean = text.removeSuffix("\u2026").removeSuffix("...").trim()
            if (clean.equals(label, ignoreCase = true) || text.contains(label, ignoreCase = true)) {
                list.selectedIndex = i

                // Invoke handleSelect directly on the popup via reflection
                val handleSelectMethod = popup.javaClass.getMethod(
                    "handleSelect",
                    Boolean::class.javaPrimitiveType,
                    java.awt.event.InputEvent::class.java,
                )
                val cellBounds = list.getCellBounds(i, i) ?: continue
                val cx = cellBounds.x + cellBounds.width / 2
                val cy = cellBounds.y + cellBounds.height / 2
                val inputEvent = java.awt.event.MouseEvent(
                    list,
                    java.awt.event.MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    cx,
                    cy,
                    1,
                    false,
                )
                handleSelectMethod.invoke(popup, false, inputEvent)
                return true
            }
        }
        return false
    }

    private fun fallbackToWindowSearch(label: String): Boolean {
        for (window in Window.getWindows()) {
            if (!window.isVisible) continue
            val list = findListIn(window) ?: continue
            if (clickItemInList(list, label)) return true
        }
        return false
    }

    private fun clickItemInList(list: javax.swing.JList<*>, label: String): Boolean {
        val model = list.model
        for (i in 0 until model.size) {
            val value = model.getElementAt(i)
            val text = value?.toString() ?: continue
            val clean = text.removeSuffix("\u2026").removeSuffix("...").trim()
            if (clean.equals(label, ignoreCase = true) || text.contains(label, ignoreCase = true)) {
                list.selectedIndex = i
                val cellBounds = list.getCellBounds(i, i) ?: continue
                val cx = cellBounds.x + cellBounds.width / 2
                val cy = cellBounds.y + cellBounds.height / 2

                val event =
                    java.awt.event.MouseEvent(
                        list,
                        java.awt.event.MouseEvent.MOUSE_CLICKED,
                        System.currentTimeMillis(),
                        0,
                        cx,
                        cy,
                        1,
                        false,
                    )
                list.dispatchEvent(event)
                Thread.sleep(300)
                return true
            }
        }
        return false
    }

    private fun findListIn(component: java.awt.Component): javax.swing.JList<*>? {
        if (component is javax.swing.JList<*>) return component
        if (component is java.awt.Container) {
            for (i in 0 until component.componentCount) {
                val found = findListIn(component.getComponent(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun findMenuItemInMenuBar(
        menuBar: JMenuBar,
        text: String,
    ): JMenuItem? {
        for (i in 0 until menuBar.menuCount) {
            val menu = menuBar.getMenu(i) ?: continue
            if (menu.text?.contains(text, ignoreCase = true) == true) return menu
            val nested = ComponentTreeWalker.findMenuItemByText(menu, text)
            if (nested != null) return nested
        }
        return null
    }

    // ── Editor interactions ──────────────────────────────────────────────────

    /**
     * Return the currently focused editor's `Editor` instance, or null if no
     * editor is open. EDT-only call wrapped in `invokeAndWait`.
     */
    fun findEditor(): com.intellij.openapi.editor.Editor? =
        runOnEdtAndWait {
            FileEditorManager.getInstance(project).selectedTextEditor
        }

    /** Focus the active editor by selecting it in the file editor manager. */
    fun focusEditor() {
        runOnEdtAndWait {
            val editor =
                FileEditorManager.getInstance(project).selectedTextEditor
                    ?: throw IllegalStateException("No editor is currently open")
            editor.contentComponent.requestFocusInWindow()
        }
    }

    /** Replace the contents of the active editor's document. */
    fun typeInEditor(text: String) {
        val editor = findEditor() ?: throw IllegalStateException("No editor is currently open")
        runOnEdtAndWait {
            editor.document.setText(text)
        }
    }

    /**
     * Move the caret to [offset] in the active editor. The offset is
     * 0-based into the document text.
     */
    fun moveCaretInEditor(offset: Int) {
        val editor = findEditor() ?: throw IllegalStateException("No editor is currently open")
        runOnEdtAndWait {
            editor.caretModel.moveToOffset(offset)
            editor.scrollingModel.scrollToCaret(
                com.intellij.openapi.editor.ScrollType.CENTER,
            )
        }
    }

    /**
     * Select text in the active editor between [startOffset] and [endOffset]
     * (both 0-based document offsets).
     */
    fun selectTextInEditor(
        startOffset: Int,
        endOffset: Int,
    ) {
        val editor = findEditor() ?: throw IllegalStateException("No editor is currently open")
        runOnEdtAndWait {
            editor.selectionModel.setSelection(startOffset, endOffset)
        }
    }

    /**
     * Select the line range from [fromLine] to [toLine] (both 1-based,
     * inclusive) in the active editor.
     */
    fun selectLineRange(
        fromLine: Int,
        toLine: Int,
    ) {
        val editor = findEditor() ?: throw IllegalStateException("No editor is currently open")
        runOnEdtAndWait {
            val doc = editor.document
            val startOffset = doc.getLineStartOffset((fromLine - 1).coerceAtLeast(0))
            val endLineIdx = (toLine - 1).coerceAtMost(doc.lineCount - 1)
            val endOffset = doc.getLineEndOffset(endLineIdx)
            editor.selectionModel.setSelection(startOffset, endOffset)
            editor.caretModel.moveToOffset(endOffset)
            editor.scrollingModel.scrollToCaret(
                com.intellij.openapi.editor.ScrollType.CENTER,
            )
        }
    }

    /**
     * Read the document text of the active editor. Document reads are
     * thread-safe in IntelliJ, so this does not need EDT dispatch.
     */
    fun getDocumentText(): String? =
        runOnEdtAndWait {
            findEditor()?.document?.text
        }

    /**
     * Move the caret to the middle of the first textual occurrence of [symbol]
     * in the active editor. Returns the resulting 1-based line/column position
     * and a flag indicating whether the caret was already inside one of the
     * matches before the call.
     */
    fun moveCaretToSymbol(symbol: String): MoveCaretOutcome {
        require(symbol.isNotEmpty()) {
            "symbol must be a non-empty identifier visible in the editor"
        }
        val editor = findEditor() ?: throw IllegalStateException("No editor is currently open")
        return runOnEdtAndWait {
            val doc = editor.document
            val text = doc.text
            val idx = text.indexOf(symbol)
            if (idx < 0) {
                throw IllegalStateException("Symbol '$symbol' not found in editor")
            }
            val currentOffset = editor.caretModel.offset
            val total = countOccurrences(text, symbol)
            val alreadyOn = currentOffset in idx until (idx + symbol.length)
            if (!alreadyOn) {
                val target = idx + symbol.length / 2
                editor.caretModel.moveToOffset(target)
                editor.scrollingModel.scrollToCaret(
                    com.intellij.openapi.editor.ScrollType.CENTER,
                )
            }
            val pos = editor.offsetToLogicalPosition(editor.caretModel.offset)
            MoveCaretOutcome(
                line = pos.line + 1,
                column = pos.column + 1,
                totalOccurrences = total,
                alreadyOnSymbol = alreadyOn,
            )
        }
    }

    private fun countOccurrences(
        text: String,
        symbol: String,
    ): Int {
        if (symbol.isEmpty()) return 0
        var count = 0
        var pos = 0
        while (true) {
            val found = text.indexOf(symbol, pos)
            if (found < 0) return count
            count++
            pos = found + symbol.length
        }
    }

    data class MoveCaretOutcome(
        val line: Int,
        val column: Int,
        val totalOccurrences: Int,
        val alreadyOnSymbol: Boolean,
    )

    data class EditorCodeContext(
        val filePath: String,
        val fileName: String,
        val caretLine: Int,
        val caretColumn: Int,
        val totalLines: Int,
        val symbolUnderCaret: String,
        val selectedText: String,
        val windowStartLine: Int,
        val windowEndLine: Int,
        val visibleText: String,
    )

    /**
     * Build an editor context snapshot (file path, caret position, symbol under
     * caret, and a small window of visible code around the caret). Returns
     * null if no editor is open.
     */
    fun getEditorContext(around: Int = 25): EditorCodeContext? =
        runOnEdtAndWait {
            val editor = findEditor() ?: return@runOnEdtAndWait null
            val doc = editor.document
            val vf =
                com.intellij.openapi.fileEditor.FileDocumentManager
                    .getInstance()
                    .getFile(doc)
            val caret = editor.caretModel.primaryCaret
            val pos = caret.logicalPosition
            val total = doc.lineCount
            val center = pos.line
            val start = (center - around).coerceAtLeast(0)
            val end = (center + around).coerceAtMost(total - 1)
            val sb = StringBuilder()
            for (i in start..end) {
                val startOff = doc.getLineStartOffset(i)
                val endOff = doc.getLineEndOffset(i)
                sb.append(doc.getText(com.intellij.openapi.util.TextRange(startOff, endOff)))
                sb.append('\n')
            }
            EditorCodeContext(
                filePath = vf?.path.orEmpty(),
                fileName = vf?.name.orEmpty(),
                caretLine = pos.line + 1,
                caretColumn = pos.column + 1,
                totalLines = total,
                symbolUnderCaret = identifierAtCaret(doc.text, caret.offset),
                selectedText = caret.selectedText.orEmpty(),
                windowStartLine = start + 1,
                windowEndLine = end + 1,
                visibleText = sb.toString(),
            )
        }

    private fun identifierAtCaret(
        text: String,
        offset: Int,
    ): String {
        if (offset < 0 || offset > text.length) return ""
        var start = offset
        var end = offset

        fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_'
        while (start > 0 && isIdent(text[start - 1])) start--
        while (end < text.length && isIdent(text[end])) end++
        return text.substring(start, end)
    }

    // ── Dialog interactions ──────────────────────────────────────────────────

    /** Click a button in the topmost dialog matching [label] (case-insensitive). */
    fun clickDialogButton(label: String) {
        runOnEdtAndWait {
            val dialog =
                ComponentTreeWalker.getTopmostDialog()
                    ?: throw IllegalStateException("No topmost dialog found")
            val button =
                ComponentTreeWalker.findButton(dialog.contentPane, label)
                    ?: throw IllegalStateException("Button '$label' not found in dialog")
            button.doClick()
        }
    }

    /** Click the dialog's default button (typically OK). */
    fun clickDefaultButton() {
        runOnEdtAndWait {
            val dialog =
                ComponentTreeWalker.getTopmostDialog()
                    ?: throw IllegalStateException("No topmost dialog found")
            val rootPane: JRootPane =
                dialog.rootPane
                    ?: throw IllegalStateException("Dialog has no root pane")
            val defaultButton =
                rootPane.defaultButton
                    ?: throw IllegalStateException("Dialog has no default button")
            defaultButton.doClick()
        }
    }

    /** Type [value] into the [field], replacing any existing text. */
    fun typeInTextField(
        field: JTextField,
        value: String,
    ) {
        runOnEdtAndWait {
            field.text = value
        }
    }

    /**
     * Type [value] into the first matching text field found in the topmost
     * dialog by [label]. Throws if no field is found.
     */
    fun typeInTextFieldByLabel(
        label: String,
        value: String,
    ) {
        runOnEdtAndWait {
            val dialog =
                ComponentTreeWalker.getTopmostDialog()
                    ?: throw IllegalStateException("No topmost dialog found")
            val field =
                ComponentTreeWalker.findTextField(dialog.contentPane, label)
                    ?: throw IllegalStateException("Text field for label '$label' not found in dialog")
            field.text = value
        }
    }

    /** Set the [label] checkbox's selected state to [checked]. */
    fun setCheckboxState(
        label: String,
        checked: Boolean,
    ) {
        runOnEdtAndWait {
            val dialog =
                ComponentTreeWalker.getTopmostDialog()
                    ?: throw IllegalStateException("No topmost dialog found")
            val cb =
                ComponentTreeWalker.findCheckBox(dialog.contentPane, label)
                    ?: throw IllegalStateException("Checkbox '$label' not found in dialog")
            cb.isSelected = checked
        }
    }

    /**
     * Close the topmost dialog. Tries `DialogWrapper.doCancelAction()` first
     * (the IntelliJ-native close path), then falls back to closing the window.
     */
    fun closeTopmostDialog() {
        runOnEdtAndWait {
            val dialog =
                ComponentTreeWalker.getTopmostDialog()
                    ?: return@runOnEdtAndWait
            closeDialog(dialog)
        }
    }

    /**
     * Iteratively close all visible dialogs, up to [maxAttempts] times. Some
     * dialogs trigger nested dialogs; looping handles those chains.
     */
    fun closeAllDialogs(maxAttempts: Int = 5) {
        repeat(maxAttempts) {
            val dialog = findTopmostDialog() ?: return
            closeDialog(dialog)
            // Pump the EDT to let the close complete before checking again
            runOnEdtAndWait { /* no-op, just wait */ }
        }
    }

    /**
     * Open the dropdown matching [label] in the topmost dialog and select
     * [value]. Falls back to typing the value into the focused field if the
     * dropdown option list is not visible.
     */
    fun selectDropdownValue(
        label: String,
        value: String,
    ) {
        runOnEdtAndWait {
            val dialog =
                ComponentTreeWalker.getTopmostDialog()
                    ?: throw IllegalStateException("No topmost dialog found")
            val combo =
                ComponentTreeWalker.findComboBox(dialog.contentPane, label)
                    ?: throw IllegalStateException("Dropdown '$label' not found in dialog")
            combo.isPopupVisible = true
            val itemIndex =
                (0 until combo.itemCount).firstOrNull { i ->
                    combo.getItemAt(i)?.toString()?.contains(value, ignoreCase = true) == true
                }
            if (itemIndex != null) {
                combo.selectedIndex = itemIndex
            }
        }
    }

    private fun closeDialog(dialog: JDialog) {
        // Try DialogWrapper reflection first
        try {
            val doCancel = dialog.javaClass.methods.firstOrNull { it.name == "doCancelAction" }
            if (doCancel != null) {
                doCancel.invoke(dialog)
                return
            }
        } catch (t: Throwable) {
            log.debug("DialogWrapper.doCancelAction not available", t)
        }
        // Fall back to disposing the window
        dialog.dispose()
    }

    // ── File opening ─────────────────────────────────────────────────────────

    /**
     * Open a file via IntelliJ's Search Everywhere (Cmd+Shift+O / Ctrl+Shift+O).
     * Triggers the `GotoFile` action, types the filename, then confirms with Enter.
     */
    fun openFile(path: String) {
        runOnEdtAndWait {
            val actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            val action =
                actionManager.getAction("GotoFile")
                    ?: throw IllegalStateException("GotoFile action not found")
            val dataManager = com.intellij.ide.DataManager.getInstance()
            val frame = findMainFrame() ?: throw IllegalStateException("No main frame")
            val dataContext = dataManager.getDataContext(frame.contentPane)
            action.actionPerformed(
                com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(
                    action,
                    null,
                    "search",
                    dataContext,
                ),
            )
        }
        waitForDialog(timeoutMs = 2000)
        typeInFocusedField(path)
        pressKey("Enter")
    }

    /**
     * Type text into whatever field currently has focus. Used for Search Everywhere
     * and similar modal dialogs where we just need to dump text into the focused input.
     */
    private fun typeInFocusedField(text: String) {
        runOnEdtAndWait {
            val focusOwner = keyboardFocusManagerFocusOwner() ?: return@runOnEdtAndWait
            if (focusOwner is JTextField) {
                focusOwner.text = text
                return@runOnEdtAndWait
            }
            val robot = java.awt.Robot()
            for (c in text) {
                val keyCode = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(c.code)
                if (keyCode != java.awt.event.KeyEvent.VK_UNDEFINED) {
                    val needsShift = c.isUpperCase() || "!@#$%^&*()_+{}|:\"<>?~".contains(c)
                    val modifiers = if (needsShift) java.awt.event.InputEvent.SHIFT_DOWN_MASK else 0
                    robot.keyPress(modifiers or keyCode)
                    robot.keyRelease(modifiers or keyCode)
                } else {
                    val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    val transferable = java.awt.datatransfer.StringSelection(c.toString())
                    clipboard.setContents(transferable, null)
                    val modifier =
                        if (System.getProperty("os.name").lowercase().let { it.contains("mac") }) {
                            java.awt.event.KeyEvent.VK_META
                        } else {
                            java.awt.event.KeyEvent.VK_CONTROL
                        }
                    robot.keyPress(modifier)
                    robot.keyPress(java.awt.event.KeyEvent.VK_V)
                    robot.keyRelease(java.awt.event.KeyEvent.VK_V)
                    robot.keyRelease(modifier)
                }
            }
        }
    }

    private fun waitForDialog(timeoutMs: Long = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (findTopmostDialog() != null) return
            Thread.sleep(50)
        }
    }

    // ── Dialog text input ────────────────────────────────────────────────────

    /**
     * Type [value] into the currently focused text field in a dialog or inline
     * template. Handles:
     * - Inline template detection (focus is on EditorComponentImpl → skip clear)
     * - Autocomplete dismissal (only for non-template fields)
     */
    fun typeInDialog(
        value: String,
        clearFirst: Boolean = true,
    ) {
        waitForDialog(timeoutMs = 1000)

        val focusOwner = keyboardFocusManagerFocusOwner()
        var inTemplateMode = false

        if (focusOwner != null) {
            val isEditorComponent = focusOwner.javaClass.simpleName.contains("Editor", ignoreCase = true)

            if (clearFirst && !isEditorComponent && focusOwner is JTextField) {
                focusOwner.text = ""
            } else if (isEditorComponent) {
                inTemplateMode = true
            }
            val robot = java.awt.Robot()
            for (c in value) {
                val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                val transferable = java.awt.datatransfer.StringSelection(c.toString())
                clipboard.setContents(transferable, null)
                val modifier =
                    if (System.getProperty("os.name").lowercase().let { it.contains("mac") }) {
                        java.awt.event.KeyEvent.VK_META
                    } else {
                        java.awt.event.KeyEvent.VK_CONTROL
                    }
                robot.keyPress(modifier)
                robot.keyPress(java.awt.event.KeyEvent.VK_V)
                robot.keyRelease(java.awt.event.KeyEvent.VK_V)
                robot.keyRelease(modifier)
            }
        } else {
            val dialog = findTopmostDialog()
            if (dialog != null) {
                val field = ComponentTreeWalker.findTextField(dialog.contentPane, "")
                if (field != null) {
                    if (clearFirst) {
                        field.text = ""
                    }
                    field.text = value
                    return
                }
            }
        }

        // Dismiss autocomplete if not in template mode
        if (!inTemplateMode) {
            dismissAutocompletePopup()
        }
    }

    private fun dismissAutocompletePopup() {
        try {
            val anyLookup =
                Window.getWindows().any { window ->
                    if (!window.isVisible) return@any false
                    ComponentTreeWalker.findAllComponentsByType<java.awt.Component>(window).any { comp ->
                        comp.javaClass.simpleName.contains("Lookup", ignoreCase = true)
                    }
                }
            if (anyLookup) {
                pressKey("Escape")
                Thread.sleep(200)
            }
        } catch (_: Exception) {
            // Best-effort
        }
    }

    // ── Dropdown / checkbox shortcuts ────────────────────────────────────────

    /**
     * Select a dropdown value by first focusing the field with the given [label],
     * then selecting [value] from the dropdown.
     */
    fun selectDropdownField(
        label: String,
        value: String,
    ) {
        focusFieldByLabel(label)
        Thread.sleep(200)
        selectDropdownValue(label, value)
    }

    /**
     * Set checkbox state by label. Wrapper around [setCheckboxState] for
     * compatibility with UiExecutor's method name.
     */
    fun setCheckbox(
        label: String,
        checked: Boolean,
    ) {
        setCheckboxState(label, checked)
    }

    // ── Shortcut dispatch ────────────────────────────────────────────────────

    /**
     * Parse a shortcut string like "Shift+F6" or "Ctrl+Alt+Shift+K" and dispatch
     * it as a key event with the appropriate modifier mask.
     */
    fun pressShortcut(keys: String) {
        val parts = keys.split("+").map { it.trim() }
        val modifiers = parts.dropLast(1).map { modifierNameToKeyCode(it) }.filter { it != 0 }
        val mainKey = keyNameToKeyCode(parts.last())

        val modifierMask = modifiers.fold(0) { acc, code -> acc or code }

        runOnEdtAndWait {
            val focusOwner = keyboardFocusManagerFocusOwner() ?: return@runOnEdtAndWait
            val event =
                KeyEvent(
                    focusOwner,
                    KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(),
                    modifierMask,
                    mainKey,
                    KeyEvent.CHAR_UNDEFINED,
                )
            focusOwner.keyListeners.forEach { it.keyPressed(event) }
        }
    }

    private fun modifierNameToKeyCode(name: String): Int =
        when (name.lowercase()) {
            "shift" -> InputEvent.SHIFT_DOWN_MASK
            "ctrl", "control" -> InputEvent.CTRL_DOWN_MASK
            "alt", "option" -> InputEvent.ALT_DOWN_MASK
            "meta", "cmd", "command" -> InputEvent.META_DOWN_MASK
            else -> 0
        }

    private fun keyNameToKeyCode(name: String): Int =
        when (name.lowercase()) {
            "enter" -> KeyEvent.VK_ENTER
            "escape", "esc" -> KeyEvent.VK_ESCAPE
            "tab" -> KeyEvent.VK_TAB
            "space" -> KeyEvent.VK_SPACE
            "backspace" -> KeyEvent.VK_BACK_SPACE
            "delete" -> KeyEvent.VK_DELETE
            "up" -> KeyEvent.VK_UP
            "down" -> KeyEvent.VK_DOWN
            "left" -> KeyEvent.VK_LEFT
            "right" -> KeyEvent.VK_RIGHT
            "f1" -> KeyEvent.VK_F1
            "f2" -> KeyEvent.VK_F2
            "f3" -> KeyEvent.VK_F3
            "f4" -> KeyEvent.VK_F4
            "f5" -> KeyEvent.VK_F5
            "f6" -> KeyEvent.VK_F6
            "f7" -> KeyEvent.VK_F7
            "f8" -> KeyEvent.VK_F8
            "f9" -> KeyEvent.VK_F9
            "f10" -> KeyEvent.VK_F10
            "f11" -> KeyEvent.VK_F11
            "f12" -> KeyEvent.VK_F12
            "home" -> KeyEvent.VK_HOME
            "end" -> KeyEvent.VK_END
            "pageup" -> KeyEvent.VK_PAGE_UP
            "pagedown" -> KeyEvent.VK_PAGE_DOWN
            else -> {
                if (name.length == 1) {
                    KeyEvent.getExtendedKeyCodeForChar(name[0].code)
                } else {
                    KeyEvent.VK_ENTER
                }
            }
        }

    // ── Field focus & navigation ─────────────────────────────────────────────

    /**
     * Focus the field associated with the given [label] in the topmost dialog.
     */
    fun focusFieldByLabel(label: String) {
        runOnEdtAndWait {
            val dialog =
                ComponentTreeWalker.getTopmostDialog()
                    ?: throw IllegalStateException("No topmost dialog found")
            val field =
                ComponentTreeWalker.findTextField(dialog.contentPane, label)
                    ?: throw IllegalStateException("Field for label '$label' not found")
            field.requestFocusInWindow()
        }
    }

    /**
     * Move focus to the next focusable component in the current focus
     * traversal cycle. Mirrors pressing Tab.
     */
    fun focusNextField() {
        runOnEdtAndWait {
            val focusOwner = keyboardFocusManagerFocusOwner()
            if (focusOwner == null) return@runOnEdtAndWait
            val container = focusOwner.parent ?: return@runOnEdtAndWait
            val policy = container.focusTraversalPolicy
            if (policy != null) {
                val next = policy.getComponentAfter(container, focusOwner)
                next?.requestFocusInWindow()
            }
        }
    }

    /** Press a single named key, e.g. "Enter", "Escape", "Tab". */
    fun pressKey(keyName: String) {
        val keyCode =
            keyCodeForName(keyName)
                ?: throw IllegalArgumentException("Unknown key name: $keyName")
        runOnEdtAndWait {
            val focusOwner = keyboardFocusManagerFocusOwner() ?: return@runOnEdtAndWait
            val keyStroke = KeyStroke.getKeyStroke(keyCode, 0)
            val listeners = focusOwner.keyListeners
            val event =
                KeyEvent(
                    focusOwner,
                    KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(),
                    0,
                    keyCode,
                    keyStroke.keyChar.takeIf { it.code != 0 } ?: KeyEvent.CHAR_UNDEFINED,
                )
            listeners.forEach { it.keyPressed(event) }
        }
    }

    /**
     * Dispatch a key stroke to the currently focused component, with optional
     * modifier mask (e.g. `InputEvent.CTRL_DOWN_MASK`).
     */
    fun pressKeyStroke(
        keyCode: Int,
        modifiers: Int = 0,
    ) {
        runOnEdtAndWait {
            val focusOwner = keyboardFocusManagerFocusOwner() ?: return@runOnEdtAndWait
            val keyStroke = KeyStroke.getKeyStroke(keyCode, modifiers)
            val listeners = focusOwner.keyListeners
            val event =
                KeyEvent(
                    focusOwner,
                    KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(),
                    modifiers,
                    keyCode,
                    KeyEvent.CHAR_UNDEFINED,
                )
            listeners.forEach { it.keyPressed(event) }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun keyCodeForName(name: String): Int? =
        when (name.lowercase()) {
            "enter" -> KeyEvent.VK_ENTER
            "escape", "esc" -> KeyEvent.VK_ESCAPE
            "tab" -> KeyEvent.VK_TAB
            "space" -> KeyEvent.VK_SPACE
            "backspace" -> KeyEvent.VK_BACK_SPACE
            "delete" -> KeyEvent.VK_DELETE
            "home" -> KeyEvent.VK_HOME
            "end" -> KeyEvent.VK_END
            "pageup" -> KeyEvent.VK_PAGE_UP
            "pagedown" -> KeyEvent.VK_PAGE_DOWN
            "up" -> KeyEvent.VK_UP
            "down" -> KeyEvent.VK_DOWN
            "left" -> KeyEvent.VK_LEFT
            "right" -> KeyEvent.VK_RIGHT
            "f1" -> KeyEvent.VK_F1
            "f2" -> KeyEvent.VK_F2
            "f3" -> KeyEvent.VK_F3
            "f4" -> KeyEvent.VK_F4
            "f5" -> KeyEvent.VK_F5
            "f12" -> KeyEvent.VK_F12
            else -> null
        }
}

// Pull focus-owner lookup into a top-level helper so it is easy to mock in tests.
private fun keyboardFocusManagerFocusOwner(): Component? = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
