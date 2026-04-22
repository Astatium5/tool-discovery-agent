package agent

import dev.langchain4j.model.chat.ChatModel
import llm.LlmConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import perception.vision.RemoteRobotScreenshotProvider
import profile.ApplicationProfile
import test.BaseTest
import vision.ElementInfo
import java.io.File

/**
 * Tests for Vision Agent implementing the actual AppAgent approach.
 *
 * AppAgent paper key features:
 * 1. Exploration = attempting the task directly (not random exploration)
 * 2. Reflection after each action: BACK/INEFFECTIVE/CONTINUE/SUCCESS
 * 3. Documentation generated from reflection comparing before/after screenshots
 *
 * BEFORE RUNNING:
 *   Terminal 1 -> ./gradlew runIdeForUiTests
 *   Terminal 2 -> ./gradlew test --tests "agent.VisionAgentTest"
 */
class VisionAgentTest : BaseTest() {
    private lateinit var visionAgent: VisionAgent
    private lateinit var llm: ChatModel
    private lateinit var profile: ApplicationProfile
    private val outputDir = "build/reports/vision-test"

    @BeforeEach
    fun setup() {
        val config = LlmConfig.loadFromEnv()
        llm = config.createChatModelForVision()
        profile = ApplicationProfile.loadFromFile("build/reports/app-profile.json")
            ?: ApplicationProfile(appName = "IntelliJ IDEA")

        File(outputDir).mkdirs()

        visionAgent =
            VisionAgent(
                llm = llm,
                profile = profile,
                robot = robot,
                documentationPath = "$outputDir/docs.txt",
                tracePath = "$outputDir/trace.json",
            )
    }

    @Test
    @DisplayName("Test AppAgent exploration - attempt task with reflection")
    fun testExplorationPhase() {
        println("\n=== TEST: AppAgent Exploration Phase ===\n")
        println("Approach: Attempt task directly, reflect on each action, generate docs")

        // Clear previous docs
        visionAgent.clearDocumentation()

        // Run exploration (actually just executing a task with doc generation)
        val docsGenerated =
            visionAgent.explore(
                explorationTask = "Navigate to the project tool window and click on a file",
            )

        println("\n  Docs generated: $docsGenerated")
        println("  Total docs: ${visionAgent.getDocCount()}")

        // Verify docs were created
        val docFile = File("$outputDir/docs.txt")
        if (docFile.exists()) {
            println("  Docs file content:")
            docFile.readLines().take(5).forEach { println("    $it") }
        }
    }

    @Test
    @DisplayName("Test deployment - use learned docs to complete task")
    fun testDeploymentPhase() {
        println("\n=== TEST: AppAgent Deployment Phase ===\n")

        // First explore to build docs
        visionAgent.clearDocumentation()
        visionAgent.explore(
            explorationTask = "Learn how to navigate the project tree",
        )

        // Now execute a task using the learned docs
        val result =
            visionAgent.execute(
                intent = "Open a file in the project tree",
                // Don't generate docs during deployment
                generateDocs = false,
            )

        println("\n  Result: ${result.message}")
        println("  Success: ${result.success}")
        println("  Actions: ${result.actionsTaken}")
    }

    @Test
    @DisplayName("Test screenshot capture with element labeling")
    fun testScreenshotCapture() {
        println("\n=== TEST: Screenshot Capture ===\n")

        val screenshotProvider = RemoteRobotScreenshotProvider(robot, profile)
        val result = screenshotProvider.capture()

        println("  Captured screenshot: ${result.screenshot.width}x${result.screenshot.height}")
        println("  Elements detected: ${result.elementMap.size}")

        for ((id, info) in result.elementMap.entries.sortedBy { it.key }.take(10)) {
            println("    [$id] ${info.label} (${info.role}) uid=${info.uid}")
        }

        assert(result.elementMap.isNotEmpty()) { "Should detect UI elements" }
    }

    @Test
    @DisplayName("Test full workflow: explore -> deploy for rename variable")
    fun testRenameVariableWorkflow() {
        println("\n=== TEST: Rename Variable Workflow ===\n")
        println("AppAgent approach: Attempt task, reflect, generate docs")

        // Clear docs
        visionAgent.clearDocumentation()

        // Phase 1: Exploration - attempt to rename, learn from reflection
        println("\n--- Phase 1: Exploration (attempt task) ---")
        val docs =
            visionAgent.explore(
                explorationTask = "Rename the variable 'myVariable' to 'newVariable'",
            )
        println("  Generated $docs documentation entries")

        // Phase 2: Deployment - use learned docs to complete task
        println("\n--- Phase 2: Deployment ---")
        val result =
            visionAgent.execute(
                intent = "Rename the variable 'myVariable' to 'newVariable'",
                // Don't generate docs during deployment
                generateDocs = false,
            )

        println("\n  Result: ${result.message}")
        println("  Success: ${result.success}")
    }
}
