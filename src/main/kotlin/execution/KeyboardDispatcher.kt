package execution

import java.awt.Component
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.KeyStroke

/** Dispatches keyboard actions through the focused Swing component in-process. */
class KeyboardDispatcher(
    private val edtDispatcher: EdtDispatcher,
) {
    fun pressShortcut(keys: String) {
        val parts = keys.split("+").map { it.trim() }
        val modifiers = parts.dropLast(1).map { modifierNameToKeyCode(it) }.filter { it != 0 }
        val mainKey = keyCodeForShortcut(parts.last())
        val modifierMask = modifiers.fold(0) { acc, code -> acc or code }

        edtDispatcher.runOnEdtAndWait {
            val focusOwner = keyboardFocusManagerFocusOwner() ?: return@runOnEdtAndWait
            val event =
                KeyEvent(
                    focusOwner,
                    KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(),
                    modifierMask,
                    mainKey,
                    KeyEvent.CHAR_UNDEFINED,
                )
            focusOwner.keyListeners.forEach { it.keyPressed(event) }
        }
    }

    fun pressKey(keyName: String) {
        val keyCode =
            keyCodeForName(keyName)
                ?: throw IllegalArgumentException("Unknown key name: $keyName")
        val dialog = edtDispatcher.findTopmostDialog()
        trace("pressKey key='$keyName' dialog='${dialog?.title}' class=${dialog?.javaClass?.name}")
        if (keyCode == KeyEvent.VK_ENTER && dialog != null) {
            confirmDialog(dialog)
            return
        }
        if (dialog != null) {
            edtDispatcher.runOnEdtAndWaitWithModality(edtDispatcher.modalityStateFor(dialog)) {
                pressKeyOnEdt(keyCode, dialog)
            }
        } else {
            edtDispatcher.runOnEdtAndWait { pressKeyOnEdt(keyCode, null) }
        }
    }

    fun pressKeyStroke(
        keyCode: Int,
        modifiers: Int = 0,
    ) {
        edtDispatcher.runOnEdtAndWait {
            val focusOwner = keyboardFocusManagerFocusOwner() ?: return@runOnEdtAndWait
            val event =
                KeyEvent(
                    focusOwner,
                    KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(),
                    modifiers,
                    keyCode,
                    KeyEvent.CHAR_UNDEFINED,
                )
            focusOwner.keyListeners.forEach { it.keyPressed(event) }
        }
    }

    private fun pressKeyOnEdt(
        keyCode: Int,
        dialog: JDialog?,
    ) {
        if (keyCode == KeyEvent.VK_ENTER) {
            val defaultButton = dialog?.rootPane?.defaultButton
            if (defaultButton != null && defaultButton.isEnabled) {
                defaultButton.doClick()
                return
            }
            val renameButton = dialog?.rootPane?.let { ComponentTreeWalker.findButton(it, "Rename") }
            if (renameButton != null && renameButton.isEnabled) {
                renameButton.doClick()
                return
            }
        }
        val focusOwner = keyboardFocusManagerFocusOwner() ?: return
        val event =
            KeyEvent(
                focusOwner,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                0,
                keyCode,
                KeyStroke.getKeyStroke(keyCode, 0).keyChar.takeIf { it.code != 0 } ?: KeyEvent.CHAR_UNDEFINED,
            )
        focusOwner.keyListeners.forEach { it.keyPressed(event) }
    }

    /**
     * Confirm a modal refactoring dialog from a background thread.
     *
     * Change Signature's default button is RefactoringDialog.RefactorAction, not
     * DialogWrapper's OK action. Invoking that action on the dialog's modality
     * (no Swing Timer, no invokeAndWait) keeps preview-usages off and lets
     * validateAndCommitData run inside the dialog's nested event loop.
     */
    private fun confirmDialog(dialog: JDialog) {
        val error = arrayOfNulls<Throwable>(1)
        val clickFinished = AtomicBoolean(false)
        val modality = edtDispatcher.modalityStateFor(dialog)
        edtDispatcher.runOnEdtLaterWithModality(modality) {
            try {
                invokeRefactorAction(dialog)
                trace("confirmDialog action returned visible=${dialog.isVisible}")
            } catch (t: Throwable) {
                error[0] = t
                t.printStackTrace()
            } finally {
                clickFinished.set(true)
            }
        }
        val deadline = System.currentTimeMillis() + 60_000
        var lastLogAt = 0L
        while (System.currentTimeMillis() < deadline) {
            error[0]?.let { throw it }
            if (!dialog.isVisible) {
                trace(
                    "confirmDialog closed title='${dialog.title}' " +
                        "clickFinished=${clickFinished.get()}",
                )
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastLogAt >= 2000) {
                lastLogAt = now
                trace(
                    "confirmDialog waiting title='${dialog.title}' " +
                        "visible=${dialog.isVisible} clickFinished=${clickFinished.get()} " +
                        "windows=${describeVisibleWindows()}",
                )
            }
            Thread.sleep(50)
        }
        error[0]?.let { throw it }
        throw IllegalStateException(
            "Dialog '${dialog.title}' did not close after Enter " +
                "(clickFinished=${clickFinished.get()} windows=${describeVisibleWindows()})",
        )
    }

    private fun invokeRefactorAction(dialog: JDialog) {
        val button =
            ComponentTreeWalker.findButton(dialog.rootPane ?: dialog, "Refactor")
                ?: ComponentTreeWalker.findButton(dialog.rootPane ?: dialog, "Rename")
                ?: dialog.rootPane?.defaultButton
        val wrapper =
            dialog.javaClass.methods
                .firstOrNull { it.name == "getDialogWrapper" && it.parameterCount == 0 }
                ?.invoke(dialog)
        trace(
            "confirmDialog button='${button?.text}' enabled=${button?.isEnabled} " +
                "action=${button?.action?.javaClass?.name} wrapper=${wrapper?.javaClass?.name}",
        )
        val action = button?.action
        if (action != null && button.isEnabled) {
            edtDispatcher.runAsUserActivity {
                action.actionPerformed(
                    ActionEvent(button, ActionEvent.ACTION_PERFORMED, "refactor"),
                )
            }
            return
        }
        if (button is JButton && button.isEnabled) {
            button.doClick()
            return
        }
        pressKeyOnEdt(KeyEvent.VK_ENTER, dialog)
    }

    private fun describeVisibleWindows(): String =
        Window.getWindows()
            .filter { it.isVisible }
            .joinToString { window ->
                val title =
                    when (window) {
                        is JDialog -> window.title.orEmpty()
                        is JFrame -> window.title.orEmpty()
                        else -> window.name.orEmpty()
                    }
                "${window.javaClass.simpleName}:'$title'"
            }

    private fun trace(message: String) {
        println("    KeyboardDispatcher: $message")
    }

    private fun modifierNameToKeyCode(name: String): Int =
        when (name.lowercase()) {
            "shift" -> InputEvent.SHIFT_DOWN_MASK
            "ctrl", "control" -> InputEvent.CTRL_DOWN_MASK
            "alt", "option" -> InputEvent.ALT_DOWN_MASK
            "meta", "cmd", "command" -> InputEvent.META_DOWN_MASK
            else -> 0
        }

    private fun keyCodeForShortcut(name: String): Int =
        keyCodeForName(name)
            ?: if (name.length == 1) {
                KeyEvent.getExtendedKeyCodeForChar(name[0].code)
            } else {
                KeyEvent.VK_ENTER
            }

    private fun keyCodeForName(name: String): Int? =
        when (name.lowercase()) {
            "enter" -> KeyEvent.VK_ENTER
            "escape", "esc" -> KeyEvent.VK_ESCAPE
            "tab" -> KeyEvent.VK_TAB
            "space" -> KeyEvent.VK_SPACE
            "backspace" -> KeyEvent.VK_BACK_SPACE
            "delete" -> KeyEvent.VK_DELETE
            "home" -> KeyEvent.VK_HOME
            "end" -> KeyEvent.VK_END
            "pageup" -> KeyEvent.VK_PAGE_UP
            "pagedown" -> KeyEvent.VK_PAGE_DOWN
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
            else -> null
        }
}

internal fun keyboardFocusManagerFocusOwner(): Component? {
    return java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
}
