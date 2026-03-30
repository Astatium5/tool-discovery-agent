package executor

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import model.RecipeStep
import parser.HtmlUiTreeProvider
import parser.UiTreeProvider
import parser.UiComponent
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.time.Duration

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
        val item = try {
            robot.find<ComponentFixture>(
                byXpath("(//div[@class='HeavyWeightWindow'])[last()]//div[@class='ActionMenuItem' and contains(@text, '$label')]"),
                Duration.ofSeconds(5)
            )
        } catch (_: Exception) {
            try {
                robot.find<ComponentFixture>(
                    byXpath("(//div[@class='HeavyWeightWindow'])[last()]//div[@class='ActionMenu' and contains(@text, '$label')]"),
                    Duration.ofSeconds(3)
                )
            } catch (_: Exception) {
                robot.find<ComponentFixture>(
                    byXpath("//div[contains(@accessiblename, '$label') and (@class='ActionMenuItem' or @class='ActionMenu')]"),
                    Duration.ofSeconds(3)
                )
            }
        }

        val coords = getComponentScreenCenter(item)
        clickAt(coords.x, coords.y)
        Thread.sleep(600)
    }

    fun typeInDialog(value: String, clearFirst: Boolean = true) {
        // Brief wait for popup/inline widget to render after a menu click
        Thread.sleep(500)

        // IMPORTANT: Must use findAll and check each one, because find() returns the FIRST match,
        // not the focused one. Dialogs can have multiple EditorComponentImpl fields.
        val focusedField = try {
            val allFields = robot.findAll<ComponentFixture>(
                byXpath("//div[@class='EditorComponentImpl' or @class='JTextField' or @class='JBTextField']")
            )
            allFields.firstOrNull { field ->
                try { field.callJs<Boolean>("component.hasFocus()") } catch (_: Exception) { false }
            }
        } catch (_: Exception) { null }

        if (focusedField != null) {
            if (clearFirst) {
                clearFieldWithJs(focusedField)
                Thread.sleep(100)
            }
            robot.keyboard { enterText(value) }
        } else {
            val (field, isInlineEditor) = findInputField()

            if (!isInlineEditor) {
                field.click()
                Thread.sleep(100)

                if (clearFirst) {
                    clearFieldWithJs(field)
                    Thread.sleep(100)
                }
            }
            // For inline rename/extract templates the cursor is already inside the
            // highlighted template field with the old name selected — just type.

            robot.keyboard { enterText(value) }
        }
        Thread.sleep(300)
        
        // Dismiss any autocomplete/lookup popup that may have appeared after typing
        dismissAutocompletePopup()
    }
    
    /**
     * Dismiss autocomplete/lookup popups that appear after typing in editor fields.
     * These popups (LookupLayeredPane) can block subsequent UI interactions.
     */
    private fun dismissAutocompletePopup() {
        try {
            println("  Dismissing autocomplete popup (Escape)")
            robot.keyboard { key(KeyEvent.VK_ESCAPE) }
            Thread.sleep(200)
        } catch (e: Exception) {
            println("  Warning: Failed to dismiss autocomplete popup: ${e.message}")
        }
    }

    /**
     * Clear a text field using JavaScript.
     *
     * Threading context (confirmed by log):
     *   callJs runs on RemoteRobot's eventLoopGroupProxy thread — NOT the EDT.
     *
     * Because we are off the EDT:
     *   - invokeAndWait is safe to call (no deadlock risk).
     *   - But bare model access (getSelectionModel, getTextLength, getText) still
     *     throws "Access is allowed from EDT only" — so we must go through the
     *     correct threading primitive for each operation.
     *
     * Strategy per component type:
     *   - EditorComponentImpl / EditorTextField:
     *       WriteCommandAction.runWriteCommandAction acquires the write-lock and
     *       dispatches to the EDT internally. Safe to call from any thread.
     *       Do NOT use selection + VK_DELETE: getSelectionModel() itself throws
     *       before any selection happens, so VK_DELETE then deletes nothing and
     *       enterText() appends to the stale text.
     *   - JTextField / JBTextField:
     *       invokeAndWait { setText('') } — EDT-safe, no deadlock from non-EDT thread.
     *   - Fallback:
     *       RemoteRobot keyboard select-all (Ctrl+A / Meta+A) + Delete.
     */
    private fun clearFieldWithJs(field: ComponentFixture) {
        // Skip JavaScript entirely - it causes EDT threading issues and timeouts.
        // RemoteRobot's keyboard injects directly into Swing EventQueue, which is reliable.
        println("  clearFieldWithJs: using RemoteRobot keyboard (Ctrl+A + Delete)")
        selectAllAndDelete()
    }

    /**
     * Select-all + delete via RemoteRobot keyboard (Swing EventQueue injection).
     * Tries Ctrl+A (Windows/Linux) then Meta+A (macOS).
     */
    private fun selectAllAndDelete() {
//        robot.keyboard {
//            pressing(KeyEvent.VK_CONTROL) { key(KeyEvent.VK_A) }
//        }
//        Thread.sleep(100)
//        robot.keyboard { key(KeyEvent.VK_DELETE) }
//        Thread.sleep(100)

        // Meta+A — macOS (no-op if Ctrl+A already cleared the field)
        robot.keyboard {
            pressing(KeyEvent.VK_META) { key(KeyEvent.VK_A) }
        }
        Thread.sleep(100)
        robot.keyboard { key(KeyEvent.VK_DELETE) }
        Thread.sleep(100)
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
        val dropdown = try {
            robot.find<ComponentFixture>(
                byXpath("(//div[@class='HeavyWeightWindow' or @class='DialogRootPane'])[last()]//div[contains(@class, 'Combo') or contains(@class, 'combo') or contains(@class, 'Dropdown') or contains(@class, 'dropdown') or contains(@class, 'Select') or contains(@class, 'select')]"),
                Duration.ofSeconds(5)
            )
        } catch (_: Exception) {
            println("  No dropdown found, skipping selection")
            return
        }

        dropdown.click()
        Thread.sleep(300)

        try {
            val option = robot.find<ComponentFixture>(
                byXpath("//div[@visible_text='$value' or @accessiblename='$value']"),
                Duration.ofSeconds(2)
            )
            option.click()
        } catch (_: Exception) {
            robot.keyboard { enterText(value) }
            Thread.sleep(200)
            robot.keyboard { key(KeyEvent.VK_ENTER) }
        }
        Thread.sleep(300)
    }

    fun clickDialogButton(label: String) {
        println("  [clickDialogButton] Starting search for button '$label'...")
        
        // Strategy 1: Find by text attribute (most reliable, like clickMenuItem)
        println("  [clickDialogButton] Strategy 1: Searching by @text attribute...")
        val button = try {
            val found = robot.find<ComponentFixture>(
                byXpath("//div[@class='JButton' and contains(@text, '$label')]"),
                Duration.ofSeconds(3)
            )
            println("  [clickDialogButton] Strategy 1 SUCCESS: Found button by @text")
            found
        } catch (e1: Exception) {
            println("  [clickDialogButton] Strategy 1 FAILED: ${e1.message}")
            
            // Strategy 2: Find by accessible name
            println("  [clickDialogButton] Strategy 2: Searching by @accessiblename attribute...")
            try {
                val found = robot.find<ComponentFixture>(
                    byXpath("//div[@accessiblename='$label' and @class='JButton']"),
                    Duration.ofSeconds(3)
                )
                println("  [clickDialogButton] Strategy 2 SUCCESS: Found button by @accessiblename")
                found
            } catch (e2: Exception) {
                println("  [clickDialogButton] Strategy 2 FAILED: ${e2.message}")
                
                // Strategy 3: Find by visible_text
                println("  [clickDialogButton] Strategy 3: Searching by @visible_text attribute...")
                try {
                    val found = robot.find<ComponentFixture>(
                        byXpath("//div[@visible_text='$label' and @class='JButton']"),
                        Duration.ofSeconds(3)
                    )
                    println("  [clickDialogButton] Strategy 3 SUCCESS: Found button by @visible_text")
                    found
                } catch (e3: Exception) {
                    println("  [clickDialogButton] Strategy 3 FAILED: ${e3.message}")
                    println("  [clickDialogButton] ALL STRATEGIES FAILED - throwing exception")
                    throw e3
                }
            }
        }

        println("  [clickDialogButton] Button found, getting screen coordinates...")
        val coords = getComponentScreenCenter(button)
        println("  [clickDialogButton] Button center: (${coords.x}, ${coords.y})")
        
        println("  [clickDialogButton] Clicking at (${coords.x}, ${coords.y})...")
        clickAt(coords.x, coords.y)
        println("  [clickDialogButton] Click completed, waiting 800ms...")
        Thread.sleep(800)
        println("  [clickDialogButton] Done")
    }

    fun pressEscape() {
        robot.keyboard { key(KeyEvent.VK_ESCAPE) }
        Thread.sleep(300)
    }

    fun pressKey(keyName: String) {
        val keyCode = when (keyName.lowercase()) {
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
            else -> KeyEvent.VK_ENTER
        }
        robot.keyboard { key(keyCode) }
        Thread.sleep(300)
    }

    // ── AWT Robot (bypasses RemoteRobot HTTP layer) ─────────────────────────────

    /**
     * AWT Robot for direct keyboard input that bypasses RemoteRobot's HTTP layer.
     * Use this when RemoteRobot times out due to IDE being busy (e.g., signature preview computation).
     */
    private val awtRobot: Robot by lazy {
        Robot().apply { autoDelay = 50 }
    }

    /**
     * Press a key directly using AWT Robot.
     * This bypasses RemoteRobot's HTTP layer and works even when the IDE is busy.
     */
    fun pressKeyDirect(keyName: String) {
        println("  pressKeyDirect: Pressing '$keyName' via AWT Robot")
        val keyCodes = parseShortcutKeys(keyName)
        for (keyCode in keyCodes) {
            awtRobot.keyPress(keyCode)
        }
        // Release in reverse order
        for (keyCode in keyCodes.reversed()) {
            awtRobot.keyRelease(keyCode)
        }
        Thread.sleep(300)
    }

    // ── Field Navigation steps ────────────────────────────────────────────────

    fun focusField(fieldLabel: String) {
        val container = "(//div[@class='HeavyWeightWindow' or @class='DialogRootPane'])[last()]"
        val timeout = Duration.ofSeconds(3)

        val normalizedLabel = fieldLabel.trimEnd(':').trim()
        val inputFieldClasses = "@class='JTextField' or @class='JBTextField' or @class='EditorComponentImpl' or contains(@class, 'TextField') or @class='JComboBox' or contains(@class, 'ComboBox') or @class='JCheckBox'"

        // Strategy 1: accessible name
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

        // Strategy 2: find label, locate nearest input field
        try {
            val label = robot.find<ComponentFixture>(
                byXpath("$container//div[@visible_text='$fieldLabel' or contains(@text, '$fieldLabel') or @visible_text='$normalizedLabel' or contains(@text, '$normalizedLabel')]"),
                timeout
            )

            val labelBounds = getComponentBounds(label)
            val labelY = labelBounds.second
            val labelRight = labelBounds.first + labelBounds.third

            val allFields = robot.findAll<ComponentFixture>(
                byXpath("$container//div[$inputFieldClasses]")
            )

            val closestField = allFields.minByOrNull { field ->
                val fieldBounds = getComponentBounds(field)
                val verticalDistance = kotlin.math.abs(fieldBounds.second - labelY)
                val horizontalDistance = if (fieldBounds.first >= labelRight) {
                    fieldBounds.first - labelRight
                } else {
                    1000 + (labelRight - fieldBounds.first)
                }
                verticalDistance * 10 + horizontalDistance
            }

            if (closestField != null) {
                closestField.click()
                Thread.sleep(200)
                println("  Found field adjacent to label: $fieldLabel")
                return
            }
        } catch (_: Exception) { }

        // Strategy 3: placeholder / text content
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

        println("  Could not find field '$fieldLabel', using Tab navigation")
        robot.keyboard { key(KeyEvent.VK_TAB) }
        Thread.sleep(200)
    }

    fun selectDropdownField(fieldLabel: String, value: String) {
        focusField(fieldLabel)
        Thread.sleep(100)
        selectDropdown(value)
    }

    fun setCheckbox(fieldLabel: String, checked: Boolean) {
        val container = "(//div[@class='HeavyWeightWindow' or @class='DialogRootPane'])[last()]"
        val timeout = Duration.ofSeconds(3)

        try {
            val checkbox = robot.find<ComponentFixture>(
                byXpath("$container//div[@class='JCheckBox' and (@visible_text='$fieldLabel' or contains(@text, '$fieldLabel') or contains(@accessiblename, '$fieldLabel'))]"),
                timeout
            )
            val currentState = checkbox.callJs<Boolean>("component.isSelected()")
            if (currentState != checked) {
                checkbox.click()
                Thread.sleep(200)
            }
            return
        } catch (_: Exception) { }

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

    fun tableRowAction(action: String, rowIndex: Int?) {
        val container = "(//div[@class='HeavyWeightWindow' or @class='DialogRootPane'])[last()]"
        val timeout = Duration.ofSeconds(3)

        if (rowIndex != null) {
            try {
                val table = robot.find<ComponentFixture>(
                    byXpath("$container//div[@class='JBTable' or @class='JTable']"),
                    timeout
                )
                table.callJs<String>("component.setRowSelectionInterval($rowIndex, $rowIndex)")
                Thread.sleep(200)
            } catch (_: Exception) {
                println("  Could not find table to select row $rowIndex")
            }
        }

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

    fun typeText(text: String) {
        robot.keyboard { enterText(text) }
        Thread.sleep(200)
    }

    fun openFile(filePath: String) {
        currentFilePath = filePath

        val ideFrame = robot.find<ComponentFixture>(
            byXpath("//div[@class='IdeFrameImpl']"),
            Duration.ofSeconds(5)
        )
        ideFrame.click()
        Thread.sleep(200)

        robot.keyboard {
            pressing(KeyEvent.VK_META) {
                pressing(KeyEvent.VK_SHIFT) {
                    key(KeyEvent.VK_O)
                }
            }
        }
        Thread.sleep(1000)

        robot.keyboard { enterText(filePath) }
        Thread.sleep(1000)

        robot.keyboard { key(KeyEvent.VK_ENTER) }
        Thread.sleep(1000)
    }

    data class LineWithNumber(val lineNumber: Int, val content: String)

    fun readFileWithLineNumbers(relativePath: String): List<LineWithNumber> {
        val projectPath = getProjectRoot()
        val resolved = projectPath.resolve(relativePath)

        val file = if (resolved.toFile().exists()) {
            resolved.toFile()
        } else {
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

    fun readFileLines(relativePath: String, startLine: Int, endLine: Int): List<LineWithNumber> {
        return readFileWithLineNumbers(relativePath)
            .filter { it.lineNumber in startLine..endLine }
    }

    fun findSymbolInFile(relativePath: String, symbol: String): List<Int> {
        return readFileWithLineNumbers(relativePath)
            .filter { it.content.contains(symbol) }
            .map { it.lineNumber }
    }

    private fun getProjectRoot(): java.nio.file.Path {
        return java.nio.file.Paths.get("").toAbsolutePath()
    }

    fun getSimpleFileContext(relativePath: String): SimpleFileContext {
        val lines = readFileWithLineNumbers(relativePath)
        if (lines.isEmpty()) return SimpleFileContext(0, emptyList(), emptyList())

        val methodNames = mutableListOf<String>()
        val variableNames = mutableListOf<String>()

        val methodRegex = Regex("""fun\s+(\w+)""")
        for (line in lines) {
            methodRegex.findAll(line.content).forEach { match ->
                methodNames.add(match.groupValues[1])
            }
        }

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

    fun analyzeFileStructure(): FileStructure? {
        val editor = try { findFocusedEditor() } catch (_: Exception) { return null }

        val result = try {
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

                    var classDepth = 0;
                    var inClass = false;
                    for (var i = 0; i < lines.length; i++) {
                        var trimmed = lines[i].trim();
                        if (trimmed.startsWith('import ') || trimmed.startsWith('package ')) {
                            importEnd = i + 1;
                        }
                        if (!inClass) {
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

                    for (var i = 0; i < lines.length; i++) {
                        var trimmed = lines[i].trim();
                        if (trimmed.indexOf('fun') >= 0 && trimmed.indexOf('(') >= 0) {
                            var funMatch = trimmed.match(/fun\s+(\w+)/);
                            if (funMatch) {
                                methodNames.push(funMatch[1]);
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
                                    methodBodies.push(bodyStartLine + ',' + (bodyStartLine + 5));
                                }
                            }
                        }
                    }

                    for (var i = 0; i < lines.length; i++) {
                        var lineNum = i + 1;
                        var trimmed = lines[i].trim();
                        if (trimmed.length == 0) continue;

                        var isComment = trimmed.startsWith('//') || trimmed.startsWith('/*') || trimmed.startsWith('*') || trimmed.startsWith('/**');
                        var isImport = trimmed.startsWith('import ') || trimmed.startsWith('package ');
                        var isStructural = trimmed == '{' || trimmed == '}' || trimmed == ')';
                        var isAnnotation = trimmed.startsWith('@');
                        var isDecl = trimmed.match(/^(class|object|interface|fun|abstract|open|data)\s/);

                        if (!isComment && !isImport && !isStructural && !isAnnotation && !isDecl) {
                            statementLines.push(lineNum);
                        }
                        if (trimmed.match(/^(val|var)\s+\w+/)) {
                            if (statementLines.indexOf(lineNum) < 0) statementLines.push(lineNum);
                            var varMatch = trimmed.match(/^(val|var)\s+(\w+)/);
                            if (varMatch) variableNames.push(varMatch[2]);
                        }

                        if (isComment || isImport) continue;

                        var strLit = trimmed.match(/".[^"]*"/);
                        if (strLit) literalLines.push(lineNum);
                        var numMatch = trimmed.match(/[\s=\(,]\s*(\d+)/);
                        if (numMatch && !trimmed.match(/^(fun |class )/)) literalLines.push(lineNum);

                        var assignMatch = trimmed.match(/(val|var)\s+\w+\s*=\s*(.+)/);
                        if (assignMatch) {
                            var rhs = assignMatch[2].trim();
                            if (rhs.length > 2) expressionTargets.push(lineNum + ':' + rhs.substring(0, Math.min(rhs.length, 40)));
                        }
                    }

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

    fun getDocumentText(): String? {
        return try {
            val editor = findFocusedEditor()
            editor.callJs("component.getDocument().getText()")
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