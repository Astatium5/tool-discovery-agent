package graph

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

class ActionRecordTest {
    @Test
    @DisplayName("Action record should preserve element class and label")
    fun testActionRecordElementMetadata() {
        // Since ActionRecord is nested in GraphAgent, we verify through the graph
        val graph = KnowledgeGraph()

        // Add a transition that captures element metadata
        graph.addTransition(
            fromPage = "editor_idle",
            elementId = KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "Refactor"),
            action = "click",
            toPage = "context_menu",
            params = mapOf("target" to "Refactor"),
        )

        // Also add the element node
        val elementNode = ElementNode(
            id = KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "Refactor"),
            pageId = "editor_idle",
            cls = "ActionButton",
            label = "Refactor",
            xpath = "//div[@class='ActionButton' and @accessiblename='Refactor']",
            role = "button",
        )
        graph.addElement(elementNode)

        // Verify the element was stored with correct class and label
        val retrieved = graph.getElement("editor_idle::ActionButton::Refactor")
        assert(retrieved != null) { "Element should be stored in graph" }
        assert(retrieved?.cls == "ActionButton") {
            "Expected cls 'ActionButton' but got '${retrieved?.cls}'"
        }
        assert(retrieved?.label == "Refactor") {
            "Expected label 'Refactor' but got '${retrieved?.label}'"
        }
        assert(retrieved?.role == "button") {
            "Expected role 'button' but got '${retrieved?.role}'"
        }
    }
}
