package recipe

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import reasoner.LLMReasoner.Action
import reasoner.LLMReasoner.HistoryEntry
import reasoner.LLMReasoner.RecipeSummary
import java.io.File
import java.time.Instant

/**
 * Verified Recipe - A saved execution pattern that was successfully completed.
 *
 * Key principle: Recipes are saved ONLY after successful end-to-end execution.
 * They serve as references for future similar tasks, NOT as blind scripts.
 *
 * The LLM uses recipes as context to understand what worked before,
 * but still validates each step against the live UI state.
 */
@Serializable
data class VerifiedRecipe(
    val id: String,
    val intentPattern: String,
    val operationType: String? = null, // e.g., "rename", "extract_function", "change_signature"
    val context: RecipeContext,
    val successfulActions: List<SuccessfulAction>,
    val verifiedAt: String,
    val successCount: Int = 1,
    val lastUsedAt: String? = null,
) {
    /**
     * Context in which the recipe was verified.
     */
    @Serializable
    data class RecipeContext(
        val precondition: String? = null, // e.g., "CARET_ON_SYMBOL"
        val responseType: String? = null, // e.g., "INLINE_WIDGET", "DIALOG"
        val application: String? = null, // e.g., "IntelliJ IDEA"
        val toolName: String? = null, // e.g., "Rename", "Extract Function"
    )

    /**
     * A successful action with its context.
     */
    @Serializable
    data class SuccessfulAction(
        val uiStateDescription: String, // What the UI looked like
        val action: ActionJson, // What action was taken
        val result: String, // What happened
    )

    /**
     * JSON representation of an action.
     */
    @Serializable
    data class ActionJson(
        val type: String,
        val params: Map<String, String> = emptyMap(),
    ) {
        fun toAction(): Action {
            return when (type.lowercase()) {
                // Navigation actions
                "open_file" -> Action.OpenFile(params["path"] ?: "")
                "move_caret" -> Action.MoveCaret(params["symbol"] ?: "")
                "select_lines" ->
                    Action.SelectLines(
                        start = params["start"]?.toIntOrNull() ?: 1,
                        end = params["end"]?.toIntOrNull() ?: 1,
                    )
                // UI interaction actions
                "click" -> Action.Click(params["target"] ?: "")
                "type" ->
                    Action.Type(
                        text = params["text"] ?: "",
                        clearFirst = params["clearFirst"]?.toBoolean() ?: true,
                        target = params["target"],
                    )
                "press_key" -> Action.PressKey(params["key"] ?: "Enter")
                "select_dropdown" ->
                    Action.SelectDropdown(
                        target = params["target"] ?: "",
                        value = params["value"] ?: "",
                    )
                "wait" ->
                    Action.Wait(
                        elementType = params["elementType"] ?: "dialog",
                        timeoutMs = params["timeoutMs"]?.toLongOrNull() ?: 5000,
                    )
                else -> Action.Observe
            }
        }

        companion object {
            fun fromAction(action: Action): ActionJson {
                return when (action) {
                    // Navigation actions
                    is Action.OpenFile -> ActionJson("open_file", mapOf("path" to action.path))
                    is Action.MoveCaret -> ActionJson("move_caret", mapOf("symbol" to action.symbol))
                    is Action.SelectLines ->
                        ActionJson(
                            "select_lines",
                            mapOf(
                                "start" to action.start.toString(),
                                "end" to action.end.toString(),
                            ),
                        )
                    // UI interaction actions
                    is Action.Click -> ActionJson("click", mapOf("target" to action.target))
                    is Action.Type ->
                        ActionJson(
                            "type",
                            mapOf(
                                "text" to action.text,
                                "clearFirst" to action.clearFirst.toString(),
                                "target" to (action.target ?: ""),
                            ),
                        )
                    is Action.PressKey -> ActionJson("press_key", mapOf("key" to action.key))
                    is Action.SelectDropdown ->
                        ActionJson(
                            "select_dropdown",
                            mapOf(
                                "target" to action.target,
                                "value" to action.value,
                            ),
                        )
                    is Action.Wait ->
                        ActionJson(
                            "wait",
                            mapOf(
                                "elementType" to action.elementType,
                                "timeoutMs" to action.timeoutMs.toString(),
                            ),
                        )
                    is Action.UseRecipe -> ActionJson("use_recipe", mapOf("recipeId" to action.recipeId))
                    is Action.Observe -> ActionJson("observe")
                    is Action.Complete -> ActionJson("complete")
                    is Action.Fail -> ActionJson("fail")
                }
            }
        }
    }

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            }

        /**
         * Generate a unique ID for a recipe.
         */
        fun generateId(intentPattern: String): String {
            val sanitized =
                intentPattern.lowercase()
                    .replace(Regex("[^a-z0-9]+"), "_")
                    .take(30)
                    .trim('_')
            val timestamp = Instant.now().epochSecond
            return "${sanitized}_$timestamp"
        }

        /**
         * Load recipes from a JSON file.
         */
        fun loadFromFile(path: String): List<VerifiedRecipe> {
            val file = File(path)
            if (!file.exists()) return emptyList()

            return try {
                val content = file.readText()
                json.decodeFromString<List<VerifiedRecipe>>(content)
            } catch (e: Exception) {
                println("  Warning: Failed to load recipes from $path: ${e.message}")
                emptyList()
            }
        }

        /**
         * Save recipes to a JSON file.
         */
        fun saveToFile(
            recipes: List<VerifiedRecipe>,
            path: String,
        ) {
            val file = File(path)
            file.parentFile?.mkdirs()

            try {
                val content = json.encodeToString(recipes)
                file.writeText(content)
            } catch (e: Exception) {
                println("  Warning: Failed to save recipes to $path: ${e.message}")
            }
        }
    }
}

/**
 * Recipe Registry - Manages verified recipes.
 *
 * Recipes are:
 * 1. Saved only after successful execution
 * 2. Used as context for LLM decision-making
 * 3. Never executed blindly
 */
class RecipeRegistry(private val storagePath: String = "build/reports/verified-recipes.json") {
    private val recipes: MutableList<VerifiedRecipe> = mutableListOf()

    init {
        load()
    }

    /**
     * Load recipes from storage.
     */
    fun load() {
        recipes.clear()
        recipes.addAll(VerifiedRecipe.loadFromFile(storagePath))
        println("  RecipeRegistry: Loaded ${recipes.size} recipes")
    }

    /**
     * Save recipes to storage.
     */
    fun save() {
        VerifiedRecipe.saveToFile(recipes, storagePath)
        println("  RecipeRegistry: Saved ${recipes.size} recipes")
    }

    /**
     * Register a new verified recipe.
     */
    fun register(recipe: VerifiedRecipe) {
        // Check if similar recipe exists
        val existing = recipes.find { it.intentPattern == recipe.intentPattern }
        if (existing != null) {
            // Update existing recipe
            val updated =
                existing.copy(
                    successCount = existing.successCount + 1,
                    lastUsedAt = Instant.now().toString(),
                )
            recipes.remove(existing)
            recipes.add(updated)
        } else {
            recipes.add(recipe)
        }
        save()
    }

    /**
     * Find recipes matching an intent.
     *
     * IMPORTANT: We match on OPERATION TYPE only, not symbol names.
     * Two intents are the same recipe if they perform the same operation type
     * (rename, extract_function, change_signature), regardless of which symbol they target.
     *
     * Example:
     * - "rename method foo to bar" matches "rename method baz to qux" (both are rename)
     * - "extract function from lines 10-20" matches "extract function from lines 30-40" (both are extract_function)
     */
    fun findMatching(intent: String): List<VerifiedRecipe> {
        // Extract operation type from intent
        val operationType = extractOperationType(intent)

        return recipes.filter { recipe ->
            // Match on operation type if available
            if (recipe.operationType != null && operationType != null) {
                recipe.operationType == operationType
            } else {
                // Fallback to pattern matching for legacy recipes without operationType
                val intentLower = intent.lowercase()
                val pattern = recipe.intentPattern.lowercase()
                intentLower.contains(pattern) || pattern.contains(intentLower)
            }
        }.sortedByDescending { it.successCount }
    }

    /**
     * Extract operation type from an intent string.
     */
    private fun extractOperationType(intent: String): String? {
        val intentLower = intent.lowercase()
        return when {
            intentLower.contains("rename") -> "rename"
            intentLower.contains("extract function") || intentLower.contains("extract method") -> "extract_function"
            intentLower.contains("extract variable") || intentLower.contains("extract constant") -> "extract_variable"
            intentLower.contains("change signature") || intentLower.contains("modify signature") -> "change_signature"
            intentLower.contains("move") -> "move"
            intentLower.contains("copy") -> "copy"
            intentLower.contains("inline") -> "inline"
            intentLower.contains("introduce parameter") -> "introduce_parameter"
            intentLower.contains("introduce variable") -> "introduce_variable"
            else -> null
        }
    }

    /**
     * Get a recipe by ID.
     */
    fun getById(id: String): VerifiedRecipe? {
        return recipes.find { it.id == id }
    }

    /**
     * Get all recipes.
     */
    fun all(): List<VerifiedRecipe> = recipes.toList()

    /**
     * Get recipe summaries for LLM context.
     */
    fun getSummaries(): List<RecipeSummary> {
        return recipes.map { recipe ->
            RecipeSummary(
                id = recipe.id,
                intentPattern = recipe.intentPattern,
                successCount = recipe.successCount,
            )
        }
    }

    /**
     * Clear all recipes.
     */
    fun clear() {
        recipes.clear()
        save()
    }

    /**
     * Create a recipe from a successful execution.
     * Automatically extracts and stores operation type for better matching.
     */
    fun createRecipe(
        intent: String,
        context: VerifiedRecipe.RecipeContext,
        actions: List<Pair<String, HistoryEntry>>,
    ): VerifiedRecipe {
        val successfulActions =
            actions.map { (uiState, entry) ->
                VerifiedRecipe.SuccessfulAction(
                    uiStateDescription = uiState,
                    action = VerifiedRecipe.ActionJson.fromAction(entry.action),
                    result = entry.result,
                )
            }

        // Extract operation type from intent for better matching
        val operationType = extractOperationType(intent)

        return VerifiedRecipe(
            id = VerifiedRecipe.generateId(intent),
            intentPattern = intent,
            operationType = operationType, // Store operation type for matching
            context = context,
            successfulActions = successfulActions,
            verifiedAt = Instant.now().toString(),
        )
    }
}
