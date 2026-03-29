package agent

import llm.LlmClient
import agent.UiAgent
import executor.UiExecutor
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import parser.UiComponent
import profile.ApplicationProfile
import test.BaseTest

/**
 * Tests for the UI Agent with LLM-centric architecture.
 *
 * These tests demonstrate the Observe-Reason-Act loop:
 * 1. Observe: Capture current UI state
 * 2. Reason: LLM decides next action
 * 3. Act: Execute the action
 * 4. Repeat until complete
 *
 * BEFORE RUNNING:
 *   Terminal 1 -> ./gradlew runIdeForUiTests
 *   Terminal 2 -> ./gradlew test --tests "agent.UiAgentTest"
 */
class UiAgentTest : BaseTest() {

    private lateinit var uiAgent: UiAgent
    private lateinit var llm: LlmClient
    private lateinit var profile: ApplicationProfile
    private lateinit var executor: UiExecutor

    @BeforeEach
    fun setup() {
        llm = LlmClient()
        profile = ApplicationProfile.loadFromFile("build/reports/app-profile.json")
            ?: ApplicationProfile(appName = "IntelliJ IDEA")
        
        // Create UiExecutor with the robot from BaseTest
        executor = UiExecutor(robot)

        // Create the UI agent with IDE integration
        uiAgent = UiAgent(
            llm = llm,
            profile = profile,
            executor = executor,
            uiTreeProvider = { executor.fetchUiTree() }
        )
    }

    @Test
    @DisplayName("Execute rename using UI Agent")
    fun testRenameWithUiAgent() {
        println("\n=== TEST: UI Agent Rename ===\n")

        // Clear any old recipes to force fresh discovery
        uiAgent.clearRecipes()

        // Execute with the UI agent
        val result = uiAgent.execute(
            "in file UiExecutor.kt, rename method executeRecipe to doWork"
        )

        println("\n  Result: ${result.message}")
        println("  Success: ${result.success}")
        println("  Actions taken: ${result.actionsTaken}")
        println("  Recipe saved: ${result.recipeSaved}")

        // Assertions
        assert(result.success) { "Rename should succeed" }
        assert(result.actionsTaken > 0) { "Should have taken some actions" }
        assert(result.recipeSaved) { "Should have saved a verified recipe" }
    }

    @Test
    @DisplayName("Execute extract function using UI Agent")
    fun testExtractFunctionWithUiAgent() {
        println("\n=== TEST: UI Agent Extract Function ===\n")

        // Execute with the UI agent
        val result = uiAgent.execute(
            "in file UiExecutor.kt, extract function from lines 149-153"
        )

        println("\n  Result: ${result.message}")
        println("  Success: ${result.success}")
        println("  Actions taken: ${result.actionsTaken}")

        // Note: This test may fail if the file doesn't have those lines
        // The brain agent should observe and adapt
    }

    @Test
    @DisplayName("Execute change signature using UI Agent")
    fun testChangeSignatureWithUiAgent() {
        println("\n=== TEST: UI Agent Change Signature ===\n")

        // Execute with the UI agent
        val result = uiAgent.execute(
            "in file UiExecutor.kt, change signature of method executeRecipe: (Return Type) Boolean to Void"
        )

        println("\n  Result: ${result.message}")
        println("  Success: ${result.success}")
        println("  Actions taken: ${result.actionsTaken}")

        // The brain agent should observe the dialog and interact with it
    }

    @Test
    @DisplayName("Use saved recipe for similar intent")
    fun testUseSavedRecipe() {
        println("\n=== TEST: UI Agent Use Saved Recipe ===\n")

        // First, execute a rename to save a recipe
        val firstResult = uiAgent.execute(
            "in file UiExecutor.kt, rename method focusEditor to setFocus"
        )

        println("\n  First execution:")
        println("    Success: ${firstResult.success}")
        println("    Recipe saved: ${firstResult.recipeSaved}")

        if (firstResult.recipeSaved) {
            // Now execute a similar intent - should use the saved recipe as reference
            val secondResult = uiAgent.execute(
                "in file UiExecutor.kt, rename method doWork to processRecipe"
            )

            println("\n  Second execution (should use recipe reference):")
            println("    Success: ${secondResult.success}")
            println("    Actions taken: ${secondResult.actionsTaken}")

            // The second execution should be more efficient
            // because the LLM has the recipe as context
        }
    }
}