package execution

import agent.AgentConfig.contextMenuDelayMs
import agent.AgentConfig.elementWaitTimeoutMs
import agent.AgentConfig.maxMenuItemsPreview
import agent.AgentConfig.maxSnapshotComponents
import agent.AgentConfig.menuClickDelayMs
import agent.AgentConfig.retryDelayMs
import agent.AgentConfig.typingCharDelayMs
import agent.AgentConfig.uiAnalysisTimeoutMs
import com.intellij.openapi.diagnostic.logger
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import model.AgentAction
import perception.tree.ScopedSnapshotBuilder
import perception.tree.UiComponent
import perception.tree.UiTreeParser
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
) {
    private val log = logger<ActionGenerator>()

    companion object {
        /**
         * Prompt for LLM to analyze UI state after clicking a menu item.
         * This helps distinguish between submenus, tool dialogs, and dismissed menus.
         */
        private const val UI_STATE_ANALYSIS_PROMPT = """You are analyzing UI state after clicking a menu item in a desktop application.

Menu item clicked: {{TOOL_NAME}}
User's goal: {{GOAL}}

Current UI State:
- Popup windows: {{POPUP_COUNT}}
- Dialog detected: {{HAS_DIALOG}}
- Inline editor detected: {{HAS_INLINE}}
- List/chooser detected: {{HAS_POPUP}}

UI Snapshot (menu items visible):
{{UI_SNAPSHOT}}

## Your Task
Determine what happened after clicking the menu item. Classify into ONE of these categories:

1. **SUBMENU** - The clicked item opened a submenu with more options
   - Multiple popup windows exist
   - Menu items are visible (e.g., "Extract Function...", "Extract Variable...")
   - NO inline editor or dialog appeared

2. **TOOL_TRIGGERED** - The actual refactoring tool was triggered
   - An inline editor appeared (for typing function name, etc.)
   - A dialog appeared with fields/buttons
   - A popup chooser appeared for selecting options

3. **DISMISSED** - The menu closed without triggering anything
   - No popups, no dialogs, no inline editors
   - The click may have failed or the action was cancelled

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
    data class ExecutionResult(
        val success: Boolean,
        val message: String,
        val data: Map<String, Any> = emptyMap(),
    )

    /**
     * Execute an action.
     *
     * @param action The action to execute
     * @param currentUiTree The current UI tree (raw, no intermediate transformation)
     * @return The result of the action
     */
    fun execute(
        action: AgentAction,
        currentUiTree: List<UiComponent>,
    ): ExecutionResult {
        log.debug("  ActionGenerator: Executing ${action::class.simpleName}")

        return when (action) {
            // Navigation actions
            is AgentAction.OpenFile -> executeOpenFile(action)
            is AgentAction.MoveCaret -> executeMoveCaret(action)
            is AgentAction.SelectLines -> executeSelectLines(action)
            // UI interaction actions
            is AgentAction.Click -> executeClick(action, currentUiTree)
            is AgentAction.Type -> executeType(action, currentUiTree)
            is AgentAction.PressKey -> executePressKey(action)
            is AgentAction.SelectDropdown -> executeSelectDropdown(action, currentUiTree)
            is AgentAction.Wait -> executeWait(action, currentUiTree)
            is AgentAction.Observe -> ExecutionResult(true, "Observed UI state")
            is AgentAction.Complete -> ExecutionResult(true, "Task completed")
            is AgentAction.Fail -> ExecutionResult(false, "Task failed")
        }
    }

    // ── Navigation Actions ─────────────────────────────────────────────────────

    /**
     * Execute an OpenFile action.
     */
    private fun executeOpenFile(action: AgentAction.OpenFile): ExecutionResult {
        return try {
            executor.openFile(action.path)
            Thread.sleep(contextMenuDelayMs)
            ExecutionResult(true, "Opened file '${action.path}'")
        } catch (e: Exception) {
            ExecutionResult(false, "Failed to open file: ${e.message}")
        }
    }

    /**
     * Execute a MoveCaret action.
     */
    private fun executeMoveCaret(action: AgentAction.MoveCaret): ExecutionResult {
        return try {
            executor.moveCaret(action.symbol)
            Thread.sleep(retryDelayMs)
            ExecutionResult(true, "Moved caret to '${action.symbol}'")
        } catch (e: Exception) {
            ExecutionResult(false, "Failed to move caret: ${e.message}")
        }
    }

    /**
     * Execute a SelectLines action.
     */
    private fun executeSelectLines(action: AgentAction.SelectLines): ExecutionResult {
        return try {
            executor.selectLines(action.start, action.end)
            Thread.sleep(retryDelayMs)
            ExecutionResult(true, "Selected lines ${action.start}-${action.end}")
        } catch (e: Exception) {
            ExecutionResult(false, "Failed to select lines: ${e.message}")
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
    ): ExecutionResult {
        val target = action.target

        return try {
            // Try to click as a menu item first, then as a dialog button
            try {
                // Get pre-click popup count for comparison
                val preClickUiTree = uiTreeProvider()
                val preClickPopupCount = ScopedSnapshotBuilder.popupCount(preClickUiTree)

                executor.clickMenuItem(target)
                // Wait for menu action to take effect
                Thread.sleep(menuClickDelayMs)

                // ALWAYS wait for UI changes and classify - no keyword-based shortcuts
                val waitResult = waitForUIElement(timeoutMs = uiAnalysisTimeoutMs)

                // Get fresh UI tree for LLM analysis
                val postClickUiTree = uiTreeProvider()

                // ALWAYS use LLM-based state analysis to classify what happened
                val analysis =
                    analyzeToolResponse(
                        toolName = target,
                        uiTree = postClickUiTree,
                        goal = "", // Goal would be passed from context
                        preClickPopupCount = preClickPopupCount,
                    )

                log.info("    LLM State Analysis: ${analysis.stateType} - ${analysis.reasoning.take(80)}")

                // Return result with analysis data
                ExecutionResult(
                    success = true,
                    message = "Clicked menu item '$target' - ${analysis.description}",
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
            } catch (e: Exception) {
                // Try as dialog button
                try {
                    executor.clickDialogButton(target)
                    Thread.sleep(contextMenuDelayMs / 2)
                    ExecutionResult(true, "Clicked button '$target'")
                } catch (e2: Exception) {
                    ExecutionResult(false, "Could not click '$target': ${e2.message}")
                }
            }
        } catch (e: Exception) {
            ExecutionResult(false, "Failed to click '$target': ${e.message}")
        }
    }

    /**
     * Wait for a UI element (dialog, popup, or inline widget) to appear.
     * Uses flattened component search and retry polling for reliability.
     * @return true if an element was detected, false otherwise
     */
    private fun waitForUIElement(timeoutMs: Long = elementWaitTimeoutMs): Boolean {
        val startTime = System.currentTimeMillis()

        log.debug("    Auto-waiting for UI element...")

        var attempt = 0
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            attempt++
            val freshUiTree = uiTreeProvider()

            // Use flattened search to find dialogs/popups at any depth
            val allComponents = UiTreeParser.flatten(freshUiTree)

            // Check for dialogs using profile
            val hasDialog = allComponents.any { profile.isDialog(it.cls) }
            val hasPopup = allComponents.any { profile.isPopupWindow(it.cls) }
            val hasTextInput = allComponents.any { profile.isTextInput(it.cls) && it.enabled }

            // Check for inline widget (popup with text input)
            val hasInlineWidget =
                allComponents.any { component ->
                    profile.isPopupWindow(component.cls) &&
                        component.children.any { profile.isTextInput(it.cls) }
                }

            // LookupLayeredPane = IntelliJ's inline-refactoring completion popup
            val hasLookupPane = allComponents.any { "LookupLayeredPane" in it.cls }

            if (hasDialog || hasPopup || hasTextInput || hasInlineWidget || hasLookupPane) {
                val detectedType =
                    when {
                        hasDialog -> "dialog"
                        hasInlineWidget -> "inline widget"
                        hasPopup -> "popup"
                        hasLookupPane -> "inline refactoring lookup"
                        else -> "text input"
                    }
                log.debug("    ✓ UI element detected: $detectedType (attempt $attempt)")
                return true
            }

            Thread.sleep(retryDelayMs)
        }

        log.debug("    No dialog/widget detected after ${timeoutMs}ms ($attempt attempts)")
        return false
    }

    /**
     * Execute a type action.
     * If target is specified, uses focusField to focus the field before typing.
     * Validates that an input field or inline rename widget exists before typing.
     */
    private fun executeType(
        action: AgentAction.Type,
        uiTree: List<UiComponent>,
    ): ExecutionResult {
        // Check if there's an input field, an inline rename template, or a popup widget
        val allComponents = UiTreeParser.flatten(uiTreeProvider())
        val hasInputField = allComponents.any { profile.isTextInput(it.cls) }
        val hasPopup = allComponents.any { profile.isPopupWindow(it.cls) }
        val hasInlineRefactoring = hasPopup && hasInputField
        // Shift+F6/Cmd+F6 opens an in-editor live template — not a separate popup — so the
        // accessibility tree shows no new input field.  Ask IntelliJ directly.
        val hasInlineTemplate = executor.hasInlineRefactoringActive()
        // LookupLayeredPane appears when IntelliJ's inline refactoring is active with suggestions
        val hasLookupPane = allComponents.any { "LookupLayeredPane" in it.cls }

        if (!hasInlineRefactoring && !hasInputField && !hasInlineTemplate && !hasLookupPane && action.target == null) {
            log.warn("    WARNING: No input field detected - cannot type safely")
            return ExecutionResult(false, "No input field available for typing - refactoring dialog may not be open")
        }

        return try {
            // If target is specified, focus the field first
            if (action.target != null) {
                log.debug("    Focusing field '${action.target}' before typing")
                try {
                    executor.focusField(action.target)
                    Thread.sleep(retryDelayMs / 3)
                } catch (e: Exception) {
                    log.debug("    Could not focus field '${action.target}': ${e.message}")
                }
            }

            // Skip clearFirst when the inline refactoring template is active.
            val isInlineRefactoringActive = hasInlineTemplate || hasLookupPane
            if (action.clearFirst && !isInlineRefactoringActive) {
                executor.pressShortcut("Ctrl+A")
                Thread.sleep(retryDelayMs / 3)
            }
            executor.typeInDialog(action.text)
            Thread.sleep(action.text.length * typingCharDelayMs)
            ExecutionResult(true, "Typed '${action.text}' in '${action.target ?: "current field"}' (clearFirst=${action.clearFirst})")
        } catch (e: Exception) {
            ExecutionResult(false, "Failed to type: ${e.message}")
        }
    }

    private fun executePressKey(action: AgentAction.PressKey): ExecutionResult {
        return try {
            val key = action.key.lowercase()

            when {
                // Special case: open context menu
                key == "context_menu" -> {
                    executor.openContextMenu()
                    Thread.sleep(contextMenuDelayMs)
                    ExecutionResult(true, "Opened context menu")
                }
                // Shortcut (contains +)
                key.contains("+") -> {
                    executor.pressShortcut(action.key)
                    if (key == "shift+f6") {
                        Thread.sleep(contextMenuDelayMs)
                        val templateActive = executor.hasInlineRefactoringActive()
                        val msg =
                            if (templateActive) {
                                "Pressed 'Shift+F6' - inline refactoring template is active, type the new name now"
                            } else {
                                "Pressed 'Shift+F6' - refactoring template not detected, consider context-menu approach"
                            }
                        ExecutionResult(true, msg)
                    } else {
                        Thread.sleep(retryDelayMs)
                        ExecutionResult(true, "Pressed shortcut '${action.key}'")
                    }
                }
                // Single key
                else -> {
                    executor.pressKey(action.key)
                    Thread.sleep(retryDelayMs)
                    ExecutionResult(true, "Pressed key '${action.key}'")
                }
            }
        } catch (e: Exception) {
            ExecutionResult(false, "Failed to press key: ${e.message}")
        }
    }

    /**
     * Execute a select dropdown action.
     */
    private fun executeSelectDropdown(
        action: AgentAction.SelectDropdown,
        uiTree: List<UiComponent>,
    ): ExecutionResult {
        return try {
            executor.selectDropdownField(action.target, action.value)
            Thread.sleep(retryDelayMs)
            ExecutionResult(true, "Selected '${action.value}' from '${action.target}'")
        } catch (e: Exception) {
            ExecutionResult(false, "Failed to select dropdown value: ${e.message}")
        }
    }

    /**
     * Execute a wait action.
     */
    private fun executeWait(
        action: AgentAction.Wait,
        uiTree: List<UiComponent>,
    ): ExecutionResult {
        val startTime = System.currentTimeMillis()
        val elementType = action.elementType.lowercase()

        log.debug("    Waiting for element '$elementType' (timeout: ${action.timeoutMs}ms)")

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
                log.debug("    ✓ Found element '$foundType'")
                return ExecutionResult(true, "Element '$elementType' appeared")
            }

            Thread.sleep(retryDelayMs)
        }

        log.debug("    ✗ Element '$elementType' not found after ${action.timeoutMs}ms")
        return ExecutionResult(false, "Element '$elementType' did not appear within ${action.timeoutMs}ms")
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
        val allComponents = if (uiTree != null) UiTreeParser.flatten(uiTree) else emptyList()

        // Programmatic detection first
        val hasDialog = allComponents.any { profile.isDialog(it.cls) }
        // LookupLayeredPane = IntelliJ's inline-rename popup; treat it as an inline editor
        val hasInline =
            allComponents.any {
                profile.isEditor(it.cls) && allComponents.any { profile.isPopupWindow(it.cls) }
            } || allComponents.any { "LookupLayeredPane" in it.cls }
        val hasPopupList =
            allComponents.any {
                profile.isList(it.cls) || profile.isTable(it.cls) || profile.isTree(it.cls)
            }
        val popupCount = ScopedSnapshotBuilder.popupCount(uiTree ?: emptyList())

        // Get menu items for context
        val allMenuItems = ScopedSnapshotBuilder.forAllPopupsStructured(uiTree ?: emptyList())
        val menuItemLabels = allMenuItems.map { it.label }.take(maxMenuItemsPreview)

        // Submenu guard: if new popups appeared AND menu items are visible,
        // an existing inline editor behind the menu should not cause a false tool_triggered.
        val newPopupsAppeared = popupCount > preClickPopupCount
        val hasMenuItems = allMenuItems.isNotEmpty()
        val inlineIsProbablyPreExisting = hasInline && newPopupsAppeared && hasMenuItems

        log.debug(
            "    UI State: $popupCount popups (was $preClickPopupCount), hasDialog=$hasDialog, hasInline=$hasInline" +
                " (pre-existing=$inlineIsProbablyPreExisting), hasPopup=$hasPopupList, menuItems=${allMenuItems.size}",
        )

        // If no LLM available, use fallback analysis
        if (llm == null) {
            log.debug("    No LLM available, using fallback analysis")
            return fallbackAnalysis(hasDialog, hasInline, hasPopupList, menuItemLabels)
        }

        // LLM-First approach: Ask LLM to classify the UI state
        val uiSnapshot = formatUiTreeForAnalysis(uiTree)

        val prompt =
            UI_STATE_ANALYSIS_PROMPT
                .replace("{{TOOL_NAME}}", toolName)
                .replace("{{GOAL}}", goal)
                .replace("{{POPUP_COUNT}}", popupCount.toString())
                .replace("{{HAS_DIALOG}}", hasDialog.toString())
                .replace("{{HAS_INLINE}}", hasInline.toString())
                .replace("{{HAS_POPUP}}", hasPopupList.toString())
                .replace("{{UI_SNAPSHOT}}", uiSnapshot)

        return try {
            val response =
                llm.chat(
                    SystemMessage.from("You are a UI state analysis agent for IDE automation."),
                    UserMessage.from(prompt),
                )
            val responseText = response.aiMessage().text()

            // Parse LLM response
            val stateType = extractJsonString(responseText, "state_type") ?: "unknown"
            val reasoning = extractJsonString(responseText, "reasoning") ?: ""
            val availableItems = extractJsonArray(responseText, "available_items")
            val toolResponseType = extractJsonString(responseText, "tool_response_type") ?: "none"
            val confirmAction = extractJsonString(responseText, "confirm_action") ?: ""
            val dialogFields = extractJsonArray(responseText, "dialog_fields")
            val description = extractJsonString(responseText, "description") ?: ""

            log.debug("    LLM Analysis: stateType=$stateType, reasoning=${reasoning.take(80)}...")

            when (stateType) {
                "submenu" -> {
                    log.info("    → LLM classified as SUBMENU with ${availableItems.size} items")
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
                        log.info("    → LLM said TOOL_TRIGGERED(inline) but inline editor is pre-existing; reclassifying as SUBMENU")
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
                        log.info("    → LLM classified as TOOL_TRIGGERED (type: $toolResponseType)")
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
                    log.info("    → LLM classified as DISMISSED (menu closed)")
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
                    log.debug("    → LLM returned unknown state type, using fallback")
                    fallbackAnalysis(hasDialog, hasInline, hasPopupList, menuItemLabels)
                }
            }
        } catch (e: Exception) {
            log.warn("    LLM analysis failed: ${e.message}, using fallback")
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
     * Format UI tree for LLM analysis prompt.
     */
    private fun formatUiTreeForAnalysis(uiTree: List<UiComponent>?): String {
        if (uiTree == null || uiTree.isEmpty()) return "(empty)"

        val sb = StringBuilder()
        val allComponents = UiTreeParser.flatten(uiTree).take(maxSnapshotComponents) // Limit for token efficiency

        for (component in allComponents) {
            val label = component.label.ifBlank { component.accessibleName.ifBlank { component.text } }
            if (label.isNotBlank()) {
                sb.append("- ${component.cls.take(30)}: '$label'\n")
            }
        }

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
}
