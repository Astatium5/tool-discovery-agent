package model

/**
 * Result of executing a single action.
 *
 * Used by Executor implementations to report action outcomes.
 */
data class ActionResult(
    val success: Boolean,
    val message: String,
    val documentation: String? = null,
)