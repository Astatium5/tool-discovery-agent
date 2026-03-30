package agent

/**
 * Tracks successful states/milestones that have been achieved during task execution.
 * These persist even when action history is truncated, preventing the LLM from
 * repeating already-completed steps.
 *
 * Problem it solves:
 * - Action history is truncated to last 10 actions
 * - LLM "forgets" what was already accomplished
 * - Agent repeats successful steps (e.g., opening file again, targeting symbol again)
 *
 * Solution:
 * - Track semantic accomplishments (FILE_OPENED, SYMBOL_TARGETED, etc.)
 * - Include these in every LLM prompt with "Do NOT Repeat" guidance
 */
class SuccessfulStateRegistry {
    
    /**
     * Represents a successfully achieved state.
     */
    data class AchievedState(
        val stateType: StateType,
        val details: Map<String, String>,
        val achievedAtIteration: Int
    )
    
    /**
     * Types of states that can be achieved during UI automation.
     */
    enum class StateType {
        FILE_OPENED,           // Target file is in editor
        SYMBOL_TARGETED,       // Cursor is on target symbol
        CONTEXT_MENU_OPENED,   // Context menu is visible
        REFACTOR_MENU_OPENED,  // Refactor submenu is visible
        DIALOG_OPENED,         // Refactoring dialog is open
        FIELD_FOCUSED,         // Input field has focus
        VALUE_SELECTED,        // Dropdown/radio value selected
        DIALOG_CONFIRMED       // Dialog confirmed with button click
    }
    
    private val achievedStates = mutableMapOf<StateType, AchievedState>()
    
    /**
     * Mark a state as achieved.
     * Only records the first achievement of each state type.
     */
    fun achieve(type: StateType, details: Map<String, String> = emptyMap(), iteration: Int = 0) {
        if (!achievedStates.containsKey(type)) {
            achievedStates[type] = AchievedState(type, details, iteration)
            println("  ✓ State achieved: ${type.name}${if (details.isNotEmpty()) " ($details)" else ""}")
        }
    }
    
    /**
     * Check if a state has been achieved.
     */
    fun isAchieved(type: StateType): Boolean = achievedStates.containsKey(type)
    
    /**
     * Get details of an achieved state.
     */
    fun getDetails(type: StateType): Map<String, String>? = achievedStates[type]?.details
    
    /**
     * Get all achieved states.
     */
    fun getAllAchieved(): Map<StateType, AchievedState> = achievedStates.toMap()
    
    /**
     * Format for LLM prompt - tells the LLM what NOT to repeat.
     */
    fun formatForPrompt(): String {
        if (achievedStates.isEmpty()) {
            return "No states achieved yet."
        }
        
        val sb = StringBuilder()
        sb.append("## Already Completed - Do NOT Repeat\n")
        sb.append("These steps have been successfully completed:\n\n")
        
        achievedStates.values.sortedBy { it.achievedAtIteration }.forEach { state ->
            val detailsStr = if (state.details.isNotEmpty()) {
                " - ${state.details.entries.joinToString(", ") { "${it.key}=${it.value}" }}"
            } else ""
            sb.append("✓ ${formatStateType(state.stateType)}$detailsStr\n")
        }
        
        sb.append("\n**Important**: Do not repeat these steps. Focus on what remains to be done.\n")
        
        return sb.toString()
    }
    
    /**
     * Clear all states (for new task).
     */
    fun clear() {
        achievedStates.clear()
    }
    
    /**
     * Check if any states have been achieved.
     */
    fun hasAchievedStates(): Boolean = achievedStates.isNotEmpty()
    
    /**
     * Get count of achieved states.
     */
    fun count(): Int = achievedStates.size
    
    private fun formatStateType(type: StateType): String {
        return when (type) {
            StateType.FILE_OPENED -> "File opened in editor"
            StateType.SYMBOL_TARGETED -> "Cursor positioned on target symbol"
            StateType.CONTEXT_MENU_OPENED -> "Context menu opened"
            StateType.REFACTOR_MENU_OPENED -> "Refactor menu opened"
            StateType.DIALOG_OPENED -> "Refactoring dialog opened"
            StateType.FIELD_FOCUSED -> "Input field focused"
            StateType.VALUE_SELECTED -> "Value selected from dropdown"
            StateType.DIALOG_CONFIRMED -> "Dialog confirmed"
        }
    }
}