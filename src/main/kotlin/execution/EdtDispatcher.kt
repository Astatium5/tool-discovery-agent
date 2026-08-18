package execution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.TransactionGuard
import java.awt.Window
import javax.swing.JDialog
import javax.swing.SwingUtilities

/**
 * Dispatches in-process GUI work onto IntelliJ's EDT while preserving modal
 * dialog state for operations that touch documents or project models.
 */
class EdtDispatcher {
    fun isOnEdt(): Boolean = SwingUtilities.isEventDispatchThread()

    fun runOnEdt(block: () -> Unit) {
        if (isOnEdt()) {
            block()
        } else {
            SwingUtilities.invokeLater(block)
        }
    }

    fun <T> runOnEdtAndWait(block: () -> T): T =
        if (isOnEdt()) {
            block()
        } else {
            runOnEdtAndWaitWithModality(ModalityState.defaultModalityState(), block)
        }

    fun <T> runOnEdtAndWaitWithModality(
        modalityState: ModalityState,
        block: () -> T,
    ): T {
        if (isOnEdt()) return block()
        val holder = arrayOfNulls<Any>(1)
        val throwable = arrayOfNulls<Throwable>(1)
        ApplicationManager.getApplication().invokeAndWait({
            try {
                holder[0] = block()
            } catch (t: Throwable) {
                throwable[0] = t
            }
        }, modalityState)
        throwable[0]?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    fun runOnEdtLaterWithModality(
        modalityState: ModalityState,
        block: () -> Unit,
    ) {
        ApplicationManager.getApplication().invokeLater(block, modalityState)
    }

    /**
     * Run [block] as if it came from a real user input event. Change Signature's
     * processor will otherwise return without closing the dialog because
     * `invokeLater { doClick() }` is not a write-safe user activity.
     */
    fun runAsUserActivity(block: () -> Unit) {
        val guard = TransactionGuard.getInstance()
        val method =
            guard.javaClass.methods.firstOrNull { declared ->
                declared.name == "performUserActivity" && declared.parameterCount == 1
            }
        if (method == null) {
            block()
            return
        }
        try {
            method.invoke(guard, Runnable { block() })
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.targetException ?: error
        }
    }

    fun findTopmostDialog(): JDialog? {
        if (isOnEdt()) return ComponentTreeWalker.getTopmostDialog()
        var dialog: JDialog? = null
        ApplicationManager.getApplication().invokeAndWait({
            dialog = ComponentTreeWalker.getTopmostDialog()
        }, ModalityState.any())
        return dialog
    }

    fun modalityStateFor(dialog: Window): ModalityState {
        if (isOnEdt()) return ModalityState.stateForComponent(dialog)
        return runOnEdtAndWaitWithModality(ModalityState.any()) {
            ModalityState.stateForComponent(dialog)
        }
    }

    fun waitUntilHidden(
        window: Window,
        timeoutMs: Long = 30_000,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // Read visibility off the EDT. invokeAndWait here would pump the
            // queue and can nest a dialog's OK handler inside the wait, which
            // deadlocks Change Signature's usages-search progress dialog.
            if (!window.isVisible) return true
            Thread.sleep(50)
        }
        return false
    }
}
