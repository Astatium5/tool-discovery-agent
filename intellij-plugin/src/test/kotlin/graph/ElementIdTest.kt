package graph

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

class ElementIdTest {
    @Test
    @DisplayName("Element ID should include page, class, and label")
    fun testElementIdFormat() {
        val pageId = "editor_idle"
        val cls = "ActionButton"
        val label = "Refactor"
        val id = KnowledgeGraph.makeElementId(pageId, cls, label)

        assert(id == "editor_idle::ActionButton::Refactor") {
            "Expected editor_idle::ActionButton::Refactor but got $id"
        }
    }

    @Test
    @DisplayName("Element ID should truncate long labels")
    fun testLongLabelTruncation() {
        val pageId = "editor_idle"
        val cls = "ActionButton"
        val label = "This is a very long button label that should be truncated"
        val id = KnowledgeGraph.makeElementId(pageId, cls, label)

        // Label truncated to 40 chars, spaces are preserved
        assert(id == "editor_idle::ActionButton::This is a very long button label that sh") {
            "Expected truncated label but got $id"
        }
    }

    @Test
    @DisplayName("Element ID should sanitize special characters")
    fun testSpecialCharacterSanitization() {
        val pageId = "context_menu"
        val cls = "ActionMenuItem"
        val label = "Rename... (Shift+F6)"
        val id = KnowledgeGraph.makeElementId(pageId, cls, label)

        // Special chars are replaced with _, but spaces are preserved
        assert(id == "context_menu::ActionMenuItem::Rename___ _Shift_F6_") {
            "Expected sanitized special chars but got $id"
        }
    }

    @Test
    @DisplayName("Same element should always generate same ID")
    fun testIdStability() {
        val pageId = "editor_idle"
        val cls = "ActionButton"
        val label = "Refactor"

        val id1 = KnowledgeGraph.makeElementId(pageId, cls, label)
        val id2 = KnowledgeGraph.makeElementId(pageId, cls, label)

        assert(id1 == id2) {
            "Expected same IDs but got id1=$id1, id2=$id2"
        }
    }

    @Test
    @DisplayName("Transition should use actual element class, not 'unknown'")
    fun testTransitionElementId() {
        val graph = KnowledgeGraph()

        // Simulate a transition from clicking "Refactor" button
        graph.addTransition(
            fromPage = "editor_idle",
            elementId = KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "Refactor"),
            action = "click",
            toPage = "context_menu",
            params = mapOf("target" to "Refactor"),
        )

        val transitions = graph.getTransitionsFrom("editor_idle")
        assert(transitions.size == 1) {
            "Expected 1 transition but got ${transitions.size}"
        }

        val transition = transitions[0]
        assert(transition.elementId == "editor_idle::ActionButton::Refactor") {
            "Expected elementId 'editor_idle::ActionButton::Refactor' but got '${transition.elementId}'"
        }
    }
}
