package graph

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import parser.UiComponent
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Delta between stored and current elements on a page.
 */
data class ElementDelta(
    val newElements: List<ElementNode>,
    val changedElements: List<ElementNode>,
    val unchangedCount: Int,
)

/**
 * A computed path through the graph from start to destination.
 */
data class GraphPath(
    val transitions: List<Transition>,
    val length: Int,
) {
    /**
     * Format the path steps as human-readable strings.
     * Takes elements map as parameter since this is a top-level class.
     */
    fun formatSteps(elements: Map<String, ElementNode>): List<String> = transitions.map {
        val el = elements[it.elementId]
        "${it.action} \"${el?.label ?: it.elementId}\""
    }
}

/**
 * Directed graph of UI page states and transitions.
 *
 * Nodes:
 *   - PageNode: A distinct UI state (e.g., "editor_idle", "context_menu", "rename_dialog")
 *   - ElementNode: An interactive component within a page
 *
 * Edges:
 *   - Transition: Taking an action on an element transitions from one page to another
 *
 * Shortcuts: Learned multi-step sequences promoted to single graph hops.
 *
 * The graph persists to JSON between runs so knowledge accumulates.
 */
class KnowledgeGraph(private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }) {
    private val pages: MutableMap<String, PageNode> = mutableMapOf()
    private val elements: MutableMap<String, ElementNode> = mutableMapOf()
    private val transitions: MutableList<Transition> = mutableListOf()
    private val shortcuts: MutableMap<String, Shortcut> = mutableMapOf()
    private val failedTransitions: MutableList<FailedTransition> = mutableListOf()

    fun getPage(pageId: String): PageNode? = pages[pageId]

    fun addPage(page: PageNode) {
        if (page.id !in pages) {
            pages[page.id] = page
        } else {
            pages[page.id]?.visitCount = pages[page.id]!!.visitCount + 1
        }
    }

    fun recordVisit(pageId: String) {
        pages[pageId]?.visitCount = pages[pageId]!!.visitCount + 1
    }

    fun getElement(elementId: String): ElementNode? = elements[elementId]

    fun addElement(element: ElementNode) {
        elements[element.id] = element
    }

    fun addTransition(
        fromPage: String,
        elementId: String,
        action: String,
        toPage: String,
        params: Map<String, String> = emptyMap(),
        success: Boolean = true,
    ) {
        transitions.add(Transition(fromPage, elementId, action, toPage, params, success))
    }

    fun getTransitionsFrom(pageId: String): List<Transition> {
        return transitions.filter { it.fromPage == pageId }
    }

    fun getShortcut(name: String): Shortcut? = shortcuts[name]

    fun addShortcut(shortcut: Shortcut) {
        shortcuts[shortcut.name] = shortcut
    }

    fun recordShortcutUsed(
        name: String,
        success: Boolean,
    ) {
        shortcuts[name]?.let { s ->
            s.usageCount++
            if (success) s.successCount++
        }
    }

    fun toPromptContext(currentPageId: String): String {
        val lines = mutableListOf<String>()

        val transitionsFromHere = getTransitionsFrom(currentPageId)
        if (transitionsFromHere.isNotEmpty()) {
            lines.add("### Known transitions from this page:")
            for (t in transitionsFromHere) {
                val el = elements[t.elementId]
                val elLabel = el?.label ?: t.elementId
                lines.add("  - ${t.action} \"$elLabel\" → ${t.toPage}")
            }
        } else {
            lines.add("### No known transitions from this page yet.")
        }

        // Show previously failed actions on this page
        val failedFromHere = getFailedTransitionsFrom(currentPageId)
        if (failedFromHere.isNotEmpty()) {
            lines.add("\n### ⚠️ Previously failed actions on this page:")
            for (f in failedFromHere.distinctBy { it.elementId }) {
                val el = elements[f.elementId]
                val elLabel = el?.label ?: f.elementId
                lines.add("  - ${f.action} \"$elLabel\" (failed: ${f.reason})")
            }
        }

        // Only show shortcuts that can be used from the current page
        val relevantShortcuts = getShortcutsForPage(currentPageId)
        if (relevantShortcuts.isNotEmpty()) {
            lines.add("\n### Available shortcuts:")
            for (s in relevantShortcuts) {
                val rate = if (s.usageCount > 0) "${s.successCount}/${s.usageCount}" else "untested"
                lines.add("  - \"${s.name}\" (${s.steps.size} steps, success: $rate)")
            }
        }

        return if (lines.isNotEmpty()) lines.joinToString("\n") else "(graph is empty)"
    }

    fun stats(): Map<String, Int> = mapOf(
        "pages" to pages.size,
        "elements" to elements.size,
        "transitions" to transitions.size,
        "shortcuts" to shortcuts.size,
    )

    fun save(path: String) {
        val filePath = Paths.get(path)
        try {
            Files.createDirectories(filePath.parent)

            val data = SerializedGraph(
                pages = pages.values.toList(),
                elements = elements.values.toList(),
                transitions = transitions.toList(),
                shortcuts = shortcuts.values.toList(),
                failedTransitions = failedTransitions.toList(),
            )

            filePath.toFile().writeText(json.encodeToString(data))
        } catch (e: Exception) {
            println("Warning: Failed to save knowledge graph: ${e.message}")
        }
    }

    fun load(path: String) {
        val filePath = Paths.get(path)
        if (!filePath.toFile().exists()) return

        try {
            val data = json.decodeFromString<SerializedGraph>(filePath.toFile().readText())

            data.pages.forEach { pages[it.id] = it }
            data.elements.forEach { elements[it.id] = it }
            transitions.addAll(data.transitions)
            data.shortcuts.forEach { shortcuts[it.name] = it }
            failedTransitions.addAll(data.failedTransitions)
        } catch (e: Exception) {
            println("Warning: Failed to load knowledge graph: ${e.message}")
        }
    }

    // ── Element Storage (Task 2) ─────────────────────────────────────────────

    /**
     * Check if we've seen this page before and have elements stored.
     */
    fun hasVisitedPage(pageId: String): Boolean {
        return pages[pageId]?.let { it.visitCount > 1 } ?: false
    }

    /**
     * Get all elements we've seen on a page.
     */
    fun getElementsForPage(pageId: String): List<ElementNode> {
        return elements.values.filter { it.pageId == pageId }
    }

    /**
     * Find an element by page and label (fuzzy match).
     */
    fun findElement(pageId: String, label: String): ElementNode? {
        return elements.values.find {
            it.pageId == pageId &&
            (it.label == label || it.label.contains(label, ignoreCase = true))
        }
    }

    /**
     * Compare current UI elements with stored elements.
     * Returns pair of (newElements, changedElements).
     */
    fun computeElementDelta(pageId: String, currentElements: List<UiComponent>): ElementDelta {
        val storedElements = getElementsForPage(pageId)
        val storedByLabel = storedElements.associateBy { it.label }

        val new = mutableListOf<ElementNode>()
        val changed = mutableListOf<ElementNode>()
        var unchanged = 0

        for (element in currentElements) {
            val stored = storedByLabel[element.label]
            if (stored == null) {
                // New element
                new.add(ElementNode(
                    id = makeElementId(pageId, element.cls, element.label),
                    pageId = pageId,
                    cls = element.cls,
                    label = element.label,
                    xpath = element.xpath,
                    role = inferRoleFromClass(element.cls),
                ))
            } else {
                // Check if anything changed
                if (stored.cls != element.cls || stored.xpath != element.xpath) {
                    changed.add(ElementNode(
                        id = stored.id,
                        pageId = pageId,
                        cls = element.cls,
                        label = element.label,
                        xpath = element.xpath,
                        role = inferRoleFromClass(element.cls),
                    ))
                } else {
                    unchanged++
                }
            }
        }

        return ElementDelta(new, changed, unchanged)
    }

    /**
     * Store all elements observed on a page visit.
     */
    fun storeElements(pageId: String, uiComponents: List<UiComponent>) {
        for (element in uiComponents) {
            val elementNode = ElementNode(
                id = makeElementId(pageId, element.cls, element.label),
                pageId = pageId,
                cls = element.cls,
                label = element.label,
                xpath = element.xpath,
                role = inferRoleFromClass(element.cls),
            )
            addElement(elementNode)
        }
    }

    /**
     * Infer UI role from component class name.
     */
    private fun inferRoleFromClass(cls: String): String {
        return when {
            cls.contains("Button", ignoreCase = true) -> "button"
            cls.contains("MenuItem", ignoreCase = true) -> "menu_item"
            cls.contains("TextField", ignoreCase = true) -> "text_field"
            cls.contains("CheckBox", ignoreCase = true) -> "checkbox"
            cls.contains("ComboBox", ignoreCase = true) -> "dropdown"
            else -> "unknown"
        }
    }

    // ── Pathfinding (Task 3) ───────────────────────────────────────────────

    /**
     * Find shortest path from startPage to endPage using BFS.
     * Returns list of transitions to follow, or null if no path exists.
     */
    fun findShortestPath(startPage: String, endPage: String): GraphPath? {
        if (startPage == endPage) {
            return GraphPath(emptyList(), 0)
        }

        // BFS queue: (current_page, path_so_far)
        val queue = ArrayDeque<Pair<String, List<Transition>>>()
        queue.addLast(startPage to emptyList())

        val visited = mutableSetOf<String>()
        visited.add(startPage)

        while (queue.isNotEmpty()) {
            val (current, path) = queue.removeFirst()

            // Get all transitions from current page
            val outgoing = getTransitionsFrom(current)

            for (transition in outgoing) {
                if (transition.toPage == endPage) {
                    // Found destination
                    return GraphPath(path + transition, path.size + 1)
                }

                if (transition.toPage !in visited) {
                    visited.add(transition.toPage)
                    queue.addLast(transition.toPage to (path + transition))
                }
            }
        }

        // No path found
        return null
    }

    /**
     * Find all pages reachable from startPage.
     */
    fun findReachablePages(startPage: String): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.addLast(startPage)
        visited.add(startPage)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (transition in getTransitionsFrom(current)) {
                if (transition.toPage !in visited) {
                    visited.add(transition.toPage)
                    queue.addLast(transition.toPage)
                }
            }
        }

        return visited
    }

    // ── Shortcut Discovery (Task 5) ──────────────────────────────────────────

    /**
     * Minimum number of times a sequence must be repeated to become a shortcut.
     */
    private val SHORTCUT_MIN_REPEATS = 3

    /**
     * Minimum sequence length to consider for shortcut creation.
     */
    private val SHORTCUT_MIN_LENGTH = 2

    /**
     * Analyze transition history to discover repeated action patterns.
     *
     * Looks for sequences of transitions that occur repeatedly and creates
     * shortcuts for commonly used patterns.
     *
     * @param recentActions Recent action records to analyze
     * @return List of newly created shortcuts
     */
    fun discoverShortcuts(recentActions: List<graph.GraphAgent.ActionRecord>): List<Shortcut> {
        if (recentActions.size < SHORTCUT_MIN_LENGTH * SHORTCUT_MIN_REPEATS) {
            return emptyList()
        }

        val newShortcuts = mutableListOf<Shortcut>()

        // Only consider successful actions for shortcuts
        val successfulActions = recentActions.filter { it.success }

        // Look for sequences of length 2-4
        for (length in SHORTCUT_MIN_LENGTH..4) {
            val sequences = findRepeatedSequences(successfulActions, length)

            for (sequence in sequences) {
                val shortcutName = generateShortcutName(sequence)

                // Only create if not already exists
                if (shortcuts[shortcutName] == null) {
                    val shortcut = Shortcut(
                        name = shortcutName,
                        steps = sequence.map { action ->
                            mapOf(
                                "action" to action.actionType,
                                "target" to (action.elementLabel ?: action.params["target"] ?: ""),
                            )
                        },
                    )
                    addShortcut(shortcut)
                    newShortcuts.add(shortcut)
                }
            }
        }

        return newShortcuts
    }

    /**
     * Find action sequences that repeat at least SHORTCUT_MIN_REPEATS times.
     *
     * @param actions Action history to analyze
     * @param length Length of sequences to look for
     * @return List of repeated action sequences
     */
    private fun findRepeatedSequences(
        actions: List<graph.GraphAgent.ActionRecord>,
        length: Int,
    ): List<List<graph.GraphAgent.ActionRecord>> {
        val sequences = mutableListOf<List<graph.GraphAgent.ActionRecord>>()
        val sequenceCounts = mutableMapOf<String, MutableList<List<graph.GraphAgent.ActionRecord>>>()

        // Extract all sequences of given length
        for (i in 0..actions.size - length) {
            val sequence = actions.subList(i, i + length)

            // Skip if any action is non-interactive (complete, fail, observe, error)
            if (sequence.any { it.actionType in setOf("complete", "fail", "observe", "error") }) {
                continue
            }

            // Create a signature for this sequence
            val signature = sequence.joinToString("|") { "${it.actionType}:${it.pageBefore}:${it.pageAfter}" }

            if (signature !in sequenceCounts) {
                sequenceCounts[signature] = mutableListOf()
            }
            sequenceCounts[signature]!!.add(sequence)
        }

        // Return sequences that occur at least SHORTCUT_MIN_REPEATS times
        for ((_, occurrences) in sequenceCounts) {
            if (occurrences.size >= SHORTCUT_MIN_REPEATS) {
                // Return the first occurrence as the canonical sequence
                sequences.add(occurrences[0])
            }
        }

        return sequences
    }

    /**
     * Generate a human-readable name for a shortcut based on its actions.
     *
     * For example: "click Refactor, click Rename" -> "rename_via_menu"
     */
    private fun generateShortcutName(sequence: List<graph.GraphAgent.ActionRecord>): String {
        val targets = sequence.mapNotNull { it.elementLabel ?: it.params["target"] }
        return targets
            .filter { it.isNotBlank() }
            .joinToString("_")
            .lowercase()
            .replace(Regex("[^a-z0-9_]"), "_")
            .take(50)
            .ifEmpty { "shortcut_${System.currentTimeMillis()}" }
    }

    /**
     * Get all shortcuts available from the current page context.
     * Returns shortcuts whose first step's target element exists on the current page.
     */
    fun getShortcutsForPage(pageId: String): List<Shortcut> {
        val pageElements = getElementsForPage(pageId).map { it.label }.toSet()

        return shortcuts.values.filter { shortcut ->
            if (shortcut.steps.isEmpty()) return@filter false

            val firstStepTarget = shortcut.steps[0]["target"] ?: ""
            firstStepTarget in pageElements
        }
    }

    // ── Failed Transition Tracking (Task 4) ─────────────────────────────────

    /**
     * Add a failed transition to the graph.
     * Used to track actions that should be avoided in the future.
     */
    fun addFailedTransition(
        fromPage: String,
        elementId: String,
        action: String,
        reason: String,
    ) {
        failedTransitions.add(FailedTransition(fromPage, elementId, action, reason))
    }

    /**
     * Check if a transition has previously failed.
     * Returns true only if the failure is recent (not stale).
     * Failures are considered stale after 1 hour.
     */
    fun hasFailedTransition(fromPage: String, elementId: String, action: String): Boolean {
        val staleThreshold = System.currentTimeMillis() - (60 * 60 * 1000)
        val recentFailures = failedTransitions.filter { it.timestamp > staleThreshold }

        return recentFailures.any {
            it.fromPage == fromPage &&
            it.elementId == elementId &&
            it.action == action
        }
    }

    /**
     * Get recent failed transitions from a specific page.
     * Only returns failures that are not stale (within 1 hour).
     */
    fun getFailedTransitionsFrom(pageId: String): List<FailedTransition> {
        val staleThreshold = System.currentTimeMillis() - (60 * 60 * 1000)
        return failedTransitions.filter {
            it.fromPage == pageId && it.timestamp > staleThreshold
        }
    }

    companion object {
        fun makeElementId(
            pageId: String,
            cls: String,
            label: String,
        ): String {
            val truncatedLabel = label.take(40).replace(Regex("[^\\w\\s-]"), "_")
            return "$pageId::$cls::$truncatedLabel"
        }
    }
}
