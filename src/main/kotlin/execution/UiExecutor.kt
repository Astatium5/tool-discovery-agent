package execution

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import model.RecipeStep
import perception.parser.HtmlUiTreeProvider
import perception.parser.UiComponent
import perception.parser.UiTreeProvider
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.time.Duration

/**
 * Translates RecipeStep sequences into actual RemoteRobot interactions.
 * Extracted from the hardcoded patterns in RenameRefactorTest.
 */
class UiExecutor(
    private val robot: RemoteRobot,
    private val treeProvider: UiTreeProvider = HtmlUiTreeProvider(),
) {
    private val defaultTimeout = Duration.ofSeconds(10)

    // Track the currently open file for context discovery
    var currentFilePath: String? = null

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
            // Field Navigation steps (new in declarative model)
            is RecipeStep.FocusField -> focusField(step.fieldLabel)
            is RecipeStep.SelectDropdownField -> selectDropdownField(step.fieldLabel, step.value)
            is RecipeStep.SetCheckbox -> setCheckbox(step.fieldLabel, step.checked)
            is RecipeStep.TableRowAction -> tableRowAction(step.action, step.rowIndex)
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
        Thread.sleep(300)
    }

    fun moveCaret(symbol: String) {
        val editor = findFocusedEditor()

        val offsetResult =
            editor.callJs<String>(
                """
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
                """.trimIndent(),
            )

        if (offsetResult == "NOT_FOUND") {
            throw IllegalStateException("Symbol '$symbol' not found in editor")
        }
        Thread.sleep(500)
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
        Thread.sleep(300)
    }

    fun pressShortcut(keys: String) {
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
        Thread.sleep(500)
    }

    fun openContextMenu() {
        val editor = findFocusedEditor()
        val coords = getCaretScreenCoords(editor)
        rightClickAt(coords.x, coords.y)
        Thread.sleep(800)
    }

    fun clickMenuItem(label: String) {
        val item =
            try {
                robot.find<ComponentFixture>(
                    byXpath("(//div[@class='HeavyWeightWindow'])[last()]//div[@class='ActionMenuItem' and contains(@text, '$label')]"),
                    Duration.ofSeconds(5),
                )
            } catch (_: Exception) {
                try {
                    robot.find<ComponentFixture>(
                        byXpath("(//div[@class='HeavyWeightWindow'])[last()]//div[@class='ActionMenu' and contains(@text, '$label')]"),
                        Duration.ofSeconds(3),
                    )
                } catch (_: Exception) {
                    robot.find<ComponentFixture>(
                        byXpath("//div[contains(@accessiblename, '$label') and (@class='ActionMenuItem' or @class='ActionMenu')]"),
                        Duration.ofSeconds(3),
                    )
                }
            }

        val coords = getComponentScreenCenter(item)
        clickAt(coords.x, coords.y)
        Thread.sleep(600)
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
                println("  No dropdown found, skipping selection")
                return
            }

        // Click to open dropdown
        dropdown.click()
        Thread.sleep(300)

        // Try to find and click the option with matching text
        try {
            val option =
                robot.find<ComponentFixture>(
                    byXpath("//div[@visible_text='$value' or @accessiblename='$value']"),
                    Duration.ofSeconds(2),
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
        val button =
            try {
                robot.find<ComponentFixture>(
                    byXpath("//div[@accessiblename='$label' and @class='JButton']"),
                    Duration.ofSeconds(5),
                )
            } catch (_: Exception) {
                robot.find<ComponentFixture>(
                    byXpath("//div[@accessiblename='$label' and @class!='ActionMenu' and @class!='ActionMenuItem']"),
                    Duration.ofSeconds(5),
                )
            }

        val coords = getComponentScreenCenter(button)
        clickAt(coords.x, coords.y)
        Thread.sleep(800)
    }

    fun pressEscape() {
        robot.keyboard { key(KeyEvent.VK_ESCAPE) }
        Thread.sleep(300)
    }

    /**
     * Presses a specific key by name. Supports: Enter, Escape, Tab, Backspace, Delete, etc.
     */
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
        Thread.sleep(300)
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
                        "$container//div[($inputFieldClasses) and (contains(@accessiblename, '$normalizedLabel') or contains(@accessiblename, '$fieldLabel'))]",
                    ),
                    timeout,
                )
            field.click()
            Thread.sleep(200)
            println("  Found field by accessible name: $fieldLabel")
            return
        } catch (_: Exception) {
        }

        // Strategy 2: Find label, then find the input field that follows it
        // In IntelliJ, labels and fields are siblings in the layout
        try {
            // Find the label first
            val label =
                robot.find<ComponentFixture>(
                    byXpath(
                        "$container//div[@visible_text='$fieldLabel' or contains(@text, '$fieldLabel') or @visible_text='$normalizedLabel' or contains(@text, '$normalizedLabel')]",
                    ),
                    timeout,
                )

            // Get the label's bounds to find adjacent input field
            val labelBounds = getComponentBounds(label)
            val labelY = labelBounds.second
            val labelRight = labelBounds.first + labelBounds.third

            // Find all input fields in the container (text fields, dropdowns, checkboxes)
            val allFields =
                robot.findAll<ComponentFixture>(
                    byXpath("$container//div[$inputFieldClasses]"),
                )

            // Find the input field that is closest to the label (same row or next row)
            val closestField =
                allFields.minByOrNull { field ->
                    val fieldBounds = getComponentBounds(field)
                    // Calculate distance: prefer fields on the same row, to the right of the label
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
                Thread.sleep(200)
                println("  Found field adjacent to label: $fieldLabel")
                return
            }
        } catch (_: Exception) {
        }

        // Strategy 3: Find any input field with the label text in its value or placeholder
        try {
            val field =
                robot.find<ComponentFixture>(
                    byXpath(
                        "$container//div[($inputFieldClasses) and (contains(@text, '$normalizedLabel') or contains(@placeholder, '$normalizedLabel'))]",
                    ),
                    timeout,
                )
            field.click()
            Thread.sleep(200)
            println("  Found field by text content: $fieldLabel")
            return
        } catch (_: Exception) {
        }

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
    fun selectDropdownField(
        fieldLabel: String,
        value: String,
    ) {
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
                        "$container//div[@class='JCheckBox' and (@visible_text='$fieldLabel' or contains(@text, '$fieldLabel') or contains(@accessiblename, '$fieldLabel'))]",
                    ),
                    timeout,
                )
            // Check current state and toggle if needed
            val currentState = checkbox.callJs<Boolean>("component.isSelected()")
            if (currentState != checked) {
                checkbox.click()
                Thread.sleep(200)
            }
            return
        } catch (_: Exception) {
        }

        // Strategy 2: Find by accessible name
        try {
            val checkbox =
                robot.find<ComponentFixture>(
                    byXpath("$container//div[@class='JCheckBox' and contains(@accessiblename, '$fieldLabel')]"),
                    timeout,
                )
            val currentState = checkbox.callJs<Boolean>("component.isSelected()")
            if (currentState != checked) {
                checkbox.click()
                Thread.sleep(200)
            }
            return
        } catch (_: Exception) {
        }

        println("  Could not find checkbox '$fieldLabel'")
    }

    /**
     * Perform an action on a table editor (Add, Remove, Up, Down).
     *
     * @param action The action to perform (e.g., "Add", "Remove", "Up", "Down")
     * @param rowIndex Optional row index for row-specific actions
     */
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
                // Select the row
                table.callJs<String>("component.setRowSelectionInterval($rowIndex, $rowIndex)")
                Thread.sleep(200)
            } catch (_: Exception) {
                println("  Could not find table to select row $rowIndex")
            }
        }

        // Find and click the action button
        try {
            val button =
                robot.find<ComponentFixture>(
                    byXpath("$container//div[@class='JButton' and (@visible_text='$action' or @accessiblename='$action')]"),
                    timeout,
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
        robot.keyboard { enterText(text) }
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
        val ideFrame =
            robot.find<ComponentFixture>(
                byXpath("//div[@class='IdeFrameImpl']"),
                Duration.ofSeconds(5),
            )
        ideFrame.click()
        Thread.sleep(200)

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

        val file =
            if (resolved.toFile().exists()) {
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
    fun readFileLines(
        relativePath: String,
        startLine: Int,
        endLine: Int,
    ): List<LineWithNumber> {
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
    fun findSymbolInFile(
        relativePath: String,
        symbol: String,
    ): List<Int> {
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
            val editor = findFocusedEditor()
            editor.callJs("component.getText()")
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
            } catch (_: Exception) {
            }
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

    private fun rightClickAt(
        x: Int,
        y: Int,
    ) {
        val awtRobot = java.awt.Robot()
        awtRobot.mouseMove(x, y)
        Thread.sleep(100)
        awtRobot.mousePress(InputEvent.BUTTON3_DOWN_MASK)
        Thread.sleep(100)
        awtRobot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK)
    }

    private fun clickAt(
        x: Int,
        y: Int,
    ) {
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
                "cmd", "meta", "command" -> KeyEvent.VK_META
                "ctrl", "control" -> KeyEvent.VK_CONTROL
                "alt", "option" -> KeyEvent.VK_ALT
                "shift" -> KeyEvent.VK_SHIFT
                "enter", "return" -> KeyEvent.VK_ENTER
                "escape", "esc" -> KeyEvent.VK_ESCAPE
                "tab" -> KeyEvent.VK_TAB
                "delete", "backspace" -> KeyEvent.VK_BACK_SPACE
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
                else -> KeyEvent.getExtendedKeyCodeForChar(part.trim().first().code)
            }
        }
    }
}
