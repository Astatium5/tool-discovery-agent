package execution

import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import llm.PromptLogger
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
 * 3. Using UiExecutor for all IDE interactions
 * 4. Analyzing UI state after tool-triggering actions using LLM
 *
 * The key principle is that actions are executed against the live UI,
 * not assumed state.
 */
class ActionGenerator(
    private val executor: UiExecutor,
    private val profile: ApplicationProfile,
    private val uiTreeProvider: () -> List<UiComponent>,
    private val llm: ChatModel? = null,
    private val promptLogger: PromptLogger? = null,
) {
    companion object {
        /**
         * Prompt for LLM to analyze UI state after clicking a menu item.
         * This helps distinguish between submenus, tool dialogs, and dismissed menus.
         */
        private const val UI_STATE_ANALYSIS_PROMPT = """You are analyzing UI state after clicking a menu item in a desktop application.

Menu item clicked: {{TOOL_NAME}}
User's goal: {{GOAL}}

Current UI State (structured signals — trust these over your own reading):
- Popup windows: {{POPUP_COUNT}}  (popup count BEFORE the click: {{PRE_POPUP_COUNT}})
- Dialog detected: {{HAS_DIALOG}}
- Inline rename/extract widget detected: {{HAS_INLINE_WIDGET}}
- Legacy inline-editor heuristic: {{HAS_INLINE}}
- List/chooser detected: {{HAS_POPUP}}

UI Snapshot (popups and focused component first):
{{UI_SNAPSHOT}}

## Your Task
Determine what happened after clicking the menu item. Classify into ONE of these categories:

1. **SUBMENU** - The clicked item opened a submenu with more options
   - Popup count grew (more popups than before the click)
   - Menu items are visible in the snapshot (e.g., "Rename...", "Move...", "Extract Function...")

2. **TOOL_TRIGGERED** - The actual tool was triggered
   - Dialog detected is true → a dialog was opened
   - Inline rename/extract widget detected is true → an inline template is live (type the new name + Enter)
   - A popup chooser (list/table/tree) is present without normal menu items

3. **DISMISSED** - The menu closed without triggering anything
   - Popup count is 0 AND Dialog detected is false AND inline widget detected is false
   - ONLY choose this when ALL three conditions hold — if any popup remains, the click almost certainly had some effect (a submenu opened, an inline widget rendered, or a chooser appeared) and you should pick SUBMENU or TOOL_TRIGGERED.

## Important
- The boolean flags above are computed by a structured detector, not by your reading of the snapshot. If "Inline rename/extract widget detected" is TRUE, the state is TOOL_TRIGGERED regardless of what the snapshot text shows.
- If you cannot identify specific menu items but popup count is non-zero, prefer TOOL_TRIGGERED over DISMISSED — the tool likely opened something we couldn't render.

Return JSON:
{
  "state_type": "submenu" | "tool_triggered" | "dismissed",
  "reasoning": "Why you classified it this way",
  "available_items": ["list of menu items if submenu, else empty"],
  "tool_response_type": "dialog" | "inline" | "popup_chooser" | "none",
  "confirm_action": "button/key to confirm (e.g., 'Enter', 'Refactor', 'OK') or empty",
  "dialog_fields": ["field names to fill if dialog, else empty"],
  "description": "What happened and what the user should do next"
}
"""
    }

    /**
     * Result of LLM-based UI state analysis after clicking a menu item.
     */
    data class ToolResponseAnalysis(
        val stateType: StateType,
        val reasoning: String,
        val availableItems: List<String>,
        val toolResponseType: String,
        val confirmAction: String,
        val dialogFields: List<String>,
        val description: String,
    ) {
        enum class StateType {
            SUBMENU, // Menu item opened a submenu
            TOOL_TRIGGERED, // Actual tool was triggered (dialog/widget appeared)
            DISMISSED, // Menu closed without action
            UNKNOWN, // Could not determine
        }

        val isSubmenu: Boolean get() = stateType == StateType.SUBMENU
        val isToolTriggered: Boolean get() = stateType == StateType.TOOL_TRIGGERED
        val isDismissed: Boolean get() = stateType == StateType.DISMISSED
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
            // Navigation actions
            is AgentAction.OpenFile -> executeOpenFile(action)
            is AgentAction.MoveCaret -> executeMoveCaret(action)
            is AgentAction.SelectLines -> executeSelectLines(action)
            // UI interaction actions
            is AgentAction.Click -> executeClick(action, currentUiTree, goal)
            is AgentAction.ClickMenuItem -> executeClickMenuItem(action, goal)
            is AgentAction.ClickButton -> executeClickButton(action)
            is AgentAction.OpenContextMenu -> executeOpenContextMenu()
            is AgentAction.CloseAllPopups -> executeCloseAllPopups()
            is AgentAction.Type -> executeType(action, currentUiTree)
            is AgentAction.PressKey -> executePressKey(action)
            is AgentAction.SelectDropdown -> executeSelectDropdown(action, currentUiTree)
            is AgentAction.Wait -> executeWait(action, currentUiTree)
            is AgentAction.UseRecipe -> executeRecipe(action, currentUiTree)
            is AgentAction.FocusEditor -> executeFocusEditor()
            is AgentAction.CancelDialog -> executeCancelDialog()
            is AgentAction.SetCheckbox -> executeSetCheckbox(action)
            is AgentAction.Scroll -> executeScroll(action)
            is AgentAction.Verify -> executeVerify(action, currentUiTree)
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
            val outcome = executor.moveCaret(action.symbol)
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
            executor.selectLines(action.start, action.end)
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

        executor.clickMenuItem(target)
        Thread.sleep(800)
        waitForUIElement(timeoutMs = 2000)

        val postClickUiTree = uiTreeProvider()

        val analysis =
            analyzeToolResponse(
                toolName = target,
                uiTree = postClickUiTree,
                goal = goal,
                preClickPopupCount = preClickPopupCount,
            )

        println("    LLM State Analysis: ${analysis.stateType} - ${analysis.reasoning.take(80)}")

        val success = !analysis.isDismissed
        val prefix = if (success) "Clicked menu item" else "Click had no effect on"

        return ActionResult(
            success = success,
            message = "$prefix '$target' - ${analysis.description}",
            data =
                mapOf(
                    "analysis" to analysis,
                    "stateType" to analysis.stateType.name,
                    "isSubmenu" to analysis.isSubmenu,
                    "isToolTriggered" to analysis.isToolTriggered,
                    "availableItems" to analysis.availableItems,
                    "confirmAction" to analysis.confirmAction,
                    "dialogFields" to analysis.dialogFields,
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
                    executor.focusField(action.target)
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

    /**
     * Execute a select dropdown action.
     */
    private fun executeSelectDropdown(
        action: AgentAction.SelectDropdown,
        uiTree: List<UiComponent>,
    ): ActionResult {
        return try {
            executor.selectDropdownField(action.target, action.value)
            Thread.sleep(300)
            ActionResult(true, "Selected '${action.value}' from '${action.target}'")
        } catch (e: Exception) {
            ActionResult(false, "Failed to select dropdown value: ${e.message}")
        }
    }

    /**
     * Execute a wait action.
     * Re-observes the UI to check if the element appeared.
     */
    private fun executeWait(
        action: AgentAction.Wait,
        uiTree: List<UiComponent>,
    ): ActionResult {
        val startTime = System.currentTimeMillis()
        val elementType = action.elementType.lowercase()

        println("    Waiting for element '$elementType' (timeout: ${action.timeoutMs}ms)")

        // Poll for the element to appear
        while (System.currentTimeMillis() - startTime < action.timeoutMs) {
            // Get fresh UI tree
            val freshUiTree = uiTreeProvider()
            val allComponents = UiTreeParser.flatten(freshUiTree)

            // Check for dialogs
            val foundDialog = allComponents.any { profile.isDialog(it.cls) }

            // Check for popups
            val foundPopup = allComponents.any { profile.isPopupWindow(it.cls) }

            // Check for inline widgets (popup with text input)
            val foundInline =
                allComponents.any { component ->
                    profile.isPopupWindow(component.cls) &&
                        component.children.any { profile.isTextInput(it.cls) }
                }

            // Check for text inputs
            val foundTextField =
                if (elementType.contains("text") || elementType.contains("input") || elementType.contains("field")) {
                    allComponents.any { profile.isTextInput(it.cls) }
                } else {
                    false
                }

            // Check for specific element types by name/label
            val foundByLabel =
                allComponents.any { component ->
                    val label = component.accessibleName.ifBlank { component.text }
                    label.lowercase().contains(elementType)
                }

            if (foundDialog || foundPopup || foundInline || foundTextField || foundByLabel) {
                val foundType =
                    when {
                        foundDialog -> "dialog"
                        foundInline -> "inline widget"
                        foundPopup -> "popup"
                        foundTextField -> "text field"
                        else -> elementType
                    }
                println("    ✓ Found element '$foundType'")
                return ActionResult(true, "Element '$elementType' appeared")
            }

            Thread.sleep(300)
        }

        println("    ✗ Element '$elementType' not found after ${action.timeoutMs}ms")
        return ActionResult(false, "Element '$elementType' did not appear within ${action.timeoutMs}ms")
    }

    /**
     * Execute a recipe (as reference, not blind execution).
     * This is a placeholder - the actual implementation would use the recipe
     * as context for the LLM to make decisions.
     */
    private fun executeRecipe(
        action: AgentAction.UseRecipe,
        uiTree: List<UiComponent>,
    ): ActionResult {
        // Recipes should not be executed blindly.
        // Instead, they should be used as context for the LLM.
        return ActionResult(
            success = false,
            message = "Recipe execution should be handled by the Brain Agent, not ActionGenerator",
        )
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
    /**
     * Click a menu item by label — dedicated form of [AgentAction.Click] that
     * skips the button-fallback heuristic and reuses the existing
     * [performMenuClick] LLM-analysed path so we still classify whether the
     * click opened a submenu, triggered a tool, or dismissed.
     */
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
            executor.closeAllPopups()
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

    private fun executeCancelDialog(): ActionResult {
        return try {
            val before = uiTreeProvider()
            val beforeSnap = ScopedSnapshotBuilder.buildCompactSnapshot(before, profile)
            val topBefore = beforeSnap.windowStack.lastOrNull()

            executor.pressEscape()
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

    private fun executeSetCheckbox(action: AgentAction.SetCheckbox): ActionResult {
        return try {
            executor.setCheckbox(action.target, action.checked)
            Thread.sleep(200)
            ActionResult(true, "Set checkbox '${action.target}' to ${action.checked}")
        } catch (e: Exception) {
            ActionResult(false, "Failed to set checkbox '${action.target}': ${e.message}")
        }
    }

    /**
     * Scroll inside the active window.
     *
     * We map abstract directions onto keyboard shortcuts so the action works
     * uniformly across lists, trees, tables, and editors.
     */
    private fun executeScroll(action: AgentAction.Scroll): ActionResult {
        val key =
            when (action.direction.lowercase()) {
                "up" -> "UP"
                "down" -> "DOWN"
                "page_up", "pageup" -> "PAGE_UP"
                "page_down", "pagedown" -> "PAGE_DOWN"
                "home" -> "HOME"
                "end" -> "END"
                else -> return ActionResult(false, "Unknown scroll direction '${action.direction}'")
            }

        return try {
            if (action.target.isNotBlank()) {
                // Best-effort focus on the named target before scrolling.
                try {
                    executor.focusField(action.target)
                } catch (_: Exception) {
                    // Non-fatal: the target may not be a field but a generic list.
                }
            }
            val steps = action.amount.coerceAtLeast(1)
            repeat(steps) {
                executor.pressKey(key)
                Thread.sleep(80)
            }
            ActionResult(
                success = true,
                message =
                    "Scrolled ${action.direction} x$steps" +
                        if (action.target.isNotBlank()) " on '${action.target}'" else "",
            )
        } catch (e: Exception) {
            ActionResult(false, "Failed to scroll: ${e.message}")
        }
    }

    /**
     * Evaluate a [AgentAction.Verify] predicate against the live UI.
     *
     * Predicates are deliberately restricted to what can be answered from a
     * [ScopedSnapshotBuilder.CompactSnapshot] — this keeps verification free
     * of side effects and cheap to run between steps.
     */
    private fun executeVerify(
        action: AgentAction.Verify,
        uiTree: List<UiComponent>,
    ): ActionResult {
        // Fetch live editor state too — source-content predicates need it, and
        // the UI-surface predicates ignore it. A null editorCode just means
        // source_* predicates will evaluate to false (no source to match
        // against) which is the correct, conservative behaviour. We pass
        // the tree so the helper can skip the `callJs` round-trip when a
        // dialog is still on top (source_* predicates for edits applied
        // after closing the dialog run in a later turn anyway, once the
        // tree no longer contains that dialog).
        val editorCode = fetchEditorCodeSafely(uiTree)
        val snap = ScopedSnapshotBuilder.buildCompactSnapshot(uiTree, profile, editorCode)
        val p = action.predicate.trim()
        val (kind, arg) =
            if (":" in p) {
                val i = p.indexOf(':')
                p.substring(0, i).lowercase() to p.substring(i + 1).trim()
            } else if ("=" in p) {
                val i = p.indexOf('=')
                p.substring(0, i).lowercase() to p.substring(i + 1).trim()
            } else {
                p.lowercase() to ""
            }

        val labels = snap.activeWindow.let { it.fields + it.buttons + it.menuItems }
        val visibleSource = editorCode?.visibleText.orEmpty()

        // Lazy full-document fetch: only fire the `callJs` round-trip when a
        // predicate actually needs it, and never while a dialog is open (same
        // EDT-wedging reasoning as `fetchEditorCodeSafely`).
        val fullDocumentText: String? by lazy {
            if (ScopedSnapshotBuilder.containsDialog(uiTree, profile)) {
                null
            } else {
                try {
                    executor.getDocumentText()
                } catch (_: Exception) {
                    null
                }
            }
        }

        val ok =
            when (kind) {
                "dialog_open" ->
                    snap.windowStack.any {
                        it.type == ScopedSnapshotBuilder.ActiveContext.DIALOG &&
                            it.title.contains(arg, ignoreCase = true)
                    }
                "popup_open" ->
                    snap.windowStack.any {
                        it.type == ScopedSnapshotBuilder.ActiveContext.POPUP_MENU ||
                            it.type == ScopedSnapshotBuilder.ActiveContext.POPUP_CHOOSER ||
                            it.type == ScopedSnapshotBuilder.ActiveContext.INLINE_WIDGET
                    }
                "no_popup" -> snap.windowStack.isEmpty()
                "context" ->
                    runCatching { ScopedSnapshotBuilder.ActiveContext.valueOf(arg.uppercase()) }
                        .getOrNull() == snap.activeContext
                "button_enabled" ->
                    snap.activeWindow.buttons.any {
                        it.label.equals(arg, ignoreCase = true) && it.enabled
                    }
                "field_present" ->
                    snap.activeWindow.fields.any { it.label.contains(arg, ignoreCase = true) }
                "focused" ->
                    snap.focused?.label?.contains(arg, ignoreCase = true) == true

                // Source-content predicates — indispensable for confirming
                // refactor outcomes like "rename took effect". They look at
                // `editorCode.visibleText`, which is a ~50-line window around
                // the caret (see UiExecutor.getEditorContext). Callers who
                // need to check text that's off-screen should either
                // MoveCaret to the region first, or use the file_* predicates
                // below which scan the whole document.
                "source_contains" ->
                    arg.isNotBlank() && visibleSource.contains(arg)
                "source_absent" ->
                    arg.isNotBlank() && !visibleSource.contains(arg)
                "line_contains" -> {
                    // Form: line_contains:<line>:<text>
                    val parts = arg.split(":", limit = 2)
                    val lineNum = parts.getOrNull(0)?.trim()?.toIntOrNull()
                    val needle = parts.getOrNull(1)?.trim().orEmpty()
                    if (lineNum == null || needle.isBlank() || editorCode == null) {
                        false
                    } else {
                        // editorCode's visibleText is the source window starting
                        // at `windowStartLine` (0-based). LLM speaks in 1-based
                        // line numbers, so translate.
                        val lines = visibleSource.lines()
                        val offset = lineNum - 1 - editorCode.windowStartLine
                        val line = lines.getOrNull(offset)
                        line != null && line.contains(needle)
                    }
                }

                // Full-document predicates — essential for refactors that
                // change code FAR from the caret. After `Change Signature`
                // the caret stays on the original call site, so the
                // modified method declaration (often hundreds of lines
                // away) never shows up in Visible Source. These scan the
                // entire open document so the LLM can confirm the edit
                // landed without having to navigate first.
                "file_contains" ->
                    arg.isNotBlank() && fullDocumentText?.contains(arg) == true
                "file_absent" ->
                    arg.isNotBlank() && fullDocumentText?.let { !it.contains(arg) } == true

                else -> labels.any { it.label.contains(p, ignoreCase = true) }
            }

        // Distinguish "predicate evaluated to false" from "predicate
        // couldn't be evaluated at all": the LLM must NOT interpret a
        // document-fetch failure as "the refactor didn't apply" (that
        // sent session 21-34-01 into a 5-iteration verify loop). Surface
        // it explicitly so the next turn can choose a different predicate
        // instead of re-triggering the refactor.
        val isFilePredicate = kind == "file_contains" || kind == "file_absent"
        val fileFetchFailed = isFilePredicate && fullDocumentText == null
        return when {
            ok ->
                ActionResult(true, "Verified predicate '${action.predicate}'")
            fileFetchFailed ->
                ActionResult(
                    false,
                    "Predicate '${action.predicate}' could not be evaluated " +
                        "— full document text is unavailable (no focused editor, or a modal " +
                        "dialog is still on top). Try a `source_contains` / `source_absent` " +
                        "predicate after MoveCaret brings the target into the Visible Source " +
                        "window, or use a UI-surface predicate like `context=EDITOR` to confirm " +
                        "the dialog closed. This is NOT evidence that the edit failed.",
                )
            else ->
                ActionResult(false, "Predicate failed: '${action.predicate}'")
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

    // ── LLM-Based UI State Analysis ─────────────────────────────────────────────

    /**
     * Analyze the UI state after clicking a menu item to determine what happened.
     * Uses LLM to classify whether a submenu opened, a tool was triggered, or the menu was dismissed.
     *
     * This is critical for distinguishing between:
     * - Submenus (need to navigate further)
     * - Tool dialogs (need to fill fields and confirm)
     * - Dismissed menus (click failed or cancelled)
     *
     * @param toolName The name of the menu item that was clicked
     * @param uiTree The current UI tree after the click
     * @param goal The user's goal (for context)
     * @param preClickPopupCount The popup count before the click (for comparison)
     * @return ToolResponseAnalysis with classification and details
     */
    fun analyzeToolResponse(
        toolName: String,
        uiTree: List<UiComponent>?,
        goal: String = "",
        preClickPopupCount: Int = 0,
    ): ToolResponseAnalysis {
        val safeTree = uiTree ?: emptyList()
        val allComponents = UiTreeParser.flatten(safeTree)

        // Canonical snapshot is the source of truth for "what's on screen":
        // reuses the same inline-widget / popup / dialog classifier as the
        // agent loop, so this analyzer and the next decision prompt never
        // disagree about the UI state. Fetch editor code too — the inline
        // rename detector needs `selectedText` to recognise the template
        // when the suggestion popup's JBList isn't classified. Passing the
        // tree causes the helper to skip the `callJs` round-trip when a
        // modal dialog is open (see `fetchEditorCodeSafely` docstring for
        // the Remote Robot dispatch-queue wedging this prevents).
        val editorCode = fetchEditorCodeSafely(safeTree)
        val snapshot = ScopedSnapshotBuilder.buildCompactSnapshot(safeTree, profile, editorCode)
        val hasInlineWidget = snapshot.inlineWidget != null

        // Dialog detection MUST agree with the compact snapshot — otherwise
        // the post-click analyzer emits FAIL/DISMISSED even though the main
        // agent loop correctly sees the dialog. That exact split is what
        // caused Change Signature clicks to be flagged as "Menu dismissed"
        // in session 21-20-21: `profile.isDialog` didn't know about
        // `DialogRootPane` / `MyDialog`, but `ScopedSnapshotBuilder.isDialog`
        // has a name-based fallback that did. Using the snapshot's own
        // window stack unifies the two paths.
        val hasDialog =
            snapshot.windowStack.any { it.type == ScopedSnapshotBuilder.ActiveContext.DIALOG } ||
                allComponents.any { profile.isDialog(it.cls) }
        // Legacy `hasInline` kept as a weaker fallback for the prompt text —
        // the authoritative signal is `hasInlineWidget` above.
        val hasInline =
            hasInlineWidget ||
                allComponents.any {
                    profile.isEditor(it.cls) && allComponents.any { profile.isPopupWindow(it.cls) }
                }
        val hasPopupList =
            allComponents.any {
                profile.isList(it.cls) || profile.isTable(it.cls) || profile.isTree(it.cls)
            }
        val popupCount = ScopedSnapshotBuilder.popupCount(safeTree)

        // Get menu items for context
        val allMenuItems = ScopedSnapshotBuilder.forAllPopupsStructured(safeTree)
        val menuItemLabels = allMenuItems.map { it.label }.take(20)

        // Submenu guard: if new popups appeared AND menu items are visible,
        // an existing inline editor behind the menu should not cause a false tool_triggered.
        val newPopupsAppeared = popupCount > preClickPopupCount
        val hasMenuItems = allMenuItems.isNotEmpty()
        val inlineIsProbablyPreExisting = hasInline && newPopupsAppeared && hasMenuItems

        // Debug logging
        println(
            "    UI State: $popupCount popups (was $preClickPopupCount), hasDialog=$hasDialog, " +
                "hasInlineWidget=$hasInlineWidget (snapshot), hasInline=$hasInline" +
                " (pre-existing=$inlineIsProbablyPreExisting), hasPopup=$hasPopupList, menuItems=${allMenuItems.size}",
        )

        // ── Structured short-circuits ──────────────────────────────────────
        //
        // When the compact snapshot has already identified an inline widget or
        // a dialog, we skip the LLM classifier entirely. Asking the LLM again
        // is strictly harmful here — the detector looks at the whole tree with
        // deterministic rules, the LLM only sees a truncated flattening and
        // can (and did, session 18-52-18) hallucinate "DISMISSED".
        if (hasInlineWidget && !inlineIsProbablyPreExisting) {
            val widget = snapshot.inlineWidget!!
            val suggestions =
                if (widget.suggestions.isNotEmpty()) {
                    " (suggestions: ${widget.suggestions.take(3).joinToString(", ")})"
                } else {
                    ""
                }
            val desc =
                "Inline ${widget.kind} widget is active for '${widget.oldIdentifier}'$suggestions. " +
                    "Type the new name with clearFirst=false, then PressKey('Enter') to commit."
            println("    → Structured detector: INLINE_WIDGET active, skipping LLM classifier")
            return ToolResponseAnalysis(
                stateType = ToolResponseAnalysis.StateType.TOOL_TRIGGERED,
                reasoning = "Compact snapshot detected an inline widget (${widget.kind}) — no LLM classification needed.",
                availableItems = emptyList(),
                toolResponseType = "inline",
                confirmAction = "Enter",
                dialogFields = emptyList(),
                description = desc,
            )
        }
        if (hasDialog) {
            val dialogTitle =
                snapshot.windowStack.lastOrNull { it.type == ScopedSnapshotBuilder.ActiveContext.DIALOG }?.title
                    ?: "dialog"
            println("    → Structured detector: DIALOG present, skipping LLM classifier")
            return ToolResponseAnalysis(
                stateType = ToolResponseAnalysis.StateType.TOOL_TRIGGERED,
                reasoning = "Compact snapshot detected dialog '$dialogTitle' — no LLM classification needed.",
                availableItems = emptyList(),
                toolResponseType = "dialog",
                confirmAction = "OK",
                dialogFields = snapshot.activeWindow.fields.map { it.label }.take(10),
                description = "Dialog '$dialogTitle' is open; fill the fields and confirm.",
            )
        }

        // If no LLM available, use fallback analysis
        if (llm == null) {
            println("    No LLM available, using fallback analysis")
            return fallbackAnalysis(hasDialog, hasInline, hasPopupList, menuItemLabels)
        }

        // LLM-First approach: Ask LLM to classify the UI state
        val uiSnapshot = formatUiTreeForAnalysis(uiTree)

        val prompt =
            UI_STATE_ANALYSIS_PROMPT
                .replace("{{TOOL_NAME}}", toolName)
                .replace("{{GOAL}}", goal)
                .replace("{{POPUP_COUNT}}", popupCount.toString())
                .replace("{{PRE_POPUP_COUNT}}", preClickPopupCount.toString())
                .replace("{{HAS_DIALOG}}", hasDialog.toString())
                .replace("{{HAS_INLINE_WIDGET}}", hasInlineWidget.toString())
                .replace("{{HAS_INLINE}}", hasInline.toString())
                .replace("{{HAS_POPUP}}", hasPopupList.toString())
                .replace("{{UI_SNAPSHOT}}", uiSnapshot)

        return try {
            val systemPrompt = "You are a UI state analysis agent for IDE automation."
            val started = System.currentTimeMillis()
            val response =
                llm.chat(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(prompt),
                )
            val durationMs = System.currentTimeMillis() - started
            val responseText = response.aiMessage().text()
            promptLogger?.log(
                context =
                    PromptLogger.LogContext(
                        caller = "ActionGenerator.analyzeToolResponse",
                        intent = goal,
                        extra = mapOf("tool" to toolName),
                    ),
                model = llm::class.simpleName ?: "unknown",
                messages = PromptLogger.messages(systemPrompt, prompt),
                rawResponse = responseText,
                parsedResponse = responseText,
                durationMs = durationMs,
            )

            // Parse LLM response
            val stateType = extractJsonString(responseText, "state_type") ?: "unknown"
            val reasoning = extractJsonString(responseText, "reasoning") ?: ""
            val availableItems = extractJsonArray(responseText, "available_items")
            val toolResponseType = extractJsonString(responseText, "tool_response_type") ?: "none"
            val confirmAction = extractJsonString(responseText, "confirm_action") ?: ""
            val dialogFields = extractJsonArray(responseText, "dialog_fields")
            val description = extractJsonString(responseText, "description") ?: ""

            println("    LLM Analysis: stateType=$stateType, reasoning=${reasoning.take(80)}...")

            when (stateType) {
                "submenu" -> {
                    println("    → LLM classified as SUBMENU with ${availableItems.size} items")
                    ToolResponseAnalysis(
                        stateType = ToolResponseAnalysis.StateType.SUBMENU,
                        reasoning = reasoning,
                        availableItems = availableItems.ifEmpty { menuItemLabels },
                        toolResponseType = toolResponseType,
                        confirmAction = "",
                        dialogFields = emptyList(),
                        description = description,
                    )
                }
                "tool_triggered" -> {
                    // Submenu guard: if new popups appeared with menu items
                    // and the inline editor was already there before the click,
                    // this is actually a submenu, not a tool trigger.
                    if (inlineIsProbablyPreExisting && toolResponseType == "inline") {
                        println("    → LLM said TOOL_TRIGGERED(inline) but inline editor is pre-existing; reclassifying as SUBMENU")
                        ToolResponseAnalysis(
                            stateType = ToolResponseAnalysis.StateType.SUBMENU,
                            reasoning = "$reasoning (Note: inline editor was pre-existing)",
                            availableItems = availableItems.ifEmpty { menuItemLabels },
                            toolResponseType = toolResponseType,
                            confirmAction = "",
                            dialogFields = emptyList(),
                            description = description,
                        )
                    } else {
                        println("    → LLM classified as TOOL_TRIGGERED (type: $toolResponseType)")
                        ToolResponseAnalysis(
                            stateType = ToolResponseAnalysis.StateType.TOOL_TRIGGERED,
                            reasoning = reasoning,
                            availableItems = emptyList(),
                            toolResponseType = toolResponseType,
                            confirmAction = confirmAction,
                            dialogFields = dialogFields,
                            description = description,
                        )
                    }
                }
                "dismissed" -> {
                    println("    → LLM classified as DISMISSED (menu closed)")
                    ToolResponseAnalysis(
                        stateType = ToolResponseAnalysis.StateType.DISMISSED,
                        reasoning = reasoning,
                        availableItems = emptyList(),
                        toolResponseType = "none",
                        confirmAction = "",
                        dialogFields = emptyList(),
                        description = "Menu dismissed: $description",
                    )
                }
                else -> {
                    println("    → LLM returned unknown state type, using fallback")
                    fallbackAnalysis(hasDialog, hasInline, hasPopupList, menuItemLabels)
                }
            }
        } catch (e: Exception) {
            println("    LLM analysis failed: ${e.message}, using fallback")
            fallbackAnalysis(hasDialog, hasInline, hasPopupList, menuItemLabels)
        }
    }

    /**
     * Fallback analysis when LLM is not available or fails.
     */
    private fun fallbackAnalysis(
        hasDialog: Boolean,
        hasInline: Boolean,
        hasPopup: Boolean,
        menuItems: List<String>,
    ): ToolResponseAnalysis {
        return when {
            hasDialog ->
                ToolResponseAnalysis(
                    stateType = ToolResponseAnalysis.StateType.TOOL_TRIGGERED,
                    reasoning = "Dialog detected (fallback)",
                    availableItems = emptyList(),
                    toolResponseType = "dialog",
                    confirmAction = "OK",
                    dialogFields = listOf("name"),
                    description = "Dialog opened (fallback detection)",
                )
            hasInline ->
                ToolResponseAnalysis(
                    stateType = ToolResponseAnalysis.StateType.TOOL_TRIGGERED,
                    reasoning = "Inline editor detected (fallback)",
                    availableItems = emptyList(),
                    toolResponseType = "inline",
                    confirmAction = "Enter",
                    dialogFields = emptyList(),
                    description = "Inline editor appeared (fallback detection)",
                )
            hasPopup ->
                ToolResponseAnalysis(
                    stateType = ToolResponseAnalysis.StateType.TOOL_TRIGGERED,
                    reasoning = "Popup chooser detected (fallback)",
                    availableItems = emptyList(),
                    toolResponseType = "popup_chooser",
                    confirmAction = "",
                    dialogFields = emptyList(),
                    description = "Popup chooser appeared (fallback detection)",
                )
            menuItems.isNotEmpty() ->
                ToolResponseAnalysis(
                    stateType = ToolResponseAnalysis.StateType.SUBMENU,
                    reasoning = "Menu items visible (fallback)",
                    availableItems = menuItems,
                    toolResponseType = "none",
                    confirmAction = "",
                    dialogFields = emptyList(),
                    description = "Submenu with ${menuItems.size} items (fallback detection)",
                )
            else ->
                ToolResponseAnalysis(
                    stateType = ToolResponseAnalysis.StateType.UNKNOWN,
                    reasoning = "No recognizable UI elements (fallback)",
                    availableItems = emptyList(),
                    toolResponseType = "none",
                    confirmAction = "",
                    dialogFields = emptyList(),
                    description = "Unknown state (fallback detection)",
                )
        }
    }

    /**
     * Format UI tree for the menu-analyzer prompt.
     *
     * Priority: popups and dialogs come FIRST — they're where the actionable
     * UI lives immediately after a menu click. Before this change we dumped a
     * flattened-prefix (first 50 nodes), which on IntelliJ is guaranteed to
     * be frame chrome (IdeFrameImpl, navigation bar, toolbar) and never
     * reaches the popups appended later in the tree. The LLM then saw
     * "no menu items, no dialog" and concluded DISMISSED — session
     * 2026-04-21_18-52-18 iter 6 is exactly that bug.
     */
    private fun formatUiTreeForAnalysis(uiTree: List<UiComponent>?): String {
        if (uiTree == null || uiTree.isEmpty()) return "(empty)"

        val sb = StringBuilder()
        val all = UiTreeParser.flatten(uiTree)

        // 1) Popups + dialogs: flatten each and dump its contents individually
        //    so menu items, buttons, list rows, etc. are preserved.
        val transient =
            all.filter { profile.isPopupWindow(it.cls) || profile.isDialog(it.cls) }
        if (transient.isNotEmpty()) {
            sb.append("### Popups / dialogs (top = most recent)\n")
            for ((idx, root) in transient.withIndex()) {
                val kind = if (profile.isDialog(root.cls)) "DIALOG" else "POPUP"
                val title = root.label.ifBlank { root.accessibleName.ifBlank { root.cls } }
                sb.append("- $kind#${idx + 1}: '$title' [${root.cls}]\n")
                UiTreeParser.flatten(listOf(root))
                    .asSequence()
                    .filter { it !== root }
                    .filter { it.label.isNotBlank() && it.label != it.cls }
                    .take(40)
                    .forEach {
                        val focused = if (it.focused) " (focused)" else ""
                        sb.append("    · ${it.cls.take(30)}: '${it.label}'$focused\n")
                    }
            }
            sb.append('\n')
        }

        // 2) Focused component anywhere (for inline-widget / editor cases where
        //    the relevant state isn't inside a popup at all).
        val focused = all.firstOrNull { it.focused && it.label.isNotBlank() }
        if (focused != null) {
            sb.append("### Focused component\n")
            sb.append("- ${focused.cls.take(30)}: '${focused.label}'\n\n")
        }

        // 3) Lightweight chrome prefix — just enough for the LLM to orient.
        sb.append("### Other visible nodes (prefix)\n")
        all.asSequence()
            .filter { it.label.isNotBlank() }
            .take(25)
            .forEach { sb.append("- ${it.cls.take(30)}: '${it.label}'\n") }

        return sb.toString().ifBlank { "(no visible labels)" }
    }

    /**
     * Extract a string value from JSON response.
     */
    private fun extractJsonString(
        json: String,
        key: String,
    ): String? {
        // Try nested key first (e.g., "next_action.type")
        if (key.contains(".")) {
            val parts = key.split(".")
            var current = json
            for (part in parts.dropLast(1)) {
                val pattern = Regex(""""$part"\s*:\s*\{([^}]*)\}""")
                val match = pattern.find(current)
                if (match != null) {
                    current = match.groupValues[1]
                } else {
                    return null
                }
            }
            val lastKey = parts.last()
            val pattern = Regex(""""$lastKey"\s*:\s*"([^"]*)"""")
            return pattern.find(current)?.groupValues?.get(1)
        }

        // Simple key
        val pattern = Regex(""""$key"\s*:\s*"([^"]*)"""")
        return pattern.find(json)?.groupValues?.get(1)
    }

    /**
     * Extract an array from JSON response.
     */
    private fun extractJsonArray(
        json: String,
        key: String,
    ): List<String> {
        val pattern = Regex(""""$key"\s*:\s*\[([^\]]*)\]""")
        val match = pattern.find(json) ?: return emptyList()

        val arrayContent = match.groupValues[1]
        val itemPattern = Regex(""""([^"]*)"""")
        return itemPattern.findAll(arrayContent).map { it.groupValues[1] }.toList()
    }

    /**
     * Fetch live editor code context (caret, selection, visible source) via
     * the JS bridge. Returns `null` on any failure so callers don't have to
     * guard — a null `editorCode` degrades gracefully (inline-widget
     * detection just loses one of its three signals).
     *
     * Mirrors the conversion logic in `UiAgent.runTask`; we intentionally
     * keep both copies tight rather than moving the mapping into `UiExecutor`
     * because `ScopedSnapshotBuilder.EditorCode` belongs to the perception
     * layer and `EditorCodeContext` belongs to the executor.
     */
    /**
     * Pull the live editor window for inline-widget detection and source
     * verification, swallowing failures so callers can keep running with
     * `null` as "no editor context available".
     *
     * When [uiTreeHint] is provided and already contains a visible dialog,
     * we skip the JS round-trip entirely. `getEditorContext` dispatches
     * onto IntelliJ's EDT via `invokeAndWait`; modal dialogs enter a
     * nested event loop on the EDT that, on macOS, doesn't reliably pump
     * non-EDT `invokeAndWait` requests. The call times out client-side
     * but the task remains stuck in Remote Robot's dispatcher — which
     * serializes component-API requests, so a single stuck `callJs`
     * wedges the entire `/api/tree` + `/api/component/...` queue
     * **indefinitely**. Once poisoned, the Robot server is unresponsive
     * even minutes later, which matches exactly what we observed after
     * Change Signature / Find Usages clicks. Checking the tree first is
     * cheap and eliminates the wedge.
     *
     * Editor code is in any case irrelevant while a modal dialog is on
     * top — the LLM should be reading the dialog, not the source
     * behind it — so this guard costs us nothing.
     */
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
