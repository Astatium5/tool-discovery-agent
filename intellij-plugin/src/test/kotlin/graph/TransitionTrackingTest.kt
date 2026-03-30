package graph

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach

class TransitionTrackingTest {
    private lateinit var graph: KnowledgeGraph

    @BeforeEach
    fun setup() {
        graph = KnowledgeGraph()
    }

    @Test
    @DisplayName("addTransition should record success status")
    fun testAddTransitionWithSuccess() {
        val elementId = KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "Refactor")

        graph.addTransition(
            fromPage = "editor_idle",
            elementId = elementId,
            action = "click",
            toPage = "context_menu",
            params = emptyMap(),
            success = true,
        )

        val transitions = graph.getTransitionsFrom("editor_idle")
        assert(transitions.size == 1) {
            "Expected 1 transition, got ${transitions.size}"
        }

        val transition = transitions[0]
        assert(transition.success) {
            "Expected transition to be marked as success=true, got ${transition.success}"
        }
    }

    @Test
    @DisplayName("addTransition should record failure status")
    fun testAddTransitionWithFailure() {
        val elementId = KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "InvalidButton")

        graph.addTransition(
            fromPage = "editor_idle",
            elementId = elementId,
            action = "click",
            toPage = "editor_idle",  // Stay on same page (action failed)
            params = emptyMap(),
            success = false,
        )

        val transitions = graph.getTransitionsFrom("editor_idle")
        assert(transitions.size == 1) {
            "Expected 1 transition, got ${transitions.size}"
        }

        val transition = transitions[0]
        assert(!transition.success) {
            "Expected transition to be marked as success=false, got ${transition.success}"
        }
    }

    @Test
    @DisplayName("Transitions should default to success=true")
    fun testTransitionDefaultsToSuccess() {
        val elementId = KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "Refactor")

        // Don't specify success parameter
        graph.addTransition(
            fromPage = "editor_idle",
            elementId = elementId,
            action = "click",
            toPage = "context_menu",
        )

        val transitions = graph.getTransitionsFrom("editor_idle")
        assert(transitions.size == 1) {
            "Expected 1 transition, got ${transitions.size}"
        }

        val transition = transitions[0]
        assert(transition.success) {
            "Expected transition to default to success=true, got ${transition.success}"
        }
    }

    @Test
    @DisplayName("Should track multiple outcomes for same action")
    fun testMultipleOutcomesForSameAction() {
        val elementId = KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "Refactor")

        // Record successful attempt
        graph.addTransition(
            fromPage = "editor_idle",
            elementId = elementId,
            action = "click",
            toPage = "context_menu",
            success = true,
        )

        // Record failed attempt (same action, different outcome)
        graph.addTransition(
            fromPage = "editor_idle",
            elementId = elementId,
            action = "click",
            toPage = "editor_idle",  // Failed, stayed on same page
            success = false,
        )

        val transitions = graph.getTransitionsFrom("editor_idle")
        assert(transitions.size == 2) {
            "Expected 2 transitions, got ${transitions.size}"
        }

        val successCount = transitions.count { it.success }
        val failureCount = transitions.count { !it.success }

        assert(successCount == 1) {
            "Expected 1 successful transition, got $successCount"
        }
        assert(failureCount == 1) {
            "Expected 1 failed transition, got $failureCount"
        }
    }

    @Test
    @DisplayName("Failed transitions should still be stored in graph")
    fun testFailedTransitionsPersisted() {
        val elementId = KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "BrokenButton")

        graph.addTransition(
            fromPage = "editor_idle",
            elementId = elementId,
            action = "click",
            toPage = "editor_idle",
            success = false,
        )

        // Transition should be retrievable
        val transitions = graph.getTransitionsFrom("editor_idle")
        assert(transitions.isNotEmpty()) {
            "Failed transitions should still be stored"
        }
        assert(!transitions[0].success) {
            "Should preserve failure status"
        }
    }

    @Test
    @DisplayName("getTransitionsFrom should return all transitions including failures")
    fun testGetTransitionsFromIncludesFailures() {
        val elementId = KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "TestButton")

        graph.addTransition(
            fromPage = "editor_idle",
            elementId = elementId,
            action = "click",
            toPage = "success_page",
            success = true,
        )

        graph.addTransition(
            fromPage = "editor_idle",
            elementId = elementId,
            action = "click",
            toPage = "editor_idle",
            success = false,
        )

        val transitions = graph.getTransitionsFrom("editor_idle")
        assert(transitions.size == 2) {
            "Should return both successful and failed transitions"
        }
    }
}
