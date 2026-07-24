package execution

import model.AgentAction
import perception.parser.ScopedSnapshotBuilder
import perception.parser.UiComponent
import perception.parser.UiTreeParser
import profile.ApplicationProfile

/**
 * Action Generator - Executes actions decided by the LLM Reasoner.
 *
 * This component is responsible for:
 * 1. Executing navigation actions (open file, move caret, select lines)
 * 2. Executing UI interaction actions (click, type, press_key)
 * 3. Using InProcessGuiExecutor for all IDE interactions
 * 4. Classifying post-click UI state using deterministic heuristics
 */
class ActionGenerator(
    private val executor: InProcessGuiExecutor,
    private val profile: ApplicationProfile,
    private val uiTreeProvider: () -> List<UiComponent>,
) {
    companion object {
        enum class ClickOutcome {
            SUBMENU,
            TOOL_TRIGGERED,
            DISMISSED,
        }

        /**
         * Deterministic post-click classifier based on popup count delta
         * and snapshot window stack.
         */
        fun classifyPostClick(
            prePopupCount: Int,
            postSnapshot: ScopedSnapshotBuilder.CompactSnapshot,
        ): ClickOutcome {
            val postPopupCount = postSnapshot.windowStack.size
            val hasDialog = postSnapshot.activeContext == ScopedSnapshotBuilder.ActiveContext.DIALOG
            val hasInlineWidget = postSnapshot.activeContext == ScopedSnapshotBuilder.ActiveContext.INLINE_WIDGET
            val hasPopup =
                postSnapshot.activeContext == ScopedSnapshotBuilder.ActiveContext.POPUP_MENU ||
                    postSnapshot.activeContext == ScopedSnapshotBuilder.ActiveContext.POPUP_CHOOSER

            return when {
                hasDialog || hasInlineWidget -> ClickOutcome.TOOL_TRIGGERED
                postPopupCount > prePopupCount -> ClickOutcome.SUBMENU
                postPopupCount > 0 && hasPopup -> ClickOutcome.SUBMENU
                postPopupCount == 0 && !hasDialog && !hasInlineWidget -> ClickOutcome.DISMISSED
                else -> ClickOutcome.TOOL_TRIGGERED
            }
        }
    }

    /**
     * Result of executing an action.
     */
    data class ActionResult(
        val success: Boolean,
        val message: String,
        val data: Map<String, Any> = emptyMap(),
    )

    /**
     * Execute an action.
     *
     * @param action The action to execute
     * @param currentUiTree The current UI tree (raw, no intermediate transformation)
     * @param goal The user's intent, forwarded to LLM-based post-click analysis so
     *   the classifier has enough context to distinguish submenu from tool trigger.
     * @return The result of the action
     */
    fun execute(
        action: AgentAction,
        currentUiTree: List<UiComponent>,
        goal: String = "",
    ): ActionResult {
        println("  ActionGenerator: Executing ${action::class.simpleName}")

        return when (action) {
            is AgentAction.OpenFile -> executeOpenFile(action)
            is AgentAction.MoveCaret -> executeMoveCaret(action)
            is AgentAction.SelectLines -> executeSelectLines(action)
            is AgentAction.Click -> executeClick(action, currentUiTree, goal)
            is AgentAction.ClickMenuItem -> executeClickMenuItem(action, goal)
            is AgentAction.ClickButton -> executeClickButton(action)
            is AgentAction.OpenContextMenu -> executeOpenContextMenu()
            is AgentAction.CloseAllPopups -> executeCloseAllPopups()
            is AgentAction.Type -> executeType(action, currentUiTree)
            is AgentAction.PressKey -> executePressKey(action)
            is AgentAction.FocusEditor -> executeFocusEditor()
            is AgentAction.CancelDialog -> executeCancelDialog()
            is AgentAction.Observe -> ActionResult(true, "Observed UI state")
            is AgentAction.Complete -> ActionResult(true, "Task completed")
            is AgentAction.Fail -> ActionResult(false, "Task failed")
        }
    }

    // ── Navigation Actions ─────────────────────────────────────────────────────

    /**
     * Execute an OpenFile action.
     *
     * After triggering the open, we inspect the UI tree for evidence that the
     * file actually came up: a matching editor tab, a nav-bar breadcrumb, or
     * an `accessibleName` like `Editor for <filename>`. If none of that is
     * visible the action is reported as a failure so the agent loop doesn't
     * silently march on assuming the file is open.
     */
    private fun executeOpenFile(action: AgentAction.OpenFile): ActionResult {
        return try {
            executor.openFile(action.path)
            Thread.sleep(1000)
            val opened = isFileVisiblyOpen(action.path)
            if (opened) {
                ActionResult(true, "Opened file '${action.path}'")
            } else {
                ActionResult(
                    false,
                    "OpenFile triggered for '${action.path}' but no matching editor/tab/breadcrumb appeared. " +
                        "The Search Everywhere dialog may still be open, or the filename may not match.",
                )
            }
        } catch (e: Exception) {
            ActionResult(false, "Failed to open file: ${e.message}")
        }
    }

    /**
     * Heuristic check for "is this file visibly open". We look across the
     * whole tree rather than relying on the compact snapshot so this works
     * even when the profile hasn't classified the editor/tab classes.
     */
    private fun isFileVisiblyOpen(path: String): Boolean {
        val filename = path.substringAfterLast('/').substringAfterLast('\\')
        if (filename.isBlank()) return false
        val all = UiTreeParser.flatten(uiTreeProvider.invoke())
        return all.any { c ->
            val acc = c.accessibleName
            val txt = c.text
            acc.equals(filename, ignoreCase = true) ||
                txt.equals(filename, ignoreCase = true) ||
                acc.endsWith("/$filename", ignoreCase = true) ||
                acc.equals("Editor for $filename", ignoreCase = true) ||
                acc.startsWith("Editor for ", ignoreCase = true) && acc.endsWith(filename, ignoreCase = true)
        }
    }

    /**
     * Execute a MoveCaret action.
     */
    private fun executeMoveCaret(action: AgentAction.MoveCaret): ActionResult {
        return try {
            val outcome = executor.moveCaretToSymbol(action.symbol)
            Thread.sleep(300)
            // When the caret was ALREADY on the symbol, say so explicitly.
            // That's the signal for the LLM to stop re-navigating and
            // invoke the next step (OpenContextMenu, etc.). We still
            // return success=true because the precondition "caret is on
            // '<symbol>'" holds — the action is just a no-op.
            val msg =
                if (outcome.alreadyOnSymbol) {
                    "Caret was already on '${action.symbol}' (line ${outcome.line}). " +
                        "No navigation needed — proceed with the next step " +
                        "(e.g. OpenContextMenu then the refactor). IntelliJ's " +
                        "refactorings resolve from a call site to the declaration."
                } else {
                    "Moved caret to '${action.symbol}' (line ${outcome.line})"
                }
            ActionResult(true, msg)
        } catch (e: Exception) {
            ActionResult(false, "Failed to move caret: ${e.message}")
        }
    }

    /**
     * Execute a SelectLines action.
     */
    private fun executeSelectLines(action: AgentAction.SelectLines): ActionResult {
        return try {
            executor.selectLineRange(action.start, action.end)
            Thread.sleep(300)
            ActionResult(true, "Selected lines ${action.start}-${action.end}")
        } catch (e: Exception) {
            ActionResult(false, "Failed to select lines: ${e.message}")
        }
    }

    // ── UI Interaction Actions ─────────────────────────────────────────────────

    /**
     * Execute a click action.
     *
     * IMPORTANT: After EVERY menu item click, we observe the UI state and use LLM
     * to classify what happened. We do NOT use keyword-based detection - every click
     * gets the same treatment: observe → classify → return analysis.
     *
     * For dialog buttons, we click and return success.
     */
    private fun executeClick(
        action: AgentAction.Click,
        uiTree: List<UiComponent>,
        goal: String = "",
    ): ActionResult {
        val target = action.target

        // Use the compact snapshot to decide which click helper to try first.
        // For POPUP_MENU / POPUP_CHOOSER we want clickMenuItem; for DIALOG we
        // want clickDialogButton. EDITOR with a *MenuItem present in the tree
        // also routes to menu-item click (e.g. just-opened context menu the
        // profile hasn't classified).
        val preClickSnapshot = ScopedSnapshotBuilder.buildCompactSnapshot(uiTree, profile)
        val hasMenuItemsInTree =
            UiTreeParser.flatten(uiTree).any { it.cls.contains("MenuItem") || it.cls == "ActionMenu" }
        val menuFirst =
            preClickSnapshot.activeContext == ScopedSnapshotBuilder.ActiveContext.POPUP_MENU ||
                preClickSnapshot.activeContext == ScopedSnapshotBuilder.ActiveContext.POPUP_CHOOSER ||
                hasMenuItemsInTree

        return try {
            if (menuFirst) {
                tryMenuItemThenDialogButton(target, goal)
            } else {
                tryDialogButtonThenMenuItem(target, goal)
            }
        } catch (e: Exception) {
            ActionResult(false, "Failed to click '$target': ${e.message}")
        }
    }

    private fun tryMenuItemThenDialogButton(
        target: String,
        goal: String,
    ): ActionResult {
        try {
            return performMenuClick(target, goal)
        } catch (menuErr: Exception) {
            println("    clickMenuItem failed: ${menuErr.message} — falling back to dialog button")
            return try {
                executor.clickDialogButton(target)
                Thread.sleep(500)
                ActionResult(true, "Clicked button '$target'")
            } catch (btnErr: Exception) {
                ActionResult(false, "Could not click '$target' as menu item or button: ${btnErr.message}")
            }
        }
    }

    private fun tryDialogButtonThenMenuItem(
        target: String,
        goal: String,
    ): ActionResult {
        return try {
            executor.clickDialogButton(target)
            Thread.sleep(500)
            ActionResult(true, "Clicked button '$target'")
        } catch (btnErr: Exception) {
            println("    clickDialogButton failed: ${btnErr.message} — falling back to menu item")
            try {
                performMenuClick(target, goal)
            } catch (menuErr: Exception) {
                ActionResult(false, "Could not click '$target' as button or menu item: ${menuErr.message}")
            }
        }
    }

    private fun performMenuClick(
        target: String,
        goal: String,
    ): ActionResult {
        val preClickUiTree = uiTreeProvider()
        val preClickPopupCount = ScopedSnapshotBuilder.popupCount(preClickUiTree)

        executor.clickMenuItemByLabel(target)
        Thread.sleep(800)
        waitForUIElement(timeoutMs = 2000)

        val postClickUiTree = uiTreeProvider()
        val postClickSnapshot = ScopedSnapshotBuilder.buildCompactSnapshot(postClickUiTree, profile)

        val outcome = classifyPostClick(preClickPopupCount, postClickSnapshot)

        println("    Post-click outcome: $outcome")

        val success = outcome != ClickOutcome.DISMISSED
        val prefix = if (success) "Clicked menu item" else "Click had no effect on"
        val description =
            when (outcome) {
                ClickOutcome.SUBMENU -> "Opened submenu"
                ClickOutcome.TOOL_TRIGGERED -> "Tool triggered (dialog or inline widget)"
                ClickOutcome.DISMISSED -> "Menu dismissed"
            }

        return ActionResult(
            success = success,
            message = "$prefix '$target' - $description",
            data =
                mapOf(
                    "outcome" to outcome.name,
                    "isSubmenu" to (outcome == ClickOutcome.SUBMENU),
                    "isToolTriggered" to (outcome == ClickOutcome.TOOL_TRIGGERED),
                ),
        )
    }

    /**
     * Wait for a UI element (dialog, popup, or inline widget) to appear
     * after a click. This is a lightweight settle-probe and MUST stay
     * side-effect free with respect to IntelliJ's EDT.
     *
     * Historical footgun: this loop used to call [UiExecutor.getEditorContext]
     * (via `fetchEditorCodeSafely`) on every attempt, purely to enrich
     * inline-widget detection. That helper fires a Remote Robot `callJs`
     * which dispatches onto the EDT via `invokeAndWait`. When the click
     * being settled is something like `Change Signature...` — which is
     * mid-construction of a modal dialog on the EDT — the JS request
     * queues, our HTTP client times out after ~10s, but **the queued
     * task on Remote Robot's side remains stuck** because macOS modal
     * nested event loops don't reliably pump non-EDT `invokeAndWait`
     * calls. Remote Robot serializes component-API requests, so a single
     * stuck `callJs` poisons its dispatch queue and every subsequent
     * `/api/tree` / `/api/component/...` hangs indefinitely.
     *
     * We therefore detect dialog / popup / text-input purely from the
     * raw HTML tree — no JS round-trip, no EDT contention. Inline
     * rename widgets are still detected from the tree alone (the
     * suggestion JBList is always present); the `selectedText`
     * refinement that editorCode used to provide is deferred to the
     * next iteration's observe step, which runs when the dialog (if
     * any) is already fully settled.
     *
     * @return true if an element was detected, false otherwise
     */
    private fun waitForUIElement(timeoutMs: Long = 3000): Boolean {
        val startTime = System.currentTimeMillis()

        println("    Auto-waiting for UI element...")

        var attempt = 0
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            attempt++
            val freshUiTree = uiTreeProvider()

            // Tree-only detection — no callJs. See docstring above.
            val snap = ScopedSnapshotBuilder.buildCompactSnapshot(freshUiTree, profile, editorCode = null)
            val hasDialog =
                snap.windowStack.any { it.type == ScopedSnapshotBuilder.ActiveContext.DIALOG }
            val hasInlineWidget = snap.inlineWidget != null
            val popupCount = ScopedSnapshotBuilder.popupCount(freshUiTree)
            val hasTextInput =
                UiTreeParser.flatten(freshUiTree).any { profile.isTextInput(it.cls) && it.enabled }

            if (hasDialog || hasInlineWidget || popupCount > 0 || hasTextInput) {
                val detectedType =
                    when {
                        hasDialog -> "dialog"
                        hasInlineWidget -> "inline widget"
                        popupCount > 0 -> "popup"
                        else -> "text input"
                    }
                println("    ✓ UI element detected: $detectedType (attempt $attempt)")
                return true
            }

            Thread.sleep(300)
        }

        println("    No dialog/widget detected after ${timeoutMs}ms ($attempt attempts)")
        return false
    }

    /**
     * Execute a type action.
     *
     * If [AgentAction.Type.target] is set, we first focus that field. Clearing
     * is delegated entirely to [UiExecutor.typeInDialog], which picks the
     * correct strategy based on focus (platform-aware select-all for text
     * fields, skip-clear for inline editor templates). The prior implementation
     * emitted an extra outer `Ctrl+A` here which, combined with the internal
     * clear, could wipe the whole source file when the inline rename template
     * left focus on `EditorComponentImpl`.
     */
    private fun executeType(
        action: AgentAction.Type,
        uiTree: List<UiComponent>,
    ): ActionResult {
        return try {
            if (action.target != null) {
                println("    Focusing field '${action.target}' before typing")
                try {
                    executor.focusFieldByLabel(action.target)
                    Thread.sleep(200)
                } catch (e: Exception) {
                    println("    Could not focus field '${action.target}': ${e.message}")
                }
            }

            executor.typeInDialog(action.text, clearFirst = action.clearFirst)
            Thread.sleep(action.text.length * 20L)
            ActionResult(
                true,
                "Typed '${action.text}' in '${action.target ?: "current field"}' (clearFirst=${action.clearFirst})",
            )
        } catch (e: Exception) {
            ActionResult(false, "Failed to type: ${e.message}")
        }
    }

    /**
     * Execute a press key action.
     * Supports both single keys (Enter, Escape) and shortcuts (Shift+F6).
     * Special key "context_menu" opens the right-click context menu.
     */
    private fun executePressKey(action: AgentAction.PressKey): ActionResult {
        return try {
            val key = action.key.lowercase()

            when {
                // Special case: open context menu
                key == "context_menu" -> {
                    executor.openContextMenu()
                    Thread.sleep(500) // Wait for menu to appear
                    ActionResult(true, "Opened context menu")
                }
                // Shortcut (contains +)
                key.contains("+") -> {
                    executor.pressShortcut(action.key) // Preserve original case
                    Thread.sleep(300)
                    ActionResult(true, "Pressed shortcut '${action.key}'")
                }
                // Single key
                else -> {
                    executor.pressKey(action.key)
                    Thread.sleep(300)
                    ActionResult(true, "Pressed key '${action.key}'")
                }
            }
        } catch (e: Exception) {
            ActionResult(false, "Failed to press key: ${e.message}")
        }
    }

    // ── Extended action primitives ─────────────────────────────────────────────

    /** Give focus back to the editor (common recovery after stray popups). */
    private fun executeFocusEditor(): ActionResult {
        return try {
            executor.focusEditor()
            Thread.sleep(150)
            ActionResult(true, "Focused editor")
        } catch (e: Exception) {
            ActionResult(false, "Failed to focus editor: ${e.message}")
        }
    }

    /**
     * Cancel the topmost dialog / popup.
     *
     * Verifies success by observing that the window stack shrank. If the
     * topmost window is still open after Escape, the action is reported as
     * a failure so the LLM doesn't assume the dialog is gone.
     */
    private fun executeCancelDialog(): ActionResult {
        return try {
            val before = uiTreeProvider()
            val beforeSnap = ScopedSnapshotBuilder.buildCompactSnapshot(before, profile)
            val topBefore = beforeSnap.windowStack.lastOrNull()

            executor.pressKey("Escape")
            Thread.sleep(300)

            val after = uiTreeProvider()
            val afterSnap = ScopedSnapshotBuilder.buildCompactSnapshot(after, profile)
            val topAfter = afterSnap.windowStack.lastOrNull()

            val closed =
                topBefore != null &&
                    (topAfter == null || topAfter.title != topBefore.title || afterSnap.windowStack.size < beforeSnap.windowStack.size)

            if (closed) {
                ActionResult(true, "Cancelled topmost window '${topBefore.title}'")
            } else {
                ActionResult(false, "Escape pressed but topmost window '${topBefore?.title}' is still open")
            }
        } catch (e: Exception) {
            ActionResult(false, "Failed to cancel dialog: ${e.message}")
        }
    }

    private fun executeClickMenuItem(
        action: AgentAction.ClickMenuItem,
        goal: String,
    ): ActionResult {
        return try {
            performMenuClick(action.target, goal)
        } catch (e: Exception) {
            ActionResult(false, "Failed to click menu item '${action.target}': ${e.message}")
        }
    }

    /**
     * Click a dialog button by label — dedicated form of [AgentAction.Click]
     * for DIALOG context. No menu fallback.
     */
    private fun executeClickButton(action: AgentAction.ClickButton): ActionResult {
        return try {
            executor.clickDialogButton(action.target)
            Thread.sleep(400)
            ActionResult(true, "Clicked button '${action.target}'")
        } catch (e: Exception) {
            ActionResult(false, "Failed to click button '${action.target}': ${e.message}")
        }
    }

    /** Open the editor context menu at the current caret position. */
    private fun executeOpenContextMenu(): ActionResult {
        return try {
            executor.openContextMenu()
            Thread.sleep(300)
            ActionResult(true, "Opened context menu")
        } catch (e: Exception) {
            ActionResult(false, "Failed to open context menu: ${e.message}")
        }
    }

    /**
     * Close every open popup/dialog with repeated Escape, verified by a
     * shrinking window stack. Safe recovery primitive when the agent is
     * stuck in a stacked-popup dead-end.
     */
    private fun executeCloseAllPopups(): ActionResult {
        return try {
            val before = uiTreeProvider()
            val beforeCount = ScopedSnapshotBuilder.buildCompactSnapshot(before, profile).windowStack.size
            executor.closeAllDialogs()
            Thread.sleep(200)
            val after = uiTreeProvider()
            val afterCount = ScopedSnapshotBuilder.buildCompactSnapshot(after, profile).windowStack.size
            if (afterCount < beforeCount) {
                ActionResult(true, "Closed popups/dialogs ($beforeCount -> $afterCount)")
            } else if (beforeCount == 0) {
                ActionResult(true, "No popups or dialogs were open")
            } else {
                ActionResult(false, "CloseAllPopups did not reduce window stack (still $afterCount)")
            }
        } catch (e: Exception) {
            ActionResult(false, "Failed to close popups: ${e.message}")
        }
    }

    /**
     * Find an element by its label in the current observation.
     */
    private fun findElementByLabel(
        label: String,
        uiTree: List<UiComponent>,
    ): UiComponent? {
        // Flatten and search all components
        val allComponents = UiTreeParser.flatten(uiTree)

        // Try exact match first
        for (component in allComponents) {
            val componentLabel = component.accessibleName.ifBlank { component.text }
            if (componentLabel.equals(label, ignoreCase = true)) {
                return component
            }
        }

        // Try partial match
        for (component in allComponents) {
            val componentLabel = component.accessibleName.ifBlank { component.text }
            if (componentLabel.contains(label, ignoreCase = true)) {
                return component
            }
        }

        return null
    }

    private fun fetchEditorCodeSafely(uiTreeHint: List<UiComponent>? = null): ScopedSnapshotBuilder.EditorCode? {
        if (uiTreeHint != null && ScopedSnapshotBuilder.containsDialog(uiTreeHint, profile)) {
            return null
        }
        return try {
            executor.getEditorContext()?.let {
                ScopedSnapshotBuilder.EditorCode(
                    caretLine = it.caretLine,
                    caretColumn = it.caretColumn,
                    totalLines = it.totalLines,
                    symbolUnderCaret = it.symbolUnderCaret,
                    selectedText = it.selectedText,
                    windowStartLine = it.windowStartLine,
                    windowEndLine = it.windowEndLine,
                    visibleText = it.visibleText,
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
