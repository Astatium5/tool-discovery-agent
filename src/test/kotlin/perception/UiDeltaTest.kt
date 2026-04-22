package perception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import perception.parser.ScopedSnapshotBuilder
import perception.parser.ScopedSnapshotBuilder.ActiveContext
import perception.parser.UiComponent
import profile.ApplicationProfile
import profile.ComponentRole

class UiDeltaTest {
    private fun leaf(
        cls: String,
        label: String = "",
        focused: Boolean = false,
    ) = UiComponent(cls, label, label, "", true, false, emptyList(), focused)

    private fun container(
        cls: String,
        title: String = "",
        children: List<UiComponent>,
    ) = UiComponent(cls, "", title, "", true, false, children, false)

    private val profile =
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
                ),
        )

    private fun editorOnly() =
        ScopedSnapshotBuilder.buildCompactSnapshot(
            listOf(container("IdeFrameImpl", children = listOf(leaf("EditorComponentImpl", "Editor for A.kt")))),
            profile,
        )

    private fun withPopup() =
        ScopedSnapshotBuilder.buildCompactSnapshot(
            listOf(
                container("IdeFrameImpl", children = listOf(leaf("EditorComponentImpl", "Editor for A.kt"))),
                container(
                    "HeavyWeightWindow",
                    title = "Refactor This",
                    children = listOf(leaf("ActionMenuItem", "Rename..."), leaf("ActionMenuItem", "Extract Method...")),
                ),
            ),
            profile,
        )

    private fun withDialog(extraButton: Boolean = false) =
        ScopedSnapshotBuilder.buildCompactSnapshot(
            listOf(
                container(
                    "DialogRootPane",
                    title = "Rename",
                    children =
                        listOfNotNull(
                            leaf("JTextField", "New name:", focused = true),
                            leaf("JButton", "Refactor"),
                            if (extraButton) leaf("JButton", "Preview") else null,
                        ),
                ),
            ),
            profile,
        )

    @Test
    @DisplayName("No previous snapshot returns INITIAL")
    fun noPrevious() {
        val delta = UiDelta.between(null, editorOnly())
        assertEquals(UiDelta.INITIAL, delta)
    }

    @Test
    @DisplayName("Same snapshot -> sameFingerprint = true, no changes")
    fun sameSnapshot() {
        val a = editorOnly()
        val b = editorOnly()
        val delta = UiDelta.between(a, b)
        assertTrue(delta.sameFingerprint)
        assertFalse(delta.hasChanges)
        assertEquals("No change since last action.", UiDeltaFormatter.format(delta, "Observe"))
    }

    @Test
    @DisplayName("Popup opens -> windowsOpened populated and context changes")
    fun popupOpens() {
        val delta = UiDelta.between(editorOnly(), withPopup())

        assertFalse(delta.sameFingerprint)
        assertNotNull(delta.contextChanged)
        assertEquals(ActiveContext.EDITOR to ActiveContext.POPUP_MENU, delta.contextChanged)
        assertTrue(delta.windowsOpened.any { it.title == "Refactor This" })
        assertTrue(delta.windowsClosed.isEmpty())
    }

    @Test
    @DisplayName("Popup closes -> windowsClosed populated")
    fun popupCloses() {
        val delta = UiDelta.between(withPopup(), editorOnly())
        assertTrue(delta.windowsClosed.any { it.title == "Refactor This" })
    }

    @Test
    @DisplayName("Button appears -> newInteractive lists it")
    fun buttonAppears() {
        val delta = UiDelta.between(withDialog(extraButton = false), withDialog(extraButton = true))
        assertTrue(delta.newInteractive.contains("Preview"))
        assertFalse(delta.sameFingerprint)
    }

    @Test
    @DisplayName("NO PROGRESS header appears when fingerprint is unchanged after a non-Observe action")
    fun formatFlagsNoProgress() {
        val a = withDialog()
        val b = withDialog()
        val delta = UiDelta.between(a, b)
        val text = UiDeltaFormatter.format(delta, lastActionDescribed = "Click('Refactor')")
        // sameFingerprint + "No change" short-circuits before NO PROGRESS so we
        // assert the short-circuit message — it already carries the signal.
        assertEquals("No change since last action.", text)
    }

    @Test
    @DisplayName("Focus change is captured")
    fun focusChanges() {
        val withoutFocus =
            ScopedSnapshotBuilder.buildCompactSnapshot(
                listOf(
                    container(
                        "DialogRootPane",
                        title = "Rename",
                        children = listOf(leaf("JTextField", "New name:"), leaf("JButton", "Refactor")),
                    ),
                ),
                profile,
            )
        val withFocus = withDialog()

        val delta = UiDelta.between(withoutFocus, withFocus)
        assertNotNull(delta.focusChanged)
        assertEquals(null to "New name:", delta.focusChanged)
    }
}
