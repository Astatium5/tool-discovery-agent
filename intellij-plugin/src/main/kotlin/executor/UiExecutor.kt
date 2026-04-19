package executor

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import model.RecipeStep
import parser.HtmlUiTreeProvider
import parser.UiTreeProvider
import parser.UiComponent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.time.Duration
import java.awt.Desktop
import java.io.File

/**
 * Translates RecipeStep sequences into actual RemoteRobot interactions.
 * Extracted from the hardcoded patterns in RenameRefactorTest.
 */
class UiExecutor(
    private val robot: RemoteRobot,
    private val treeProvider: UiTreeProvider = HtmlUiTreeProvider()
) {
    private val defaultTimeout = Duration.ofSeconds(10)
    
    // Track the currently open file for context discovery
    var currentFilePath: String? = null

    init {
        // Ensure IntelliJ has focus on startup
        bringTestIdeToFront()
    }

    /**
     * Bring the test IDE (QuestDB) to the front on macOS using AppleScript.
     * The test IDE runs as process "java" with window title "questdb".
     * This is needed because java.awt.Robot keyboard events go to the focused application.
     */
    fun bringTestIdeToFront() {
        try {
            // Check if running on macOS
            val osName = System.getProperty("os.name").lowercase()
            if (!osName.contains("mac")) {
                return // Not macOS, skip
            }

            // Use AppleScript to bring the java process (QuestDB test IDE) to front
            val script = """
                tell application "System Events"
                    set frontmost of the first process whose name is "java" to true
                end tell
                tell application "System Events" to tell process "java"
                    perform action "AXRaise" of window 1
                end tell
            """.trimIndent()

            val processBuilder = ProcessBuilder("osascript", "-e", script)
            processBuilder.start().waitFor()
            Thread.sleep(500) // Wait for window to come to front
            println("  [FOCUS] Brought QuestDB test IDE to front using AppleScript")
        } catch (e: Exception) {
            println("  [WARNING] Could not bring test IDE to front: ${e.message}")
        }
    }

    /**
     * Bring IntelliJ IDEA to the front on macOS using AppleScript.
     * This is a generic method for any IntelliJ window.
     */
    private fun bringIntelliJToFront() {
        try {
            // Check if running on macOS
            val osName = System.getProperty("os.name").lowercase()
            if (!osName.contains("mac")) {
                return // Not macOS, skip
            }

            // Use AppleScript to bring IntelliJ to front
            val script = """
                tell application "System Events"
                    set frontmost of the first process whose name contains "IntelliJ" to true
                end tell
            """.trimIndent()

            val processBuilder = ProcessBuilder("osascript", "-e", script)
            processBuilder.start().waitFor()
            Thread.sleep(500) // Wait for window to come to front
            println("  [FOCUS] Brought IntelliJ to front using AppleScript")
        } catch (e: Exception) {
            println("  [WARNING] Could not bring IntelliJ to front: ${e.message}")
        }
    }

    data class ScreenCoords(val x: Int, val y: Int)

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Execute a full recipe step by step. Returns true if all steps succeeded.
     */
    fun executeRecipe(steps: List<RecipeStep>): Boolean {
        for ((i, step) in steps.withIndex()) {
            println("  [Step ${i + 1}/${steps.size}] ${RecipeStep.describe(step)}")
            try {
                executeStep(step)
                Thread.sleep(300)
            } catch (e: Exception) {
                println("  FAILED at step ${i + 1}: ${e.message}")
                dismissPopups()
                return false
            }
        }
        return true
    }

    fun executeStep(step: RecipeStep) {
        when (step) {
            is RecipeStep.OpenFile          -> openFile(step.path)
            is RecipeStep.FocusEditor       -> focusEditor()
            is RecipeStep.MoveCaret         -> moveCaret(step.toSymbol)
            is RecipeStep.SelectLines       -> selectLines(step.from, step.to)
            is RecipeStep.SelectLinesPlaceholder -> {
                val from = step.from.toIntOrNull() ?: throw IllegalArgumentException(
                    "SelectLinesPlaceholder.from='${step.from}' was not resolved to an integer before execution"
                )
                val to = step.to.toIntOrNull() ?: throw IllegalArgumentException(
                    "SelectLinesPlaceholder.to='${step.to}' was not resolved to an integer before execution"
                )
                selectLines(from, to)
            }
            is RecipeStep.PressShortcut     -> pressShortcut(step.keys)
            is RecipeStep.OpenContextMenu   -> openContextMenu()
            is RecipeStep.ClickMenu         -> clickMenuItem(step.label)
            is RecipeStep.TypeInDialog      -> typeInDialog(step.value)
            is RecipeStep.SelectDropdown    -> selectDropdown(step.value)
            is RecipeStep.ClickDialogButton -> clickDialogButton(step.label)
            is RecipeStep.PressKey          -> pressKey(step.key)
            is RecipeStep.CancelDialog      -> pressEscape()
            // Field Navigation steps (new in declarative model)
            is RecipeStep.FocusField        -> focusField(step.fieldLabel)
            is RecipeStep.SelectDropdownField -> selectDropdownField(step.fieldLabel, step.value)
            is RecipeStep.SetCheckbox       -> setCheckbox(step.fieldLabel, step.checked)
            is RecipeStep.TableRowAction    -> tableRowAction(step.action, step.rowIndex)
        }
    }

    // ── Step implementations ─────────────────────────────────────────────────

    fun focusEditor() {
        val editors = robot.findAll<ComponentFixture>(
            byXpath("//div[@class='EditorComponentImpl']")
        )
        val editor = editors.firstOrNull { comp ->
            try { comp.callJs<Boolean>("component.hasFocus()") } catch (_: Exception) { false }
        } ?: editors.firstOrNull()
            ?: throw IllegalStateException("No editor component found")
        editor.click()
        Thread.sleep(300)
    }

    /**
     * Click a component by its target (label, accessible name, etc.).
     * Uses multiple strategies to find and click components.
     */
    fun clickComponent(target: String) {
        println("  [EXECUTE] Clicking component: $target")

        // Try different strategies to find and click the component
        val strategies = listOf<Pair<String, () -> ComponentFixture>>(
            "accessible name exact" to {
                robot.find<ComponentFixture>(
                    byXpath("//div[@accessiblename='$target']"),
                    Duration.ofSeconds(3)
                )
            },
            "accessible name contains" to {
                robot.find<ComponentFixture>(
                    byXpath("//div[contains(@accessiblename, '$target')]"),
                    Duration.ofSeconds(3)
                )
            },
            "visible text" to {
                robot.find<ComponentFixture>(
                    byXpath("//div[contains(@visible_text, '$target')]"),
                    Duration.ofSeconds(3)
                )
            },
            "tooltip text" to {
                robot.find<ComponentFixture>(
                    byXpath("//div[contains(@tooltiptext, '$target')]"),
                    Duration.ofSeconds(3)
                )
            }
        )

        for ((name, finder) in strategies) {
            try {
                val component = finder()
                println("  [SUCCESS] Found by $name: $target")
                component.click()
                Thread.sleep(300)
                return
            } catch (e: Exception) {
                println("  [TRY] $name failed: ${e.message}")
            }
        }

        throw IllegalArgumentException("Could not find component with target: $target")
    }

    fun moveCaret(symbol: String) {
        val editor = findFocusedEditor()

        val offsetResult = editor.callJs<String>("""
            var doc = component.getDocument();
            var fullText = doc.getText(0, doc.getLength());
            var idx = fullText.indexOf('$symbol');
            if (idx < 0) {
                'NOT_FOUND';
            } else {
                com.intellij.openapi.application.ApplicationManager
                    .getApplication().invokeAndWait(new Runnable() {
                        run: function() {
                            var project = com.intellij.openapi.project.ProjectManager
                                .getInstance().getOpenProjects()[0];
                            var editorEx = com.intellij.openapi.fileEditor.FileEditorManager
                                .getInstance(project).getSelectedTextEditor();
                            if (editorEx != null) {
                                var targetOffset = idx + ${symbol.length / 2};
                                editorEx.getCaretModel().moveToOffset(targetOffset);
                                editorEx.getScrollingModel().scrollToCaret(
                                    com.intellij.openapi.editor.ScrollType.CENTER
                                );
                            }
                        }
                    });
                '' + idx;
            }
        """.trimIndent())

        if (offsetResult == "NOT_FOUND") {
            throw IllegalStateException("Symbol '$symbol' not found in editor")
        }
        Thread.sleep(500)
    }

    fun selectLines(from: Int, to: Int) {
        val editor = findFocusedEditor()

        editor.callJs<String>("""
            com.intellij.openapi.application.ApplicationManager
                .getApplication().invokeAndWait(new Runnable() {
                    run: function() {
                        var project = com.intellij.openapi.project.ProjectManager
                            .getInstance().getOpenProjects()[0];
                        var editorEx = com.intellij.openapi.fileEditor.FileEditorManager
                            .getInstance(project).getSelectedTextEditor();
                        if (editorEx != null) {
                            var doc = editorEx.getDocument();
                            var startOffset = doc.getLineStartOffset($from - 1);
                            var endOffset = doc.getLineEndOffset($to - 1);
                            editorEx.getSelectionModel().setSelection(startOffset, endOffset);
                            editorEx.getCaretModel().moveToOffset(endOffset);
                            editorEx.getScrollingModel().scrollToCaret(
                                com.intellij.openapi.editor.ScrollType.CENTER
                            );
                        }
                    }
                });
            'OK';
        """.trimIndent())
        Thread.sleep(300)
    }

    fun pressShortcut(keys: String) {
        // On macOS, use AppleScript to send shortcuts to IntelliJ specifically
        val osName = System.getProperty("os.name").lowercase()
        if (osName.contains("mac")) {
            val parts = keys.split(" ").map { it.lowercase() }
            val keyName = parts.last()
            val modifiers = parts.dropLast(1).map { mod ->
                when (mod) {
                    "cmd", "meta" -> "command down"
                    "shift" -> "shift down"
                    "ctrl", "control" -> "control down"
                    "alt", "option" -> "option down"
                    else -> ""
                }
            }.filter { it.isNotEmpty() }

            sendKeystrokeToIntelliJ(keyName, modifiers)
        } else {
            // Fallback to java.awt.Robot for other platforms
            val keyCodes = parseShortcutKeys(keys)
            val awtRobot = java.awt.Robot()

            val modifiers = keyCodes.dropLast(1)
            val mainKey = keyCodes.last()

            modifiers.forEach { awtRobot.keyPress(it) }
            Thread.sleep(50)
            awtRobot.keyPress(mainKey)
            Thread.sleep(50)
            awtRobot.keyRelease(mainKey)
            modifiers.reversed().forEach { awtRobot.keyRelease(it) }
        }
        Thread.sleep(500)
    }

    fun openContextMenu() {
        val editor = findFocusedEditor()
        val coords = getCaretScreenCoords(editor)
        rightClickAt(coords.x, coords.y)
        Thread.sleep(800)
    }

    fun clickMenuItem(label: String) {
        val item = findMenuItem(label) ?: openRefactorSubmenuAndFind(label)
            ?: throw IllegalStateException("Could not find menu item '$label' in the active popup flow")
        val coords = getComponentScreenCenter(item)
        clickAt(coords.x, coords.y)
        Thread.sleep(600)
    }

    private fun findMenuItem(label: String): ComponentFixture? =
        try {
            robot.find<ComponentFixture>(
                byXpath("(//div[@class='HeavyWeightWindow'])[last()]//div[@class='ActionMenuItem' and contains(@text, '$label')]"),
                Duration.ofSeconds(3)
            )
        } catch (_: Exception) {
            try {
                robot.find<ComponentFixture>(
                    byXpath("(//div[@class='HeavyWeightWindow'])[last()]//div[@class='ActionMenu' and contains(@text, '$label')]"),
                    Duration.ofSeconds(2)
                )
            } catch (_: Exception) {
                try {
                    robot.find<ComponentFixture>(
                        byXpath("//div[contains(@accessiblename, '$label') and (@class='ActionMenuItem' or @class='ActionMenu')]"),
                        Duration.ofSeconds(2)
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }

    private fun openRefactorSubmenuAndFind(label: String): ComponentFixture? {
        if (label.equals("Refactor", ignoreCase = true)) {
            return null
        }

        val refactorMenu = findMenuItem("Refactor") ?: return null
        val coords = getComponentScreenCenter(refactorMenu)
        clickAt(coords.x, coords.y)
        Thread.sleep(800)
        return findMenuItem(label)
    }

    fun typeInDialog(value: String) {
        // Brief wait for popup/inline widget to render after a menu click
        Thread.sleep(500)

        val (field, isInlineEditor) = findInputField()

        if (!isInlineEditor) {
            // For popup/dialog fields, click to focus and select all existing text
            field.click()
            Thread.sleep(100)
            robot.keyboard {
                pressing(KeyEvent.VK_META) { key(KeyEvent.VK_A) }
            }
            Thread.sleep(200)
        }
        // For inline rename/extract templates the cursor is already inside the
        // highlighted template field with the old name selected — just type.

        robot.keyboard { enterText(value) }
        Thread.sleep(300)
    }

    /**
     * Locate the text input field for typing.
     * Returns the field and a flag indicating whether it's the inline code editor
     * (true = inline rename/extract template field, false = popup/dialog field).
     */
    private fun findInputField(): Pair<ComponentFixture, Boolean> {
        val container = "(//div[@class='HeavyWeightWindow' or @class='DialogRootPane'])[last()]"
        val timeout = Duration.ofSeconds(3)

        // Strategy 1: EditorComponentImpl inside popup/dialog
        try {
            return robot.find<ComponentFixture>(byXpath("$container//div[@class='EditorComponentImpl']"), timeout) to false
        } catch (_: Exception) { }

        // Strategy 2: JTextField/JBTextField inside popup/dialog
        try {
            return robot.find<ComponentFixture>(byXpath("$container//div[@class='JTextField' or @class='JBTextField']"), timeout) to false
        } catch (_: Exception) { }

        // Strategy 3: EditorComponentImpl inside a JDialog
        try {
            return robot.find<ComponentFixture>(byXpath("//div[@class='JDialog']//div[@class='EditorComponentImpl']"), timeout) to false
        } catch (_: Exception) { }

        // Strategy 4: focused editor — for inline rename/extract, the input field
        // IS the code editor itself (IntelliJ puts a template field in-place).
        return findFocusedEditor() to true
    }

    fun selectDropdown(value: String) {
        // Find dropdown dynamically - look for combobox-like components
        // Use contains() to match any class with "combo", "dropdown", or "select" in the name
        val dropdown = try {
            robot.find<ComponentFixture>(
                byXpath("(//div[@class='HeavyWeightWindow' or @class='DialogRootPane'])[last()]//div[contains(@class, 'Combo') or contains(@class, 'combo') or contains(@class, 'Dropdown') or contains(@class, 'dropdown') or contains(@class, 'Select') or contains(@class, 'select')]"),
                Duration.ofSeconds(5)
            )
        } catch (_: Exception) {
            println("  No dropdown found, skipping selection")
            return
        }
        
        // Click to open dropdown
        dropdown.click()
        Thread.sleep(300)
        
        // Try to find and click the option with matching text
        try {
            val option = robot.find<ComponentFixture>(
                byXpath("//div[@visible_text='$value' or @accessiblename='$value']"),
                Duration.ofSeconds(2)
            )
            option.click()
        } catch (_: Exception) {
            // If exact match not found, try typing the value
            robot.keyboard { enterText(value) }
            Thread.sleep(200)
            robot.keyboard { key(KeyEvent.VK_ENTER) }
        }
        Thread.sleep(300)
    }

    fun clickDialogButton(label: String) {
        val button = try {
            robot.find<ComponentFixture>(
                byXpath("//div[@accessiblename='$label' and @class='JButton']"),
                Duration.ofSeconds(5)
            )
        } catch (_: Exception) {
            robot.find<ComponentFixture>(
                byXpath("//div[@accessiblename='$label' and @class!='ActionMenu' and @class!='ActionMenuItem']"),
                Duration.ofSeconds(5)
            )
        }

        val coords = getComponentScreenCenter(button)
        clickAt(coords.x, coords.y)
        Thread.sleep(800)
    }

    fun pressEscape() {
        // On macOS, use AppleScript to send Escape to IntelliJ specifically
        val osName = System.getProperty("os.name").lowercase()
        if (osName.contains("mac")) {
            sendKeystrokeToIntelliJ("escape")
        } else {
            // Fallback to java.awt.Robot for other platforms
            val awtRobot = java.awt.Robot()
            awtRobot.keyPress(KeyEvent.VK_ESCAPE)
            Thread.sleep(50)
            awtRobot.keyRelease(KeyEvent.VK_ESCAPE)
        }
        Thread.sleep(300)
    }

    /**
     * Presses a specific key by name. Supports: Enter, Escape, Tab, Backspace, Delete, etc.
     * Uses AppleScript on macOS to send keystrokes specifically to IntelliJ.
     */
    fun pressKey(keyName: String) {
        // Map key name to AppleScript key name format
        val appleScriptKeyName = when (keyName.lowercase()) {
            "enter", "return" -> "return"
            "escape", "esc" -> "escape"
            "tab" -> "tab"
            "backspace" -> "delete"
            "delete" -> "forward delete"
            "up" -> "up arrow"
            "down" -> "down arrow"
            "left" -> "left arrow"
            "right" -> "right arrow"
            "f6" -> "f6"
            "f5" -> "f5"
            else -> keyName.lowercase()
        }

        val keyCode = when (keyName.lowercase()) {
            "enter", "return" -> KeyEvent.VK_ENTER
            "escape", "esc" -> KeyEvent.VK_ESCAPE
            "tab" -> KeyEvent.VK_TAB
            "backspace" -> KeyEvent.VK_BACK_SPACE
            "delete" -> KeyEvent.VK_DELETE
            "space" -> KeyEvent.VK_SPACE
            "up" -> KeyEvent.VK_UP
            "down" -> KeyEvent.VK_DOWN
            "left" -> KeyEvent.VK_LEFT
            "right" -> KeyEvent.VK_RIGHT
            "f6" -> KeyEvent.VK_F6
            "f5" -> KeyEvent.VK_F5
            else -> KeyEvent.VK_ENTER
        }

        // On macOS, use AppleScript to send keystrokes to IntelliJ specifically
        val osName = System.getProperty("os.name").lowercase()
        if (osName.contains("mac")) {
            sendKeystrokeToIntelliJ(appleScriptKeyName)
        } else {
            // Fallback to java.awt.Robot for other platforms
            val awtRobot = java.awt.Robot()
            awtRobot.keyPress(keyCode)
            Thread.sleep(50)
            awtRobot.keyRelease(keyCode)
        }
        Thread.sleep(300)
    }

    /**
     * Map Java KeyEvent to AppleScript key code.
     * Returns null if the key should use keystroke instead of key code.
     */
    private fun mapKeyCodeToAppleScriptCode(keyCode: Int): Int? {
        return when (keyCode) {
            KeyEvent.VK_ESCAPE -> 53
            KeyEvent.VK_ENTER -> 36
            KeyEvent.VK_TAB -> 48
            KeyEvent.VK_BACK_SPACE -> 51
            KeyEvent.VK_DELETE -> 117
            KeyEvent.VK_UP -> 126
            KeyEvent.VK_DOWN -> 125
            KeyEvent.VK_LEFT -> 123
            KeyEvent.VK_RIGHT -> 124
            KeyEvent.VK_F1 -> 122
            KeyEvent.VK_F2 -> 120
            KeyEvent.VK_F3 -> 99
            KeyEvent.VK_F4 -> 118
            KeyEvent.VK_F5 -> 96
            KeyEvent.VK_F6 -> 97
            KeyEvent.VK_F7 -> 98
            KeyEvent.VK_F8 -> 100
            KeyEvent.VK_F9 -> 101
            KeyEvent.VK_F10 -> 109
            KeyEvent.VK_F11 -> 103
            KeyEvent.VK_F12 -> 111
            else -> null
        }
    }

    /**
     * Send a keystroke to the test IDE (java process/QuestDB) using AppleScript on macOS.
     * This ensures keystrokes go to the test IDE even when running from terminal.
     */
    private fun sendKeystrokeToIntelliJ(keyName: String, modifiers: List<String> = emptyList()) {
        try {
            // Check if this is a special key that needs key code instead of keystroke
            val specialKeysWithCodes = mapOf(
                "escape" to 53,
                "return" to 36,
                "tab" to 48,
                "delete" to 51,
                "forward delete" to 117,
                "up arrow" to 126,
                "down arrow" to 125,
                "left arrow" to 123,
                "right arrow" to 124,
                "f6" to 97,
                "f5" to 96
            )

            val keyCode = specialKeysWithCodes[keyName.lowercase()]

            val script = if (keyCode != null) {
                // Use key code for special keys
                val modifierClause = if (modifiers.isNotEmpty()) {
                    modifiers.joinToString(" ") { mod ->
                        when (mod) {
                            "command down" -> "command down"
                            "shift down" -> "shift down"
                            "control down" -> "control down"
                            "option down" -> "option down"
                            else -> mod
                        }
                    } + " "
                } else {
                    ""
                }
                """
                tell application "System Events"
                    tell process "java"
                        ${modifierClause}key code $keyCode
                    end tell
                end tell
                """.trimIndent()
            } else {
                // Use keystroke for regular keys
                val modifierClause = if (modifiers.isNotEmpty()) {
                    "using {${modifiers.joinToString(", ")}}"
                } else {
                    ""
                }
                """
                tell application "System Events"
                    tell process "java"
                        keystroke "$keyName" $modifierClause
                    end tell
                end tell
                """.trimIndent()
            }

            val processBuilder = ProcessBuilder("osascript", "-e", script)
            processBuilder.start().waitFor()
            val modifierStr = if (modifiers.isNotEmpty()) modifiers.joinToString("+") + "+" else ""
            println("  [KEYBOARD] Sent '$modifierStr$keyName' to QuestDB test IDE via AppleScript")
        } catch (e: Exception) {
            println("  [WARNING] Could not send keystroke via AppleScript: ${e.message}")
            // Fallback to java.awt.Robot
            val awtRobot = java.awt.Robot()
            val keyCode = when (keyName) {
                "return" -> KeyEvent.VK_ENTER
                "escape" -> KeyEvent.VK_ESCAPE
                "tab" -> KeyEvent.VK_TAB
                else -> KeyEvent.VK_ENTER
            }
            awtRobot.keyPress(keyCode)
            Thread.sleep(50)
            awtRobot.keyRelease(keyCode)
        }
    }

    // ── Field Navigation steps (new in declarative model) ─────────────────────

    /**
     * Focus a specific field in a dialog by its label.
     * Uses multiple strategies to find and focus the field.
     * 
     * IMPORTANT: In IntelliJ, clicking on labels (static text) does NOT focus
     * the associated field. We need to find and click the actual input component.
     * 
     * Supports:
     * - Text fields (JTextField, JBTextField, EditorComponentImpl)
     * - Dropdowns/ComboBoxes (JComboBox, ComboBox)
     * - Checkboxes (JCheckBox)
     *
     * @param fieldLabel The label text of the field (e.g., "Name:", "Visibility:")
     */
    fun focusField(fieldLabel: String) {
        val container = "(//div[@class='HeavyWeightWindow' or @class='DialogRootPane'])[last()]"
        val timeout = Duration.ofSeconds(3)
        
        // Normalize the field label (remove trailing colon for matching)
        val normalizedLabel = fieldLabel.trimEnd(':').trim()
        
        // Common input field classes (text fields, dropdowns, checkboxes)
        val inputFieldClasses = "@class='JTextField' or @class='JBTextField' or @class='EditorComponentImpl' or contains(@class, 'TextField') or @class='JComboBox' or contains(@class, 'ComboBox') or @class='JCheckBox'"
        
        // Strategy 1: Find input field by accessible name (most reliable for IntelliJ)
        // IntelliJ sets the accessible name of input fields to include the field label
        try {
            val field = robot.find<ComponentFixture>(
                byXpath("$container//div[($inputFieldClasses) and (contains(@accessiblename, '$normalizedLabel') or contains(@accessiblename, '$fieldLabel'))]"),
                timeout
            )
            field.click()
            Thread.sleep(200)
            println("  Found field by accessible name: $fieldLabel")
            return
        } catch (_: Exception) { }
        
        // Strategy 2: Find label, then find the input field that follows it
        // In IntelliJ, labels and fields are siblings in the layout
        try {
            // Find the label first
            val label = robot.find<ComponentFixture>(
                byXpath("$container//div[@visible_text='$fieldLabel' or contains(@text, '$fieldLabel') or @visible_text='$normalizedLabel' or contains(@text, '$normalizedLabel')]"),
                timeout
            )
            
            // Get the label's bounds to find adjacent input field
            val labelBounds = getComponentBounds(label)
            val labelY = labelBounds.second
            val labelRight = labelBounds.first + labelBounds.third
            
            // Find all input fields in the container (text fields, dropdowns, checkboxes)
            val allFields = robot.findAll<ComponentFixture>(
                byXpath("$container//div[$inputFieldClasses]")
            )
            
            // Find the input field that is closest to the label (same row or next row)
            val closestField = allFields.minByOrNull { field ->
                val fieldBounds = getComponentBounds(field)
                // Calculate distance: prefer fields on the same row, to the right of the label
                val verticalDistance = kotlin.math.abs(fieldBounds.second - labelY)
                val horizontalDistance = if (fieldBounds.first >= labelRight) {
                    fieldBounds.first - labelRight  // Field is to the right
                } else {
                    1000 + (labelRight - fieldBounds.first)  // Field is to the left (penalize)
                }
                verticalDistance * 10 + horizontalDistance  // Weight vertical distance more
            }
            
            if (closestField != null) {
                closestField.click()
                Thread.sleep(200)
                println("  Found field adjacent to label: $fieldLabel")
                return
            }
        } catch (_: Exception) { }
        
        // Strategy 3: Find any input field with the label text in its value or placeholder
        try {
            val field = robot.find<ComponentFixture>(
                byXpath("$container//div[($inputFieldClasses) and (contains(@text, '$normalizedLabel') or contains(@placeholder, '$normalizedLabel'))]"),
                timeout
            )
            field.click()
            Thread.sleep(200)
            println("  Found field by text content: $fieldLabel")
            return
        } catch (_: Exception) { }
        
        // Fallback: Use Tab navigation from current position
        println("  Could not find field '$fieldLabel', using Tab navigation")
        robot.keyboard { key(KeyEvent.VK_TAB) }
        Thread.sleep(200)
    }

    /**
     * Select a value from a specific dropdown field by its label.
     *
     * @param fieldLabel The label text of the dropdown field
     * @param value The value to select
     */
    fun selectDropdownField(fieldLabel: String, value: String) {
        // First focus the field
        focusField(fieldLabel)
        Thread.sleep(100)
        
        // Then select the value using existing dropdown logic
        selectDropdown(value)
    }

    /**
     * Set a checkbox to a specific state.
     *
     * @param fieldLabel The label text of the checkbox
     * @param checked Whether to check or uncheck the checkbox
     */
    fun setCheckbox(fieldLabel: String, checked: Boolean) {
        val container = "(//div[@class='HeavyWeightWindow' or @class='DialogRootPane'])[last()]"
        val timeout = Duration.ofSeconds(3)
        
        // Strategy 1: Find checkbox by label
        try {
            val checkbox = robot.find<ComponentFixture>(
                byXpath("$container//div[@class='JCheckBox' and (@visible_text='$fieldLabel' or contains(@text, '$fieldLabel') or contains(@accessiblename, '$fieldLabel'))]"),
                timeout
            )
            // Check current state and toggle if needed
            val currentState = checkbox.callJs<Boolean>("component.isSelected()")
            if (currentState != checked) {
                checkbox.click()
                Thread.sleep(200)
            }
            return
        } catch (_: Exception) { }
        
        // Strategy 2: Find by accessible name
        try {
            val checkbox = robot.find<ComponentFixture>(
                byXpath("$container//div[@class='JCheckBox' and contains(@accessiblename, '$fieldLabel')]"),
                timeout
            )
            val currentState = checkbox.callJs<Boolean>("component.isSelected()")
            if (currentState != checked) {
                checkbox.click()
                Thread.sleep(200)
            }
            return
        } catch (_: Exception) { }
        
        println("  Could not find checkbox '$fieldLabel'")
    }

    /**
     * Perform an action on a table editor (Add, Remove, Up, Down).
     *
     * @param action The action to perform (e.g., "Add", "Remove", "Up", "Down")
     * @param rowIndex Optional row index for row-specific actions
     */
    fun tableRowAction(action: String, rowIndex: Int?) {
        val container = "(//div[@class='HeavyWeightWindow' or @class='DialogRootPane'])[last()]"
        val timeout = Duration.ofSeconds(3)
        
        // If rowIndex is specified, first select the row
        if (rowIndex != null) {
            try {
                val table = robot.find<ComponentFixture>(
                    byXpath("$container//div[@class='JBTable' or @class='JTable']"),
                    timeout
                )
                // Select the row
                table.callJs<String>("component.setRowSelectionInterval($rowIndex, $rowIndex)")
                Thread.sleep(200)
            } catch (_: Exception) {
                println("  Could not find table to select row $rowIndex")
            }
        }
        
        // Find and click the action button
        try {
            val button = robot.find<ComponentFixture>(
                byXpath("$container//div[@class='JButton' and (@visible_text='$action' or @accessiblename='$action')]"),
                timeout
            )
            button.click()
            Thread.sleep(300)
        } catch (_: Exception) {
            println("  Could not find table action button '$action'")
        }
    }

    /**
     * Types text at the current cursor position.
     */
    fun typeText(text: String) {
        // On macOS, use AppleScript to send keystrokes to test IDE (java process) specifically
        val osName = System.getProperty("os.name").lowercase()
        if (osName.contains("mac")) {
            try {
                // Send each character to the java process (QuestDB test IDE)
                text.forEach { char ->
                    val script = """
                        tell application "System Events"
                            tell process "java"
                                keystroke "$char"
                            end tell
                        end tell
                    """.trimIndent()
                    val processBuilder = ProcessBuilder("osascript", "-e", script)
                    processBuilder.start().waitFor()
                    Thread.sleep(30)
                }
                println("  [KEYBOARD] Typed '$text' via AppleScript")
            } catch (e: Exception) {
                println("  [WARNING] Could not type via AppleScript: ${e.message}")
            }
        } else {
            // Fallback to java.awt.Robot for other platforms
            val awtRobot = java.awt.Robot()
            text.forEach { char ->
                val keyCode = KeyEvent.getExtendedKeyCodeForChar(char.code)
                awtRobot.keyPress(keyCode)
                Thread.sleep(20)
                awtRobot.keyRelease(keyCode)
                Thread.sleep(50)
            }
        }
        Thread.sleep(200)
    }

    /**
     * Opens a file in the IDE using Cmd+Shift+O.
     * @param filePath The filename or path to open
     */
    fun openFile(filePath: String) {
        // Track the current file path for context discovery
        currentFilePath = filePath

        // Focus the IDE first
        val ideFrame = robot.find<ComponentFixture>(
            byXpath("//div[@class='IdeFrameImpl']"),
            Duration.ofSeconds(5),
        )
        ideFrame.click()
        Thread.sleep(200)

        val path = java.nio.file.Path.of(filePath)
        if (path.isAbsolute && java.nio.file.Files.exists(path)) {
            openAbsoluteFile(ideFrame, path)
            Thread.sleep(1000)
            return
        }

        // Press Cmd+Shift+O to open file dialog
        robot.keyboard {
            pressing(KeyEvent.VK_META) {
                pressing(KeyEvent.VK_SHIFT) {
                    key(KeyEvent.VK_O)
                }
            }
        }
        Thread.sleep(1000)

        // Type the file path
        robot.keyboard { enterText(filePath) }
        // Wait for results to appear
        Thread.sleep(1000)

        // Press Enter to open
        robot.keyboard { key(KeyEvent.VK_ENTER) }
        Thread.sleep(1000)
    }

    private fun openAbsoluteFile(
        ideFrame: ComponentFixture,
        path: java.nio.file.Path,
    ) {
        val escapedPath =
            path.toAbsolutePath().normalize().toString()
                .replace("\\", "\\\\")
                .replace("'", "\\'")

        val result =
            ideFrame.callJs<String>(
                """
                var status = "ERROR";
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait(new Runnable() {
                    run: function() {
                        var ioFile = new java.io.File('$escapedPath');
                        var virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);
                        if (virtualFile == null) {
                            status = "FILE_NOT_FOUND";
                            return;
                        }

                        var projects = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
                        if (projects.length === 0) {
                            status = "NO_PROJECT";
                            return;
                        }

                        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(projects[0]).openFile(virtualFile, true);
                        status = "OK";
                    }
                });
                status;
                """.trimIndent(),
            )

        check(result == "OK") { "Could not open absolute file '$path': $result" }
    }

    /**
     * Represents a line of code with its line number.
     */
    data class LineWithNumber(val lineNumber: Int, val content: String)

    /**
     * Reads file contents directly from disk with line numbers.
     * This is a simpler alternative to using IDE's getDocument() API.
     * 
     * @param relativePath The path to the file relative to the project root
     * @return List of LineWithNumber objects containing line number and content
     */
    fun readFileWithLineNumbers(relativePath: String): List<LineWithNumber> {
        val projectPath = getProjectRoot()
        val resolved = projectPath.resolve(relativePath)

        val file = if (resolved.toFile().exists()) {
            resolved.toFile()
        } else {
            // Bare filename or partial path — walk the src tree to find it
            val srcRoot = projectPath.resolve("src").toFile()
            val searchRoot = if (srcRoot.isDirectory) srcRoot else projectPath.toFile()
            searchRoot.walk()
                .filter { it.isFile && (it.name == relativePath || it.path.endsWith(relativePath)) }
                .firstOrNull()
        }

        return try {
            if (file == null || !file.exists()) {
                println("File not found in project: $relativePath")
                return emptyList()
            }
            file.readLines()
                .mapIndexed { index, line -> LineWithNumber(index + 1, line) }
        } catch (e: Exception) {
            println("Failed to read file $relativePath: ${e.message}")
            emptyList()
        }
    }

    /**
     * Reads file contents with line numbers and filters to a specific line range.
     * 
     * @param relativePath The path to the file relative to the project root
     * @param startLine Start line number (1-based, inclusive)
     * @param endLine End line number (1-based, inclusive)
     * @return List of LineWithNumber objects within the range
     */
    fun readFileLines(relativePath: String, startLine: Int, endLine: Int): List<LineWithNumber> {
        return readFileWithLineNumbers(relativePath)
            .filter { it.lineNumber in startLine..endLine }
    }

    /**
     * Finds all occurrences of a symbol in the file and returns their line numbers.
     * 
     * @param relativePath The path to the file relative to the project root
     * @param symbol The symbol to search for
     * @return List of line numbers where the symbol appears
     */
    fun findSymbolInFile(relativePath: String, symbol: String): List<Int> {
        return readFileWithLineNumbers(relativePath)
            .filter { it.content.contains(symbol) }
            .map { it.lineNumber }
    }

    /**
     * Gets the project root path.
     */
    private fun getProjectRoot(): java.nio.file.Path {
        // Get the working directory (project root)
        return java.nio.file.Paths.get("").toAbsolutePath()
    }

    /**
     * Provides simple file context by reading directly from disk.
     * This is a simpler alternative to analyzeFileStructure() that doesn't require IDE.
     * 
     * @param relativePath The path to the file relative to the project root
     * @return SimpleFileContext with methods, variables, and line count
     */
    fun getSimpleFileContext(relativePath: String): SimpleFileContext {
        val lines = readFileWithLineNumbers(relativePath)
        if (lines.isEmpty()) {
            return SimpleFileContext(0, emptyList(), emptyList())
        }
        
        val methodNames = mutableListOf<String>()
        val variableNames = mutableListOf<String>()
        
        // Find method names using regex
        val methodRegex = Regex("""fun\s+(\w+)""")
        for (line in lines) {
            methodRegex.findAll(line.content).forEach { match ->
                methodNames.add(match.groupValues[1])
            }
        }
        
        // Find variable names (val/var declarations)
        val varRegex = Regex("""(val|var)\s+(\w+)""")
        for (line in lines) {
            varRegex.findAll(line.content).forEach { match ->
                variableNames.add(match.groupValues[2])
            }
        }
        
        return SimpleFileContext(
            totalLines = lines.size,
            methodNames = methodNames,
            variableNames = variableNames
        )
    }

    /**
     * Simple file context data class for LLM prompts.
     */
    data class SimpleFileContext(
        val totalLines: Int,
        val methodNames: List<String>,
        val variableNames: List<String>
    ) {
        fun toPromptString(): String = """
            File has $totalLines lines
            Methods: ${methodNames.joinToString(", ")}
            Variables: ${variableNames.take(10).joinToString(", ")}
        """.trimIndent()
    }

    // ── File structure analysis ─────────────────────────────────────────────

    /**
     * Analyzed structure of the currently open file.
     * Used by the discovery agent to make informed caret/selection choices.
     */
    data class FileStructure(
        val totalLines: Int,
        val methodNames: List<String>,
        val methodBodyRanges: List<Pair<Int, Int>>,
        val firstMethodNameLine: Int,
        val firstMethodBodyStart: Int,
        val firstMethodBodyEnd: Int,
        val importEndLine: Int,
        val classBodyStart: Int,
        val classBodyEnd: Int,
        val literalLines: List<Int>,
        val statementLines: List<Int>,
        val variableNames: List<String>,
        val expressionTargets: List<String>
    ) {
        fun statementsInMethod(index: Int): Pair<Int, Int>? {
            if (index >= methodBodyRanges.size) return null
            val (start, end) = methodBodyRanges[index]
            val stmts = statementLines.filter { it in start until end }
            if (stmts.size < 2) return null
            return stmts.first() to minOf(stmts.first() + 2, stmts.last())
        }

        fun statementsInFirstMethod(): Pair<Int, Int>? = statementsInMethod(0)

        fun literalsInMethod(index: Int): List<Int> {
            if (index >= methodBodyRanges.size) return emptyList()
            val (start, end) = methodBodyRanges[index]
            return literalLines.filter { it in start until end }
        }
    }

    /**
     * Reads the focused editor's document and extracts file structure:
     * methods, body ranges, class boundaries, literals, statements,
     * variables, and expression targets.
     */
    fun analyzeFileStructure(): FileStructure? {
        val editor = try { findFocusedEditor() } catch (_: Exception) { return null }

        val result = try {
            // Wrap the entire JavaScript in a try-catch to provide better error messages
            editor.callJs<String>("""
                try {
                    var doc = component.getDocument();
                    var text = doc.getText(0, doc.getLength());
                    var lines = text.split('\n');
                    var totalLines = lines.length;
                    var methodNames = [];
                    var methodBodies = [];
                    var importEnd = 0;
                    var classBodyStart = 0;
                    var classBodyEnd = 0;
                    var literalLines = [];
                    var statementLines = [];
                    var variableNames = [];
                    var expressionTargets = [];

                    // Pass 1: find imports and class boundaries
                    var classDepth = 0;
                    var inClass = false;
                    for (var i = 0; i < lines.length; i++) {
                        var trimmed = lines[i].trim();
                        if (trimmed.startsWith('import ') || trimmed.startsWith('package ')) {
                            importEnd = i + 1;
                        }
                        if (!inClass) {
                            // Simplified class detection regex - fix potential issues
                            if (trimmed.match(/^(class|object|abstract class|open class|data class)\s/)) {
                                for (var ci = i; ci < lines.length; ci++) {
                                    if (lines[ci].indexOf('{') >= 0) {
                                        inClass = true;
                                        classBodyStart = ci + 2;
                                        classDepth = 0;
                                        for (var ck = 0; ck < lines[ci].length; ck++) {
                                            if (lines[ci].charAt(ck) == '{') classDepth++;
                                            if (lines[ci].charAt(ck) == '}') classDepth--;
                                        }
                                        break;
                                    }
                                }
                            }
                        } else if (classBodyEnd == 0) {
                            for (var ck2 = 0; ck2 < lines[i].length; ck2++) {
                                if (lines[i].charAt(ck2) == '{') classDepth++;
                                if (lines[i].charAt(ck2) == '}') classDepth--;
                            }
                            if (classDepth <= 0) classBodyEnd = i + 1;
                        }
                    }
                    if (classBodyEnd == 0 && inClass) classBodyEnd = totalLines;

                    // Pass 2: find methods with correct brace-counted body ranges
                    // Simplified: find opening brace and closing brace
                    for (var i = 0; i < lines.length; i++) {
                        var trimmed = lines[i].trim();
                        // Simple method detection - just find 'fun' keyword
                        if (trimmed.indexOf('fun') >= 0 && trimmed.indexOf('(') >= 0) {
                            // Find method name
                            var funMatch = trimmed.match(/fun\s+(\w+)/);
                            if (funMatch) {
                                methodNames.push(funMatch[1]);
                                
                                // Find the method body - look for { on this or subsequent lines
                                var bodyStartLine = -1;
                                var bodyEndLine = -1;
                                var braceCount = 0;
                                
                                for (var j = i; j < lines.length && bodyEndLine < 0; j++) {
                                    var line = lines[j];
                                    for (var k = 0; k < line.length; k++) {
                                        if (line.charAt(k) == '{') {
                                            if (braceCount == 0) bodyStartLine = j + 1;
                                            braceCount++;
                                        } else if (line.charAt(k) == '}') {
                                            braceCount--;
                                            if (braceCount == 0 && bodyStartLine > 0) {
                                                bodyEndLine = j + 1;
                                                break;
                                            }
                                        }
                                    }
                                }
                                
                                if (bodyStartLine > 0 && bodyEndLine > 0) {
                                    methodBodies.push(bodyStartLine + ',' + bodyEndLine);
                                } else if (bodyStartLine > 0) {
                                    // No closing brace found, use a reasonable estimate
                                    methodBodies.push(bodyStartLine + ',' + (bodyStartLine + 5));
                                }
                            }
                        }
                    }

                    // Pass 3: find statements, literals, variables, expressions
                    for (var i = 0; i < lines.length; i++) {
                        var lineNum = i + 1;
                        var trimmed = lines[i].trim();
                        if (trimmed.length == 0) continue;

                        var isComment = trimmed.startsWith('//') || trimmed.startsWith('/*') || trimmed.startsWith('*') || trimmed.startsWith('/**');
                        var isImport = trimmed.startsWith('import ') || trimmed.startsWith('package ');
                        var isStructural = trimmed == '{' || trimmed == '}' || trimmed == ')';
                        var isAnnotation = trimmed.startsWith('@');
                        var isDecl = trimmed.match(/^(class|object|interface|fun|abstract|open|data)\s/);

                        // Statements: val/var assignments, method calls, returns, etc.
                        if (!isComment && !isImport && !isStructural && !isAnnotation && !isDecl) {
                            statementLines.push(lineNum);
                        }
                        // Also count val/var lines as statements
                        if (trimmed.match(/^(val|var)\s+\w+/)) {
                            if (statementLines.indexOf(lineNum) < 0) statementLines.push(lineNum);
                            var varMatch = trimmed.match(/^(val|var)\s+(\w+)/);
                            if (varMatch) variableNames.push(varMatch[2]);
                        }

                        if (isComment || isImport) continue;

                        // Literals: strings and numbers inside code
                        var strLit = trimmed.match(/".[^"]*"/);
                        if (strLit) literalLines.push(lineNum);
                        // Simplified number detection - avoid complex regex with parentheses
                        var numMatch = trimmed.match(/[\s=\(,]\s*(\d+)/);
                        if (numMatch && !trimmed.match(/^(fun |class )/)) literalLines.push(lineNum);

                        // Expression targets: right-hand side of assignments
                        var assignMatch = trimmed.match(/(val|var)\s+\w+\s*=\s*(.+)/);
                        if (assignMatch) {
                            var rhs = assignMatch[2].trim();
                            if (rhs.length > 2) expressionTargets.push(lineNum + ':' + rhs.substring(0, Math.min(rhs.length, 40)));
                        }
                    }

                    // Deduplicate literal lines
                    var uniqueLiterals = [];
                    for (var li = 0; li < literalLines.length; li++) {
                        if (uniqueLiterals.indexOf(literalLines[li]) < 0) uniqueLiterals.push(literalLines[li]);
                    }

                    totalLines + '|' + importEnd + '|' + methodNames.join(',')
                        + '|' + methodBodies.join(';')
                        + '|' + classBodyStart + '|' + classBodyEnd
                        + '|' + uniqueLiterals.join(',')
                        + '|' + statementLines.join(',')
                        + '|' + variableNames.join(',')
                        + '|' + expressionTargets.join(';');
                } catch (e) {
                    'ERROR: ' + e.message;
                }
            """.trimIndent())
        } catch (e: Exception) {
            println("  File structure analysis failed: ${e.message}")
            return null
        }

        val parts = result.split("|")
        // Check if there was a JavaScript error
        if (parts.size == 1 && parts[0].startsWith("ERROR:")) {
            println("  File structure analysis failed: ${parts[0].substring(6)}")
            return null
        }
        if (parts.size < 10) return null

        val totalLines = parts[0].toIntOrNull() ?: 0
        val importEnd = parts[1].toIntOrNull() ?: 0
        val methodNames = parts[2].split(",").filter { it.isNotBlank() }
        val bodyRanges = parts[3].split(";").filter { it.isNotBlank() }.mapNotNull { range ->
            val r = range.split(",")
            if (r.size == 2) (r[0].toIntOrNull() ?: 0) to (r[1].toIntOrNull() ?: 0) else null
        }
        val classStart = parts[4].toIntOrNull() ?: 0
        val classEnd = parts[5].toIntOrNull() ?: 0
        val literalLines = parts[6].split(",").mapNotNull { it.toIntOrNull() }
        val stmtLines = parts[7].split(",").mapNotNull { it.toIntOrNull() }
        val varNames = parts[8].split(",").filter { it.isNotBlank() }
        val exprTargets = parts[9].split(";").filter { it.isNotBlank() }

        val firstBodyStart = bodyRanges.firstOrNull()?.first ?: (importEnd + 2)
        val firstBodyEnd = bodyRanges.firstOrNull()?.second ?: (firstBodyStart + 2)
        val firstMethodLine = if (firstBodyStart > 1) firstBodyStart - 1 else 1

        return FileStructure(
            totalLines = totalLines,
            methodNames = methodNames,
            methodBodyRanges = bodyRanges,
            firstMethodNameLine = firstMethodLine,
            firstMethodBodyStart = firstBodyStart,
            firstMethodBodyEnd = firstBodyEnd,
            importEndLine = importEnd,
            classBodyStart = classStart,
            classBodyEnd = classEnd,
            literalLines = literalLines,
            statementLines = stmtLines,
            variableNames = varNames,
            expressionTargets = exprTargets
        )
    }

    // ── UI tree fetching ─────────────────────────────────────────────────────

    fun fetchUiTree(): List<UiComponent> = treeProvider.fetchTree()

    /**
     * Get the current document text from the focused editor.
     * Used for detecting source code changes as a completion signal.
     *
     * @return The current document text, or null if no editor is focused
     */
    fun getDocumentText(): String? {
        return try {
            val documentText = findFocusedEditor().callJs<String>(
                """
                var text = '__NO_EDITOR__';
                com.intellij.openapi.application.ApplicationManager
                    .getApplication().invokeAndWait(new Runnable() {
                        run: function() {
                            var project = com.intellij.openapi.project.ProjectManager
                                .getInstance().getOpenProjects()[0];
                            var editorEx = com.intellij.openapi.fileEditor.FileEditorManager
                                .getInstance(project).getSelectedTextEditor();
                            if (editorEx != null) {
                                text = editorEx.getDocument().getImmutableCharSequence().toString();
                            }
                        }
                    });
                text;
                """.trimIndent(),
            )
            documentText.takeUnless { it == "__NO_EDITOR__" }
        } catch (e: Exception) {
            println("  Warning: Could not get document text: ${e.message}")
            null
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    fun dismissPopups() {
        repeat(3) {
            try {
                robot.keyboard { key(KeyEvent.VK_ESCAPE) }
                Thread.sleep(200)
            } catch (_: Exception) {}
        }
    }

    private fun findFocusedEditor(): ComponentFixture {
        val editors = robot.findAll<ComponentFixture>(
            byXpath("//div[@class='EditorComponentImpl']")
        )
        return editors.firstOrNull { comp ->
            try { comp.callJs<Boolean>("component.hasFocus()") } catch (_: Exception) { false }
        } ?: editors.firstOrNull()
            ?: throw IllegalStateException("No editor component found")
    }

    private fun getCaretScreenCoords(editor: ComponentFixture): ScreenCoords {
        val coordResult = editor.callJs<String>("""
            var screenX = -1;
            var screenY = -1;
            com.intellij.openapi.application.ApplicationManager
                .getApplication().invokeAndWait(new Runnable() {
                    run: function() {
                        var project = com.intellij.openapi.project.ProjectManager
                            .getInstance().getOpenProjects()[0];
                        var editorEx = com.intellij.openapi.fileEditor.FileEditorManager
                            .getInstance(project).getSelectedTextEditor();
                        if (editorEx != null) {
                            var caretOffset = editorEx.getCaretModel().getOffset();
                            var pos = editorEx.offsetToXY(caretOffset);
                            var editorComp = editorEx.getContentComponent();
                            var loc = editorComp.getLocationOnScreen();
                            screenX = loc.x + pos.x;
                            screenY = loc.y + pos.y + 8;
                        }
                    }
                });
            screenX + ',' + screenY;
        """.trimIndent())

        val parts = coordResult.split(",")
        return ScreenCoords(parts[0].trim().toInt(), parts[1].trim().toInt())
    }

    private fun getComponentScreenCenter(component: ComponentFixture): ScreenCoords {
        val result = component.callJs<String>("""
            var screenX = -1;
            var screenY = -1;
            com.intellij.openapi.application.ApplicationManager
                .getApplication().invokeAndWait(new Runnable() {
                    run: function() {
                        var loc = component.getLocationOnScreen();
                        var w = component.getWidth();
                        var h = component.getHeight();
                        screenX = loc.x + (w / 2);
                        screenY = loc.y + (h / 2);
                    }
                });
            screenX + ',' + screenY;
        """.trimIndent())

        val parts = result.split(",")
        return ScreenCoords(parts[0].trim().toDouble().toInt(), parts[1].trim().toDouble().toInt())
    }
    
    /**
     * Get the bounds (x, y, width, height) of a component.
     * Returns a Triple of (x, y, width) - height can be added if needed.
     */
    private fun getComponentBounds(component: ComponentFixture): Triple<Int, Int, Int> {
        val result = component.callJs<String>("""
            var x = 0;
            var y = 0;
            var w = 0;
            com.intellij.openapi.application.ApplicationManager
                .getApplication().invokeAndWait(new Runnable() {
                    run: function() {
                        var loc = component.getLocationOnScreen();
                        w = component.getWidth();
                        x = loc.x;
                        y = loc.y;
                    }
                });
            x + ',' + y + ',' + w;
        """.trimIndent())
        
        val parts = result.split(",")
        return Triple(
            parts[0].trim().toDouble().toInt(),
            parts[1].trim().toDouble().toInt(),
            parts[2].trim().toDouble().toInt()
        )
    }

    private fun rightClickAt(x: Int, y: Int) {
        val awtRobot = java.awt.Robot()
        awtRobot.mouseMove(x, y)
        Thread.sleep(100)
        awtRobot.mousePress(InputEvent.BUTTON3_DOWN_MASK)
        Thread.sleep(100)
        awtRobot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK)
    }

    private fun clickAt(x: Int, y: Int) {
        val awtRobot = java.awt.Robot()
        awtRobot.mouseMove(x, y)
        Thread.sleep(100)
        awtRobot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(100)
        awtRobot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    }

    private fun parseShortcutKeys(shortcut: String): List<Int> {
        return shortcut.split("+").map { part ->
            when (part.trim().lowercase()) {
                "cmd", "meta", "command"   -> KeyEvent.VK_META
                "ctrl", "control"          -> KeyEvent.VK_CONTROL
                "alt", "option"            -> KeyEvent.VK_ALT
                "shift"                    -> KeyEvent.VK_SHIFT
                "enter", "return"          -> KeyEvent.VK_ENTER
                "escape", "esc"            -> KeyEvent.VK_ESCAPE
                "tab"                      -> KeyEvent.VK_TAB
                "delete", "backspace"      -> KeyEvent.VK_BACK_SPACE
                "f1"  -> KeyEvent.VK_F1
                "f2"  -> KeyEvent.VK_F2
                "f3"  -> KeyEvent.VK_F3
                "f4"  -> KeyEvent.VK_F4
                "f5"  -> KeyEvent.VK_F5
                "f6"  -> KeyEvent.VK_F6
                "f7"  -> KeyEvent.VK_F7
                "f8"  -> KeyEvent.VK_F8
                "f9"  -> KeyEvent.VK_F9
                "f10" -> KeyEvent.VK_F10
                "f11" -> KeyEvent.VK_F11
                "f12" -> KeyEvent.VK_F12
                "a" -> KeyEvent.VK_A
                "b" -> KeyEvent.VK_B
                "c" -> KeyEvent.VK_C
                "d" -> KeyEvent.VK_D
                "e" -> KeyEvent.VK_E
                "f" -> KeyEvent.VK_F
                "g" -> KeyEvent.VK_G
                "h" -> KeyEvent.VK_H
                "i" -> KeyEvent.VK_I
                "j" -> KeyEvent.VK_J
                "k" -> KeyEvent.VK_K
                "l" -> KeyEvent.VK_L
                "m" -> KeyEvent.VK_M
                "n" -> KeyEvent.VK_N
                "o" -> KeyEvent.VK_O
                "p" -> KeyEvent.VK_P
                "q" -> KeyEvent.VK_Q
                "r" -> KeyEvent.VK_R
                "s" -> KeyEvent.VK_S
                "t" -> KeyEvent.VK_T
                "u" -> KeyEvent.VK_U
                "v" -> KeyEvent.VK_V
                "w" -> KeyEvent.VK_W
                "x" -> KeyEvent.VK_X
                "y" -> KeyEvent.VK_Y
                "z" -> KeyEvent.VK_Z
                else -> KeyEvent.getExtendedKeyCodeForChar(part.trim().first().code)
            }
        }
    }

}
