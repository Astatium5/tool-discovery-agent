package execution

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.Messages
import com.intellij.remoterobot.RemoteRobot
import agent.UiAgent
import llm.LlmModel
import perception.UiTreeFormatter
import perception.parser.HtmlUiTreeProvider
import profile.ApplicationProfile
import java.io.File

class LaunchAgentAction : AnAction() {
    private val log = logger<LaunchAgentAction>()

    override fun actionPerformed(e: AnActionEvent) {
        log.info("Tool Discovery Agent action triggered")

        val project = e.project ?: run {
            Messages.showErrorDialog("No project open.", "Tool Discovery Agent")
            return
        }

        // Read .env config — try project dir, then working dir, then walk up
        val searchDirs = buildList {
            project.basePath?.let { add(it) }
            add(System.getProperty("user.dir"))
            // Walk up from working dir looking for .env
            var dir = File(System.getProperty("user.dir"))
            while (dir.parentFile != null) {
                dir = dir.parentFile
                add(dir.absolutePath)
            }
        }
        val envConfig = searchDirs.firstNotNullOfOrNull { loadEnvConfig(it) } ?: emptyMap()
        val apiKey = envConfig["LLM_API_KEY"]
            ?: System.getenv("LLM_API_KEY")
        val baseUrl = envConfig["LLM_BASE_URL"]
            ?: System.getenv("LLM_BASE_URL")
            ?: "https://coding-intl.dashscope.aliyuncs.com/v1"
        val model = envConfig["LLM_MODEL"]
            ?: System.getenv("LLM_MODEL")
            ?: "MiniMax-M2.5"

        if (apiKey.isNullOrBlank()) {
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
        val robotPort = 8082
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
        val llm = LlmModel.create(apiKey = apiKey, baseUrl = baseUrl, model = model)
        val treeProvider = HtmlUiTreeProvider("http://127.0.0.1:$robotPort")
        val basePath = searchDirs.first()
        val profile = ApplicationProfile.loadFromFile("$basePath/build/reports/app-profile.json")
            ?: ApplicationProfile(appName = "IntelliJ IDEA")
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

    private fun loadEnvConfig(basePath: String): Map<String, String> {
        val envFile = File(basePath, ".env")
        if (!envFile.exists()) return emptyMap()
        return envFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx > 0) {
                    line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                } else {
                    null
                }
            }
            .toMap()
    }
}
