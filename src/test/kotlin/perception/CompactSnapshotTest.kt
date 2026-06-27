package perception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import perception.parser.ScopedSnapshotBuilder
import perception.parser.ScopedSnapshotBuilder.ActiveContext
import perception.parser.UiComponent
import profile.ApplicationProfile
import profile.ComponentRole

/**
 * Unit tests for [ScopedSnapshotBuilder.buildCompactSnapshot].
 *
 * These tests are hermetic: they build [UiComponent] trees directly and
 * assemble a minimal [ApplicationProfile] rather than driving the live IDE,
 * so they can run from `./gradlew test` without `runIdeForUiTests`.
 */
class CompactSnapshotTest {
    private fun leaf(
        cls: String,
        label: String = "",
        enabled: Boolean = true,
        focused: Boolean = false,
    ) = UiComponent(
        cls = cls,
        text = label,
        accessibleName = label,
        tooltip = "",
        enabled = enabled,
        hasSubmenu = false,
        children = emptyList(),
        focused = focused,
    )

    private fun container(
        cls: String,
        title: String = "",
        children: List<UiComponent>,
    ) = UiComponent(
        cls = cls,
        text = "",
        accessibleName = title,
        tooltip = "",
        enabled = true,
        hasSubmenu = false,
        children = children,
        focused = false,
    )

    private fun defaultProfile(): ApplicationProfile =
        ApplicationProfile(
            appName = "Test",
            classRoles =
                mutableMapOf(
                    "IdeFrameImpl" to ComponentRole.FRAME,
                    "HeavyWeightWindow" to ComponentRole.POPUP_WINDOW,
                    "DialogRootPane" to ComponentRole.DIALOG,
                    "JButton" to ComponentRole.BUTTON,
                    "JTextField" to ComponentRole.TEXT_FIELD,
                    "EditorComponentImpl" to ComponentRole.EDITOR,
                    "ActionMenuItem" to ComponentRole.MENU_ITEM,
                    "ActionMenu" to ComponentRole.MENU_CONTAINER,
                    "EditorTabLabel" to ComponentRole.TAB,
                    "JBList" to ComponentRole.LIST,
                ),
        )

    @Test
    @DisplayName("Editor-only state exposes editor + no window stack")
    fun editorOnly() {
        val profile = defaultProfile()
        val tree =
            listOf(
                container(
                    "IdeFrameImpl",
                    children =
                        listOf(
                            leaf("EditorComponentImpl", "Editor for Foo.kt", focused = true),
                            leaf("EditorTabLabel", "Foo.kt"),
                        ),
                ),
            )

        val snap = ScopedSnapshotBuilder.buildCompactSnapshot(tree, profile)

        assertEquals(ActiveContext.EDITOR, snap.activeContext)
        assertTrue(snap.windowStack.isEmpty(), "No popups/dialogs -> empty stack")
        assertNotNull(snap.editor)
        assertEquals("Foo.kt", snap.editor!!.file)
        assertTrue(snap.editor!!.tabs.contains("Foo.kt"))
    }

    @Test
    @DisplayName("Dialog wins over popup in active context and topmost window")
    fun dialogTakesPriority() {
        val profile = defaultProfile()
        val tree =
            listOf(
                container("IdeFrameImpl", children = listOf(leaf("EditorComponentImpl", "Editor for A.kt"))),
                container(
                    "HeavyWeightWindow",
                    title = "SomePopup",
                    children = listOf(leaf("ActionMenuItem", "Item")),
                ),
                container(
                    "DialogRootPane",
                    title = "Rename",
                    children =
                        listOf(
                            leaf("JTextField", "New name:"),
                            leaf("JButton", "Refactor"),
                            leaf("JButton", "Preview"),
                        ),
                ),
            )

        val snap = ScopedSnapshotBuilder.buildCompactSnapshot(tree, profile)

        assertEquals(ActiveContext.DIALOG, snap.activeContext)
        assertEquals("Rename", snap.windowStack.last().title)
        assertEquals(ActiveContext.DIALOG, snap.windowStack.last().type)
        assertEquals("Rename", snap.activeWindow.title)
        assertTrue(snap.activeWindow.buttons.any { it.label == "Refactor" })
        assertTrue(snap.activeWindow.fields.any { it.label == "New name:" })
    }

    @Test
    @DisplayName("Popup menu enumerates menu items as active window")
    fun popupMenu() {
        val profile = defaultProfile()
        val tree =
            listOf(
                container("IdeFrameImpl", children = listOf(leaf("EditorComponentImpl", "Editor for A.kt"))),
                container(
                    "HeavyWeightWindow",
                    title = "Refactor This",
                    children =
                        listOf(
                            leaf("ActionMenuItem", "Rename..."),
                            leaf("ActionMenuItem", "Extract Method..."),
                            leaf("ActionMenuItem", "Inline..."),
                        ),
                ),
            )

        val snap = ScopedSnapshotBuilder.buildCompactSnapshot(tree, profile)

        assertEquals(ActiveContext.POPUP_MENU, snap.activeContext)
        assertEquals(3, snap.activeWindow.menuItems.size)
        assertTrue(snap.activeWindow.menuItems.any { it.label == "Rename..." })
    }

    @Test
    @DisplayName("Fingerprint is stable across identical trees and changes with labels")
    fun fingerprintStability() {
        val profile = defaultProfile()
        val tree1 =
            listOf(
                container(
                    "DialogRootPane",
                    title = "Rename",
                    children =
                        listOf(
                            leaf("JTextField", "New name:"),
                            leaf("JButton", "Refactor"),
                        ),
                ),
            )
        val tree2 =
            listOf(
                container(
                    "DialogRootPane",
                    title = "Rename",
                    children =
                        listOf(
                            leaf("JTextField", "New name:"),
                            leaf("JButton", "Refactor"),
                        ),
                ),
            )
        val tree3 =
            listOf(
                container(
                    "DialogRootPane",
                    title = "Rename",
                    children =
                        listOf(
                            leaf("JTextField", "New name:"),
                            leaf("JButton", "Refactor"),
                            leaf("JButton", "Preview"),
                        ),
                ),
            )

        val fp1 = ScopedSnapshotBuilder.buildCompactSnapshot(tree1, profile).fingerprint
        val fp2 = ScopedSnapshotBuilder.buildCompactSnapshot(tree2, profile).fingerprint
        val fp3 = ScopedSnapshotBuilder.buildCompactSnapshot(tree3, profile).fingerprint

        assertEquals(fp1, fp2, "Same tree -> same fingerprint")
        assertNotEquals(fp1, fp3, "Different buttons -> different fingerprint")
    }

    @Test
    @DisplayName("Focused component surfaces in FocusedItem")
    fun focusedItem() {
        val profile = defaultProfile()
        val tree =
            listOf(
                container(
                    "DialogRootPane",
                    title = "Rename",
                    children =
                        listOf(
                            leaf("JTextField", "New name:", focused = true),
                            leaf("JButton", "Refactor"),
                        ),
                ),
            )

        val snap = ScopedSnapshotBuilder.buildCompactSnapshot(tree, profile)

        assertNotNull(snap.focused)
        assertEquals("New name:", snap.focused!!.label)
    }

    @Test
    @DisplayName("formatCompactSnapshot renders fixed sections in order")
    fun formatHasFixedHeaders() {
        val profile = defaultProfile()
        val tree =
            listOf(
                container(
                    "DialogRootPane",
                    title = "Rename",
                    children = listOf(leaf("JButton", "OK")),
                ),
            )
        val snap = ScopedSnapshotBuilder.buildCompactSnapshot(tree, profile)
        val text = ScopedSnapshotBuilder.formatCompactSnapshot(snap)

        assertTrue(text.contains("Active Context:"))
        assertTrue(text.contains("Window Stack"))
        assertTrue(text.contains("Active Window"))
        assertTrue(text.contains("\"Rename\""))
    }

    @Test
    @DisplayName("In-place rename: focused editor + suggestion popup -> INLINE_WIDGET")
    fun inlineRenameDetected() {
        val profile = defaultProfile()
        val tree =
            listOf(
                container(
                    "IdeFrameImpl",
                    children =
                        listOf(
                            leaf("EditorComponentImpl", "completePendingCheckpoint", focused = true),
                        ),
                ),
                container(
                    "HeavyWeightWindow",
                    title = "NameSuggestions",
                    children =
                        listOf(
                            container(
                                "JBList",
                                children =
                                    listOf(
                                        leaf("SizedIcon", "finalizePendingCheckpoint"),
                                        leaf("SizedIcon", "completeCheckpoint"),
                                        leaf("SizedIcon", "finalizeCheckpoint"),
                                    ),
                            ),
                        ),
                ),
            )

        val snap = ScopedSnapshotBuilder.buildCompactSnapshot(tree, profile)

        assertEquals(ActiveContext.INLINE_WIDGET, snap.activeContext)
        assertNotNull(snap.inlineWidget)
        assertEquals("completePendingCheckpoint", snap.inlineWidget!!.oldIdentifier)
        assertTrue(snap.inlineWidget!!.suggestions.contains("finalizePendingCheckpoint"))
        // Active window should surface the identifier + suggestions + hint.
        assertTrue(snap.activeWindow.fields.any { it.role == "identifier" })
        assertTrue(snap.activeWindow.fields.any { it.role == "hint" })
        assertTrue(snap.activeWindow.buttons.isEmpty())
        assertTrue(snap.activeWindow.menuItems.isEmpty())
    }

    @Test
    @DisplayName("In-place rename fingerprint changes when suggestions shift")
    fun inlineRenameFingerprintMovesWithSuggestions() {
        val profile = defaultProfile()
        fun make(suggestions: List<String>) =
            listOf(
                container(
                    "IdeFrameImpl",
                    children = listOf(leaf("EditorComponentImpl", "foo", focused = true)),
                ),
                container(
                    "HeavyWeightWindow",
                    title = "NameSuggestions",
                    children =
                        listOf(
                            container(
                                "JBList",
                                children = suggestions.map { leaf("SizedIcon", it) },
                            ),
                        ),
                ),
            )

        val fp1 =
            ScopedSnapshotBuilder.buildCompactSnapshot(make(listOf("foo1", "foo2")), profile).fingerprint
        val fp2 =
            ScopedSnapshotBuilder.buildCompactSnapshot(make(listOf("bar1", "bar2")), profile).fingerprint
        assertNotEquals(fp1, fp2, "Different suggestion labels -> different fingerprint")
    }

    @Test
    @DisplayName("Context menu is NOT classified as inline widget")
    fun contextMenuStaysPopupMenu() {
        val profile = defaultProfile()
        // A HeavyWeightWindow full of menu items + a focused editor should be
        // treated as a popup menu, not an inline rename template.
        val tree =
            listOf(
                container(
                    "IdeFrameImpl",
                    children = listOf(leaf("EditorComponentImpl", "foo", focused = true)),
                ),
                container(
                    "HeavyWeightWindow",
                    title = "Refactor",
                    children =
                        listOf(
                            leaf("ActionMenuItem", "Rename..."),
                            leaf("ActionMenuItem", "Extract Method..."),
                        ),
                ),
            )

        val snap = ScopedSnapshotBuilder.buildCompactSnapshot(tree, profile)
        assertEquals(ActiveContext.POPUP_MENU, snap.activeContext)
        assertNull(snap.inlineWidget)
    }

    @Test
    @DisplayName("Session 18-52-18 regression: post-Rename inline widget with 2 HWWs is detected")
    fun postRenameInlineWidgetDetected() {
        // Reproduces the tree shape IntelliJ leaves behind after clicking
        // Refactor → Rename…: the main editor remains focused, and TWO
        // HeavyWeightWindow popups are still alive:
        //
        //  1. the transparent inline-rename template overlay (no menu items),
        //  2. the suggestion popup with alternate identifier names.
        //
        // Session 2026-04-21_18-52-18 iteration 6 misclassified this state as
        // DISMISSED because the post-click analyzer relied on a legacy
        // `profile.isEditor + profile.isPopupWindow` heuristic and the
        // `formatUiTreeForAnalysis` truncated the tree before ever reaching
        // the popups. The compact snapshot's `inlineWidget` detector (now
        // used as the short-circuit in `ActionGenerator.analyzeToolResponse`)
        // must flip to INLINE_WIDGET here.
        val profile = defaultProfile()
        val tree =
            listOf(
                container(
                    "IdeFrameImpl",
                    title = "flink - IntelliJ IDEA",
                    children =
                        listOf(
                            leaf("EditorComponentImpl", "completePendingCheckpoint", focused = true),
                        ),
                ),
                // Inline-template overlay: a HWW whose contents are NOT menu
                // items and NOT buttons. This alone would not be a reliable
                // signal, but combined with the suggestion popup below it is.
                container(
                    "HeavyWeightWindow",
                    title = "InlineTemplateHint",
                    children =
                        listOf(
                            leaf("JLabel", "Press ↵ or → to replace"),
                        ),
                ),
                // Suggestion popup with identifier-shaped labels.
                container(
                    "HeavyWeightWindow",
                    title = "NameSuggestions",
                    children =
                        listOf(
                            container(
                                "JBList",
                                children =
                                    listOf(
                                        leaf("SizedIcon", "finalizePendingCheckpoint"),
                                        leaf("SizedIcon", "completependingcheckpoint"),
                                        leaf("SizedIcon", "completePendingCheckpoint"),
                                    ),
                            ),
                        ),
                ),
            )

        val snap = ScopedSnapshotBuilder.buildCompactSnapshot(tree, profile)

        assertEquals(
            ActiveContext.INLINE_WIDGET,
            snap.activeContext,
            "2 popups + focused editor must resolve to INLINE_WIDGET, not POPUP_MENU/EDITOR",
        )
        assertNotNull(
            snap.inlineWidget,
            "inlineWidget must be non-null so analyzeToolResponse can short-circuit",
        )
        assertEquals("completePendingCheckpoint", snap.inlineWidget!!.oldIdentifier)
        assertTrue(
            snap.inlineWidget!!.suggestions.contains("finalizePendingCheckpoint"),
            "suggestion popup contents should surface into inlineWidget.suggestions",
        )
    }

    @Test
    @DisplayName("Session 19-13-20 regression: inline rename detected via editor selectedText alone")
    fun postRenameInlineWidgetDetectedFromSelection() {
        // Reproduces the harder tree shape from session 2026-04-21_19-13-20
        // iteration 6:
        //
        //  - The editor itself has an EMPTY accessibleName (no focused
        //    component is captured because `focusedComponent` is gated on
        //    `label.isNotBlank()`).
        //  - Both remaining HeavyWeightWindows have children that don't
        //    classify cleanly as a JBList / identifier labels (so
        //    `suggestions` comes out empty).
        //  - There's no "Press ↵ to replace" hint text in the tree.
        //
        // The ONLY signal IntelliJ still gives us is the live editor state:
        // `editorCode.selectedText == "completePendingCheckpoint"`. That's
        // enough — the rename template pre-selects the old name, and no
        // other state leaves the editor with an identifier selected while
        // popups are open. The detector must pick this up and classify as
        // INLINE_WIDGET.
        val profile = defaultProfile()
        val tree =
            listOf(
                container(
                    "IdeFrameImpl",
                    title = "flink - IntelliJ IDEA",
                    children =
                        listOf(
                            // NOTE: no focused=true, blank accessible name → the
                            // focused-component lookup misses this entirely.
                            leaf("EditorComponentImpl", ""),
                        ),
                ),
                // Both popups are opaque — their children don't look like
                // identifiers and there's no hint text. A tree in this shape
                // previously slipped through as POPUP_MENU.
                container(
                    "HeavyWeightWindow",
                    title = "OpaquePopup1",
                    children = listOf(leaf("JPanel", "some panel")),
                ),
                container(
                    "HeavyWeightWindow",
                    title = "OpaquePopup2",
                    children = listOf(leaf("JPanel", "another panel")),
                ),
            )
        val editorCode =
            ScopedSnapshotBuilder.EditorCode(
                caretLine = 1102,
                caretColumn = 33,
                totalLines = 2455,
                symbolUnderCaret = "completePendingCheckpoint",
                // ← the crucial signal: IntelliJ pre-selects the old identifier
                // the instant the inline rename template goes live.
                selectedText = "completePendingCheckpoint",
                windowStartLine = 1077,
                windowEndLine = 1127,
                visibleText = "class Foo {}",
            )

        val snap = ScopedSnapshotBuilder.buildCompactSnapshot(tree, profile, editorCode)

        assertEquals(
            ActiveContext.INLINE_WIDGET,
            snap.activeContext,
            "selected identifier + any popup must resolve to INLINE_WIDGET",
        )
        assertNotNull(
            snap.inlineWidget,
            "inlineWidget must be detected from selectedText alone",
        )
        assertEquals(
            "completePendingCheckpoint",
            snap.inlineWidget!!.oldIdentifier,
            "oldIdentifier should be taken from the live selection",
        )
    }

    @Test
    @DisplayName("Selected text without any popup does NOT false-positive as inline widget")
    fun selectionAloneWithoutPopupIsNotInlineWidget() {
        // Sanity check the Signal 3 gate: the selection-only signal requires
        // a popup to be open. A plain editor selection during normal editing
        // must not mis-fire.
        val profile = defaultProfile()
        val tree =
            listOf(
                container(
                    "IdeFrameImpl",
                    children = listOf(leaf("EditorComponentImpl", "Editor for Foo.kt")),
                ),
            )
        val editorCode =
            ScopedSnapshotBuilder.EditorCode(
                caretLine = 1,
                caretColumn = 0,
                totalLines = 10,
                symbolUnderCaret = "foo",
                selectedText = "foo",
                windowStartLine = 0,
                windowEndLine = 3,
                visibleText = "line1",
            )

        val snap = ScopedSnapshotBuilder.buildCompactSnapshot(tree, profile, editorCode)

        assertEquals(ActiveContext.EDITOR, snap.activeContext)
        assertNull(snap.inlineWidget, "selection without any popup must NOT trigger INLINE_WIDGET")
    }

    @Test
    @DisplayName("Empty editor tree has null editor but stays in EDITOR context")
    fun emptyEditor() {
        val profile = defaultProfile()
        val tree =
            listOf(
                container("IdeFrameImpl", children = emptyList()),
            )

        val snap = ScopedSnapshotBuilder.buildCompactSnapshot(tree, profile)

        assertEquals(ActiveContext.EDITOR, snap.activeContext)
        assertNull(snap.editor)
        assertFalse(snap.fingerprint.isBlank())
    }

    @Test
    @DisplayName("EditorCode surfaces caret, symbol, and visible window")
    fun editorCodeRendering() {
        val profile = defaultProfile()
        val tree =
            listOf(
                container("IdeFrameImpl", children = emptyList()),
            )
        val code =
            ScopedSnapshotBuilder.EditorCode(
                caretLine = 3,
                caretColumn = 7,
                totalLines = 120,
                symbolUnderCaret = "compute",
                selectedText = "",
                windowStartLine = 0,
                windowEndLine = 5,
                visibleText =
                    "class Foo {\n" +
                        "  fun bar() {}\n" +
                        "  fun compute() {\n" +
                        "    return 1\n" +
                        "  }\n" +
                        "}",
            )

        val snap = ScopedSnapshotBuilder.buildCompactSnapshot(tree, profile, code)
        val rendered = ScopedSnapshotBuilder.formatCompactSnapshot(snap)

        assertNotNull(snap.editor?.code)
        assertEquals("compute", snap.editor?.code?.symbolUnderCaret)
        // Caret is rendered as 1-based line:col in the prompt.
        assertTrue(
            rendered.contains("Caret: line 4, col 8"),
            "rendered prompt should surface 1-based caret coords, got:\n$rendered",
        )
        assertTrue(
            rendered.contains("symbol under caret: \"compute\""),
            "rendered prompt should surface symbol under caret, got:\n$rendered",
        )
        assertTrue(
            rendered.contains("Visible Source (lines 1–6)"),
            "rendered prompt should surface the visible source window, got:\n$rendered",
        )
        assertTrue(
            rendered.contains("fun compute()"),
            "rendered prompt should include actual source content",
        )
    }

    @Test
    @DisplayName("EditorCode fingerprint moves when caret moves, stable when it doesn't")
    fun editorCodeFingerprintChangesWithCaret() {
        val profile = defaultProfile()
        val tree =
            listOf(
                container("IdeFrameImpl", children = emptyList()),
            )
        val code1 =
            ScopedSnapshotBuilder.EditorCode(
                caretLine = 1,
                caretColumn = 0,
                totalLines = 10,
                symbolUnderCaret = "foo",
                selectedText = "",
                windowStartLine = 0,
                windowEndLine = 3,
                visibleText = "line1\nline2\nline3\nline4",
            )
        val code2 = code1.copy(caretLine = 5, caretColumn = 12, symbolUnderCaret = "bar")

        val fp1 = ScopedSnapshotBuilder.buildCompactSnapshot(tree, profile, code1).fingerprint
        val fp1Again = ScopedSnapshotBuilder.buildCompactSnapshot(tree, profile, code1).fingerprint
        val fp2 = ScopedSnapshotBuilder.buildCompactSnapshot(tree, profile, code2).fingerprint

        assertEquals(fp1, fp1Again, "fingerprint should be deterministic")
        assertNotEquals(fp1, fp2, "fingerprint should change when caret/symbol changes")
    }
}
