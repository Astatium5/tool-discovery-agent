package graph

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import parser.UiComponent
import java.nio.file.Files
import java.nio.file.Paths

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

    /**
     * Delta between stored and current elements on a page.
     */
    data class ElementDelta(
        val newElements: List<ElementNode>,
        val changedElements: List<ElementNode>,
        val unchangedCount: Int,
    )

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
    ) {
        transitions.add(Transition(fromPage, elementId, action, toPage, params))
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

        if (shortcuts.isNotEmpty()) {
            lines.add("\n### Available shortcuts:")
            for (s in shortcuts.values) {
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
