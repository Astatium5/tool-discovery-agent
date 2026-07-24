package execution

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.Messages

class RunTestAction : AnAction() {
    private val log = logger<RunTestAction>()

    override fun actionPerformed(e: AnActionEvent) {
        log.info("Test Script action triggered")

        val project =
            e.project ?: run {
                Messages.showErrorDialog("No project open.", "Test Script")
                return
            }

        val executor = InProcessGuiExecutor(project)

        Thread {
            try {
                runTestScript(executor)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage("Test script completed. Check the IDEA log.", "Test Script")
                }
            } catch (ex: Exception) {
                log.error("Test script failed", ex)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog("Test script failed: ${ex.message}", "Test Script")
                }
            }
        }.start()
    }
}
