package evaluation

import kotlinx.serialization.Serializable

/**
 * Benchmark tasks for evaluating GUI agent approaches.
 *
 * Each task represents an IDE refactoring operation with:
 * - Intent string (natural language instruction)
 * - Success criteria (how to verify completion)
 * - Expected steps (rough estimate for timeout)
 * - Category (for grouping results)
 */
object BenchmarkTasks {
    /**
     * A single benchmark task.
     */
    @Serializable
    data class Task(
        val id: String,
        val intent: String,
        val category: String,
        val expectedSteps: Int,
        val timeoutSeconds: Int,
        val successCriteria: String,
        val verifyCodeChange: Boolean = true,
    )

    /**
     * All benchmark tasks for 10-run evaluation.
     * Includes only: rename, extract, and change_signature operations.
     */
    val allTasks: List<Task> =
        listOf(
            // === Rename Operations (6 tasks) ===
            Task(
                id = "rename_method_1",
                intent = "rename method executeRecipe to runRecipe in UiExecutor.kt",
                category = "rename",
                expectedSteps = 6,
                timeoutSeconds = 60,
                successCriteria = "Method name changed from executeRecipe to runRecipe",
            ),
            Task(
                id = "rename_method_2",
                intent = "rename method focusEditor to setEditorFocus in UiExecutor.kt",
                category = "rename",
                expectedSteps = 6,
                timeoutSeconds = 60,
                successCriteria = "Method name changed from focusEditor to setEditorFocus",
            ),
            Task(
                id = "rename_variable_1",
                intent = "rename variable robot to ideRobot in UiExecutor.kt",
                category = "rename",
                expectedSteps = 5,
                timeoutSeconds = 45,
                successCriteria = "Variable name changed from robot to ideRobot",
            ),
            Task(
                id = "rename_variable_2",
                intent = "rename variable log to logger in TreeReasoner.kt",
                category = "rename",
                expectedSteps = 5,
                timeoutSeconds = 45,
                successCriteria = "Variable name changed from log to logger",
            ),
            Task(
                id = "rename_variable_3",
                intent = "rename variable actionDelayMs to actionDelay in AgentConfig.kt",
                category = "rename",
                expectedSteps = 5,
                timeoutSeconds = 45,
                successCriteria = "Variable name changed from actionDelayMs to actionDelay",
            ),
            Task(
                id = "rename_class_1",
                intent = "rename class TreeReasoner to IdeReasoner in reasoner/TreeReasoner.kt",
                category = "rename",
                expectedSteps = 6,
                timeoutSeconds = 60,
                successCriteria = "Class name changed from TreeReasoner to IdeReasoner",
            ),
            // === Extract Operations (3 tasks) ===
            Task(
                id = "extract_method_1",
                intent = "extract method from lines 196-258 in UiExecutor.kt (the executeClick method logic)",
                category = "extract",
                expectedSteps = 7,
                timeoutSeconds = 90,
                successCriteria = "New method created from selected lines",
            ),
            Task(
                id = "extract_method_2",
                intent = "extract method from the keyboard shortcut handling code in UiExecutor.kt",
                category = "extract",
                expectedSteps = 7,
                timeoutSeconds = 90,
                successCriteria = "New method created from selected code",
            ),
            Task(
                id = "extract_variable_1",
                intent = "extract variable 'timeout' from the timeout calculation in UiExecutor.kt",
                category = "extract",
                expectedSteps = 5,
                timeoutSeconds = 45,
                successCriteria = "New variable extracted from expression",
            ),
            // === Change Signature (1 task) ===
            Task(
                id = "change_signature_1",
                intent = "change signature of method executeRecipe: add parameter verbose: Boolean",
                category = "change_signature",
                expectedSteps = 8,
                timeoutSeconds = 90,
                successCriteria = "Method signature changed with new parameter",
            ),
        )

    /**
     * Get tasks by category.
     */
    fun getTasksByCategory(category: String): List<Task> = allTasks.filter { it.category == category }

    /**
     * Get all categories.
     */
    fun getCategories(): List<String> = allTasks.map { it.category }.distinct()

    /**
     * Get a subset of tasks for quick testing.
     */
    fun getQuickTestTasks(): List<Task> = allTasks.filter { it.expectedSteps <= 5 }

    /**
     * Summary of task categories.
     */
    fun summary(): String {
        val sb = StringBuilder()
        sb.append("Benchmark Tasks Summary\n")
        sb.append("=======================\n\n")

        for (category in getCategories()) {
            val tasks = getTasksByCategory(category)
            sb.append("$category: ${tasks.size} tasks\n")
            for (task in tasks) {
                sb.append("  - ${task.id}: ~${task.expectedSteps} steps\n")
            }
        }

        sb.append("\nTotal: ${allTasks.size} tasks\n")
        return sb.toString()
    }
}
