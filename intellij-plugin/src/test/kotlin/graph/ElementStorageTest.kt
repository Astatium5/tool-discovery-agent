package graph

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach
import parser.UiComponent

class ElementStorageTest {
    private lateinit var graph: KnowledgeGraph

    @BeforeEach
    fun setup() {
        graph = KnowledgeGraph()
    }

    @Test
    @DisplayName("First visit should have no stored elements")
    fun testFirstVisitNoElements() {
        assert(!graph.hasVisitedPage("editor_idle")) {
            "Page should not be visited yet"
        }
        assert(graph.getElementsForPage("editor_idle").isEmpty()) {
            "Should have no stored elements"
        }
    }

    @Test
    @DisplayName("Store and retrieve elements for a page")
    fun testStoreElements() {
        val elements = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
            UiComponent(cls = "ActionButton", text = "Find", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )

        graph.storeElements("editor_idle", elements)

        val stored = graph.getElementsForPage("editor_idle")
        assert(stored.size == 2) {
            "Expected 2 stored elements, got ${stored.size}"
        }
        assert(stored[0].label == "Refactor") {
            "First element should be Refactor"
        }
        assert(stored[1].label == "Find") {
            "Second element should be Find"
        }
    }

    @Test
    @DisplayName("Compute delta should find new elements on first visit")
    fun testComputeDeltaNewElements() {
        val elements = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )

        val delta = graph.computeElementDelta("editor_idle", elements)
        assert(delta.newElements.size == 1) {
            "Expected 1 new element, got ${delta.newElements.size}"
        }
        assert(delta.changedElements.isEmpty()) {
            "Expected no changed elements"
        }
    }

    @Test
    @DisplayName("Compute delta should detect unchanged elements on revisit")
    fun testComputeDeltaRevisitUnchanged() {
        // Store initial elements
        val initial = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        graph.storeElements("editor_idle", initial)

        // Compute delta with same elements
        val delta = graph.computeElementDelta("editor_idle", initial)
        assert(delta.newElements.isEmpty()) {
            "Expected no new elements"
        }
        assert(delta.changedElements.isEmpty()) {
            "Expected no changed elements"
        }
        assert(delta.unchangedCount == 1) {
            "Expected 1 unchanged element"
        }
    }

    @Test
    @DisplayName("Compute delta should detect changed elements")
    fun testComputeDeltaChangedElements() {
        val initial = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "Refactor", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        graph.storeElements("editor_idle", initial)

        // Same label but different class (element changed type)
        val changed = listOf(
            UiComponent(cls = "ActionMenuItem", text = "Refactor", accessibleName = "Refactor", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        val delta = graph.computeElementDelta("editor_idle", changed)

        assert(delta.newElements.isEmpty()) {
            "Expected no new elements"
        }
        assert(delta.changedElements.size == 1) {
            "Expected 1 changed element"
        }
        assert(delta.unchangedCount == 0) {
            "Expected 0 unchanged elements"
        }
    }

    @Test
    @DisplayName("Find element by page and label")
    fun testFindElement() {
        val elements = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        graph.storeElements("editor_idle", elements)

        val found = graph.findElement("editor_idle", "Refactor")
        assert(found != null) {
            "Element should be found"
        }
        assert(found?.label == "Refactor") {
            "Found element should be Refactor"
        }
    }

    @Test
    @DisplayName("Find element with fuzzy label matching")
    fun testFindElementFuzzy() {
        val elements = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor This Code", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        graph.storeElements("editor_idle", elements)

        val found = graph.findElement("editor_idle", "refactor") // lowercase, partial
        assert(found != null) {
            "Element should be found with fuzzy match"
        }
        assert(found?.label?.contains("Refactor", ignoreCase = true) == true) {
            "Found element should contain 'Refactor'"
        }
    }
}
