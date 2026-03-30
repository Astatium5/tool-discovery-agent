package graph

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
