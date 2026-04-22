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
 * 50 refactoring tasks for Apache Flink (main branch).
 *
 * Branch: main (https://github.com/apache/flink)
 *
 * Design rules (to avoid blocked refactorings):
 *   - Rename   → ONLY private methods (never overrides an interface or superclass).
 *   - Change Signature → ONLY private methods (no interface contract to break).
 *   - Introduce Variable/Constant/Parameter/Functional Parameter → safe anywhere.
 *   - Extract Function → code blocks identified by BOTH approximate line number range
 *       AND a code-snippet anchor so the agent can find them even if lines shifted.
 *   - Extract Interface / Superclass → always safe (new type, no existing contract).
 *   - Inline Function → ONLY private single-call helpers.
 *
 * All methods have been verified to exist in the current Apache Flink main branch.
 *
 * BEFORE RUNNING:
 *   Terminal 1 -> ./gradlew runIdeForUiTests   (Flink project open in IntelliJ)
 *   Terminal 2 -> ./gradlew test --tests "agent.UiAgentTestExtended"
 */
class UiAgentTestExtended : BaseTest() {

    private lateinit var uiAgent: UiAgent
    private lateinit var llm: ChatModel
    private lateinit var profile: ApplicationProfile
    private lateinit var executor: UiExecutor

    @BeforeEach
    fun setup() {
        llm = LlmModel.create(
            apiKey = "sk-sp-494544412a3b4e4c8aa38d6555a4cdac",
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
    // RENAME  — 6 tasks
    // All targets are `private` methods: no interface or superclass
    // override is possible, so IntelliJ will never block the rename.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("01 · Rename · private completePendingCheckpoint → finalizePendingCheckpoint (CheckpointCoordinator.java)")
    fun task01_renameCompletePendingCheckpoint() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, refactor to rename the private method " +
                    "completePendingCheckpoint to finalizePendingCheckpoint"
        )
        assert(result.success) { "Task 01 failed: ${result.message}" }
    }

    @Test
    @DisplayName("02 · Rename · private rescheduleTrigger → rescheduleCheckpointTrigger (CheckpointCoordinator.java)")
    fun task02_renameRescheduleTrigger() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, refactor to rename the private method " +
                    "rescheduleTrigger to rescheduleCheckpointTrigger"
        )
        assert(result.success) { "Task 02 failed: ${result.message}" }
    }

    @Test
    @DisplayName("03 · Rename · private scheduleTriggerWithDelay → scheduleDelayedTrigger (CheckpointCoordinator.java)")
    fun task03_renameScheduleTriggerWithDelay() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, refactor to rename the private method " +
                    "scheduleTriggerWithDelay to scheduleDelayedTrigger"
        )
        assert(result.success) { "Task 03 failed: ${result.message}" }
    }

    @Test
    @DisplayName("04 · Rename · private cancelPeriodicTrigger → cancelScheduledTrigger (CheckpointCoordinator.java)")
    fun task04_renameCancelPeriodicTrigger() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, refactor to rename the private method " +
                    "cancelPeriodicTrigger to cancelScheduledTrigger"
        )
        assert(result.success) { "Task 04 failed: ${result.message}" }
    }

    @Test
    @DisplayName("05 · Rename · private legacyTransform → transformLegacyNode (StreamGraphGenerator.java)")
    fun task05_renameLegacyTransform() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file StreamGraphGenerator.java, refactor to rename the private method " +
                    "legacyTransform to transformLegacyNode"
        )
        assert(result.success) { "Task 05 failed: ${result.message}" }
    }

    @Test
    @DisplayName("06 · Rename · private translate → translateTransformation (StreamGraphGenerator.java)")
    fun task06_renameTranslate() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file StreamGraphGenerator.java, refactor to rename the private method " +
                    "translate to translateTransformation"
        )
        assert(result.success) { "Task 06 failed: ${result.message}" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CHANGE SIGNATURE — 6 tasks
    // All targets are `private` methods: no interface contract to break.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("07 · Change Signature · private cancelPeriodicTrigger: add param boolean forceCancel (CheckpointCoordinator.java)")
    fun task07_addParamCancelPeriodicTrigger() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Change Signature of the private method " +
                    "cancelPeriodicTrigger: add parameter (boolean forceCancel)"
        )
        assert(result.success) { "Task 07 failed: ${result.message}" }
    }

    @Test
    @DisplayName("08 · Change Signature · private rescheduleTrigger: add param long maxDelayMillis (CheckpointCoordinator.java)")
    fun task08_addParamRescheduleTrigger() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Change Signature of the private method " +
                    "rescheduleTrigger: add parameter (long maxDelayMillis)"
        )
        assert(result.success) { "Task 08 failed: ${result.message}" }
    }

    @Test
    @DisplayName("09 · Change Signature · private scheduleTriggerWithDelay: add param TimeUnit timeUnit (CheckpointCoordinator.java)")
    fun task09_addParamScheduleTrigger() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Change Signature of the private method " +
                    "scheduleTriggerWithDelay: add parameter (TimeUnit timeUnit)"
        )
        assert(result.success) { "Task 09 failed: ${result.message}" }
    }

    @Test
    @DisplayName("10 · Change Signature · private completePendingCheckpoint: change visibility Private → Package-private (CheckpointCoordinator.java)")
    fun task10_changeVisibilityCompletePendingCheckpoint() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Change Signature of the private method " +
                    "completePendingCheckpoint: change visibility to Package-private"
        )
        assert(result.success) { "Task 10 failed: ${result.message}" }
    }

    @Test
    @DisplayName("11 · Change Signature · private legacyTransform: change visibility Private → Package-private (StreamGraphGenerator.java)")
    fun task11_changeVisibilityLegacyTransform() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file StreamGraphGenerator.java, Change Signature of the private method " +
                    "legacyTransform: change visibility to Package-private"
        )
        assert(result.success) { "Task 11 failed: ${result.message}" }
    }

    @Test
    @DisplayName("12 · Change Signature · private getParentInputIds: add param boolean includeVirtualNodes (StreamGraphGenerator.java)")
    fun task12_addParamGetParentInputIds() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file StreamGraphGenerator.java, Change Signature of the private method " +
                    "getParentInputIds: add parameter (boolean includeVirtualNodes)"
        )
        assert(result.success) { "Task 12 failed: ${result.message}" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // INTRODUCE VARIABLE — 5 tasks
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("13 · Introduce Variable · pendingCheckpoint.getCheckpointID() → checkpointId (completePendingCheckpoint)")
    fun task13_introduceVarCheckpointId() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, inside the method completePendingCheckpoint, " +
                    "Introduce Variable for the expression pendingCheckpoint.getCheckpointID() " +
                    "and name it checkpointId"
        )
        assert(result.success) { "Task 13 failed: ${result.message}" }
    }

    @Test
    @DisplayName("14 · Introduce Variable · pendingCheckpoints.size() → pendingCount (abortPendingCheckpoints)")
    fun task14_introduceVarPendingCount() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, inside the method abortPendingCheckpoints, " +
                    "Introduce Variable for the expression pendingCheckpoints.size() " +
                    "and name it pendingCount"
        )
        assert(result.success) { "Task 14 failed: ${result.message}" }
    }

    @Test
    @DisplayName("15 · Introduce Variable · pendingCheckpoint.getCheckpointPlan().getTasksToCommitTo() → tasksToCommit (completePendingCheckpoint)")
    fun task15_introduceVarTasksToCommit() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, inside the method completePendingCheckpoint, " +
                    "Introduce Variable for the expression pendingCheckpoint.getCheckpointPlan().getTasksToCommitTo() " +
                    "and name it tasksToCommit"
        )
        assert(result.success) { "Task 15 failed: ${result.message}" }
    }

    @Test
    @DisplayName("16 · Introduce Variable · completedCheckpoint.getCheckpointID() → completedCheckpointId (cleanupAfterCompletedCheckpoint)")
    fun task16_introduceVarCompletedCheckpointId() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, inside the method cleanupAfterCompletedCheckpoint, " +
                    "Introduce Variable for the expression completedCheckpoint.getCheckpointID() " +
                    "and name it completedCheckpointId"
        )
        assert(result.success) { "Task 16 failed: ${result.message}" }
    }

    @Test
    @DisplayName("17 · Introduce Variable · transform.getOutputType() → outputType (legacyTransform)")
    fun task17_introduceVarOutputType() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file StreamGraphGenerator.java, inside the method legacyTransform, " +
                    "Introduce Variable for the expression transform.getOutputType() " +
                    "and name it outputType"
        )
        assert(result.success) { "Task 17 failed: ${result.message}" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // INTRODUCE CONSTANT — 5 tasks
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("18 · Introduce Constant · string 'Stopping checkpoint coordinator' → SHUTDOWN_LOG_MESSAGE (CheckpointCoordinator.java)")
    fun task18_introduceConstantShutdownLogMessage() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Introduce Constant for the string literal " +
                    "\"Stopping checkpoint coordinator for job {}.\" used in the shutdown method " +
                    "and name it SHUTDOWN_LOG_MESSAGE"
        )
        assert(result.success) { "Task 18 failed: ${result.message}" }
    }

    @Test
    @DisplayName("19 · Introduce Constant · Long.MAX_VALUE → NO_SCHEDULED_TRIGGER_TIME (CheckpointCoordinator.java)")
    fun task19_introduceConstantNoScheduledTriggerTime() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Introduce Constant for the literal " +
                    "Long.MAX_VALUE used for nextCheckpointTriggeringRelativeTime " +
                    "and name it NO_SCHEDULED_TRIGGER_TIME"
        )
        assert(result.success) { "Task 19 failed: ${result.message}" }
    }

    @Test
    @DisplayName("20 · Introduce Constant · \"Checkpoint {} of job {} expired\" → CHECKPOINT_EXPIRED_LOG_MESSAGE (CheckpointCoordinator.java)")
    fun task20_introduceConstantCheckpointExpiredMessage() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Introduce Constant for the string literal " +
                    "\"Checkpoint {} of job {} expired before completing.\" used in CheckpointCanceller " +
                    "and name it CHECKPOINT_EXPIRED_LOG_MESSAGE"
        )
        assert(result.success) { "Task 20 failed: ${result.message}" }
    }

    @Test
    @DisplayName("21 · Introduce Constant · \"Completed checkpoint {} for job\" → COMPLETED_CHECKPOINT_LOG_PREFIX (CheckpointCoordinator.java)")
    fun task21_introduceConstantCompletedCheckpointLogPrefix() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Introduce Constant for the string literal " +
                    "\"Completed checkpoint {} for job {}\" used in logCheckpointInfo method " +
                    "and name it COMPLETED_CHECKPOINT_LOG_PREFIX"
        )
        assert(result.success) { "Task 21 failed: ${result.message}" }
    }

    @Test
    @DisplayName("22 · Introduce Constant · \"default\" literal → DEFAULT_GROUP_NAME_LITERAL (StreamGraphGenerator.java)")
    fun task22_introduceConstantDefaultGroupName() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file StreamGraphGenerator.java, Introduce Constant for the string literal " +
                    "\"default\" used in determineSlotSharingGroup method " +
                    "and name it DEFAULT_GROUP_NAME_LITERAL"
        )
        assert(result.success) { "Task 22 failed: ${result.message}" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // INTRODUCE PARAMETER — 5 tasks
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("23 · Introduce Parameter · hardcoded log message → shutdownLogMessage in shutdown() (CheckpointCoordinator.java)")
    fun task23_introduceParamShutdownMessage() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, inside the method shutdown, " +
                    "Introduce Parameter for the hardcoded string in the log message " +
                    "\"Stopping checkpoint coordinator for job {}.\" " +
                    "and name the parameter shutdownLogMessage"
        )
        assert(result.success) { "Task 23 failed: ${result.message}" }
    }

    @Test
    @DisplayName("24 · Introduce Parameter · hardcoded TimeUnit.MILLISECONDS in scheduleTriggerWithDelay → delayUnit (CheckpointCoordinator.java)")
    fun task24_introduceParamDelayUnit() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, inside the private method scheduleTriggerWithDelay, " +
                    "Introduce Parameter for the hardcoded TimeUnit.MILLISECONDS " +
                    "and name the parameter delayUnit"
        )
        assert(result.success) { "Task 24 failed: ${result.message}" }
    }

    @Test
    @DisplayName("25 · Introduce Parameter · hardcoded checkpointProperties field → defaultProperties in triggerCheckpoint (CheckpointCoordinator.java)")
    fun task25_introduceParamProps() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, inside the method triggerCheckpoint(boolean isPeriodic), " +
                    "Introduce Parameter for the hardcoded field checkpointProperties " +
                    "and name the parameter defaultProperties"
        )
        assert(result.success) { "Task 25 failed: ${result.message}" }
    }

    @Test
    @DisplayName("26 · Introduce Parameter · hardcoded triggerDelay field → initialDelay in startCheckpointScheduler (CheckpointCoordinator.java)")
    fun task26_introduceParamInitialDelay() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, inside the method startCheckpointScheduler, " +
                    "Introduce Parameter for the hardcoded field triggerDelay " +
                    "and name the parameter initialDelay"
        )
        assert(result.success) { "Task 26 failed: ${result.message}" }
    }

    @Test
    @DisplayName("27 · Introduce Parameter · hardcoded clock.relativeTimeMillis() → currentRelativeTimeSupplier in startCheckpointScheduler (CheckpointCoordinator.java)")
    fun task27_introduceParamCurrentTimeSupplier() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, inside the method startCheckpointScheduler, " +
                    "Introduce Parameter for the hardcoded call clock.relativeTimeMillis() " +
                    "and name the parameter currentRelativeTimeSupplier"
        )
        assert(result.success) { "Task 27 failed: ${result.message}" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // INTRODUCE FUNCTIONAL PARAMETER — 3 tasks
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("28 · Introduce Functional Parameter · checkpoint filter predicate → checkpointFilter in abortPendingCheckpoints (CheckpointCoordinator.java)")
    fun task28_introduceFuncParamAbortAction() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, inside the method abortPendingCheckpoints, " +
                    "Introduce Functional Parameter for the lambda that filters " +
                    "checkpointToFailPredicate in the stream " +
                    "and name the parameter checkpointFilter"
        )
        assert(result.success) { "Task 28 failed: ${result.message}" }
    }

    @Test
    @DisplayName("29 · Introduce Functional Parameter · masterHooks.containsKey(id) predicate → hookExistsCheck in addMasterHook (CheckpointCoordinator.java)")
    fun task29_introduceFuncParamHookExistsCheck() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, inside the method addMasterHook, " +
                    "Introduce Functional Parameter for the lambda that checks " +
                    "masterHooks.containsKey(id) " +
                    "and name the parameter hookExistsCheck"
        )
        assert(result.success) { "Task 29 failed: ${result.message}" }
    }

    @Test
    @DisplayName("30 · Introduce Functional Parameter · transformation filter predicate → transformationFilter in transform (StreamGraphGenerator.java)")
    fun task30_introduceFuncParamTransformationFilter() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file StreamGraphGenerator.java, inside the method transform, " +
                    "Introduce Functional Parameter for the lambda that filters " +
                    "transformations based on whether they have already been transformed " +
                    "and name the parameter transformationFilter"
        )
        assert(result.success) { "Task 30 failed: ${result.message}" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // EXTRACT FUNCTION — 8 tasks
    // Each task cites the containing method + a code-snippet anchor
    // so the agent can locate the block unambiguously.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("31 · Extract Function · shutdown(): lines 436-446 → cleanupMasterHooksAndAbort (CheckpointCoordinator.java)")
    fun task31_extractFnCleanupMasterHooksAndAbort() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, in method shutdown, do extract function from lines 436-446 " +
                    "and name the function cleanupMasterHooksAndAbort"
        )
        assert(result.success) { "Task 31 failed: ${result.message}" }
    }

    @Test
    @DisplayName("32 · Extract Function · constructor: lines 350-360 → initializeCheckpointStorage (CheckpointCoordinator.java)")
    fun task32_extractFnInitializeCheckpointStorage() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, in the constructor, do extract function from lines 350-360 " +
                    "and name the function initializeCheckpointStorage"
        )
        assert(result.success) { "Task 32 failed: ${result.message}" }
    }

    @Test
    @DisplayName("33 · Extract Function · constructor: lines 362-369 → startCheckpointIdCounter (CheckpointCoordinator.java)")
    fun task33_extractFnStartCheckpointIdCounter() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, in the constructor, do extract function from lines 362-369 " +
                    "and name the function startCheckpointIdCounter"
        )
        assert(result.success) { "Task 33 failed: ${result.message}" }
    }

    @Test
    @DisplayName("34 · Extract Function · completePendingCheckpoint: lines 1382-1395 → reportAndCleanupCheckpoint (CheckpointCoordinator.java)")
    fun task34_extractFnReportAndCleanupCheckpoint() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, in method completePendingCheckpoint, do extract function from lines 1382-1395 " +
                    "and name the function reportAndCleanupCheckpoint"
        )
        assert(result.success) { "Task 34 failed: ${result.message}" }
    }

    @Test
    @DisplayName("35 · Extract Function · completePendingCheckpoint: lines 1368-1380 → finalizeAndStoreCheckpoint (CheckpointCoordinator.java)")
    fun task35_extractFnFinalizeAndStoreCheckpoint() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, in method completePendingCheckpoint, do extract function from lines 1368-1380 " +
                    "and name the function finalizeAndStoreCheckpoint"
        )
        assert(result.success) { "Task 35 failed: ${result.message}" }
    }

    @Test
    @DisplayName("36 · Extract Function · generate: lines 254-258 → configureAndInitializeStreamGraph (StreamGraphGenerator.java)")
    fun task36_extractFnConfigureAndInitializeStreamGraph() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file StreamGraphGenerator.java, in method generate, do extract function from lines 254-258 " +
                    "and name the function configureAndInitializeStreamGraph"
        )
        assert(result.success) { "Task 36 failed: ${result.message}" }
    }

    @Test
    @DisplayName("37 · Extract Function · generate: lines 260-265 → transformAllTransformations (StreamGraphGenerator.java)")
    fun task37_extractFnTransformAllTransformations() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file StreamGraphGenerator.java, in method generate, do extract function from lines 260-265 " +
                    "and name the function transformAllTransformations"
        )
        assert(result.success) { "Task 37 failed: ${result.message}" }
    }

    @Test
    @DisplayName("38 · Extract Function · generate: lines 281-292 → setupDistributedCache (StreamGraphGenerator.java)")
    fun task38_extractFnSetupDistributedCache() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file StreamGraphGenerator.java, in method generate, do extract function from lines 281-292 " +
                    "and name the function setupDistributedCache"
        )
        assert(result.success) { "Task 38 failed: ${result.message}" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // EXTRACT INTERFACE — 4 tasks
    // (Always safe — creates a brand-new type.)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("39 · Extract Interface · addMasterHook + getNumberOfRegisteredMasterHooks → MasterHookRegistry from CheckpointCoordinator.java")
    fun task39_extractInterfaceMasterHookRegistry() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Extract Interface " +
                    "with public methods addMasterHook and getNumberOfRegisteredMasterHooks " +
                    "and name the interface MasterHookRegistry"
        )
        assert(result.success) { "Task 39 failed: ${result.message}" }
    }

    @Test
    @DisplayName("40 · Extract Interface · isShutdown + shutdown → ShutdownCapable from CheckpointCoordinator.java")
    fun task40_extractInterfaceShutdownCapable() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Extract Interface " +
                    "with public methods isShutdown and shutdown " +
                    "and name the interface ShutdownCapable"
        )
        assert(result.success) { "Task 40 failed: ${result.message}" }
    }

    @Test
    @DisplayName("41 · Extract Interface · generate → GraphGenerator from StreamGraphGenerator.java")
    fun task41_extractInterfaceGraphGenerator() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file StreamGraphGenerator.java, Extract Interface " +
                    "with public method generate " +
                    "and name the interface GraphGenerator"
        )
        assert(result.success) { "Task 41 failed: ${result.message}" }
    }

    @Test
    @DisplayName("42 · Extract Interface · triggerCheckpoint + triggerSavepoint → CheckpointTrigger from CheckpointCoordinator.java)")
    fun task42_extractInterfaceCheckpointTrigger() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Extract Interface " +
                    "with public methods triggerCheckpoint and triggerSavepoint " +
                    "and name the interface CheckpointTrigger"
        )
        assert(result.success) { "Task 42 failed: ${result.message}" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // EXTRACT SUPERCLASS — 4 tasks
    // (Always safe — creates a brand-new abstract type.)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("43 · Extract Superclass · shutdown + isShutdown → AbstractCheckpointCoordinator from CheckpointCoordinator.java")
    fun task43_extractSuperclassAbstractCheckpointCoordinator() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Extract Superclass " +
                    "with methods shutdown and isShutdown " +
                    "and name the superclass AbstractCheckpointCoordinator"
        )
        assert(result.success) { "Task 43 failed: ${result.message}" }
    }

    @Test
    @DisplayName("44 · Extract Superclass · getNumberOfRegisteredMasterHooks → CheckpointCoordinatorBase from CheckpointCoordinator.java")
    fun task44_extractSuperclassCheckpointCoordinatorBase() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Extract Superclass " +
                    "with method getNumberOfRegisteredMasterHooks " +
                    "and name the superclass CheckpointCoordinatorBase"
        )
        assert(result.success) { "Task 44 failed: ${result.message}" }
    }

    @Test
    @DisplayName("45 · Extract Superclass · generate + setSlotSharingGroupResource → AbstractStreamGraphGenerator from StreamGraphGenerator.java")
    fun task45_extractSuperclassAbstractStreamGraphGenerator() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file StreamGraphGenerator.java, Extract Superclass " +
                    "with methods generate and setSlotSharingGroupResource " +
                    "and name the superclass AbstractStreamGraphGenerator"
        )
        assert(result.success) { "Task 45 failed: ${result.message}" }
    }

    @Test
    @DisplayName("46 · Extract Superclass · addMasterHook + getNumberOfRegisteredMasterHooks → MasterHookCoordinator from CheckpointCoordinator.java")
    fun task46_extractSuperclassMasterHookCoordinator() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Extract Superclass " +
                    "with methods addMasterHook and getNumberOfRegisteredMasterHooks " +
                    "and name the superclass MasterHookCoordinator"
        )
        assert(result.success) { "Task 46 failed: ${result.message}" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // INLINE FUNCTION — 4 tasks
    // All targets are PRIVATE helpers with a small, localised call-site.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("47 · Inline Function · private cancelPeriodicTrigger → inline into its caller (CheckpointCoordinator.java)")
    fun task47_inlineCancelPeriodicTrigger() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Inline Function for the private method " +
                    "cancelPeriodicTrigger into all its call sites and remove the method"
        )
        assert(result.success) { "Task 47 failed: ${result.message}" }
    }

    @Test
    @DisplayName("48 · Inline Function · private rescheduleTrigger → inline into its caller (CheckpointCoordinator.java)")
    fun task48_inlineRescheduleTrigger() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Inline Function for the private method " +
                    "rescheduleTrigger into all its call sites and remove the method"
        )
        assert(result.success) { "Task 48 failed: ${result.message}" }
    }

    @Test
    @DisplayName("49 · Inline Function · private scheduleTriggerWithDelay → inline into its callers (CheckpointCoordinator.java)")
    fun task49_inlineScheduleTriggerWithDelay() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file CheckpointCoordinator.java, Inline Function for the private method " +
                    "scheduleTriggerWithDelay into all its call sites and remove the method"
        )
        assert(result.success) { "Task 49 failed: ${result.message}" }
    }

    @Test
    @DisplayName("50 · Inline Function · private getParentInputIds → inline into its caller (StreamGraphGenerator.java)")
    fun task50_inlineGetParentInputIds() {
        uiAgent.clearRecipes()
        val result = uiAgent.execute(
            "in file StreamGraphGenerator.java, Inline Function for the private method " +
                    "getParentInputIds into all its call sites and remove the method"
        )
        assert(result.success) { "Task 50 failed: ${result.message}" }
    }
}