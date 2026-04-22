package evaluation

import agent.AgentConfig.actionDelayMs
import agent.AgentConfig.robotPort
import agent.UiAgent
import agent.VisionAgent
import com.intellij.openapi.diagnostic.logger
import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.utils.keyboard
import dev.langchain4j.model.chat.ChatModel
import execution.UiExecutor
import llm.LlmConfig
import perception.tree.HtmlUiTreeProvider
import profile.ApplicationProfile
import java.awt.event.KeyEvent
import java.io.File

/**
 * Evaluation runner that compares both approaches on benchmark tasks.
 *
 * Runs:
 * 1. UI Tree Parsing approach (UiAgent)
 * 2. Vision approach (VisionAgent)
 *
 * Collects metrics and generates comparison report.
 */
class EvaluationRunner(
    private val robot: RemoteRobot,
    llmConfig: LlmConfig,
    private val profile: ApplicationProfile,
    private val outputDir: String = "build/reports/evaluation",
) {
    private val log = logger<EvaluationRunner>()
    private val treeProvider = HtmlUiTreeProvider("http://localhost:$robotPort")
    private val treeLlm: ChatModel = llmConfig.createChatModel()
    private val visionLlm: ChatModel = llmConfig.createChatModelForVision()

    /**
     * Run exploration phase to learn UI element functions.
     * Call this before runFullEvaluation() to give VisionAgent prior knowledge.
     * Implements AppAgent paper's exploration approach.
     */
    fun runExploration(explorationTask: String = "Explore the IDE's main features"): Int {
        log.info("=== EXPLORATION PHASE ===")
        log.info("Task: $explorationTask")
        log.info("Learning UI element functions for Vision approach...")

        File(outputDir).mkdirs()
        val docPath = "$outputDir/vision-docs.txt"
        val tracePath = "$outputDir/exploration-trace.json"

        val agent =
            VisionAgent(
                llm = visionLlm,
                profile = profile,
                robot = robot,
                documentationPath = docPath,
                tracePath = tracePath,
            )

        val exploreResult = agent.explore(explorationTask)
        log.info("Exploration complete: ${exploreResult.docsGenerated} elements documented")
        return exploreResult.docsGenerated
    }

    /**
     * Run evaluation on all benchmark tasks.
     * @param runExplorationFirst If true, runs exploration phase before tasks
     */
    fun runFullEvaluation(runExplorationFirst: Boolean = false): Pair<EvaluationMetrics, EvaluationMetrics> {
        log.info(BenchmarkTasks.summary())
        log.info("Starting evaluation...")

        // Ensure output directory exists
        File(outputDir).mkdirs()

        // Optional: Run exploration first
        if (runExplorationFirst) {
            runExploration()
        }

        val uiTreeResults = mutableListOf<TaskResult>()
        val visionResults = mutableListOf<TaskResult>()

        // Run each task with both approaches
        for (task in BenchmarkTasks.allTasks) {
            log.info("=== TASK: ${task.id} ===")
            log.info("Intent: ${task.intent}")

            // Run UI Tree approach
            log.info("--- UI Tree Approach ---")
            val uiTreeResult = runUiTreeApproach(task)
            uiTreeResults.add(uiTreeResult)

            // Reset state between runs
            Thread.sleep(actionDelayMs)
            dismissPopups()

            // Run Vision approach
            log.info("--- Vision Approach ---")
            val visionResult = runVisionApproach(task)
            visionResults.add(visionResult)

            // Reset state
            Thread.sleep(actionDelayMs)
            dismissPopups()
        }

        // Calculate metrics
        val uiTreeMetrics = MetricsCalculator.calculateMetrics("ui_tree", uiTreeResults)
        val visionMetrics = MetricsCalculator.calculateMetrics("vision", visionResults)

        // Generate and display comparison
        val comparison = MetricsCalculator.generateComparisonTable(uiTreeMetrics, visionMetrics)
        log.info(comparison)

        // Export results
        MetricsCalculator.exportResults(uiTreeResults, "$outputDir/ui_tree_results.json")
        MetricsCalculator.exportResults(visionResults, "$outputDir/vision_results.json")
        MetricsCalculator.exportMetrics(uiTreeMetrics, "$outputDir/ui_tree_metrics.json")
        MetricsCalculator.exportMetrics(visionMetrics, "$outputDir/vision_metrics.json")

        // Write comparison to file
        File("$outputDir/comparison.txt").writeText(comparison)

        log.info("Results exported to $outputDir")

        return Pair(uiTreeMetrics, visionMetrics)
    }

    /**
     * Run a single task with UI Tree approach.
     */
    fun runUiTreeApproach(task: BenchmarkTasks.Task): TaskResult {
        val startTime = System.currentTimeMillis()

        try {
            val executor = UiExecutor(robot, treeProvider)
            val agent =
                UiAgent(
                    llm = treeLlm,
                    profile = profile,
                    executor = executor,
                    uiTreeProvider = { executor.fetchUiTree() },
                )

            val result = agent.execute(task.intent)

            val endTime = System.currentTimeMillis()
            val timeSeconds = (endTime - startTime) / 1000.0

            return TaskResult(
                taskId = task.id,
                approach = "ui_tree",
                success = result.success,
                steps = result.actionsTaken,
                timeSeconds = timeSeconds,
                reward = MetricsCalculator.calculateReward(task, result.success, result.actionsTaken),
                errorMessage = if (!result.success) result.message else null,
            )
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            return TaskResult(
                taskId = task.id,
                approach = "ui_tree",
                success = false,
                steps = 0,
                timeSeconds = (endTime - startTime) / 1000.0,
                reward = 0.0,
                errorMessage = e.message,
            )
        }
    }

    /**
     * Run a single task with Vision approach.
     */
    fun runVisionApproach(task: BenchmarkTasks.Task): TaskResult {
        val startTime = System.currentTimeMillis()

        try {
            // Documentation and trace persistence paths for evaluation
            val docPath = "$outputDir/vision-docs.txt"
            val tracePath = "$outputDir/task-${task.id}-trace.json"

            val agent =
                VisionAgent(
                    llm = visionLlm,
                    profile = profile,
                    robot = robot,
                    documentationPath = docPath,
                    tracePath = tracePath,
                )

            val result =
                agent.execute(
                    intent = task.intent,
                    generateDocs = false,
                )

            val endTime = System.currentTimeMillis()
            val timeSeconds = (endTime - startTime) / 1000.0

            return TaskResult(
                taskId = task.id,
                approach = "vision",
                success = result.success,
                steps = result.actionsTaken,
                timeSeconds = timeSeconds,
                reward = MetricsCalculator.calculateReward(task, result.success, result.actionsTaken),
                errorMessage = if (!result.success) result.message else null,
            )
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            return TaskResult(
                taskId = task.id,
                approach = "vision",
                success = false,
                steps = 0,
                timeSeconds = (endTime - startTime) / 1000.0,
                reward = 0.0,
                errorMessage = e.message,
            )
        }
    }

    /**
     * Run quick evaluation on subset of tasks.
     * @param runExplorationFirst If true, runs exploration phase before tasks
     */
    fun runQuickEvaluation(runExplorationFirst: Boolean = false): Pair<EvaluationMetrics, EvaluationMetrics> {
        log.info("Running quick evaluation (5 tasks)...")

        File(outputDir).mkdirs()

        // Optional: Run exploration first
        if (runExplorationFirst) {
            runExploration()
        }

        val quickTasks = BenchmarkTasks.getQuickTestTasks().take(5)
        val uiTreeResults = mutableListOf<TaskResult>()
        val visionResults = mutableListOf<TaskResult>()

        for (task in quickTasks) {
            log.info("=== TASK: ${task.id} ===")

            val uiTreeResult = runUiTreeApproach(task)
            uiTreeResults.add(uiTreeResult)

            Thread.sleep(actionDelayMs / 2)
            dismissPopups()

            val visionResult = runVisionApproach(task)
            visionResults.add(visionResult)

            Thread.sleep(actionDelayMs / 2)
            dismissPopups()
        }

        val uiTreeMetrics = MetricsCalculator.calculateMetrics("ui_tree", uiTreeResults)
        val visionMetrics = MetricsCalculator.calculateMetrics("vision", visionResults)

        log.info(MetricsCalculator.generateComparisonTable(uiTreeMetrics, visionMetrics))

        return Pair(uiTreeMetrics, visionMetrics)
    }

    /**
     * Dismiss any open popups/dialogs.
     */
    private fun dismissPopups() {
        repeat(3) {
            try {
                robot.keyboard { key(KeyEvent.VK_ESCAPE) }
                Thread.sleep(actionDelayMs / 5)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        /**
         * Create an evaluation runner with default configuration.
         */
        fun createDefault(): EvaluationRunner {
            val robot = RemoteRobot("http://localhost:$robotPort")
            val config = LlmConfig.loadFromEnv()
            val profile =
                ApplicationProfile.loadFromFile("build/reports/app-profile.json")
                    ?: ApplicationProfile(appName = "IntelliJ IDEA")
            return EvaluationRunner(robot, config, profile)
        }
    }
}
