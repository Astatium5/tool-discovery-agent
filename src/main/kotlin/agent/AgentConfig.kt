package agent

/**
 * Configuration constants for the Tool Discovery Agent.
 *
 * All hardcoded values (iteration limits, delays, ports) are centralized here
 * for easy tuning and environment-specific overrides.
 */
object AgentConfig {
    // ── Iteration Limits ────────────────────────────────────────────────────────

    /** Maximum iterations in the observe-reason-act loop */
    var maxIterations: Int = 30

    /** Maximum retries for LLM response parsing */
    var maxRetries: Int = 3

    // ── Timing Constants ─────────────────────────────────────────────────────────

    /** Delay after observing UI before taking action (ms) */
    var observeDelayMs: Long = 500

    /** Delay after action execution for UI to update (ms) */
    var actionDelayMs: Long = 1000

    /** Delay after opening context menu (ms) */
    var contextMenuDelayMs: Long = 800

    /** Delay after clicking menu item (ms) */
    var menuClickDelayMs: Long = 600

    /** Delay after typing each character (ms per char) */
    var typingCharDelayMs: Long = 20

    /** Timeout for waiting for UI element to appear (ms) */
    var elementWaitTimeoutMs: Long = 3000

    /** Timeout for UI state analysis after click (ms) */
    var uiAnalysisTimeoutMs: Long = 2000

    /** Delay between retry attempts (ms) */
    var retryDelayMs: Long = 300

    /** Timeout for LLM response (ms) - not used directly but available */
    var llmTimeoutMs: Long = 30000

    // ── Remote Robot Configuration ───────────────────────────────────────────────

    /** Default port for Remote Robot server */
    var robotPort: Int = 8082

    /** Default timeout for Remote Robot operations (seconds) */
    var robotTimeoutSeconds: Long = 10

    // ── UI Tree Configuration ────────────────────────────────────────────────────

    /** Maximum depth when traversing UI tree */
    var uiTreeMaxDepth: Int = 10

    /** Maximum elements to include in formatted UI tree */
    var uiTreeMaxElements: Int = 100

    /** Maximum depth for focused component formatting */
    var focusedTreeMaxDepth: Int = 15

    /** Maximum elements for focused component formatting */
    var focusedTreeMaxElements: Int = 200

    // ── Vision Agent Configuration ───────────────────────────────────────────────

    /** Delay after action in vision agent (ms) */
    var visionActionDelayMs: Long = 500

    /** Delay after going back from wrong page (ms) */
    var visionGoBackDelayMs: Long = 300

    /** Interval for saving trace incrementally (every N steps) */
    var traceSaveInterval: Int = 5

    // ── Prompt Configuration ─────────────────────────────────────────────────────

    /** Maximum length for response preview in logs */
    var maxResponsePreviewLength: Int = 500

    /** Maximum components in UI snapshot for analysis */
    var maxSnapshotComponents: Int = 50

    /** Maximum menu items to show in logs */
    var maxMenuItemsPreview: Int = 20

    // ── Loop Detection ───────────────────────────────────────────────────────────

    /** Number of consecutive same actions before detecting loop */
    var loopDetectionThreshold: Int = 3

    // ── Recipe Configuration ─────────────────────────────────────────────────────

    /** Whether to use saved recipes as reference during execution */
    var useRecipes: Boolean = false

    /** Whether to save successful executions as new recipes */
    var saveRecipes: Boolean = true

    // ── Environment Overrides ─────────────────────────────────────────────────────

    /**
     * Apply environment variable overrides.
     * Useful for testing different configurations without code changes.
     */
    fun applyEnvironmentOverrides() {
        System.getenv("AGENT_MAX_ITERATIONS")?.toIntOrNull()?.let { maxIterations = it }
        System.getenv("AGENT_OBSERVE_DELAY_MS")?.toLongOrNull()?.let { observeDelayMs = it }
        System.getenv("AGENT_ACTION_DELAY_MS")?.toLongOrNull()?.let { actionDelayMs = it }
        System.getenv("AGENT_ROBOT_PORT")?.toIntOrNull()?.let { robotPort = it }
    }
}
