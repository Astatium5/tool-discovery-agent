package execution

import agent.AgentConfig.robotPort
import agent.UiAgent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.Messages
import com.intellij.remoterobot.RemoteRobot
import llm.LlmConfig
import perception.tree.HtmlUiTreeProvider
import profile.ApplicationProfile

class LaunchAgentAction : AnAction() {
    private val log = logger<LaunchAgentAction>()

    override fun actionPerformed(e: AnActionEvent) {
        log.info("Tool Discovery Agent action triggered")

        val project =
            e.project ?: run {
                Messages.showErrorDialog("No project open.", "Tool Discovery Agent")
                return
            }

        val config = LlmConfig.load(project.basePath)
        if (!config.hasApiKey()) {
            Messages.showErrorDialog(
                "LLM_API_KEY not set. Copy .env.example to .env and configure it.",
                "Tool Discovery Agent",
            )
            return
        }

        // Prompt for intent
        val intent =
            Messages.showInputDialog(
                project,
                "What would you like the agent to do?",
                "Tool Discovery Agent",
                Messages.getQuestionIcon(),
            ) ?: return

        if (intent.isBlank()) return

        // Connect to RemoteRobot
        val robot =
            try {
                RemoteRobot("http://127.0.0.1:$robotPort")
            } catch (ex: Exception) {
                Messages.showErrorDialog(
                    "Cannot connect to Robot Server on port $robotPort.\n" +
                        "Start the IDE with: ./gradlew runIdeForUiTests",
                    "Tool Discovery Agent",
                )
                return
            }

        // Build components
        val llm = config.createChatModel()
        val treeProvider = HtmlUiTreeProvider("http://127.0.0.1:$robotPort")
        val basePath = project.basePath ?: System.getProperty("user.dir")
        val profile =
            ApplicationProfile.loadFromFile("$basePath/build/reports/app-profile.json")
                ?: run {
                    log.warn("Application profile not found, using default. Run UI profiling first for better results.")
                    ApplicationProfile(appName = "IntelliJ IDEA")
                }
        val executor = UiExecutor(robot, treeProvider)

        val agent = UiAgent(llm, profile, executor) { treeProvider.fetchTree() }

        // Run on background thread
        Thread {
            try {
                val result = agent.execute(intent)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    if (result.success) {
                        Messages.showInfoMessage(
                            "Agent completed: ${result.message}\nActions taken: ${result.actionsTaken}",
                            "Tool Discovery Agent",
                        )
                    } else {
                        Messages.showWarningDialog(
                            "Agent failed: ${result.message}",
                            "Tool Discovery Agent",
                        )
                    }
                }
            } catch (ex: Exception) {
                log.error("Agent execution failed", ex)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        "Agent error: ${ex.message}",
                        "Tool Discovery Agent",
                    )
                }
            }
        }.start()
    }
}
