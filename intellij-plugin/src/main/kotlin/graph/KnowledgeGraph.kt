package graph

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * AppAgentX-style knowledge graph for IntelliJ UI navigation.
 *
 * Nodes:
 *   - PageNode  : a distinct UI state (editor_idle, context_menu, rename_dialog…)
 *   - ElementNode: an interactive component within a page
 *
 * Edges:
 *   - LEADS_TO  : taking an action on an element transitions from one page to another
 *
 * Shortcuts: learned multi-step sequences that get promoted to single graph hops.
 *
 * The graph persists to JSON between runs so knowledge accumulates.
 *
 * Core data types are defined in GraphTypes.kt
 */

/**
 * Directed graph of UI page states and transitions.
 */
class KnowledgeGraph(private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }) {
    private val pages: MutableMap<String, PageNode> = mutableMapOf()
    private val elements: MutableMap<String, ElementNode> = mutableMapOf()
    private val transitions: MutableList<Transition> = mutableListOf()
    private val shortcuts: MutableMap<String, Shortcut> = mutableMapOf()

    // ── Pages ─────────────────────────────────────────────────────────────────

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

    // ── Elements ──────────────────────────────────────────────────────────────

    fun getElement(elementId: String): ElementNode? = elements[elementId]

    fun addElement(element: ElementNode) {
        elements[element.id] = element
    }

    companion object {
        fun makeElementId(pageId: String, cls: String, label: String): String {
            val truncatedLabel = label.take(40).replace(Regex("[^\\w\\s-]"), "_")
            return "${pageId}::${cls}::${truncatedLabel}"
        }
    }

    // ── Transitions ───────────────────────────────────────────────────────────

    fun addTransition(
        fromPage: String,
        elementId: String,
        action: String,
        toPage: String,
        params: Map<String, String> = emptyMap()
    ) {
        val transition = Transition(fromPage, elementId, action, toPage, params)
        transitions.add(transition)
    }

    fun getTransitionsFrom(pageId: String): List<Transition> {
        return transitions.filter { it.fromPage == pageId }
    }

    // ── Shortcuts ─────────────────────────────────────────────────────────────

    fun getShortcut(name: String): Shortcut? = shortcuts[name]

    fun addShortcut(shortcut: Shortcut) {
        shortcuts[shortcut.name] = shortcut
    }

    fun recordShortcutUsed(name: String, success: Boolean) {
        shortcuts[name]?.let { s ->
            s.usageCount++
            if (success) s.successCount++
        }
    }

    // ── Context for LLM ──────────────────────────────────────────────────────

    fun toPromptContext(currentPageId: String): String {
        val lines = mutableListOf<String>()

        val transitionsFromHere = getTransitionsFrom(currentPageId)
        if (transitionsFromHere.isNotEmpty()) {
            lines.add("### Known transitions from this page (learned):")
            for (t in transitionsFromHere) {
                val el = elements[t.elementId]
                val elLabel = el?.label ?: t.elementId
                lines.add("  - ${t.action} \"$elLabel\" → page: ${t.toPage}")
            }
        } else {
            lines.add("### No known transitions from this page yet (first visit).")
        }

        if (shortcuts.isNotEmpty()) {
            lines.add("\n### Available shortcuts (learned sequences):")
            for (s in shortcuts.values) {
                val rate = if (s.usageCount > 0) "${s.successCount}/${s.usageCount}" else "untested"
                lines.add("  - \"${s.name}\" (${s.steps.size} steps, success: $rate)")
            }
        }

        return if (lines.isNotEmpty()) lines.joinToString("\n") else "(graph is empty — exploring for the first time)"
    }

    fun stats(): Map<String, Int> {
        return mapOf(
            "pages" to pages.size,
            "elements" to elements.size,
            "transitions" to transitions.size,
            "shortcuts" to shortcuts.size
        )
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    fun save(path: String) {
        val filePath = Paths.get(path)
        try {
            Files.createDirectories(filePath.parent)

            // Convert to simpler format for serialization
            val pagesData = pages.mapValues { (_, page) ->
                mapOf(
                    "id" to page.id,
                    "description" to page.description,
                    "visitCount" to page.visitCount
                )
            }

            val elementsData = elements.mapValues { (_, element) ->
                mapOf(
                    "id" to element.id,
                    "pageId" to element.pageId,
                    "cls" to element.cls,
                    "label" to element.label,
                    "xpath" to element.xpath,
                    "role" to element.role
                )
            }

            val transitionsData = transitions.map { t ->
                mapOf(
                    "fromPage" to t.fromPage,
                    "elementId" to t.elementId,
                    "action" to t.action,
                    "toPage" to t.toPage,
                    "params" to t.params
                )
            }

            val shortcutsData = shortcuts.mapValues { (_, shortcut) ->
                mapOf(
                    "name" to shortcut.name,
                    "steps" to shortcut.steps,
                    "usageCount" to shortcut.usageCount,
                    "successCount" to shortcut.successCount
                )
            }

            val data = mapOf(
                "pages" to pagesData,
                "elements" to elementsData,
                "transitions" to transitionsData,
                "shortcuts" to shortcutsData
            )

            filePath.toFile().writeText(json.encodeToString(data))
        } catch (e: Exception) {
            println("Warning: Failed to save knowledge graph: ${e.message}")
        }
    }

    fun load(path: String) {
        val filePath = Paths.get(path)
        if (!filePath.toFile().exists()) {
            return
        }

        try {
            val data = json.decodeFromString<Map<String, Any>>(filePath.toFile().readText())

            @Suppress("UNCHECKED_CAST")
            val pagesData = data["pages"] as? List<Map<String, Any>> ?: emptyList()

            @Suppress("UNCHECKED_CAST")
            val elementsData = data["elements"] as? List<Map<String, Any>> ?: emptyList()

            @Suppress("UNCHECKED_CAST")
            val transitionsData = data["transitions"] as? List<Map<String, Any>> ?: emptyList()

            @Suppress("UNCHECKED_CAST")
            val shortcutsData = data["shortcuts"] as? Map<String, Map<String, Any>> ?: emptyMap()

            // Load pages
            pagesData.forEach { pageData ->
                val id = pageData["id"] as String
                val description = pageData["description"] as String
                val visitCount = pageData["visitCount"] as Int
                pages[id] = PageNode(id, description, visitCount)
            }

            // Load elements
            elementsData.forEach { elementData ->
                val id = elementData["id"] as String
                val pageId = elementData["pageId"] as String
                val cls = elementData["cls"] as String
                val label = elementData["label"] as String
                val xpath = elementData["xpath"] as String
                val role = elementData["role"] as String
                elements[id] = ElementNode(id, pageId, cls, label, xpath, role)
            }

            // Load transitions
            transitionsData.forEach { transitionData ->
                val fromPage = transitionData["fromPage"] as String
                val elementId = transitionData["elementId"] as String
                val action = transitionData["action"] as String
                val toPage = transitionData["toPage"] as String
                @Suppress("UNCHECKED_CAST")
                val params = transitionData["params"] as? Map<String, String> ?: emptyMap()
                transitions.add(Transition(fromPage, elementId, action, toPage, params))
            }

            // Load shortcuts
            shortcutsData.forEach { (name, shortcutData) ->
                val steps = shortcutData["steps"] as List<Map<String, String>>
                val usageCount = shortcutData["usageCount"] as Int
                val successCount = shortcutData["successCount"] as Int
                shortcuts[name] = Shortcut(name, steps, usageCount, successCount)
            }
        } catch (e: Exception) {
            println("Warning: Failed to load knowledge graph: ${e.message}")
        }
    }
}
