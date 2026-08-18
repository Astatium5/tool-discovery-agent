package execution

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.Messages

/** Shared action runner for the individual in-process refactoring scripts. */
abstract class RefactoringTestAction(
    private val testName: String,
    private val test: (InProcessGuiExecutor) -> Unit,
) : AnAction() {
    private val log = logger<RefactoringTestAction>()

    override fun actionPerformed(e: AnActionEvent) {
        val project =
            e.project ?: run {
                Messages.showErrorDialog("No project open.", testName)
                return
            }
        val executor = InProcessGuiExecutor(project)

        Thread {
            try {
                test(executor)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage("$testName test completed.", testName)
                }
            } catch (ex: Exception) {
                println("=== $testName FAILED: ${ex.message} ===")
                ex.printStackTrace()
                log.error("$testName test failed", ex)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog("${ex.message ?: ex.javaClass.simpleName}", testName)
                }
            }
        }.start()
    }
}

class RunRenameTestAction : RefactoringTestAction("Rename", ::runRenameTest)

class RunChangeSignatureVisibilityTestAction :
    RefactoringTestAction("Change Signature · Visibility", ::runChangeSignatureVisibilityTest)

class RunChangeSignatureNameTestAction :
    RefactoringTestAction("Change Signature · Name", ::runChangeSignatureNameTest)

class RunChangeSignatureReturnTypeTestAction :
    RefactoringTestAction("Change Signature · Return Type", ::runChangeSignatureReturnTypeTest)

class RunChangeSignatureParametersTestAction :
    RefactoringTestAction("Change Signature · Parameters", ::runChangeSignatureParametersTest)
