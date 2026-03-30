package graph

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

class GraphPersistenceTest {

    @Test
    @DisplayName("Should save and load graph from JSON")
    fun testSaveAndLoadGraph() {
        val tempFile = Files.createTempFile("knowledge_graph", ".json")

        try {
            // Create a graph with some data
            val graph1 = KnowledgeGraph()

            // Add pages
            graph1.addPage(PageNode("editor_idle", "Editor idle state"))
            graph1.addPage(PageNode("context_menu", "Context menu"))
            graph1.addPage(PageNode("rename_dialog", "Rename dialog"))

            // Add elements
            val elementId = KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "Refactor")
            graph1.addElement(ElementNode(
                id = elementId,
                pageId = "editor_idle",
                cls = "ActionButton",
                label = "Refactor",
                xpath = "//button[@text='Refactor']",
                role = "button",
            ))

            // Add transitions
            graph1.addTransition(
                fromPage = "editor_idle",
                elementId = elementId,
                action = "click",
                toPage = "context_menu",
                params = mapOf("target" to "Refactor"),
                success = true,
            )

            // Add a shortcut
            graph1.addShortcut(Shortcut(
                name = "rename_via_menu",
                steps = listOf(
                    mapOf("action" to "click", "target" to "Refactor"),
                    mapOf("action" to "click_menu_item", "target" to "Rename"),
                ),
            ))

            // Save
            graph1.save(tempFile.toString())

            // Create a new graph and load
            val graph2 = KnowledgeGraph()
            graph2.load(tempFile.toString())

            // Verify all data was loaded
            assert(graph2.stats()["pages"] == 3) {
                "Expected 3 pages, got ${graph2.stats()["pages"]}"
            }
            assert(graph2.stats()["elements"] == 1) {
                "Expected 1 element, got ${graph2.stats()["elements"]}"
            }
            assert(graph2.stats()["transitions"] == 1) {
                "Expected 1 transition, got ${graph2.stats()["transitions"]}"
            }
            assert(graph2.stats()["shortcuts"] == 1) {
                "Expected 1 shortcut, got ${graph2.stats()["shortcuts"]}"
            }

            // Verify specific data
            val page = graph2.getPage("editor_idle")
            assert(page != null) {
                "Page 'editor_idle' should exist"
            }
            assert(page!!.description == "Editor idle state") {
                "Page description should match"
            }

            val element = graph2.getElement(elementId)
            assert(element != null) {
                "Element should exist"
            }
            assert(element!!.label == "Refactor") {
                "Element label should match"
            }

            val transitions = graph2.getTransitionsFrom("editor_idle")
            assert(transitions.size == 1) {
                "Expected 1 transition from editor_idle"
            }
            assert(transitions[0].success) {
                "Transition should be marked as successful"
            }

            val shortcut = graph2.getShortcut("rename_via_menu")
            assert(shortcut != null) {
                "Shortcut should exist"
            }
            assert(shortcut!!.steps.size == 2) {
                "Shortcut should have 2 steps"
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    @DisplayName("Loading non-existent file should not crash")
    fun testLoadNonExistentFile() {
        val graph = KnowledgeGraph()
        graph.load("/tmp/this_file_does_not_exist_12345.json")

        // Should have empty stats
        assert(graph.stats()["pages"] == 0) {
            "Expected empty graph after loading non-existent file"
        }
    }

    @Test
    @DisplayName("Should accumulate data across save/load cycles")
    fun testAccumulationAcrossCycles() {
        val tempFile = Files.createTempFile("knowledge_graph", ".json")

        try {
            // First cycle: add some data
            val graph1 = KnowledgeGraph()
            graph1.addPage(PageNode("page1", "First page"))
            graph1.save(tempFile.toString())

            // Second cycle: load and add more data
            val graph2 = KnowledgeGraph()
            graph2.load(tempFile.toString())
            graph2.addPage(PageNode("page2", "Second page"))
            graph2.save(tempFile.toString())

            // Third cycle: load and verify
            val graph3 = KnowledgeGraph()
            graph3.load(tempFile.toString())

            assert(graph3.stats()["pages"] == 2) {
                "Expected 2 pages after accumulation"
            }
            assert(graph3.getPage("page1") != null) {
                "Page from first cycle should exist"
            }
            assert(graph3.getPage("page2") != null) {
                "Page from second cycle should exist"
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    @DisplayName("JSON output should be valid and readable")
    fun testJsonFormatIsValid() {
        val tempFile = Files.createTempFile("knowledge_graph", ".json")

        try {
            val graph = KnowledgeGraph()
            graph.addPage(PageNode("test_page", "Test page"))
            graph.save(tempFile.toString())

            // Read the file and check it's valid JSON
            val content = Files.readString(tempFile)
            assert(content.contains("test_page")) {
                "JSON should contain page data"
            }
            assert(content.contains("Test page")) {
                "JSON should contain page description"
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    @DisplayName("Failed transitions should persist correctly")
    fun testFailedTransitionPersistence() {
        val tempFile = Files.createTempFile("knowledge_graph", ".json")

        try {
            val graph1 = KnowledgeGraph()

            val elementId = KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "BrokenButton")
            graph1.addTransition(
                fromPage = "editor_idle",
                elementId = elementId,
                action = "click",
                toPage = "editor_idle",  // Stayed on same page = failed
                success = false,
            )

            graph1.save(tempFile.toString())

            val graph2 = KnowledgeGraph()
            graph2.load(tempFile.toString())

            val transitions = graph2.getTransitionsFrom("editor_idle")
            assert(transitions.size == 1) {
                "Expected 1 transition"
            }
            assert(!transitions[0].success) {
                "Transition should persist as failed"
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    @DisplayName("Element deltas should persist via stored elements")
    fun testElementDeltaPersistence() {
        val tempFile = Files.createTempFile("knowledge_graph", ".json")

        try {
            val graph1 = KnowledgeGraph()

            // Store some elements
            val elements = listOf(
                parser.UiComponent(
                    cls = "ActionButton",
                    text = "Refactor",
                    accessibleName = "Refactor",
                    tooltip = "",
                    enabled = true,
                    hasSubmenu = false,
                    children = emptyList(),
                ),
            )
            graph1.storeElements("editor_idle", elements)

            graph1.save(tempFile.toString())

            val graph2 = KnowledgeGraph()
            graph2.load(tempFile.toString())

            // Verify we can get elements for the page
            val storedElements = graph2.getElementsForPage("editor_idle")
            assert(storedElements.size == 1) {
                "Expected 1 stored element"
            }
            assert(storedElements[0].label == "Refactor") {
                "Element label should match"
            }

            // Verify delta computation works with loaded data
            val delta = graph2.computeElementDelta("editor_idle", elements)
            assert(delta.newElements.isEmpty()) {
                "Expected no new elements (same data)"
            }
            assert(delta.unchangedCount == 1) {
                "Expected 1 unchanged element"
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}
