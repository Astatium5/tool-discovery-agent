package model

import recipe.VerifiedRecipe

/**
 * Agent execution result.
 *
 * Returned by all agent implementations after executing an intent.
 */
data class AgentResult(
    val success: Boolean,
    val message: String,
    val actionsTaken: Int = 0,
    val recipe: VerifiedRecipe? = null,
    val docsGenerated: Int = 0,
)