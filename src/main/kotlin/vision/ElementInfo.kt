package vision

/**
 * Shared model for UI element information used by vision-based agents.
 *
 * Previously duplicated in RemoteRobotScreenshotProvider, now centralized
 * for use across VisionAgent, VisionReasoner, and UiExecutor.
 */
data class ElementInfo(
    val id: Int,
    val cls: String,
    val label: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val enabled: Boolean,
    val role: String,
    val xpath: String,
    val uid: String = "${cls}_${label.hashCode()}_${x}_$y",
)
