package execution

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import model.RecipeStep
import perception.parser.HtmlUiTreeProvider
import perception.parser.UiComponent
import perception.parser.UiTreeParser
import perception.parser.UiTreeProvider
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
    private val treeProvider: UiTreeProvider = HtmlUiTreeProvider(),
) {
    private val defaultTimeout = Duration.ofSeconds(10)

    /**
     * Host OS detected once at class load time. Used to pick between
     * `Cmd+A` (macOS) and `Ctrl+A` (everywhere else) for select-all, so
     * `clearFirst` works on Windows/Linux CI runners too — the previous
     * implementation hard-coded `Meta+A` which is a no-op off macOS.
     */
    private val isMacOs: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().let {
            it.contains("mac") || it.contains("darwin")
        }

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

    /**
     * Outcome of a [moveCaret] call.
     *
     * [alreadyOnSymbol] is true when the caret was already INSIDE one of the
     * matches before we called it — i.e. the move was a no-op. Callers
     * (typically the agent loop) should surface this as a distinct signal
     * so the LLM stops trying to re-navigate to a spot it's already on and
     * instead moves on to the next step (OpenContextMenu, Refactor, etc.).
     *
     * [line] / [column] are 1-based, [totalOccurrences] is the number of
     * textual occurrences of the symbol in the document (informational).
     */
    data class MoveCaretOutcome(
        val line: Int,
        val column: Int,
        val totalOccurrences: Int,
        val alreadyOnSymbol: Boolean,
    )

    fun moveCaret(symbol: String): MoveCaretOutcome {
        val editor = findFocusedEditor()

        // Pull everything we need in ONE JS round-trip:
        //   "NOT_FOUND" | "<line>|<col>|<total>|<alreadyOn>"
        //
        // We leave the caret on the FIRST textual occurrence — which, for
        // IntelliJ refactorings (Rename, Change Signature, etc.), is fine:
        // those commands resolve from a call site to the declaration
        // internally. The agent does NOT need to land on the declaration.
        val safeSym = jsStringLiteral(symbol)
        val payload =
            editor.callJs<String>(
                """
                var doc = component.getDocument();
                var fullText = doc.getText(0, doc.getLength());
                var sym = $safeSym;
                var idx = fullText.indexOf(sym);
                if (idx < 0) {
                    'NOT_FOUND';
                } else {
                    var project = com.intellij.openapi.project.ProjectManager
                        .getInstance().getOpenProjects()[0];
                    var editorEx = com.intellij.openapi.fileEditor.FileEditorManager
                        .getInstance(project).getSelectedTextEditor();
                    var caretOffset = editorEx != null
                        ? editorEx.getCaretModel().getOffset()
                        : -1;

                    // Count occurrences (informational only).
                    var total = 0;
                    var scan = 0;
                    while (true) {
                        var j = fullText.indexOf(sym, scan);
                        if (j < 0) break;
                        total++;
                        scan = j + sym.length;
                    }

                    // Is the caret already INSIDE any match? If so, do not
                    // move it — report that it was already on the symbol.
                    var alreadyOn = false;
                    if (caretOffset >= 0) {
                        var s = 0;
                        while (true) {
                            var k = fullText.indexOf(sym, s);
                            if (k < 0) break;
                            if (caretOffset >= k && caretOffset <= k + sym.length) {
                                alreadyOn = true;
                                break;
                            }
                            s = k + sym.length;
                        }
                    }

                    var targetOffset = alreadyOn
                        ? caretOffset
                        : (idx + Math.floor(sym.length / 2));

                    if (!alreadyOn) {
                        com.intellij.openapi.application.ApplicationManager
                            .getApplication().invokeAndWait(new Runnable() {
                                run: function() {
                                    if (editorEx != null) {
                                        editorEx.getCaretModel().moveToOffset(targetOffset);
                                        editorEx.getScrollingModel().scrollToCaret(
                                            com.intellij.openapi.editor.ScrollType.CENTER
                                        );
                                    }
                                }
                            });
                    }

                    var line = 0, col = 0;
                    if (editorEx != null) {
                        var lp = editorEx.offsetToLogicalPosition(targetOffset);
                        line = lp.line + 1;
                        col = lp.column + 1;
                    }
                    '' + line + '|' + col + '|' + total + '|' + (alreadyOn ? 'true' : 'false');
                }
                """.trimIndent(),
            )

        if (payload == "NOT_FOUND") {
            throw IllegalStateException("Symbol '$symbol' not found in editor")
        }

        Thread.sleep(500)

        val parts = payload.split("|")
        return MoveCaretOutcome(
            line = parts.getOrNull(0)?.toIntOrNull() ?: 0,
            column = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            totalOccurrences = parts.getOrNull(2)?.toIntOrNull() ?: 1,
            alreadyOnSymbol = parts.getOrNull(3) == "true",
        )
    }

    /**
     * Produce a JS string literal for [s] that is safe to embed inside a
     * template-interpolated JS snippet. Handles embedded single-quotes,
     * backslashes and newlines. Rhino accepts single-quoted strings.
     */
    private fun jsStringLiteral(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('\'')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(c)
            }
        }
        sb.append('\'')
        return sb.toString()
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

    /**
     * Close every open popup / dialog / transient window by pressing Escape
     * repeatedly until the stack stops shrinking or the cap is hit.
     *
     * Unlike a single `pressEscape()`, this is a safe recovery primitive for
     * the agent when it finds itself inside a deeply stacked menu (the
     * 10-HeavyWeightWindow situation we saw after Refactor submenus).
     */
    fun closeAllPopups(maxAttempts: Int = 12) {
        // Escape needs some time for IntelliJ to tear down the popup —
        // especially stacked Refactor submenus where the host HeavyWeightWindow
        // lingers briefly after the menu is gone. We also tolerate up to
        // [STALL_ALLOWANCE] consecutive "no reduction" attempts before giving
        // up, because a single Escape sometimes closes an inner menu while
        // the outer host survives the next poll.
        val stallAllowance = 2
        var stalls = 0
        var lastCount = -1
        repeat(maxAttempts) { attempt ->
            val before = runCatching { fetchUiTree() }.getOrNull() ?: emptyList()
            val beforeCount = countTransientWindows(before)
            if (beforeCount == 0) {
                println("  closeAllPopups: no popups/dialogs open (after ${attempt} Escapes)")
                return
            }
            try {
                robot.keyboard { key(KeyEvent.VK_ESCAPE) }
            } catch (_: Exception) {
                // keep going; transient failures shouldn't abort the drain
            }
            Thread.sleep(350)
            val after = runCatching { fetchUiTree() }.getOrNull() ?: emptyList()
            val afterCount = countTransientWindows(after)
            if (afterCount < beforeCount) {
                stalls = 0
            } else {
                stalls++
                if (stalls > stallAllowance) {
                    println(
                        "  closeAllPopups: stalled at $afterCount transient windows " +
                            "after ${attempt + 1} Escapes (last=$lastCount)",
                    )
                    return
                }
            }
            lastCount = afterCount
        }
    }

    /**
     * Count distinct transient windows (popups + dialogs) in a raw UI tree,
     * without double-counting a HeavyWeightWindow whose sole purpose is to
     * host a single JBPopupMenu / *Popup / JDialog. IntelliJ's hit-test tree
     * wraps EVERY popup in a HeavyWeightWindow, so the naive "count both"
     * gives us 2 nodes per logical popup — which made the Refactor-submenu
     * state look like 10 popups when it was really 2 (context menu + submenu).
     */
    private fun countTransientWindows(roots: List<UiComponent>): Int {
        fun isTransientContent(n: UiComponent): Boolean =
            n.cls == "JDialog" ||
                n.cls == "DialogRootPane" ||
                n.cls == "JBPopupMenu" ||
                n.cls.endsWith("Popup")

        fun hasTransientContent(n: UiComponent): Boolean {
            if (isTransientContent(n)) return true
            for (c in n.children) if (hasTransientContent(c)) return true
            return false
        }

        fun walk(n: UiComponent): Int {
            // HeavyWeightWindow: a pure host. Count it ONLY when it doesn't
            // already contain a transient-content node; otherwise its child
            // will be counted and we'd double-bill.
            val selfIsHost = n.cls == "HeavyWeightWindow"
            val descendantIsTransient = hasTransientContent(n)
            val self =
                when {
                    selfIsHost && descendantIsTransient -> 0
                    selfIsHost -> 1
                    isTransientContent(n) -> 1
                    else -> 0
                }
            return self + n.children.sumOf { walk(it) }
        }

        return roots.sumOf { walk(it) }
    }

    fun clickMenuItem(label: String) {
        // Menu items in IntelliJ render with varying text shapes:
        //   - "Rename..."           (ASCII ellipsis)
        //   - "Rename\u2026"        (Unicode ellipsis, rarely but happens)
        //   - "Rename... ⇧F6"       (label + shortcut hint in same @text)
        //   - accessiblename usually has no shortcut but may or may not
        //     include the ellipsis.
        // We try a cascade of XPaths matching the *label core* (without the
        // trailing '…' or '...') against both @text and @accessiblename, and
        // against ActionMenuItem / ActionMenu / JMenuItem / JMenu / *MenuItem.
        val core =
            label
                .removeSuffix("\u2026")
                .removeSuffix("...")
                .trim()
        // Quote the string for XPath safely — XPath 1.0 has no built-in
        // escape, so if the label contains a single quote we concat().
        val quoted = xpathQuote(core)

        println("  [clickMenuItem] label='$label' core='$core'")

        val menuClassSelector =
            "(@class='ActionMenuItem' or @class='ActionMenu' or " +
                "@class='JMenuItem' or @class='JMenu' or " +
                "@class='CheckboxMenuItem' or @class='RadioButtonMenuItem' or " +
                "contains(@class,'MenuItem'))"

        val strategies =
            listOf(
                "(//div[@class='HeavyWeightWindow'])[last()]//div[$menuClassSelector and contains(@text, $quoted)]",
                "(//div[@class='HeavyWeightWindow'])[last()]//div[$menuClassSelector and contains(@accessiblename, $quoted)]",
                "(//div[@class='HeavyWeightWindow'])[last()]//div[$menuClassSelector and contains(@visible_text, $quoted)]",
                "//div[$menuClassSelector and contains(@text, $quoted)]",
                "//div[$menuClassSelector and contains(@accessiblename, $quoted)]",
                "//div[$menuClassSelector and contains(@visible_text, $quoted)]",
            )

        var item: ComponentFixture? = null
        var lastError: Exception? = null
        for ((i, xp) in strategies.withIndex()) {
            try {
                item = robot.find<ComponentFixture>(byXpath(xp), Duration.ofSeconds(3))
                println("  [clickMenuItem] strategy ${i + 1} matched")
                break
            } catch (e: Exception) {
                lastError = e
            }
        }

        if (item == null) {
            println("  [clickMenuItem] ALL strategies failed for '$label'")
            throw lastError ?: IllegalStateException("Menu item '$label' not found")
        }

        val coords = getComponentScreenCenter(item)
        clickAt(coords.x, coords.y)
        Thread.sleep(600)
    }

    /** XPath 1.0-safe literal wrap for a string that may contain single quotes. */
    private fun xpathQuote(s: String): String {
        if (!s.contains('\'')) return "'$s'"
        if (!s.contains('"')) return "\"$s\""
        // Contains both — build concat('a', "'", 'b', ...)
        val parts = s.split('\'')
        return buildString {
            append("concat(")
            parts.forEachIndexed { i, part ->
                if (i > 0) append(", \"'\", ")
                append('\'').append(part).append('\'')
            }
            append(")")
        }
    }

    fun typeInDialog(
        value: String,
        clearFirst: Boolean = true,
    ) {
        // Brief wait for popup/inline widget to render after a menu click
        Thread.sleep(500)

        // IMPORTANT: Must use findAll and check each one, because find() returns the FIRST match,
        // not the focused one. Dialogs can have multiple EditorComponentImpl fields.
        val focusedField =
            try {
                val allFields =
                    robot.findAll<ComponentFixture>(
                        byXpath("//div[@class='EditorComponentImpl' or @class='JTextField' or @class='JBTextField']"),
                    )
                allFields.firstOrNull { field ->
                    try {
                        field.callJs<Boolean>("component.hasFocus()")
                    } catch (_: Exception) {
                        false
                    }
                }
            } catch (_: Exception) {
                null
            }

        val focusedIsEditorComponent =
            focusedField?.let { runCatching { it.callJs<String>("component.getClass().getSimpleName()") }.getOrNull() }
                ?.contains("Editor", ignoreCase = true) == true

        // Track whether we're typing into an inline rename/extract template.
        // In template mode, the suggestion popup IS the template's own helper
        // popup — pressing Escape here cancels the whole rename refactor, so
        // we must skip the post-type dismiss. Session 2026-04-21_19-04-13
        // iteration 7 is exactly that failure.
        var inTemplateMode = false

        if (focusedField != null) {
            // Safety: never select-all-and-delete inside an EditorComponentImpl.
            // The focused editor could be either:
            //   (a) a small inline template (rename, extract) where the old
            //       identifier is ALREADY selected by IntelliJ — typing replaces it,
            //   (b) the full source-code editor, in which case Cmd/Ctrl+A would
            //       select the entire file and Delete would wipe it.
            // In both cases we must not clear; typing alone is the right move.
            if (clearFirst && !focusedIsEditorComponent) {
                clearFieldWithJs(focusedField)
                Thread.sleep(100)
            } else if (clearFirst && focusedIsEditorComponent) {
                println("  typeInDialog: focus is on EditorComponentImpl — skipping clear (template mode)")
            }
            if (focusedIsEditorComponent) inTemplateMode = true
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
            } else {
                println("  typeInDialog: inline editor template — skipping clear (old name is pre-selected)")
                inTemplateMode = true
            }
            // For inline rename/extract templates the cursor is already inside the
            // highlighted template field with the old name selected — just type.

            robot.keyboard { enterText(value) }
        }
        Thread.sleep(300)

        // Dismiss any autocomplete/lookup popup that may have appeared after
        // typing — but ONLY when we're typing into a plain field. Inside an
        // inline template, the popup is the rename helper and Escape would
        // cancel the refactor.
        if (!inTemplateMode) {
            dismissAutocompletePopup()
        } else {
            println("  typeInDialog: template mode — skipping autocomplete dismiss (Escape would cancel rename)")
        }
    }

    /**
     * Dismiss IntelliJ's code-completion lookup popup (LookupLayeredPane / LookupImpl)
     * if — and only if — it is actually open.
     *
     * Previously this method pressed Escape unconditionally after every
     * `typeInDialog` call. That killed two legitimate states:
     *   1. Inline rename/extract templates (Escape cancels the refactor).
     *   2. Any state with no autocomplete at all (Escape closes a still-open
     *      parent dialog / popup that the user hadn't finished with).
     *
     * We now probe for a Lookup* class via the UI tree; if no lookup is up we
     * skip the Escape entirely. Inline-template guarding is handled by the
     * caller (see `typeInDialog`).
     */
    private fun dismissAutocompletePopup() {
        try {
            val lookupOpen =
                runCatching {
                    val roots = treeProvider.fetchTree()
                    UiTreeParser.flatten(roots).any { c ->
                        // IntelliJ's code-completion lookup is hosted inside a
                        // LookupLayeredPane / LookupImpl. Match loosely in case
                        // the class name is subclassed.
                        c.cls.contains("Lookup", ignoreCase = true)
                    }
                }.getOrDefault(false)

            if (!lookupOpen) {
                return
            }

            println("  Dismissing autocomplete lookup popup (Escape)")
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
        // Preferred: ask the component for its current text so we know whether
        // select-all+delete actually worked, and can fall back to backspaces.
        val initialLength = readTextLength(field)
        if (initialLength == 0) {
            // Already empty — nothing to do. Avoids stray keystrokes that could
            // dismiss popups or trigger shortcuts.
            println("  clearFieldWithJs: field already empty, skip")
            return
        }

        println("  clearFieldWithJs: platform=${if (isMacOs) "macOS" else "win/linux"}, len=$initialLength")
        selectAllAndDelete()

        // Verify the field is now empty. If not, fall back to character-by-
        // character backspace up to the known length. This rescues fields
        // whose key bindings don't honour the default select-all shortcut
        // (some custom renderers, combo editors, or platforms where the
        // focused window is inside a popup that intercepts Cmd/Ctrl+A).
        val afterLength = readTextLength(field)
        if (afterLength > 0) {
            val n = afterLength.coerceAtMost(initialLength)
            println("  clearFieldWithJs: select-all+delete left $afterLength chars — falling back to $n backspaces")
            backspaceN(n)
        }
    }

    /**
     * Read the current text length of a focused text-like component.
     *
     * Works for `JTextField`/`JBTextField` and the Swing `Editor*Field`
     * variants via their `getText()` method. Returns -1 when the component
     * does not expose a `getText()` hook or when the JS bridge throws — in
     * that case the caller must assume the worst and skip the length-based
     * safety checks.
     */
    private fun readTextLength(field: ComponentFixture): Int {
        return try {
            // `component.getText` returns String for JTextField; for editors we
            // intentionally avoid it because reading the whole document off the
            // EDT is expensive and can deadlock. For those components we simply
            // return -1 and rely on the keyboard path.
            val js =
                """
                var c = component;
                if (c.getClass().getSimpleName().toLowerCase().indexOf('editor') >= 0) {
                    -1;
                } else if (typeof c.getText === 'function') {
                    var t = c.getText();
                    t == null ? 0 : t.length;
                } else {
                    -1;
                }
                """.trimIndent()
            val result = field.callJs<Int>(js)
            if (result < 0) 0 else result
        } catch (e: Exception) {
            println("  readTextLength: JS bridge failed (${e.message}); assuming non-empty")
            // Assume non-empty so callers still attempt to clear; backspace
            // fallback will stop once the field is empty (enterText afterwards
            // ignores stale content anyway).
            0
        }
    }

    /**
     * Select-all + delete via RemoteRobot keyboard (Swing EventQueue injection).
     *
     * Uses the OS-appropriate modifier: `Cmd+A` on macOS, `Ctrl+A` on
     * Windows/Linux. The previous implementation hard-coded `Meta+A` which
     * silently failed on non-macOS CI runners.
     */
    private fun selectAllAndDelete() {
        val selectAllModifier = if (isMacOs) KeyEvent.VK_META else KeyEvent.VK_CONTROL
        robot.keyboard {
            pressing(selectAllModifier) { key(KeyEvent.VK_A) }
        }
        Thread.sleep(80)
        robot.keyboard { key(KeyEvent.VK_DELETE) }
        Thread.sleep(80)
    }

    /**
     * Fire [count] backspaces as a last-resort clear. Capped to a reasonable
     * upper bound so a buggy length read can't spam thousands of keystrokes.
     */
    private fun backspaceN(count: Int) {
        val safe = count.coerceAtMost(256)
        repeat(safe) {
            robot.keyboard { key(KeyEvent.VK_BACK_SPACE) }
            // Small sleep avoids key-repeat coalescing; keep it tiny so clearing
            // a ~30-char field stays well under 1s.
            Thread.sleep(5)
        }
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

        dropdown.click()
        Thread.sleep(300)

        try {
            val option =
                robot.find<ComponentFixture>(
                    byXpath("//div[@visible_text='$value' or @accessiblename='$value']"),
                    Duration.ofSeconds(2),
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
        val button =
            try {
                val found =
                    robot.find<ComponentFixture>(
                        byXpath("//div[@class='JButton' and contains(@text, '$label')]"),
                        Duration.ofSeconds(3),
                    )
                println("  [clickDialogButton] Strategy 1 SUCCESS: Found button by @text")
                found
            } catch (e1: Exception) {
                println("  [clickDialogButton] Strategy 1 FAILED: ${e1.message}")

                // Strategy 2: Find by accessible name
                println("  [clickDialogButton] Strategy 2: Searching by @accessiblename attribute...")
                try {
                    val found =
                        robot.find<ComponentFixture>(
                            byXpath("//div[@accessiblename='$label' and @class='JButton']"),
                            Duration.ofSeconds(3),
                        )
                    println("  [clickDialogButton] Strategy 2 SUCCESS: Found button by @accessiblename")
                    found
                } catch (e2: Exception) {
                    println("  [clickDialogButton] Strategy 2 FAILED: ${e2.message}")

                    // Strategy 3: Find by visible_text
                    println("  [clickDialogButton] Strategy 3: Searching by @visible_text attribute...")
                    try {
                        val found =
                            robot.find<ComponentFixture>(
                                byXpath("//div[@visible_text='$label' and @class='JButton']"),
                                Duration.ofSeconds(3),
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

        // Strategy 2: find label, locate nearest input field
        try {
            val label =
                robot.find<ComponentFixture>(
                    byXpath(
                        "$container//div[@visible_text='$fieldLabel' or contains(@text, '$fieldLabel') or @visible_text='$normalizedLabel' or contains(@text, '$normalizedLabel')]",
                    ),
                    timeout,
                )

            val labelBounds = getComponentBounds(label)
            val labelY = labelBounds.second
            val labelRight = labelBounds.first + labelBounds.third

            val allFields =
                robot.findAll<ComponentFixture>(
                    byXpath("$container//div[$inputFieldClasses]"),
                )

            val closestField =
                allFields.minByOrNull { field ->
                    val fieldBounds = getComponentBounds(field)
                    val verticalDistance = kotlin.math.abs(fieldBounds.second - labelY)
                    val horizontalDistance =
                        if (fieldBounds.first >= labelRight) {
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
        } catch (_: Exception) {
        }

        // Strategy 3: placeholder / text content
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

        println("  Could not find field '$fieldLabel', using Tab navigation")
        robot.keyboard { key(KeyEvent.VK_TAB) }
        Thread.sleep(200)
    }

    fun selectDropdownField(
        fieldLabel: String,
        value: String,
    ) {
        focusField(fieldLabel)
        Thread.sleep(100)
        selectDropdown(value)
    }

    fun setCheckbox(
        fieldLabel: String,
        checked: Boolean,
    ) {
        val container = "(//div[@class='HeavyWeightWindow' or @class='DialogRootPane'])[last()]"
        val timeout = Duration.ofSeconds(3)

        try {
            val checkbox =
                robot.find<ComponentFixture>(
                    byXpath(
                        "$container//div[@class='JCheckBox' and (@visible_text='$fieldLabel' or contains(@text, '$fieldLabel') or contains(@accessiblename, '$fieldLabel'))]",
                    ),
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

    fun tableRowAction(
        action: String,
        rowIndex: Int?,
    ) {
        val container = "(//div[@class='HeavyWeightWindow' or @class='DialogRootPane'])[last()]"
        val timeout = Duration.ofSeconds(3)

        if (rowIndex != null) {
            try {
                val table =
                    robot.find<ComponentFixture>(
                        byXpath("$container//div[@class='JBTable' or @class='JTable']"),
                        timeout,
                    )
                table.callJs<String>("component.setRowSelectionInterval($rowIndex, $rowIndex)")
                Thread.sleep(200)
            } catch (_: Exception) {
                println("  Could not find table to select row $rowIndex")
            }
        }

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

    fun typeText(text: String) {
        robot.keyboard { enterText(text) }
        Thread.sleep(200)
    }

    fun openFile(filePath: String) {
        // Normalize the path: extract just the filename if path contains malformed segments
        // like "src/././File.java" - IntelliJ's Search Everywhere works with just filenames
        val normalizedPath = normalizeFilePath(filePath)
        currentFilePath = normalizedPath

        println("    OpenFile: input='$filePath', normalized='$normalizedPath'")

        val ideFrame =
            robot.find<ComponentFixture>(
                byXpath("//div[@class='IdeFrameImpl']"),
                Duration.ofSeconds(5),
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

        robot.keyboard { enterText(normalizedPath) }
        Thread.sleep(1000)

        robot.keyboard { key(KeyEvent.VK_ENTER) }
        Thread.sleep(1000)
    }

    /**
     * Normalize a file path for IntelliJ's Search Everywhere.
     *
     * Handles malformed paths like "src/././File.java" by extracting just the filename.
     * IntelliJ's Search Everywhere (Cmd+Shift+O) works best with simple filenames.
     */
    private fun normalizeFilePath(path: String): String {
        // If path contains ./ segments, it's likely malformed - extract just the filename
        if (path.contains("./") || path.contains(".\\")) {
            val filename = path.substringAfterLast("/")
            return filename
        }

        // If path is already just a filename (no directory separators), use it directly
        if (!path.contains("/") && !path.contains("\\")) {
            return path
        }

        // For valid relative paths like "src/main/kotlin/File.kt",
        // extract just the filename for Search Everywhere
        val filename = path.substringAfterLast("/").substringAfterLast("\\")
        return filename
    }

    data class LineWithNumber(val lineNumber: Int, val content: String)

    fun readFileWithLineNumbers(relativePath: String): List<LineWithNumber> {
        val projectPath = getProjectRoot()
        val resolved = projectPath.resolve(relativePath)

        val file =
            if (resolved.toFile().exists()) {
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

    fun readFileLines(
        relativePath: String,
        startLine: Int,
        endLine: Int,
    ): List<LineWithNumber> {
        return readFileWithLineNumbers(relativePath)
            .filter { it.lineNumber in startLine..endLine }
    }

    fun findSymbolInFile(
        relativePath: String,
        symbol: String,
    ): List<Int> {
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
            variableNames = variableNames,
        )
    }

    data class SimpleFileContext(
        val totalLines: Int,
        val methodNames: List<String>,
        val variableNames: List<String>,
    ) {
        fun toPromptString(): String =
            """
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
        val expressionTargets: List<String>,
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
        val editor =
            try {
                findFocusedEditor()
            } catch (_: Exception) {
                return null
            }

        val result =
            try {
                editor.callJs<String>(
                    """
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
                    """.trimIndent(),
                )
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
        val bodyRanges =
            parts[3].split(";").filter { it.isNotBlank() }.mapNotNull { range ->
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
            expressionTargets = exprTargets,
        )
    }

    // ── UI tree fetching ─────────────────────────────────────────────────────

    fun fetchUiTree(): List<UiComponent> = treeProvider.fetchTree()

    /**
     * Full text of the currently-open document. Returns `null` on any
     * failure (no project open, no focused editor, JS call fails).
     *
     * Historical footgun: a previous revision did
     * `component.getDocument().getText()` where `component` was an
     * `EditorComponentImpl`. That `getDocument()` returns a
     * `javax.swing.text.Document`, whose `getText` is `(int, int) → String`
     * — the no-arg `getText()` lives on IntelliJ's own
     * `com.intellij.openapi.editor.Document`. The call threw
     * "Can't find method javax.swing.text.Document.getText()" and every
     * `file_contains` / `file_absent` predicate silently returned `null`,
     * which `executeVerify` then rendered as "Predicate failed". That
     * false negative made the LLM believe successful refactors had not
     * landed and trapped it in a verify loop.
     *
     * We now go through `FileEditorManager.getSelectedTextEditor()`,
     * which returns IntelliJ's `Editor` — its `getDocument()` is the
     * IntelliJ `Document` with the real `getText()`. All access is
     * wrapped in `invokeAndWait` for thread safety, matching
     * [getEditorContext].
     */
    fun getDocumentText(): String? {
        return try {
            val editor =
                try {
                    findFocusedEditor()
                } catch (_: Exception) {
                    return null
                }
            val raw =
                editor.callJs<String>(
                    """
                    var out = null;
                    com.intellij.openapi.application.ApplicationManager
                        .getApplication().invokeAndWait(new Runnable() {
                            run: function() {
                                try {
                                    var project = com.intellij.openapi.project.ProjectManager
                                        .getInstance().getOpenProjects()[0];
                                    if (project == null) { out = ''; return; }
                                    var fem = com.intellij.openapi.fileEditor.FileEditorManager
                                        .getInstance(project);
                                    var ed = fem.getSelectedTextEditor();
                                    if (ed == null) { out = ''; return; }
                                    out = ed.getDocument().getText();
                                } catch (e) {
                                    out = '';
                                }
                            }
                        });
                    out;
                    """.trimIndent(),
                ) ?: ""
            if (raw.isEmpty()) null else raw
        } catch (e: Exception) {
            println("  Warning: Could not get document text: ${e.message}")
            null
        }
    }

    /**
     * Snapshot of the currently-active editor, packaged for the LLM prompt.
     *
     * Captures the caret position, the identifier immediately under the
     * caret (very handy for rename / refactor tasks), and a bounded window
     * of surrounding source. Everything is pulled in a single JS
     * round-trip — the editor and its document are read atomically inside
     * `invokeAndWait` so the snapshot is internally consistent.
     */
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
     * Fetch the current editor state (file, caret, symbol, visible window).
     *
     * Returns `null` when no editor is open or the read fails — callers
     * should treat that as "no extra context" rather than a hard error
     * because the snapshot without code is still usable for modal UI flows.
     *
     * Window size is clamped ([around] lines on each side of the caret)
     * so the prompt stays bounded even on multi-thousand-line files.
     */
    fun getEditorContext(around: Int = 25): EditorCodeContext? {
        return try {
            val editor =
                try {
                    findFocusedEditor()
                } catch (_: Exception) {
                    return null
                }
            val raw =
                editor.callJs<String>(
                    """
                    var out = null;
                    com.intellij.openapi.application.ApplicationManager
                        .getApplication().invokeAndWait(new Runnable() {
                            run: function() {
                                try {
                                    var project = com.intellij.openapi.project.ProjectManager
                                        .getInstance().getOpenProjects()[0];
                                    if (project == null) { out = ''; return; }
                                    var fem = com.intellij.openapi.fileEditor.FileEditorManager
                                        .getInstance(project);
                                    var ed = fem.getSelectedTextEditor();
                                    if (ed == null) { out = ''; return; }

                                    var doc = ed.getDocument();
                                    var vf = com.intellij.openapi.fileEditor.FileDocumentManager
                                        .getInstance().getFile(doc);
                                    var path = vf != null ? vf.getPath() : '';
                                    var name = vf != null ? vf.getName() : '';

                                    var caretModel = ed.getCaretModel();
                                    var caret = caretModel.getPrimaryCaret();
                                    var logical = caret.getLogicalPosition();
                                    var line = logical.line;
                                    var col = logical.column;
                                    var total = doc.getLineCount();

                                    var selText = caret.getSelectedText();
                                    if (selText == null) selText = '';

                                    // Identifier under caret via simple char-class walk.
                                    var offset = caret.getOffset();
                                    var text = doc.getCharsSequence();
                                    var startIdent = offset;
                                    var endIdent = offset;
                                    function isIdentChar(ch) {
                                        return (ch >= 'a' && ch <= 'z') ||
                                               (ch >= 'A' && ch <= 'Z') ||
                                               (ch >= '0' && ch <= '9') ||
                                               ch == '_' || ch == '$';
                                    }
                                    while (startIdent > 0 && isIdentChar(text.charAt(startIdent - 1))) startIdent--;
                                    while (endIdent < text.length() && isIdentChar(text.charAt(endIdent))) endIdent++;
                                    var ident = startIdent < endIdent ? text.subSequence(startIdent, endIdent).toString() : '';

                                    var around = $around;
                                    var startLine = line - around;
                                    if (startLine < 0) startLine = 0;
                                    var endLine = line + around;
                                    if (endLine >= total) endLine = total - 1;

                                    var startOff = doc.getLineStartOffset(startLine);
                                    var endOff = doc.getLineEndOffset(endLine);
                                    var windowText = text.subSequence(startOff, endOff).toString();

                                    // Delimit fields with sentinel \u0001 so newlines
                                    // inside the source window survive the round-trip.
                                    out = path + '\u0001' +
                                          name + '\u0001' +
                                          line + '\u0001' +
                                          col + '\u0001' +
                                          total + '\u0001' +
                                          ident + '\u0001' +
                                          selText + '\u0001' +
                                          startLine + '\u0001' +
                                          endLine + '\u0001' +
                                          windowText;
                                } catch (e) {
                                    out = '';
                                }
                            }
                        });
                    out;
                    """.trimIndent(),
                ) ?: ""

            if (raw.isBlank()) return null
            val parts = raw.split('\u0001')
            if (parts.size < 10) return null

            EditorCodeContext(
                filePath = parts[0],
                fileName = parts[1],
                caretLine = parts[2].toIntOrNull() ?: 0,
                caretColumn = parts[3].toIntOrNull() ?: 0,
                totalLines = parts[4].toIntOrNull() ?: 0,
                symbolUnderCaret = parts[5],
                selectedText = parts[6],
                windowStartLine = parts[7].toIntOrNull() ?: 0,
                windowEndLine = parts[8].toIntOrNull() ?: 0,
                visibleText = parts[9],
            )
        } catch (e: Exception) {
            println("  Warning: Could not get editor context: ${e.message}")
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
