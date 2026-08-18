package execution

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import java.awt.Window
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JTextField

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
    private val edtDispatcher = EdtDispatcher()
    private val keyboardDispatcher = KeyboardDispatcher(edtDispatcher)
    private val dialogController = DialogController(edtDispatcher, keyboardDispatcher)
    private val editorController = EditorController(project, edtDispatcher)
    private var activePopup: com.intellij.openapi.ui.popup.JBPopup? = null

    // ── EDT helpers ──────────────────────────────────────────────────────────

    fun isOnEdt(): Boolean = edtDispatcher.isOnEdt()

    fun runOnEdt(block: () -> Unit) = edtDispatcher.runOnEdt(block)

    fun <T> runOnEdtAndWait(block: () -> T): T = edtDispatcher.runOnEdtAndWait(block)

    private fun <T> runOnEdtAndWaitWithModality(
        modalityState: com.intellij.openapi.application.ModalityState,
        block: () -> T,
    ): T = edtDispatcher.runOnEdtAndWaitWithModality(modalityState, block)

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

    fun findTopmostDialog(): JDialog? = edtDispatcher.findTopmostDialog()

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
                    group,
                    dataContext,
                    com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
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

            if (core.equals("Rename", ignoreCase = true)) {
                // RenameDialog.show() is modal and blocks until the dialog
                // closes. Schedule it after this EDT callback returns so the
                // calling test script can continue to type and confirm it.
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
                    { invokeRenameDialog(editor, dataContext) },
                    com.intellij.openapi.application.ModalityState.current(),
                )
                return@runOnEdtAndWait
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
                                com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                                false,
                            )
                    (subPopup as com.intellij.openapi.ui.popup.JBPopup).showInBestPositionFor(dataContext)
                    activePopup = subPopup
                    return@runOnEdtAndWait
                }
                if (isModalRefactoringAction(core)) {
                    val event =
                        com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(
                            action,
                            null,
                            "editorPopupMenu",
                            dataContext,
                        )
                    scheduleActionInvocation(action, event)
                    return@runOnEdtAndWait
                }
                val editorComponent = editor?.contentComponent
                val mouseEvent =
                    if (editorComponent != null) {
                        java.awt.event.MouseEvent(
                            editorComponent,
                            java.awt.event.MouseEvent.MOUSE_CLICKED,
                            System.currentTimeMillis(),
                            java.awt.event.InputEvent.BUTTON3_DOWN_MASK,
                            editorComponent.width / 2,
                            editorComponent.height / 2,
                            1,
                            false,
                        )
                    } else {
                        null
                    }

                val popupList =
                    findPopupListInWindows() ?: run {
                        val event =
                            com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(
                                action,
                                mouseEvent,
                                "editorPopupMenu",
                                dataContext,
                            )
                        performInProcessAction(action, event, dataContext, editor, core)
                        return@runOnEdtAndWait
                    }

                val model = popupList.model
                for (i in 0 until model.size) {
                    val value = model.getElementAt(i)
                    val text = value?.toString() ?: continue
                    val clean = text.removeSuffix("\u2026").removeSuffix("...").trim()
                    if (clean.equals(core, ignoreCase = true) || text.contains(core, ignoreCase = true)) {
                        popupList.selectedIndex = i
                        val cellBounds = popupList.getCellBounds(i, i) ?: continue
                        val cx = cellBounds.x + cellBounds.width / 2
                        val cy = cellBounds.y + cellBounds.height / 2
                        val clickEvent =
                            java.awt.event.MouseEvent(
                                popupList,
                                java.awt.event.MouseEvent.MOUSE_CLICKED,
                                System.currentTimeMillis(),
                                0,
                                cx,
                                cy,
                                1,
                                false,
                            )
                        popupList.dispatchEvent(clickEvent)
                        return@runOnEdtAndWait
                    }
                }

                val event =
                    com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(
                        action,
                        mouseEvent,
                        "editorPopupMenu",
                        dataContext,
                    )
                performInProcessAction(action, event, dataContext, editor, core)
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

    /** Invoke Rename through the platform's explicit dialog API. */
    private fun invokeRenameDialog(
        editor: com.intellij.openapi.editor.Editor?,
        dataContext: com.intellij.openapi.actionSystem.DataContext,
    ) {
        val activeEditor = editor ?: throw IllegalStateException("No editor is active for Rename")
        val target =
            com.intellij.refactoring.rename.PsiElementRenameHandler.getElement(dataContext)
                ?: com.intellij.codeInsight.TargetElementUtil.findTargetElement(
                    activeEditor,
                    com.intellij.codeInsight.TargetElementUtil.getInstance().getAllAccepted(),
                )
                ?: throw IllegalStateException("No rename target found at the editor caret")
        val psiFile =
            com.intellij.psi.PsiDocumentManager
                .getInstance(project)
                .getPsiFile(activeEditor.document)
                ?: throw IllegalStateException("No PSI file is available for Rename")

        activePopup?.cancel()
        activePopup = null
        val nameSuggestionContext = psiFile.findElementAt(activeEditor.caretModel.offset)
        com.intellij.refactoring.rename.PsiElementRenameHandler.rename(
            target,
            project,
            nameSuggestionContext,
            activeEditor,
        )
    }

    private fun performInProcessAction(
        action: com.intellij.openapi.actionSystem.AnAction,
        event: com.intellij.openapi.actionSystem.AnActionEvent,
        dataContext: com.intellij.openapi.actionSystem.DataContext,
        editor: com.intellij.openapi.editor.Editor?,
        label: String,
    ) {
        if (label.equals("Rename", ignoreCase = true)) {
            invokeRenameDialog(editor, dataContext)
            return
        }
        if (isModalRefactoringAction(label)) {
            scheduleActionInvocation(action, event)
            return
        }

        action.actionPerformed(event)
    }

    private fun isModalRefactoringAction(label: String): Boolean =
        label.equals("Change Signature", ignoreCase = true) ||
            label.equals("Introduce Variable", ignoreCase = true) ||
            label.equals("Introduce Constant", ignoreCase = true) ||
            label.equals("Extract Method", ignoreCase = true) ||
            label.equals("Extract Function", ignoreCase = true) ||
            label.equals("Introduce Parameter", ignoreCase = true) ||
            label.equals("Introduce Field", ignoreCase = true) ||
            label.equals("Extract Interface", ignoreCase = true) ||
            label.equals("Extract Superclass", ignoreCase = true)

    private fun scheduleActionInvocation(
        action: com.intellij.openapi.actionSystem.AnAction,
        event: com.intellij.openapi.actionSystem.AnActionEvent,
    ) {
        activePopup?.cancel()
        activePopup = null
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
            { action.actionPerformed(event) },
            com.intellij.openapi.application.ModalityState.current(),
        )
    }

    private fun findActionByLabel(
        actionManager: com.intellij.openapi.actionSystem.ActionManager,
        groupId: String,
        label: String,
    ): com.intellij.openapi.actionSystem.AnAction? {
        val group =
            actionManager.getAction(groupId) as? com.intellij.openapi.actionSystem.DefaultActionGroup
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
                val handleSelectMethod =
                    popup.javaClass.getMethod(
                        "handleSelect",
                        Boolean::class.javaPrimitiveType,
                        java.awt.event.InputEvent::class.java,
                    )
                val cellBounds = list.getCellBounds(i, i) ?: continue
                val cx = cellBounds.x + cellBounds.width / 2
                val cy = cellBounds.y + cellBounds.height / 2
                val inputEvent =
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

    private fun clickItemInList(
        list: javax.swing.JList<*>,
        label: String,
    ): Boolean {
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

    private fun findPopupListInWindows(): javax.swing.JList<*>? {
        for (window in Window.getWindows()) {
            if (!window.isVisible) continue
            val list = findListIn(window)
            if (list != null && list.model.size > 0) return list
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
    fun findEditor(): com.intellij.openapi.editor.Editor? = editorController.findEditor()

    /** Focus the active editor by selecting it in the file editor manager. */
    fun focusEditor() = editorController.focusEditor()

    /** Replace the contents of the active editor's document. */
    fun typeInEditor(text: String) = editorController.typeInEditor(text)

    /**
     * Move the caret to [offset] in the active editor. The offset is
     * 0-based into the document text.
     */
    fun moveCaretInEditor(offset: Int) = editorController.moveCaretInEditor(offset)

    /**
     * Select text in the active editor between [startOffset] and [endOffset]
     * (both 0-based document offsets).
     */
    fun selectTextInEditor(
        startOffset: Int,
        endOffset: Int,
    ) = editorController.selectTextInEditor(startOffset, endOffset)

    /**
     * Select the line range from [fromLine] to [toLine] (both 1-based,
     * inclusive) in the active editor.
     */
    fun selectLineRange(
        fromLine: Int,
        toLine: Int,
    ) = editorController.selectLineRange(fromLine, toLine)

    /**
     * Read the document text of the active editor. Document reads are
     * thread-safe in IntelliJ, so this does not need EDT dispatch.
     */
    fun getDocumentText(): String? = editorController.getDocumentText()

    /**
     * Move the caret to the middle of the first textual occurrence of [symbol]
     * in the active editor. Returns the resulting 1-based line/column position
     * and a flag indicating whether the caret was already inside one of the
     * matches before the call.
     */
    fun moveCaretToSymbol(symbol: String): MoveCaretOutcome = editorController.moveCaretToSymbol(symbol)

    fun waitUntilIndexed() {
        com.intellij.openapi.project.DumbService.getInstance(project).waitForSmartMode()
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
    fun getEditorContext(around: Int = 25): EditorCodeContext? = editorController.getEditorContext(around)

    // ── Dialog interactions ──────────────────────────────────────────────────

    fun clickDialogButton(label: String) = dialogController.clickDialogButton(label)

    fun clickDefaultButton() = dialogController.clickDefaultButton()

    fun clickRefactorButton() = dialogController.clickRefactorButton()

    /** Type [value] into the [field], replacing any existing text. */
    fun typeInTextField(
        field: JTextField,
        value: String,
    ) = dialogController.typeInTextField(field, value)

    /**
     * Type [value] into the first matching text field found in the topmost
     * dialog by [label]. Throws if no field is found.
     */
    fun typeInTextFieldByLabel(
        label: String,
        value: String,
    ) = dialogController.typeInTextFieldByLabel(label, value)

    fun typeInLabeledField(
        label: String,
        value: String,
    ) = dialogController.typeInLabeledField(label, value)

    fun setParameterName(
        row: Int,
        name: String,
    ) = dialogController.setParameterName(row, name)

    fun setCheckboxState(
        label: String,
        checked: Boolean,
    ) = dialogController.setCheckboxState(label, checked)

    fun closeTopmostDialog() = dialogController.closeTopmostDialog()

    fun closeAllDialogs(maxAttempts: Int = 5) = dialogController.closeAllDialogs(maxAttempts)

    fun selectDropdownValue(
        label: String,
        value: String,
    ) = dialogController.selectDropdownValue(label, value)

    // ── File opening ─────────────────────────────────────────────────────────

    /**
     * Open a file via IntelliJ's Search Everywhere (Cmd+Shift+O / Ctrl+Shift+O).
     * Triggers the `GotoFile` action, types the filename, then confirms with Enter.
     */
    fun openFile(path: String) = editorController.openFile(path)

    fun typeInDialog(
        value: String,
        clearFirst: Boolean = true,
    ) = dialogController.typeInDialog(value, clearFirst)

    // ── Shortcut dispatch ────────────────────────────────────────────────────

    fun pressShortcut(keys: String) = keyboardDispatcher.pressShortcut(keys)

    fun pressKey(keyName: String) = keyboardDispatcher.pressKey(keyName)

    fun pressKeyStroke(
        keyCode: Int,
        modifiers: Int = 0,
    ) = keyboardDispatcher.pressKeyStroke(keyCode, modifiers)

    fun selectDropdownField(
        label: String,
        value: String,
    ) = dialogController.selectDropdownField(label, value)

    fun setCheckbox(
        label: String,
        checked: Boolean,
    ) = dialogController.setCheckbox(label, checked)

    fun focusFieldByLabel(label: String) = dialogController.focusFieldByLabel(label)

    fun focusNextField() = dialogController.focusNextField()
}
