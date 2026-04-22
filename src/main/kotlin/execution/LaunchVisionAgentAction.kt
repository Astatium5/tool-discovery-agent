package execution

import agent.AgentConfig.robotPort
import agent.VisionAgent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.Messages
import com.intellij.remoterobot.RemoteRobot
import llm.LlmConfig
import profile.ApplicationProfile

/**
 * Action to launch the Vision-based agent (AppAgent approach).
 *
 * Uses screenshots with numeric overlays instead of parsed UI trees.
 */
class LaunchVisionAgentAction : AnAction() {
    private val log = logger<LaunchVisionAgentAction>()

    override fun actionPerformed(e: AnActionEvent) {
        log.info("Vision Agent action triggered")

        val project =
            e.project ?: run {
                Messages.showErrorDialog("No project open.", "Vision Agent")
                return
            }

        val config = LlmConfig.load(project.basePath)
        if (!config.hasApiKey()) {
            Messages.showErrorDialog(
                "LLM_API_KEY not set. Copy .env.example to .env and configure it.",
                "Vision Agent",
            )
            return
        }

        // Ask user: exploration first or direct deployment?
        val options = arrayOf("Explore first (learn UI)", "Direct deployment (try now)")
        val choice =
            Messages.showDialog(
                project,
                "How should the Vision Agent proceed?\n\n" +
                    "Explore first: Agent will learn UI element functions before attempting the task.\n" +
                    "Direct deployment: Agent will attempt the task immediately (may fail if unfamiliar with UI).",
                "Vision Agent Mode",
                options,
                0,
                Messages.getQuestionIcon(),
            )

        if (choice == -1) return

        val runExploration = choice == 0

        val intent =
            Messages.showInputDialog(
                project,
                "What would you like the Vision Agent to do?",
                "Vision Agent",
                Messages.getQuestionIcon(),
            ) ?: return

        if (intent.isBlank()) return

        val llm = config.createChatModelForVision()
        val basePath = project.basePath ?: System.getProperty("user.dir")
        val profile =
            ApplicationProfile.loadFromFile("$basePath/build/reports/app-profile.json")
                ?: ApplicationProfile(appName = "IntelliJ IDEA")

        val robot = RemoteRobot("http://127.0.0.1:$robotPort")
        val docPath = "$basePath/build/reports/vision-docs.txt"
        val tracePath = "$basePath/build/reports/vision-trace.json"

        val agent = VisionAgent(llm, profile, robot, documentationPath = docPath, tracePath = tracePath)

        Thread {
            try {
                var result: VisionAgent.VisionResult? = null
                val hasPriorDocs = agent.getDocCount() > 0
                var skipDeployment = false

                // EXPLORATION PHASE: Learn UI by attempting the task
                if (runExploration) {
                    log.info("=== EXPLORATION PHASE ===")
                    log.info("Task: $intent")
                    log.info("Learning UI element functions by attempting task...")
                    val exploreResult = agent.explore(explorationTask = intent)
                    result = exploreResult

                    if (exploreResult.success) {
                        // Task completed during exploration - no need for deployment
                        log.info("=== TASK COMPLETED IN EXPLORATION ===")
                        skipDeployment = true
                    } else {
                        log.info("Exploration finished. Docs generated: ${exploreResult.docsGenerated}")
                    }
                } else if (!hasPriorDocs) {
                    log.info("=== NO PRIOR DOCUMENTATION ===")
                    log.info("Agent will attempt task without prior UI knowledge (may fail).")
                }

                // DEPLOYMENT PHASE: Execute task using learned docs
                if (!skipDeployment) {
                    log.info("=== DEPLOYMENT PHASE ===")
                    log.info("Task: $intent")
                    log.info("Using ${agent.getDocCount()} documented elements")
                    result = agent.execute(intent = intent, generateDocs = false)
                }

                showResult(result!!)
            } catch (ex: Exception) {
                log.error("Vision Agent execution failed", ex)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        "Vision Agent error: ${ex.message}",
                        "Vision Agent",
                    )
                }
            }
        }.start()
    }

    private fun showResult(result: VisionAgent.VisionResult) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            if (result.success) {
                Messages.showInfoMessage(
                    "Vision Agent completed: ${result.message}\n" +
                        "Actions taken: ${result.actionsTaken}\n" +
                        "Docs available: ${result.docsGenerated}",
                    "Vision Agent",
                )
            } else {
                Messages.showWarningDialog(
                    "Vision Agent failed: ${result.message}\n" +
                        "Actions taken: ${result.actionsTaken}",
                    "Vision Agent",
                )
            }
        }
    }
}
