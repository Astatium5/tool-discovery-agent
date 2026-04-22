package evaluation

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Metrics for evaluating GUI agent performance.
 *
 * Matches AppAgent paper metrics:
 * - Success Rate (SR): Task completion within max steps
 * - Average Steps: Mean steps for successful tasks
 * - Reward: Progress-based scoring (partial credit)
 * - Time: Wall-clock seconds per task
 * - Error Rate: Failures due to wrong actions
 */
@Serializable
data class TaskResult(
    val taskId: String,
    // "ui_tree" or "vision"
    val approach: String,
    val success: Boolean,
    val steps: Int,
    val timeSeconds: Double,
    val reward: Double,
    val errorMessage: String? = null,
)

@Serializable
data class EvaluationMetrics(
    val approach: String,
    val totalTasks: Int,
    val successfulTasks: Int,
    val successRate: Double,
    val averageSteps: Double,
    val averageTime: Double,
    val averageReward: Double,
    val categoryResults: Map<String, CategoryMetrics>,
)

@Serializable
data class CategoryMetrics(
    val category: String,
    val tasks: Int,
    val successRate: Double,
    val averageSteps: Double,
)

/**
 * Calculate metrics from task results.
 */
object MetricsCalculator {
    private val json = Json { prettyPrint = true }

    /**
     * Calculate overall metrics for an approach.
     */
    fun calculateMetrics(
        approach: String,
        results: List<TaskResult>,
    ): EvaluationMetrics {
        val successful = results.filter { it.success }
        val successRate =
            if (results.isNotEmpty()) {
                successful.size.toDouble() / results.size
            } else {
                0.0
            }

        val averageSteps =
            if (successful.isNotEmpty()) {
                successful.map { it.steps }.average()
            } else {
                0.0
            }

        val averageTime =
            if (successful.isNotEmpty()) {
                successful.map { it.timeSeconds }.average()
            } else {
                0.0
            }

        val averageReward =
            if (results.isNotEmpty()) {
                results.map { it.reward }.average()
            } else {
                0.0
            }

        // Calculate per-category metrics
        val categoryResults =
            results
                .groupBy { BenchmarkTasks.allTasks.find { t -> t.id == it.taskId }?.category ?: "unknown" }
                .mapValues { (category, categoryResults) ->
                    val categorySuccessful = categoryResults.filter { it.success }
                    CategoryMetrics(
                        category = category,
                        tasks = categoryResults.size,
                        successRate =
                            if (categoryResults.isNotEmpty()) {
                                categorySuccessful.size.toDouble() / categoryResults.size
                            } else {
                                0.0
                            },
                        averageSteps =
                            if (categorySuccessful.isNotEmpty()) {
                                categorySuccessful.map { it.steps }.average()
                            } else {
                                0.0
                            },
                    )
                }

        return EvaluationMetrics(
            approach = approach,
            totalTasks = results.size,
            successfulTasks = successful.size,
            successRate = successRate,
            averageSteps = averageSteps,
            averageTime = averageTime,
            averageReward = averageReward,
            categoryResults = categoryResults,
        )
    }

    /**
     * Calculate reward for a task (partial credit based on progress).
     *
     * Reward = 1.0 if successful
     * Reward = 0.5-0.9 if partially complete (based on steps taken vs expected)
     * Reward = 0.0 if failed early
     */
    fun calculateReward(
        task: BenchmarkTasks.Task,
        success: Boolean,
        stepsTaken: Int,
    ): Double {
        return when {
            success -> 1.0
            stepsTaken >= task.expectedSteps -> 0.5 // Got close
            stepsTaken >= task.expectedSteps / 2 -> 0.3 // Halfway
            stepsTaken > 1 -> 0.1 // Started but failed
            else -> 0.0 // Failed immediately
        }
    }

    /**
     * Generate comparison table for both approaches.
     */
    fun generateComparisonTable(
        uiTreeMetrics: EvaluationMetrics,
        visionMetrics: EvaluationMetrics,
    ): String {
        val sb = StringBuilder()
        sb.append("\n=== EVALUATION COMPARISON ===\n\n")

        sb.append("| Metric | UI Tree Approach | Vision Approach |\n")
        sb.append("|--------|-----------------|----------------|\n")
        sb.append(
            "| Success Rate | ${"%.1f".format(uiTreeMetrics.successRate * 100)}% | ${"%.1f".format(visionMetrics.successRate * 100)}% |\n",
        )
        sb.append("| Avg Steps | ${"%.1f".format(uiTreeMetrics.averageSteps)} | ${"%.1f".format(visionMetrics.averageSteps)} |\n")
        sb.append("| Avg Time | ${"%.1f".format(uiTreeMetrics.averageTime)}s | ${"%.1f".format(visionMetrics.averageTime)}s |\n")
        sb.append("| Avg Reward | ${"%.2f".format(uiTreeMetrics.averageReward)} | ${"%.2f".format(visionMetrics.averageReward)} |\n")
        sb.append("| Tasks Completed | ")
        sb.append("${uiTreeMetrics.successfulTasks}/${uiTreeMetrics.totalTasks} | ")
        sb.append("${visionMetrics.successfulTasks}/${visionMetrics.totalTasks} |\n")

        sb.append("\n=== CATEGORY BREAKDOWN ===\n\n")
        for (category in BenchmarkTasks.getCategories()) {
            val uiCat = uiTreeMetrics.categoryResults[category]
            val visCat = visionMetrics.categoryResults[category]

            if (uiCat != null && visCat != null) {
                sb.append("$category:\n")
                sb.append(
                    "  UI Tree:   ${"%.1f".format(uiCat.successRate * 100)}% success, ${"%.1f".format(uiCat.averageSteps)} avg steps\n",
                )
                sb.append(
                    "  Vision:    ${"%.1f".format(visCat.successRate * 100)}% success, ${"%.1f".format(visCat.averageSteps)} avg steps\n",
                )
            }
        }

        return sb.toString()
    }

    /**
     * Export results to JSON.
     */
    fun exportResults(
        results: List<TaskResult>,
        path: String,
    ) {
        val content = json.encodeToString(results)
        java.io.File(path).writeText(content)
    }

    /**
     * Export metrics to JSON.
     */
    fun exportMetrics(
        metrics: EvaluationMetrics,
        path: String,
    ) {
        val content = json.encodeToString(metrics)
        java.io.File(path).writeText(content)
    }
}
