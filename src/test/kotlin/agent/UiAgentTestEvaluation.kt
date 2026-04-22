package agent

import dev.langchain4j.model.chat.ChatModel
import execution.UiExecutor
import llm.LlmModel
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import profile.ApplicationProfile
import test.BaseTest

/**
 * 10 selected refactoring tasks for class project evaluation.
 *
 * Extracted from the full 50-task Apache Flink benchmark (UiAgentTestExtended.kt).
 * Covers diverse refactoring categories: Rename, Change Signature, Introduce Variable,
 * Introduce Constant, Introduce Parameter, Extract Function, Extract Interface.
 *
 * BEFORE RUNNING:
 *   Terminal 1 -> ./gradlew runIdeForUiTests   (Apache Flink project open in IntelliJ)
 *   Terminal 2 -> ./gradlew test --tests "agent.UiAgentTestEvaluation"
 */
class UiAgentTestEvaluation : BaseTest() {

    private lateinit var uiAgent: UiAgent
    private lateinit var llm: ChatModel
    private lateinit var profile: ApplicationProfile
    private lateinit var executor: UiExecutor

    @BeforeEach
    fun setup() {
        llm = LlmModel.create(
            apiKey = "",
            baseUrl = "https://coding-intl.dashscope.aliyuncs.com/v1",
            model = "MiniMax-M2.5",
        )
        profile = ApplicationProfile.loadFromFile("build/reports/app-profile.json")
            ?: ApplicationProfile(appName = "IntelliJ IDEA")
        executor = UiExecutor(robot)
        uiAgent = UiAgent(
            llm = llm,
            profile = profile,
            executor = executor,
            uiTreeProvider = { executor.fetchUiTree() },
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // RENAME — 2 tasks (private methods only)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("01 · Rename · private completePendingCheckpoint → finalizePendingCheckpoint")
    fun task01_renameCompletePendingCheckpoint() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, refactor to rename the private method " +
                    "completePendingCheckpoint to finalizePendingCheckpoint"
        )
        assert(result.success) { "Task 01 failed: ${result.message}" }
    }

    @Test
    @DisplayName("02 · Rename · private legacyTransform → transformLegacyNode")
    fun task02_renameLegacyTransform() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file StreamGraphGenerator.java, refactor to rename the private method " +
                    "legacyTransform to transformLegacyNode"
        )
        assert(result.success) { "Task 02 failed: ${result.message}" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CHANGE SIGNATURE — 2 tasks (private methods only)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("03 · Change Signature · add param boolean forceCancel to cancelPeriodicTrigger")
    fun task03_addParamCancelPeriodicTrigger() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Change Signature of the private method " +
                    "cancelPeriodicTrigger: add parameter (boolean forceCancel)"
        )
        assert(result.success) { "Task 03 failed: ${result.message}" }
    }

    @Test
    @DisplayName("04 · Change Signature · change visibility to Package-private for completePendingCheckpoint")
    fun task04_changeVisibilityCompletePendingCheckpoint() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Change Signature of the private method " +
                    "completePendingCheckpoint: change visibility to Package-private"
        )
        assert(result.success) { "Task 04 failed: ${result.message}" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // INTRODUCE VARIABLE — 2 tasks
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("05 · Introduce Variable · pendingCheckpoint.getCheckpointID() → checkpointId")
    fun task05_introduceVarCheckpointId() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, inside the method completePendingCheckpoint, " +
                    "Introduce Variable for the expression pendingCheckpoint.getCheckpointID() " +
                    "and name it checkpointId"
        )
        assert(result.success) { "Task 05 failed: ${result.message}" }
    }

    @Test
    @DisplayName("06 · Introduce Variable · transform.getOutputType() → outputType")
    fun task06_introduceVarOutputType() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file StreamGraphGenerator.java, inside the method legacyTransform, " +
                    "Introduce Variable for the expression transform.getOutputType() " +
                    "and name it outputType"
        )
        assert(result.success) { "Task 06 failed: ${result.message}" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // INTRODUCE CONSTANT — 1 task
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("07 · Introduce Constant · Long.MAX_VALUE → NO_SCHEDULED_TRIGGER_TIME")
    fun task07_introduceConstantNoScheduledTriggerTime() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Introduce Constant for the literal " +
                    "Long.MAX_VALUE used for nextCheckpointTriggeringRelativeTime " +
                    "and name it NO_SCHEDULED_TRIGGER_TIME"
        )
        assert(result.success) { "Task 07 failed: ${result.message}" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // INTRODUCE PARAMETER — 1 task
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("08 · Introduce Parameter · TimeUnit.MILLISECONDS → delayUnit in scheduleTriggerWithDelay")
    fun task08_introduceParamDelayUnit() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, inside the private method scheduleTriggerWithDelay, " +
                    "Introduce Parameter for the hardcoded TimeUnit.MILLISECONDS " +
                    "and name the parameter delayUnit"
        )
        assert(result.success) { "Task 08 failed: ${result.message}" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // EXTRACT FUNCTION — 1 task
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("09 · Extract Function · shutdown() lines 436-446 → cleanupMasterHooksAndAbort")
    fun task09_extractFnCleanupMasterHooksAndAbort() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, in method shutdown, do extract function from lines 436-446 " +
                    "and name the function cleanupMasterHooksAndAbort"
        )
        assert(result.success) { "Task 09 failed: ${result.message}" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // EXTRACT INTERFACE — 1 task
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("10 · Extract Interface · isShutdown + shutdown → ShutdownCapable")
    fun task10_extractInterfaceShutdownCapable() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Extract Interface " +
                    "with public methods isShutdown and shutdown " +
                    "and name the interface ShutdownCapable"
        )
        assert(result.success) { "Task 10 failed: ${result.message}" }
    }
}