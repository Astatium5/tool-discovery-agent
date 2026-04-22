package execution

import agent.AgentConfig.actionDelayMs
import agent.AgentConfig.contextMenuDelayMs
import agent.AgentConfig.menuClickDelayMs
import agent.AgentConfig.retryDelayMs
import agent.AgentConfig.robotTimeoutSeconds
import com.intellij.openapi.diagnostic.logger
import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import model.RecipeStep
import perception.tree.HtmlUiTreeProvider
import perception.tree.UiComponent
import perception.tree.UiTreeParser
import perception.tree.UiTreeProvider
import vision.ElementInfo
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.time.Duration

/**
 * Translates RecipeStep sequences into actual RemoteRobot interactions.
 * Extracted from the hardcoded patterns in RenameRefactorTest.
 *
 * Also supports vision-based operations (ClickElement, etc) when elementMap is provided.
 */
class UiExecutor(
    private val robot: RemoteRobot,
    private val treeProvider: UiTreeProvider = HtmlUiTreeProvider(),
    elementMap: Map<Int, ElementInfo>? = null,
) {
    private val log = logger<UiExecutor>()
    private val defaultTimeout = Duration.ofSeconds(robotTimeoutSeconds)
    private val awtRobot = java.awt.Robot()

    // Track the currently open file for context discovery
    var currentFilePath: String? = null

    // Mutable elementMap for vision-based coordinate clicking (can be updated during execution)
    private var elementMap: Map<Int, ElementInfo>? = elementMap

    /**
     * Update the element map for coordinate-based clicking.
     * Called by VisionAgent before each action with the latest screenshot's element map.
     */
    fun setElementMap(newElementMap: Map<Int, ElementInfo>) {
        this.elementMap = newElementMap
    }

    data class ScreenCoords(val x: Int, val y: Int)

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Execute a full recipe step by step. Returns true if all steps succeeded.
     */
    fun executeRecipe(steps: List<RecipeStep>): Boolean {
        for ((i, step) in steps.withIndex()) {
            log.info("  [Step ${i + 1}/${steps.size}] ${step.describe()}")
            try {
                executeStep(step)
                Thread.sleep(retryDelayMs)
            } catch (e: Exception) {
                log.warn("  FAILED at step ${i + 1}: ${e.message}")
                dismissPopups()
                return false
            }
        }
        return true
    }

    fun executeStep(step: RecipeStep) {
        when (step) {
            is RecipeStep.OpenFile -> openFile(step.path)
            is RecipeStep.FocusEditor -> focusEditor()
            is RecipeStep.MoveCaret -> moveCaret(step.toSymbol)
            is RecipeStep.SelectLines -> selectLines(step.from, step.to)
            is RecipeStep.SelectLinesPlaceholder -> {
                val from =
                    step.from.toIntOrNull() ?: throw IllegalArgumentException(
                        "SelectLinesPlaceholder.from='${step.from}' was not resolved to an integer before execution",
                    )
                val to =
                    step.to.toIntOrNull() ?: throw IllegalArgumentException(
                        "SelectLinesPlaceholder.to='${step.to}' was not resolved to an integer before execution",
                    )
                selectLines(from, to)
            }
            is RecipeStep.PressShortcut -> pressShortcut(step.keys)
            is RecipeStep.OpenContextMenu -> openContextMenu()
            is RecipeStep.ClickMenu -> clickMenuItem(step.label)
            is RecipeStep.TypeInDialog -> typeInDialog(step.value)
            is RecipeStep.SelectDropdown -> selectDropdown(step.value)
            is RecipeStep.ClickDialogButton -> clickDialogButton(step.label)
            is RecipeStep.PressKey -> pressKey(step.key)
            is RecipeStep.CancelDialog -> pressEscape()
            // Field Navigation steps
            is RecipeStep.FocusField -> focusField(step.fieldLabel)
            is RecipeStep.SelectDropdownField -> selectDropdownField(step.fieldLabel, step.value)
            is RecipeStep.SetCheckbox -> setCheckbox(step.fieldLabel, step.checked)
            is RecipeStep.TableRowAction -> tableRowAction(step.action, step.rowIndex)
            // Vision-based steps (coordinate clicking)
            is RecipeStep.ClickElement -> clickElementByCoordinate(step.elementId)
            is RecipeStep.DoubleClickElement -> doubleClickElementByCoordinate(step.elementId)
            is RecipeStep.RightClickElement -> rightClickElementByCoordinate(step.elementId)
        }
    }

    // ── Step implementations ─────────────────────────────────────────────────

    fun focusEditor() {
        val editors =
            robot.findAll<ComponentFixture>(
                byXpath("//div[@class='EditorComponentImpl']"),
            )
        val editor =
            editors.firstOrNull { comp ->
                try {
                    comp.callJs<Boolean>("component.hasFocus()")
                } catch (_: Exception) {
                    false
                }
            } ?: editors.firstOrNull()
                ?: throw IllegalStateException("No editor component found")
        editor.click()
        Thread.sleep(retryDelayMs)
    }

    fun moveCaret(symbol: String) {
        val editor = findFocusedEditor()

        log.debug("  moveCaret: Looking for '$symbol' in editor...")

        val offsetResult =
            editor.callJs<String>(
                """
                var doc = component.getDocument();
                var fullText = doc.getText(0, doc.getLength());
                var idx = fullText.indexOf('${jsEscape(symbol)}');
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
                """.trimIndent(),
            )

        log.debug("  moveCaret result: $offsetResult")

        if (offsetResult == "NOT_FOUND") {
            throw IllegalStateException("Symbol '$symbol' not found in editor")
        }

        Thread.sleep(retryDelayMs / 2)
    }

    fun selectLines(
        from: Int,
        to: Int,
    ) {
        val editor = findFocusedEditor()

        editor.callJs<String>(
            """
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
            """.trimIndent(),
        )
        Thread.sleep(retryDelayMs)
    }

    fun pressShortcut(keys: String) {
        // Ensure the editor has focus before pressing shortcut.
        try {
            val editors = robot.findAll<ComponentFixture>(byXpath("//div[@class='EditorComponentImpl']"))
            val editor =
                editors.firstOrNull { comp ->
                    try {
                        comp.callJs<Boolean>("component.hasFocus()")
                    } catch (_: Exception) {
                        false
                    }
                } ?: editors.firstOrNull()

            if (editor != null) {
                log.debug("    pressShortcut: Requesting editor focus (caret preserved)")
                editor.callJs<String>(
                    """
                    com.intellij.openapi.application.ApplicationManager
                        .getApplication().invokeAndWait(new Runnable() {
                            run: function() { component.requestFocus(); }
                        });
                    'ok';
                    """.trimIndent(),
                )
                Thread.sleep(retryDelayMs / 3)
            } else {
                val ideFrame =
                    robot.find<ComponentFixture>(
                        byXpath("//div[@class='IdeFrameImpl']"),
                        Duration.ofSeconds(2),
                    )
                log.debug("    pressShortcut: No editor found, clicking IDE frame")
                ideFrame.click()
                Thread.sleep(retryDelayMs)
            }
        } catch (e: Exception) {
            log.debug("    pressShortcut: Could not focus editor/frame, proceeding anyway")
        }

        val parts = keys.split("+").map { it.trim() }
        log.debug("    pressShortcut: Parsing '$keys' -> parts: $parts")

        // Separate modifiers from main key
        val modifierNames = parts.dropLast(1)
        val mainKeyName = parts.last()

        log.debug("    pressShortcut: Modifiers: $modifierNames, Main key: $mainKeyName")

        // Use Remote Robot's keyboard API with pressing() for modifiers
        robot.keyboard {
            // Build nested pressing() calls for each modifier
            when (modifierNames.size) {
                0 -> key(keyNameToKeyCode(mainKeyName))
                1 -> pressing(keyNameToKeyCode(modifierNames[0])) { key(keyNameToKeyCode(mainKeyName)) }
                2 ->
                    pressing(keyNameToKeyCode(modifierNames[0])) {
                        pressing(keyNameToKeyCode(modifierNames[1])) { key(keyNameToKeyCode(mainKeyName)) }
                    }
                3 ->
                    pressing(keyNameToKeyCode(modifierNames[0])) {
                        pressing(keyNameToKeyCode(modifierNames[1])) {
                            pressing(keyNameToKeyCode(modifierNames[2])) { key(keyNameToKeyCode(mainKeyName)) }
                        }
                    }
                else -> {
                    log.debug("    pressShortcut: Too many modifiers (${modifierNames.size}), falling back to simple key")
                    key(keyNameToKeyCode(mainKeyName))
                }
            }
        }

        Thread.sleep(menuClickDelayMs)
        log.debug("    pressShortcut: Shortcut '$keys' executed via Remote Robot")
    }

    private fun keyNameToKeyCode(name: String): Int {
        return when (name.lowercase()) {
            "cmd", "meta", "command" -> KeyEvent.VK_META
            "ctrl", "control" -> KeyEvent.VK_CONTROL
            "alt", "option" -> KeyEvent.VK_ALT
            "shift" -> KeyEvent.VK_SHIFT
            "enter", "return" -> KeyEvent.VK_ENTER
            "escape", "esc" -> KeyEvent.VK_ESCAPE
            "tab" -> KeyEvent.VK_TAB
            "delete", "backspace" -> KeyEvent.VK_BACK_SPACE
            "space" -> KeyEvent.VK_SPACE
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
            "context_menu" -> KeyEvent.VK_CONTEXT_MENU
            else -> KeyEvent.getExtendedKeyCodeForChar(name.first().code)
        }
    }

    fun openContextMenu() {
        // VK_CONTEXT_MENU is a Windows-only key — it silently does nothing on macOS.
        // Right-click via AWT Robot at the caret screen position is the reliable approach.
        try {
            val editor = findFocusedEditor()
            val coords = getCaretScreenCoords(editor)
            log.debug("  openContextMenu: Right-clicking at caret (${coords.x}, ${coords.y})")
            awtRobot.mouseMove(coords.x, coords.y)
            Thread.sleep(50)
            awtRobot.mousePress(InputEvent.BUTTON3_DOWN_MASK)
            Thread.sleep(50)
            awtRobot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK)
            Thread.sleep(contextMenuDelayMs)
        } catch (e: Exception) {
            log.debug("  openContextMenu: Right-click failed (${e.message}), falling back to keyboard")
            robot.keyboard { key(KeyEvent.VK_CONTEXT_MENU) }
            Thread.sleep(contextMenuDelayMs)
        }
    }

    fun clickMenuItem(label: String) {
        log.debug("  clickMenuItem: Looking for '$label'")

        // Normalize label: handle both Unicode ellipsis (U+2026) and ASCII dots
        val normalizedLabel = label.replace("...", "…")
        val altLabel = label.replace("…", "...")

        // Approach 1: Find JBPopupMenu items via JS
        try {
            log.debug("    Approach 1: Search JBPopupMenu via JavaScript")
            val popupMenus = robot.findAll<ComponentFixture>(byXpath("//div[@class='JBPopupMenu' or @class='MyMenu']"))
            log.debug("    Found ${popupMenus.size} popup menus")

            for (pm in popupMenus.toList().reversed()) {
                val pmY = pm.callJs<Int>("component.getLocationOnScreen().y")
                log.debug("      PopupMenu at y=$pmY")
                if (pmY < 100) continue

                // Use JS to find matching items inside this popup
                val jsNorm = jsEscape(normalizedLabel)
                val jsAlt = jsEscape(altLabel)
                val found =
                    pm.callJs<String>(
                        """
                        var found = false;
                        var children = component.getComponents();
                        for (var i = 0; i < children.length; i++) {
                            var c = children[i];
                            var txt = c.getText ? (c.getText() || '') : '';
                            if (txt.indexOf('$jsNorm') >= 0 || txt.indexOf('$jsAlt') >= 0) {
                                found = true;
                                break;
                            }
                            if (c instanceof java.awt.Container) {
                                var nested = c.getComponents();
                                for (var j = 0; j < nested.length; j++) {
                                    var n = nested[j];
                                    var ntxt = n.getText ? (n.getText() || '') : '';
                                    if (ntxt.indexOf('$jsNorm') >= 0 || ntxt.indexOf('$jsAlt') >= 0) {
                                        found = true;
                                        break;
                                    }
                                }
                            }
                        }
                        java_result = found ? 'true' : 'false';
                        """.trimIndent(),
                    )

                if (found == "true") {
                    log.debug("        Item confirmed in popup, clicking via JS doClick()")
                    pm.callJs<String>(
                        """
                        com.intellij.openapi.application.ApplicationManager
                            .getApplication().invokeAndWait(new Runnable() {
                                run: function() {
                                    var children = component.getComponents();
                                    for (var i = 0; i < children.length; i++) {
                                        var c = children[i];
                                        var txt = c.getText ? (c.getText() || '') : '';
                                        if (txt.indexOf('$jsNorm') >= 0 || txt.indexOf('$jsAlt') >= 0) {
                                            c.doClick();
                                            break;
                                        }
                                        if (c instanceof java.awt.Container) {
                                            var nested = c.getComponents();
                                            for (var j = 0; j < nested.length; j++) {
                                                var n = nested[j];
                                                var ntxt = n.getText ? (n.getText() || '') : '';
                                                if (ntxt.indexOf('$jsNorm') >= 0 || ntxt.indexOf('$jsAlt') >= 0) {
                                                    n.doClick();
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                            });
                        'ok';
                        """.trimIndent(),
                    )
                    Thread.sleep(menuClickDelayMs)
                    return
                }
            }
        } catch (e: Exception) {
            log.debug("    Approach 1 failed: ${e.message?.take(30)}")
        }

        // Approach 2: Scope search to HeavyWeightWindow
        try {
            log.debug("    Approach 2: ActionMenuItems inside HeavyWeightWindow (popup-scoped)")
            val all =
                robot.findAll<ComponentFixture>(
                    byXpath("//div[@class='HeavyWeightWindow']//div[@class='ActionMenuItem']"),
                )
            log.debug("    Found ${all.size} ActionMenuItems in popup windows")
            for (item in all.toList()) {
                try {
                    val itemY = item.callJs<Int>("component.getLocationOnScreen().y")
                    val text = item.callJs<String>("component.getText() || ''")
                    log.debug("      y=$itemY text='$text'")
                    val baseName = normalizedLabel.trimEnd('…').trimEnd('.')
                    if (text == normalizedLabel || text == altLabel ||
                        (baseName.length >= 3 && text.startsWith(baseName, ignoreCase = true))
                    ) {
                        log.info("        Match! Clicking '$text'")
                        item.click()
                        Thread.sleep(menuClickDelayMs)
                        return
                    }
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            log.debug("    Approach 2 failed: ${e.message?.take(30)}")
        }

        throw Exception("Could not find menu item '$label' in popup windows")
    }

    private fun debugComponent(
        component: ComponentFixture,
        strategy: String,
    ) {
        try {
            val cls = component.callJs<String>("component.getClass().getSimpleName()")
            val text = component.callJs<String>("component.getText()")
            val accessibleName = component.callJs<String>("component.getAccessibleContext().getAccessibleName()")
            val bounds =
                component.callJs<String>(
                    """
                    var loc = component.getLocationOnScreen();
                    var w = component.getWidth();
                    var h = component.getHeight();
                    loc.x + ',' + loc.y + ',' + w + ',' + h;
                    """.trimIndent(),
                )
            log.debug("    [$strategy] Found: class='$cls', text='$text', accessibleName='$accessibleName', bounds=$bounds")
        } catch (e: Exception) {
            log.debug("    [$strategy] Could not get component details: ${e.message?.take(30)}")
        }
    }

    fun typeInDialog(value: String) {
        Thread.sleep(contextMenuDelayMs / 2)

        val (field, isInlineEditor) = findInputField()

        if (!isInlineEditor) {
            field.click()
            Thread.sleep(retryDelayMs / 3)
            robot.keyboard {
                pressing(KeyEvent.VK_META) { key(KeyEvent.VK_A) }
            }
            Thread.sleep(retryDelayMs / 2)
        }

        robot.keyboard { enterText(value) }
        Thread.sleep(retryDelayMs)
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
        } catch (_: Exception) {
        }

        // Strategy 2: JTextField/JBTextField inside popup/dialog
        try {
            return robot.find<ComponentFixture>(byXpath("$container//div[@class='JTextField' or @class='JBTextField']"), timeout) to false
        } catch (_: Exception) {
        }

        // Strategy 3: EditorComponentImpl inside a JDialog
        try {
            return robot.find<ComponentFixture>(byXpath("//div[@class='JDialog']//div[@class='EditorComponentImpl']"), timeout) to false
        } catch (_: Exception) {
        }

        // Strategy 4: focused editor — for inline rename/extract, the input field
        // IS the code editor itself (IntelliJ puts a template field in-place).
        return findFocusedEditor() to true
    }

    fun selectDropdown(value: String) {
        // Find dropdown dynamically - look for combobox-like components
        // Use contains() to match any class with "combo", "dropdown", or "select" in the name
        val dropdown =
            try {
                robot.find<ComponentFixture>(
                    byXpath(
                        "(//div[@class='HeavyWeightWindow' or @class='DialogRootPane'])[last()]//div[contains(@class, 'Combo') or contains(@class, 'combo') or contains(@class, 'Dropdown') or contains(@class, 'dropdown') or contains(@class, 'Select') or contains(@class, 'select')]",
                    ),
                    Duration.ofSeconds(5),
                )
            } catch (_: Exception) {
                log.debug("  No dropdown found, skipping selection")
                return
            }

        // Click to open dropdown
        dropdown.click()
        Thread.sleep(retryDelayMs)

        // Try to find and click the option with matching text
        try {
            val option =
                robot.find<ComponentFixture>(
                    byXpath("//div[@visible_text=${xpathLiteral(value)} or @accessiblename=${xpathLiteral(value)}]"),
                    Duration.ofSeconds(2),
                )
            option.click()
        } catch (_: Exception) {
            robot.keyboard { enterText(value) }
            Thread.sleep(retryDelayMs / 2)
            robot.keyboard { key(KeyEvent.VK_ENTER) }
        }
        Thread.sleep(retryDelayMs)
    }

    fun clickDialogButton(label: String) {
        log.debug("  clickDialogButton: Looking for '$label'")

        val button =
            try {
                robot.find<ComponentFixture>(
                    byXpath("//div[@accessiblename=${xpathLiteral(label)} and @class='JButton']"),
                    Duration.ofSeconds(3),
                )
            } catch (_: Exception) {
                try {
                    robot.find<ComponentFixture>(
                        byXpath("//div[@class='DialogRootPane']//div[@accessiblename=${xpathLiteral(label)}]"),
                        Duration.ofSeconds(2),
                    )
                } catch (_: Exception) {
                    robot.find<ComponentFixture>(
                        byXpath("//div[@class='DialogRootPane']//div[contains(@text, ${xpathLiteral(label)})]"),
                        Duration.ofSeconds(2),
                    )
                }
            }

        button.click()
        Thread.sleep(contextMenuDelayMs)
        log.debug("  clickDialogButton: Clicked button '$label'")
    }

    fun pressEscape() {
        robot.keyboard { key(KeyEvent.VK_ESCAPE) }
        Thread.sleep(retryDelayMs)
    }

    fun pressKey(keyName: String) {
        val keyCode =
            when (keyName.lowercase()) {
                "enter" -> KeyEvent.VK_ENTER
                "escape", "esc" -> KeyEvent.VK_ESCAPE
                "tab" -> KeyEvent.VK_TAB
                "backspace" -> KeyEvent.VK_BACK_SPACE
                "delete" -> KeyEvent.VK_DELETE
                "space" -> KeyEvent.VK_SPACE
                "up" -> KeyEvent.VK_UP
                "down" -> KeyEvent.VK_DOWN
                "left" -> KeyEvent.VK_LEFT
                "right" -> KeyEvent.VK_RIGHT
                else -> KeyEvent.VK_ENTER // Default to Enter
            }
        robot.keyboard { key(keyCode) }
        Thread.sleep(retryDelayMs)
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
            val field =
                robot.find<ComponentFixture>(
                    byXpath(
                        "$container//div[($inputFieldClasses) and (contains(@accessiblename, ${xpathLiteral(
                            normalizedLabel,
                        )}) or contains(@accessiblename, ${xpathLiteral(fieldLabel)}))]",
                    ),
                    timeout,
                )
            field.click()
            Thread.sleep(retryDelayMs / 2)
            log.debug("  Found field by accessible name: $fieldLabel")
            return
        } catch (_: Exception) {
        }

        // Strategy 2: Find label, then find the input field that follows it
        try {
            // Find the label first
            val label =
                robot.find<ComponentFixture>(
                    byXpath(
                        "$container//div[@visible_text=${xpathLiteral(
                            fieldLabel,
                        )} or contains(@text, ${xpathLiteral(
                            fieldLabel,
                        )}) or @visible_text=${xpathLiteral(normalizedLabel)} or contains(@text, ${xpathLiteral(normalizedLabel)})]",
                    ),
                    timeout,
                )

            // Get the label's bounds to find adjacent input field
            val labelBounds = getComponentBounds(label)
            val labelY = labelBounds.second
            val labelRight = labelBounds.first + labelBounds.third

            // Find all input fields in the container
            val allFields =
                robot.findAll<ComponentFixture>(
                    byXpath("$container//div[$inputFieldClasses]"),
                )

            // Find the input field that is closest to the label
            val closestField =
                allFields.minByOrNull { field ->
                    val fieldBounds = getComponentBounds(field)
                    val verticalDistance = kotlin.math.abs(fieldBounds.second - labelY)
                    val horizontalDistance =
                        if (fieldBounds.first >= labelRight) {
                            fieldBounds.first - labelRight // Field is to the right
                        } else {
                            1000 + (labelRight - fieldBounds.first) // Field is to the left (penalize)
                        }
                    verticalDistance * 10 + horizontalDistance // Weight vertical distance more
                }

            if (closestField != null) {
                closestField.click()
                Thread.sleep(retryDelayMs / 2)
                log.debug("  Found field adjacent to label: $fieldLabel")
                return
            }
        } catch (_: Exception) {
        }

        // Strategy 3: Find any input field with the label text in its value or placeholder
        try {
            val field =
                robot.find<ComponentFixture>(
                    byXpath(
                        "$container//div[($inputFieldClasses) and (contains(@text, ${xpathLiteral(
                            normalizedLabel,
                        )}) or contains(@placeholder, ${xpathLiteral(normalizedLabel)}))]",
                    ),
                    timeout,
                )
            field.click()
            Thread.sleep(retryDelayMs / 2)
            log.debug("  Found field by text content: $fieldLabel")
            return
        } catch (_: Exception) {
        }

        throw IllegalStateException("Could not find field '$fieldLabel' after all strategies")
    }

    fun selectDropdownField(
        fieldLabel: String,
        value: String,
    ) {
        focusField(fieldLabel)
        Thread.sleep(retryDelayMs / 3)
        selectDropdown(value)
    }

    fun setCheckbox(
        fieldLabel: String,
        checked: Boolean,
    ) {
        val container = "(//div[@class='HeavyWeightWindow' or @class='DialogRootPane'])[last()]"
        val timeout = Duration.ofSeconds(3)

        // Strategy 1: Find checkbox by label
        try {
            val checkbox =
                robot.find<ComponentFixture>(
                    byXpath(
                        "$container//div[@class='JCheckBox' and (@visible_text=${xpathLiteral(
                            fieldLabel,
                        )} or contains(@text, ${xpathLiteral(fieldLabel)}) or contains(@accessiblename, ${xpathLiteral(fieldLabel)}))]",
                    ),
                    timeout,
                )
            val currentState = checkbox.callJs<Boolean>("component.isSelected()")
            if (currentState != checked) {
                checkbox.click()
                Thread.sleep(retryDelayMs / 2)
            }
            return
        } catch (_: Exception) {
        }

        // Strategy 2: Find by accessible name
        try {
            val checkbox =
                robot.find<ComponentFixture>(
                    byXpath("$container//div[@class='JCheckBox' and contains(@accessiblename, ${xpathLiteral(fieldLabel)})]"),
                    timeout,
                )
            val currentState = checkbox.callJs<Boolean>("component.isSelected()")
            if (currentState != checked) {
                checkbox.click()
                Thread.sleep(retryDelayMs / 2)
            }
            return
        } catch (_: Exception) {
        }

        throw IllegalStateException("Could not find checkbox '$fieldLabel'")
    }

    fun tableRowAction(
        action: String,
        rowIndex: Int?,
    ) {
        val container = "(//div[@class='HeavyWeightWindow' or @class='DialogRootPane'])[last()]"
        val timeout = Duration.ofSeconds(3)

        // If rowIndex is specified, first select the row
        if (rowIndex != null) {
            try {
                val table =
                    robot.find<ComponentFixture>(
                        byXpath("$container//div[@class='JBTable' or @class='JTable']"),
                        timeout,
                    )
                table.callJs<String>("component.setRowSelectionInterval($rowIndex, $rowIndex)")
                Thread.sleep(retryDelayMs / 2)
            } catch (_: Exception) {
                log.debug("  Could not find table to select row $rowIndex")
            }
        }

        // Find and click the action button
        try {
            val button =
                robot.find<ComponentFixture>(
                    byXpath(
                        "$container//div[@class='JButton' and (@visible_text=${xpathLiteral(
                            action,
                        )} or @accessiblename=${xpathLiteral(action)})]",
                    ),
                    timeout,
                )
            button.click()
            Thread.sleep(retryDelayMs)
        } catch (_: Exception) {
            log.debug("  Could not find table action button '$action'")
        }
    }

    fun typeText(text: String) {
        robot.keyboard { enterText(text) }
        Thread.sleep(retryDelayMs / 2)
    }

    fun openFile(filePath: String) {
        // Track the current file path for context discovery
        currentFilePath = filePath

        // Focus the IDE first
        val ideFrame =
            robot.find<ComponentFixture>(
                byXpath("//div[@class='IdeFrameImpl']"),
                Duration.ofSeconds(5),
            )
        ideFrame.click()
        Thread.sleep(retryDelayMs / 2)

        // Press Cmd+Shift+O to open file dialog
        robot.keyboard {
            pressing(KeyEvent.VK_META) {
                pressing(KeyEvent.VK_SHIFT) {
                    key(KeyEvent.VK_O)
                }
            }
        }
        Thread.sleep(actionDelayMs)

        // Type the file path
        robot.keyboard { enterText(filePath) }
        // Wait for results to appear
        Thread.sleep(actionDelayMs)

        // Press Enter to open
        robot.keyboard { key(KeyEvent.VK_ENTER) }
        Thread.sleep(actionDelayMs)
    }

    // ── UI tree fetching ─────────────────────────────────────────────────────

    fun fetchUiTree(): List<UiComponent> = treeProvider.fetchTree()

    /**
     * Get the text content of the current editor document.
     * Uses simpler JS approach that doesn't require anonymous classes.
     */
    fun getDocumentText(): String? =
        try {
            val editor = findFocusedEditor()
            // Use simpler approach: access document directly, catch any threading errors
            val text = editor.callJs<String>(
                """
                try {
                    var doc = component.getDocument();
                    if (doc != null) {
                        doc.getText(0, doc.getLength());
                    } else {
                        null;
                    }
                } catch (e) {
                    null;
                }
                """.trimIndent(),
            )
            println("  [DEBUG] getDocumentText: returned ${text?.take(100) ?: "null"}...")
            text
        } catch (e: Exception) {
            println("  [DEBUG] Could not get document text: ${e.message}")
            null
        }

    /**
     * Check if an inline refactoring template is active (rename, extract, inline, etc).
     *
     * IntelliJ has two refactoring modes:
     * 1. Popup dialog - separate window (HeavyWeightWindow)
     * 2. Inline refactoring - variable highlighted in editor, type directly, press Enter to confirm
     *
     * The HTML tree misses inline mode entirely. We check via Remote Robot JS for:
     * 1. LookupLayeredPane (suggestions popup during inline rename)
     * 2. Editor template state - the inplace refactoring template is active
     * 3. HeavyWeightWindow popup (dialog mode)
     */
    fun hasInlineRefactoringActive(): Boolean =
        try {
            println("  [DEBUG] hasInlineRefactoringActive: Checking for inline refactoring state...")

            // Check 1: LookupLayeredPane - appears during inline rename with suggestions
            try {
                val lookupPanes = robot.findAll<ComponentFixture>(
                    byXpath("//div[contains(@class, 'LookupLayeredPane')]")
                )
                if (lookupPanes.isNotEmpty()) {
                    println("  [DEBUG] hasInlineRefactoringActive: Found LookupLayeredPane (inline suggestions)")
                    return true
                }
            } catch (_: Exception) {}

            // Check 2: Editor template state - check if inplace refactoring is active
            // This is the key check for inline rename mode (not popup dialog)
            try {
                val editor = findFocusedEditor()
                val templateActive = editor.callJs<String>(
                    """
                    try {
                        // Check for active template in editor (rename/extract inline mode)
                        var editorEx = component;

                        // Method 1: Check for TemplateState in editor
                        var templateManager = com.intellij.codeInsight.template.impl.TemplateManagerImpl.getInstance();
                        if (templateManager != null) {
                            var templateState = templateManager.getTemplateState(editorEx);
                            if (templateState != null) {
                                'template_state';
                            }
                        }

                        // Method 2: Check for InplaceRefactoring
                        var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
                        if (project != null) {
                            var fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
                            var selectedEditor = fileEditorManager.getSelectedTextEditor();
                            if (selectedEditor != null) {
                                // Check if there's an inplace refactoring happening
                                var inplaceRename = selectedEditor.getUserData(com.intellij.refactoring.rename.inplace.InplaceRefactoring.INPLACE_REFACTORING_KEY);
                                if (inplaceRename != null) {
                                    'inplace_refactoring';
                                }
                            }
                        }

                        'false';
                    } catch (e) {
                        'error:' + e.message;
                    }
                    """.trimIndent()
                )
                if (templateActive != "false" && templateActive.startsWith("error:") != true) {
                    println("  [DEBUG] hasInlineRefactoringActive: Editor template detected: $templateActive")
                    return true
                }
            } catch (e: Exception) {
                println("  [DEBUG] hasInlineRefactoringActive: Template check failed: ${e.message}")
            }

            // Check 3: HeavyWeightWindow popup (dialog mode rename)
            try {
                val popups = robot.findAll<ComponentFixture>(
                    byXpath("//div[@class='HeavyWeightWindow']")
                )
                for (popup in popups) {
                    try {
                        val y = popup.callJs<Int>("component.getLocationOnScreen().y")
                        // Popup at reasonable Y position (above main menu)
                        if (y > 50) {
                            println("  [DEBUG] hasInlineRefactoringActive: Found HeavyWeightWindow popup at y=$y")
                            return true
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            println("  [DEBUG] hasInlineRefactoringActive: No refactoring state detected")
            false
        } catch (e: Exception) {
            println("  [DEBUG] hasInlineRefactoringActive check failed: ${e.message}")
            false
        }

    /**
     * Check if the editor has a selection.
     * Uses UI tree check to avoid threading issues.
     * An active selection might indicate in-progress operation.
     */
    fun hasEditorSelection(): Boolean = hasInlineRefactoringActive()

    // ── XPath / JS escape helpers ─────────────────────────────────────────────

    /**
     * Wraps [s] in an XPath string literal.
     * Uses double-quotes when [s] contains a single quote, and concat() when it contains both.
     */
    private fun xpathLiteral(s: String): String =
        when {
            '\'' !in s -> "'$s'"
            '"' !in s -> "\"$s\""
            else -> "concat(${s.split("'").joinToString(", \"'\", ") { "'$it'" }})"
        }

    /**
     * Escapes [s] for embedding in a single-quoted JavaScript string literal.
     */
    private fun jsEscape(s: String): String =
        s
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    // ── Utility ──────────────────────────────────────────────────────────────

    fun dismissPopups() {
        repeat(3) {
            try {
                robot.keyboard { key(KeyEvent.VK_ESCAPE) }
                Thread.sleep(retryDelayMs)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Detect if a context menu popup is currently open and get its items.
     * Uses Remote Robot's fixture API directly (not HTML tree).
     * Returns list of menu item labels visible in the popup.
     */
    fun getContextMenuItems(): List<String> {
        val items = mutableListOf<String>()
        try {
            // Scope to HeavyWeightWindow so main-menu-bar ActionMenuItems are never included.
            val menuItems =
                robot.findAll<ComponentFixture>(
                    byXpath("//div[@class='HeavyWeightWindow']//div[@class='ActionMenuItem']"),
                )
            for (item in menuItems.toList()) {
                try {
                    val y = item.callJs<Int>("component.getLocationOnScreen().y")
                    if (y > 35) { // Skip any hidden items above the visible screen area
                        val text = item.callJs<String>("component.getText()") ?: ""
                        val accName = item.callJs<String>("var ctx = component.getAccessibleContext(); ctx != null ? ctx.getAccessibleName() : ''") ?: ""
                        items.add(text.ifBlank { accName })
                    }
                } catch (_: Exception) {
                }
            }

            // Also check for ActionMenu (submenus) — scoped to popup windows
            val submenus =
                robot.findAll<ComponentFixture>(
                    byXpath("//div[@class='HeavyWeightWindow']//div[@class='ActionMenu']"),
                )
            for (submenu in submenus.toList()) {
                try {
                    val y = submenu.callJs<Int>("component.getLocationOnScreen().y")
                    if (y > 35) {
                        val text = submenu.callJs<String>("component.getText()") ?: ""
                        val accName = submenu.callJs<String>("var ctx = component.getAccessibleContext(); ctx != null ? ctx.getAccessibleName() : ''") ?: ""
                        items.add(text.ifBlank { accName } + " [submenu]")
                    }
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            log.warn("  Warning: Could not get context menu items: ${e.message}")
        }
        return items.filter { it.isNotBlank() }
    }

    fun hasContextMenuOpen(): Boolean {
        return try {
            val popups =
                robot.findAll<ComponentFixture>(
                    byXpath("//div[@class='HeavyWeightWindow']"),
                )
            popups.any { popup ->
                val y = popup.callJs<Int>("component.getLocationOnScreen().y")
                y > 50 // Exclude main menu bar (y≈39)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun findFocusedEditor(): ComponentFixture {
        val editors =
            robot.findAll<ComponentFixture>(
                byXpath("//div[@class='EditorComponentImpl']"),
            )
        return editors.firstOrNull { comp ->
            try {
                comp.callJs<Boolean>("component.hasFocus()")
            } catch (_: Exception) {
                false
            }
        } ?: editors.firstOrNull()
            ?: throw IllegalStateException("No editor component found")
    }

    private fun getCaretScreenCoords(editor: ComponentFixture): ScreenCoords {
        val coordResult =
            editor.callJs<String>(
                """
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
                """.trimIndent(),
            )

        val parts = coordResult.split(",")
        return ScreenCoords(parts[0].trim().toInt(), parts[1].trim().toInt())
    }

    private fun getComponentScreenCenter(component: ComponentFixture): ScreenCoords {
        val result =
            component.callJs<String>(
                """
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
                """.trimIndent(),
            )

        val parts = result.split(",")
        return ScreenCoords(parts[0].trim().toDouble().toInt(), parts[1].trim().toDouble().toInt())
    }

    /**
     * Get the bounds (x, y, width, height) of a component.
     * Returns a Triple of (x, y, width) - height can be added if needed.
     */
    private fun getComponentBounds(component: ComponentFixture): Triple<Int, Int, Int> {
        val result =
            component.callJs<String>(
                """
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
                """.trimIndent(),
            )

        val parts = result.split(",")
        return Triple(
            parts[0].trim().toDouble().toInt(),
            parts[1].trim().toDouble().toInt(),
            parts[2].trim().toDouble().toInt(),
        )
    }

    // ── Vision-based coordinate clicking ──────────────────────────────────────

    /**
     * Click on element by numeric ID from vision overlay.
     * Uses AWT Robot for coordinate-based clicking.
     */
    fun clickElementByCoordinate(elementId: Int) {
        val elementMap = this.elementMap ?: throw IllegalStateException("elementMap not provided for coordinate clicking")
        val element = elementMap[elementId] ?: throw IllegalArgumentException("Element $elementId not found")

        val centerX = element.x + element.width / 2
        val centerY = element.y + element.height / 2

        awtRobot.mouseMove(centerX, centerY)
        Thread.sleep(100)
        awtRobot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(50)
        awtRobot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(300)
    }

    /**
     * Double-click on element by numeric ID.
     */
    fun doubleClickElementByCoordinate(elementId: Int) {
        val elementMap = this.elementMap ?: throw IllegalStateException("elementMap not provided for coordinate clicking")
        val element = elementMap[elementId] ?: throw IllegalArgumentException("Element $elementId not found")

        val centerX = element.x + element.width / 2
        val centerY = element.y + element.height / 2

        awtRobot.mouseMove(centerX, centerY)
        Thread.sleep(100)
        awtRobot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(50)
        awtRobot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(100)
        awtRobot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(50)
        awtRobot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(300)
    }

    /**
     * Right-click on element by numeric ID.
     */
    fun rightClickElementByCoordinate(elementId: Int) {
        val elementMap = this.elementMap ?: throw IllegalStateException("elementMap not provided for coordinate clicking")
        val element = elementMap[elementId] ?: throw IllegalArgumentException("Element $elementId not found")

        val centerX = element.x + element.width / 2
        val centerY = element.y + element.height / 2

        awtRobot.mouseMove(centerX, centerY)
        Thread.sleep(100)
        awtRobot.mousePress(InputEvent.BUTTON3_DOWN_MASK)
        Thread.sleep(50)
        awtRobot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK)
        Thread.sleep(300)
    }
}
