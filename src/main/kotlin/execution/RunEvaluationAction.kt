package execution

import agent.AgentConfig.robotPort
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.Messages
import com.intellij.remoterobot.RemoteRobot
import evaluation.EvaluationRunner
import llm.LlmConfig
import profile.ApplicationProfile

/**
 * Action to run the evaluation comparison between both approaches.
 */
class RunEvaluationAction : AnAction() {
    private val log = logger<RunEvaluationAction>()

    override fun actionPerformed(e: AnActionEvent) {
        log.info("Evaluation action triggered")

        val project =
            e.project ?: run {
                Messages.showErrorDialog("No project open.", "Evaluation")
                return
            }

        val config = LlmConfig.load(project.basePath)
        if (!config.hasApiKey()) {
            Messages.showErrorDialog(
                "LLM_API_KEY not set. Copy .env.example to .env and configure it.",
                "Evaluation",
            )
            return
        }

        val explorationOptions = arrayOf("Run exploration first", "Skip exploration", "Cancel")
        val explorationChoice =
            Messages.showDialog(
                project,
                "Should the Vision Agent run exploration before evaluation?\n\n" +
                    "Exploration: Agent learns UI element functions first (recommended for fair comparison)\n" +
                    "Skip: Uses existing documentation (if available), or attempts tasks without prior knowledge",
                "Vision Agent Exploration",
                explorationOptions,
                0,
                Messages.getQuestionIcon(),
            )

        if (explorationChoice == 2) return

        val runExplorationFirst = explorationChoice == 0

        val confirm =
            Messages.showYesNoDialog(
                project,
                "This will run comparison evaluation on benchmark tasks.\n" +
                    "UI Tree Agent vs Vision Agent (AppAgent).\n\n" +
                    (if (runExplorationFirst) "EXPLORATION will run first to learn UI elements.\n\n" else "") +
                    "Make sure the IDE is running with Robot Server:\n" +
                    "./gradlew runIdeForUiTests\n\n" +
                    "Run quick evaluation (5 tasks)?",
                "Run Evaluation",
                Messages.getQuestionIcon(),
            )

        if (confirm != Messages.YES) return

        val robot =
            try {
                RemoteRobot("http://127.0.0.1:$robotPort")
            } catch (ex: Exception) {
                Messages.showErrorDialog(
                    "Cannot connect to Robot Server on port $robotPort.\n" +
                        "Start the IDE with: ./gradlew runIdeForUiTests",
                    "Evaluation",
                )
                return
            }

        val basePath = project.basePath ?: System.getProperty("user.dir")
        val profile =
            ApplicationProfile.loadFromFile("$basePath/build/reports/app-profile.json")
                ?: ApplicationProfile(appName = "IntelliJ IDEA")

        val runner = EvaluationRunner(robot, config, profile, "$basePath/build/reports/evaluation")

        Thread {
            try {
                val (uiTreeMetrics, visionMetrics) = runner.runQuickEvaluation(runExplorationFirst)

                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    val message =
                        """
                        === Evaluation Results ===

                        UI Tree Approach:
                          Success Rate: ${"%.1f".format(uiTreeMetrics.successRate * 100)}%
                          Avg Steps: ${"%.1f".format(uiTreeMetrics.averageSteps)}
                          Tasks: ${uiTreeMetrics.successfulTasks}/${uiTreeMetrics.totalTasks}

                        Vision Approach:
                          Success Rate: ${"%.1f".format(visionMetrics.successRate * 100)}%
                          Avg Steps: ${"%.1f".format(visionMetrics.averageSteps)}
                          Tasks: ${visionMetrics.successfulTasks}/${visionMetrics.totalTasks}

                        Results saved to: build/reports/evaluation/
                        """.trimIndent()

                    Messages.showInfoMessage(message, "Evaluation Complete")
                }
            } catch (ex: Exception) {
                log.error("Evaluation failed", ex)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        "Evaluation error: ${ex.message}",
                        "Evaluation",
                    )
                }
            }
        }.start()
    }
}
