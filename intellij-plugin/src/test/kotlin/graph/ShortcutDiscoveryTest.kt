package graph

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach
import graph.GraphAgent.ActionRecord

class ShortcutDiscoveryTest {
    private lateinit var graph: KnowledgeGraph

    @BeforeEach
    fun setup() {
        graph = KnowledgeGraph()
    }

    @Test
    @DisplayName("Should discover no shortcuts with too few actions")
    fun testInsufficientActionsForShortcut() {
        val actions = listOf(
            ActionRecord("click", mapOf("target" to "Refactor"), "editor_idle", "context_menu", "test", true),
        )

        val shortcuts = graph.discoverShortcuts(actions)
        assert(shortcuts.isEmpty()) {
            "Expected no shortcuts with only 1 action"
        }
    }

    @Test
    @DisplayName("Should discover shortcut after repeated sequence")
    fun testDiscoverShortcutFromRepeatedSequence() {
        // Create a repeated 2-action sequence (click Refactor, then click Rename)
        // Repeated 3 times to meet SHORTCUT_MIN_REPEATS threshold
        val actions = mutableListOf<ActionRecord>()

        repeat(3) {
            actions.add(ActionRecord(
                actionType = "click",
                params = mapOf("target" to "Refactor"),
                pageBefore = "editor_idle",
                pageAfter = "context_menu",
                reasoning = "open refactor menu",
                success = true,
                elementClass = "ActionButton",
                elementLabel = "Refactor",
            ))
            actions.add(ActionRecord(
                actionType = "click_menu_item",
                params = mapOf("label" to "Rename"),
                pageBefore = "context_menu",
                pageAfter = "rename_dialog",
                reasoning = "select rename",
                success = true,
                elementClass = "ActionMenuItem",
                elementLabel = "Rename",
            ))
        }

        val shortcuts = graph.discoverShortcuts(actions)

        assert(shortcuts.size == 1) {
            "Expected 1 shortcut to be discovered, got ${shortcuts.size}"
        }

        val shortcut = shortcuts[0]
        assert(shortcut.name == "refactor_rename") {
            "Expected shortcut name 'refactor_rename', got '${shortcut.name}'"
        }
        assert(shortcut.steps.size == 2) {
            "Expected 2 steps in shortcut, got ${shortcut.steps.size}"
        }
    }

    @Test
    @DisplayName("Should not discover shortcuts from failed actions")
    fun testIgnoresFailedActions() {
        val actions = mutableListOf<ActionRecord>()

        repeat(3) {
            actions.add(ActionRecord(
                actionType = "click",
                params = mapOf("target" to "InvalidButton"),
                pageBefore = "editor_idle",
                pageAfter = "editor_idle",
                reasoning = "click invalid button",
                success = false,  // Failed action
                elementClass = "ActionButton",
                elementLabel = "InvalidButton",
            ))
        }

        val shortcuts = graph.discoverShortcuts(actions)

        assert(shortcuts.isEmpty()) {
            "Expected no shortcuts from failed actions"
        }
    }

    @Test
    @DisplayName("Should discover shortcuts for different sequence lengths")
    fun testDiscoversShortcutsOfVariousLengths() {
        val actions = mutableListOf<ActionRecord>()

        // 3-action sequence repeated 3 times
        repeat(3) {
            actions.add(ActionRecord("click", mapOf("target" to "A"), "p1", "p2", "test", true, "Button", "A"))
            actions.add(ActionRecord("click", mapOf("target" to "B"), "p2", "p3", "test", true, "Button", "B"))
            actions.add(ActionRecord("click", mapOf("target" to "C"), "p3", "p4", "test", true, "Button", "C"))
        }

        val shortcuts = graph.discoverShortcuts(actions)

        // Should find shortcuts of length 2 and 3
        assert(shortcuts.isNotEmpty()) {
            "Expected to find shortcuts"
        }
    }

    @Test
    @DisplayName("getShortcutsForPage should filter by page elements")
    fun testGetShortcutsForPageFiltersByPage() {
        // Add some elements to different pages
        graph.addElement(ElementNode("id1", "page_a", "Button", "Refactor", "//btn", "button"))
        graph.addElement(ElementNode("id2", "page_b", "Button", "Save", "//btn", "button"))

        // Create a shortcut that starts with "Refactor" action
        val shortcut = Shortcut(
            name = "refactor_rename",
            steps = listOf(
                mapOf("action" to "click", "target" to "Refactor"),
                mapOf("action" to "click_menu_item", "target" to "Rename"),
            ),
        )
        graph.addShortcut(shortcut)

        // On page_a, shortcut should be available
        val pageAShortcuts = graph.getShortcutsForPage("page_a")
        assert(pageAShortcuts.size == 1) {
            "Expected 1 shortcut on page_a, got ${pageAShortcuts.size}"
        }

        // On page_b, shortcut should NOT be available (no Refactor element)
        val pageBShortcuts = graph.getShortcutsForPage("page_b")
        assert(pageBShortcuts.isEmpty()) {
            "Expected no shortcuts on page_b"
        }
    }

    @Test
    @DisplayName("Should not create duplicate shortcuts")
    fun testNoDuplicateShortcuts() {
        val actions = mutableListOf<ActionRecord>()

        repeat(3) {
            actions.add(ActionRecord("click", mapOf("target" to "A"), "p1", "p2", "test", true, "Button", "A"))
            actions.add(ActionRecord("click", mapOf("target" to "B"), "p2", "p3", "test", true, "Button", "B"))
        }

        // First discovery
        val shortcuts1 = graph.discoverShortcuts(actions)
        assert(shortcuts1.size == 1) {
            "Expected 1 shortcut on first discovery"
        }

        // Second discovery with same actions
        val shortcuts2 = graph.discoverShortcuts(actions)
        assert(shortcuts2.isEmpty()) {
            "Expected no new shortcuts on second discovery (already exists)"
        }

        // Verify only one shortcut exists in graph
        val allShortcuts = graph.stats()["shortcuts"]
        assert(allShortcuts == 1) {
            "Expected only 1 shortcut in graph, got $allShortcuts"
        }
    }

    @Test
    @DisplayName("recordShortcutUsed should track usage and success")
    fun testRecordShortcutUsed() {
        val shortcut = Shortcut(
            name = "test_shortcut",
            steps = listOf(mapOf("action" to "click")),
            usageCount = 0,
            successCount = 0,
        )
        graph.addShortcut(shortcut)

        graph.recordShortcutUsed("test_shortcut", success = true)
        graph.recordShortcutUsed("test_shortcut", success = true)
        graph.recordShortcutUsed("test_shortcut", success = false)

        val retrieved = graph.getShortcut("test_shortcut")
        assert(retrieved != null) {
            "Shortcut should exist"
        }
        assert(retrieved!!.usageCount == 3) {
            "Expected usageCount=3, got ${retrieved.usageCount}"
        }
        assert(retrieved.successCount == 2) {
            "Expected successCount=2, got ${retrieved.successCount}"
        }
    }

    @Test
    @DisplayName("generateShortcutName should handle edge cases")
    fun testShortcutNameGeneration() {
        val actions = listOf(
            ActionRecord("click", mapOf("target" to ""), "p1", "p2", "test", true, null, null),
            ActionRecord("click", mapOf("target" to "Button!!!"), "p2", "p3", "test", true, "Button", "Button!!!"),
        )

        // This test verifies the shortcut name generation doesn't crash
        // with empty or special character labels
        val shortcuts = graph.discoverShortcuts(actions)

        // With only 1 occurrence, no shortcut should be created
        // but we're testing the code doesn't crash
        assert(shortcuts.isEmpty()) {
            "Expected no shortcuts (insufficient repeats)"
        }
    }
}
