package graph

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach

class PathfindingTest {
    private lateinit var graph: KnowledgeGraph

    @BeforeEach
    fun setup() {
        graph = KnowledgeGraph()
    }

    @Test
    @DisplayName("Empty graph should return null for any path")
    fun testEmptyGraphPathfinding() {
        val path = graph.findShortestPath("editor_idle", "rename_dialog")
        assert(path == null) {
            "Expected null path in empty graph, got $path"
        }
    }

    @Test
    @DisplayName("Direct transition should return path of length 1")
    fun testDirectTransition() {
        // Create a direct path: editor_idle -> rename_dialog
        val elementId = KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "Rename")
        graph.addTransition("editor_idle", elementId, "click", "rename_dialog")

        // Store the element so labels can be resolved
        graph.addElement(ElementNode(
            id = elementId,
            pageId = "editor_idle",
            cls = "ActionButton",
            label = "Rename",
            xpath = "//button[@text='Rename']",
            role = "button",
        ))

        val path = graph.findShortestPath("editor_idle", "rename_dialog")
        assert(path != null) {
            "Expected path to exist"
        }
        assert(path!!.length == 1) {
            "Expected path length 1, got ${path.length}"
        }
        assert(path.transitions.size == 1) {
            "Expected 1 transition, got ${path.transitions.size}"
        }
    }

    @Test
    @DisplayName("Multi-hop path should find shortest route")
    fun testMultiHopPath() {
        // Create a path: editor_idle -> context_menu -> rename_dialog
        val refactorButton = KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "Refactor")
        val renameMenuItem = KnowledgeGraph.makeElementId("context_menu", "ActionMenuItem", "Rename")

        graph.addTransition("editor_idle", refactorButton, "click", "context_menu")
        graph.addTransition("context_menu", renameMenuItem, "click_menu_item", "rename_dialog")

        // Store elements
        graph.addElement(ElementNode(
            id = refactorButton,
            pageId = "editor_idle",
            cls = "ActionButton",
            label = "Refactor",
            xpath = "//button[@text='Refactor']",
            role = "button",
        ))
        graph.addElement(ElementNode(
            id = renameMenuItem,
            pageId = "context_menu",
            cls = "ActionMenuItem",
            label = "Rename",
            xpath = "//menuitem[@text='Rename']",
            role = "menu_item",
        ))

        val path = graph.findShortestPath("editor_idle", "rename_dialog")
        assert(path != null) {
            "Expected path to exist"
        }
        assert(path!!.length == 2) {
            "Expected path length 2, got ${path.length}"
        }
        assert(path.transitions.size == 2) {
            "Expected 2 transitions, got ${path.transitions.size}"
        }

        // Verify the path goes through context_menu
        assert(path.transitions[0].toPage == "context_menu") {
            "Expected first hop to context_menu, got ${path.transitions[0].toPage}"
        }
        assert(path.transitions[1].toPage == "rename_dialog") {
            "Expected second hop to rename_dialog, got ${path.transitions[1].toPage}"
        }
    }

    @Test
    @DisplayName("Should return null when no path exists")
    fun testNoPathExists() {
        // Create two disconnected components
        graph.addTransition(
            "editor_idle",
            KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "Find"),
            "click",
            "find_dialog",
        )
        graph.addTransition(
            "settings",
            KnowledgeGraph.makeElementId("settings", "ActionButton", "Save"),
            "click",
            "saved_state",
        )

        val path = graph.findShortestPath("editor_idle", "saved_state")
        assert(path == null) {
            "Expected null when no path exists, got $path"
        }
    }

    @Test
    @DisplayName("Same start and end should return empty path")
    fun testSameStartEnd() {
        val path = graph.findShortestPath("editor_idle", "editor_idle")
        assert(path != null) {
            "Expected empty path for same start/end"
        }
        assert(path!!.length == 0) {
            "Expected length 0 for same start/end, got ${path.length}"
        }
        assert(path.transitions.isEmpty()) {
            "Expected no transitions for same start/end"
        }
    }

    @Test
    @DisplayName("Should find all reachable pages from start")
    fun testReachablePages() {
        // Create a graph structure:
        //   editor_idle -> context_menu -> rename_dialog
        //                 -> find_dialog
        //   settings -> saved_state

        graph.addTransition(
            "editor_idle",
            KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "Refactor"),
            "click",
            "context_menu",
        )
        graph.addTransition(
            "context_menu",
            KnowledgeGraph.makeElementId("context_menu", "ActionMenuItem", "Rename"),
            "click_menu_item",
            "rename_dialog",
        )
        graph.addTransition(
            "editor_idle",
            KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "Find"),
            "click",
            "find_dialog",
        )
        graph.addTransition(
            "settings",
            KnowledgeGraph.makeElementId("settings", "ActionButton", "Save"),
            "click",
            "saved_state",
        )

        val reachable = graph.findReachablePages("editor_idle")

        // Should reach editor_idle itself, context_menu, rename_dialog, and find_dialog
        assert(reachable.size == 4) {
            "Expected 4 reachable pages, got ${reachable.size}: $reachable"
        }
        assert("editor_idle" in reachable) {
            "Should include start page"
        }
        assert("context_menu" in reachable) {
            "Should include context_menu"
        }
        assert("rename_dialog" in reachable) {
            "Should include rename_dialog"
        }
        assert("find_dialog" in reachable) {
            "Should include find_dialog"
        }
        assert("settings" !in reachable) {
            "Should NOT include disconnected page 'settings'"
        }
        assert("saved_state" !in reachable) {
            "Should NOT include disconnected page 'saved_state'"
        }
    }

    @Test
    @DisplayName("Should prefer shorter path when multiple exist")
    fun testShortestPathPreference() {
        // Create two paths from editor_idle to rename_dialog:
        // Path 1: editor_idle -> rename_dialog (direct)
        // Path 2: editor_idle -> context_menu -> rename_dialog (longer)

        graph.addTransition(
            "editor_idle",
            KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "Rename"),
            "click",
            "rename_dialog",
        )
        graph.addTransition(
            "editor_idle",
            KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "Refactor"),
            "click",
            "context_menu",
        )
        graph.addTransition(
            "context_menu",
            KnowledgeGraph.makeElementId("context_menu", "ActionMenuItem", "Rename"),
            "click_menu_item",
            "rename_dialog",
        )

        val path = graph.findShortestPath("editor_idle", "rename_dialog")
        assert(path != null) {
            "Expected path to exist"
        }
        assert(path!!.length == 1) {
            "Should prefer shorter path (length 1), got ${path.length}"
        }
    }

    @Test
    @DisplayName("formatSteps should resolve element labels")
    fun testFormatSteps() {
        val elementId = KnowledgeGraph.makeElementId("editor_idle", "ActionButton", "Refactor")
        graph.addTransition("editor_idle", elementId, "click", "context_menu")
        graph.addElement(ElementNode(
            id = elementId,
            pageId = "editor_idle",
            cls = "ActionButton",
            label = "Refactor",
            xpath = "//button",
            role = "button",
        ))

        val path = graph.findShortestPath("editor_idle", "context_menu")
        assert(path != null) {
            "Expected path to exist"
        }

        val steps = path!!.formatSteps(graph.getElementsForPage("editor_idle")
            .associateBy { it.id })
        assert(steps.size == 1) {
            "Expected 1 step, got ${steps.size}"
        }
        assert(steps[0] == "click \"Refactor\"") {
            "Expected formatted step, got ${steps[0]}"
        }
    }
}
