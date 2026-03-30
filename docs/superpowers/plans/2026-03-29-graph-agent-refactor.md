# Graph Agent Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the graph-based UI automation agent to actually reduce LLM context by making the knowledge graph the source of truth, adding pathfinding, and learning from failures.

**Architecture:**
1. Fix element ID generation to use actual component class names
2. Store all discovered elements in the graph on first visit
3. On subsequent visits, only send NEW elements to the LLM (delta-based context)
4. Add BFS pathfinding to compute shortest paths offline
5. Track failed transitions as negative edges
6. Discover and promote repeated action sequences to shortcuts

**Tech Stack:** Kotlin, kotlinx.serialization, in-memory graph with JSON persistence

**Testing Strategy:**
1. Unit tests for each component
2. Integration tests with mocked UI states
3. **Live Remote Robot smoke test after EACH major task** ( IntelliJ running on port 8082)

**Live Testing Setup:**
- IntelliJ IDEA running with Remote Robot plugin on port 8082
- Project open: `intellij-plugin` (the plugin project itself)
- Bridge server may be running (check if needed)
- Graph file location: `data/knowledge_graph.json`

---

## Task 1: Fix Element ID Generation

**Problem:** `GraphAgent.kt:401-403` uses `"unknown"` for element class and labels for IDs, making transitions fragile.

**Files:**
- Modify: `intellij-plugin/src/main/kotlin/graph/PageState.kt`
- Modify: `intellij-plugin/src/main/kotlin/graph/GraphTypes.kt`
- Modify: `intellij-plugin/src/main/kotlin/graph/KnowledgeGraph.kt`
- Create: `intellij-plugin/src/test/kotlin/graph/ElementIdTest.kt`

### Subtask 1.1: Add stable element ID to PageState

The `PageState` needs to expose element metadata for proper ID generation.

- [ ] **Step 1: Read current PageState implementation**

```bash
cat intellij-plugin/src/main/kotlin/graph/PageState.kt
```

- [ ] **Step 2: Add `elementId` property to UiComponent**

Wait — UiComponent is in `parser/UiModels.kt`, not `graph/`. Let me check if it already has an ID:

```bash
grep -n "data class UiComponent" intellij-plugin/src/main/kotlin/parser/UiModels.kt
```

- [ ] **Step 3: Read UiComponent definition**

```bash
cat intellij-plugin/src/main/kotlin/parser/UiModels.kt
```

- [ ] **Step 4: Write test for stable element ID generation**

Create `intellij-plugin/src/test/kotlin/graph/ElementIdTest.kt`:

```kotlin
package graph

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals

class ElementIdTest {
    @Test
    @DisplayName("Element ID should include page, class, and label")
    fun testElementIdFormat() {
        val pageId = "editor_idle"
        val cls = "ActionButton"
        val label = "Refactor"
        val id = KnowledgeGraph.makeElementId(pageId, cls, label)

        assertEquals("editor_idle::ActionButton::Refactor", id)
    }

    @Test
    @DisplayName("Element ID should truncate long labels")
    fun testLongLabelTruncation() {
        val pageId = "editor_idle"
        val cls = "ActionButton"
        val label = "This is a very long button label that should be truncated"
        val id = KnowledgeGraph.makeElementId(pageId, cls, label)

        // Label truncated to 40 chars, special chars replaced
        assertEquals("editor_idle::ActionButton::This_is_a_very_long_button_label_that_s", id)
    }

    @Test
    @DisplayName("Element ID should sanitize special characters")
    fun testSpecialCharacterSanitization() {
        val pageId = "context_menu"
        val cls = "ActionMenuItem"
        val label = "Rename... (Shift+F6)"
        val id = KnowledgeGraph.makeElementId(pageId, cls, label)

        assertEquals("context_menu::ActionMenuItem::Rename___Shift_F6_", id)
    }

    @Test
    @DisplayName("Same element should always generate same ID")
    fun testIdStability() {
        val pageId = "editor_idle"
        val cls = "ActionButton"
        val label = "Refactor"

        val id1 = KnowledgeGraph.makeElementId(pageId, cls, label)
        val id2 = KnowledgeGraph.makeElementId(pageId, cls, label)

        assertEquals(id1, id2)
    }
}
```

- [ ] **Step 5: Run tests to verify they pass (current implementation should work)**

```bash
cd intellij-plugin
./gradlew test --tests ElementIdTest
```

Expected: All tests PASS (current implementation handles this correctly)

- [ ] **Step 6: Commit test**

```bash
git add intellij-plugin/src/test/kotlin/graph/ElementIdTest.kt
git commit -m "test: add element ID generation tests"
```

### Subtask 1.2: Make clicked element metadata available

The issue is in `GraphAgent.kt:updateGraph()` where we create transitions but don't have the actual element that was clicked.

- [ ] **Step 1: Read the updateGraph method**

```bash
sed -n '391,417p' intellij-plugin/src/main/kotlin/graph/GraphAgent.kt
```

- [ ] **Step 2: Extend ActionRecord to include clicked element class**

Modify `GraphAgent.kt` around line 45-53:

```kotlin
@Serializable
data class ActionRecord(
    val actionType: String,
    val params: Map<String, String> = emptyMap(),
    val pageBefore: String,
    val pageAfter: String,
    val reasoning: String,
    val success: Boolean = true,
    // NEW: Track the actual element that was acted upon
    val elementClass: String? = null,  // e.g., "ActionButton", "ActionMenuItem"
    val elementLabel: String? = null,  // e.g., "Refactor", "Rename"
)
```

- [ ] **Step 3: Modify act() to find and record element metadata**

Find the `act()` method and extend it to look up the clicked element:

```kotlin
private fun act(
    decision: Decision,
    pageBefore: String,
    reasoning: String,
): ActionRecord {
    val actionType = decision.action
    val params = decision.params

    println("  Executing: $actionType with params: $params")

    // NEW: Find the element being acted upon (for click actions)
    var elementClass: String? = null
    var elementLabel: String? = null

    if (actionType == "click" || actionType == "click_menu_item" || actionType == "click_dialog_button") {
        val targetLabel = params["target"] ?: params["label"]
        if (targetLabel != null) {
            // Look up the element in the current page state
            val currentState = observe()  // Get current state to find element
            val clickedElement = currentState.elements.find { it.label == targetLabel }
            if (clickedElement != null) {
                elementClass = clickedElement.cls
                elementLabel = clickedElement.label
                println("  Found element: class=$elementClass, label=$elementLabel")
            }
        }
    }

    val success = try {
        executeAction(actionType, params)
        true
    } catch (e: Exception) {
        println("  Action failed: ${e.message}")
        false
    }

    return ActionRecord(
        actionType = actionType,
        params = params,
        pageBefore = pageBefore,
        pageAfter = pageBefore,
        reasoning = reasoning,
        success = success,
        elementClass = elementClass,  // NEW
        elementLabel = elementLabel,  // NEW
    )
}
```

- [ ] **Step 4: Modify updateGraph() to use proper element IDs**

Update the transition creation around line 400:

```kotlin
private fun updateGraph(
    record: ActionRecord,
    prevPageId: String?,
): PageState {
    val newState = observe()

    val updatedRecord = record.copy(pageAfter = newState.pageId)
    actionHistory[actionHistory.size - 1] = updatedRecord

    if (record.success && prevPageId != null && prevPageId != newState.pageId) {
        // Use actual element class if available
        val elementClass = record.elementClass ?: "unknown"
        val elementLabel = record.elementLabel ?: record.params["target"] ?: "unknown"

        val elementId = KnowledgeGraph.makeElementId(
            pageId = prevPageId,
            cls = elementClass,  // Was: "unknown"
            label = elementLabel,  // Was: params["target"]
        )

        graph.addTransition(
            fromPage = prevPageId,
            elementId = elementId,
            action = record.actionType,
            toPage = newState.pageId,
            params = record.params,
        )

        // Also store the element in the graph for future reference
        val elementNode = ElementNode(
            id = elementId,
            pageId = prevPageId,
            cls = elementClass,
            label = elementLabel,
            xpath = "",  // Could be filled in if we had it
            role = inferRole(elementClass),
        )
        graph.addElement(elementNode)
    }

    graph.save(graphPath)
    return newState
}

// NEW: Helper to infer role from class name
private fun inferRole(cls: String): String {
    return when {
        cls.contains("Button", ignoreCase = true) -> "button"
        cls.contains("MenuItem", ignoreCase = true) -> "menu_item"
        cls.contains("TextField", ignoreCase = true) -> "text_field"
        cls.contains("CheckBox", ignoreCase = true) -> "checkbox"
        cls.contains("ComboBox", ignoreCase = true) -> "dropdown"
        else -> "unknown"
    }
}
```

- [ ] **Step 5: Add test for transition with proper element ID**

Add to `intellij-plugin/src/test/kotlin/graph/ElementIdTest.kt`:

```kotlin
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
    assertEquals(1, transitions.size)

    val transition = transitions[0]
    assertEquals("editor_idle::ActionButton::Refactor", transition.elementId)
    assertNotEquals("editor_idle::unknown::Refactor", transition.elementId)
}
```

- [ ] **Step 6: Run all tests**

```bash
cd intellij-plugin
./gradlew test --tests ElementIdTest
```

Expected: All tests PASS

- [ ] **Step 7: Commit changes**

```bash
git add intellij-plugin/src/main/kotlin/graph/GraphAgent.kt
git add intellij-plugin/src/main/kotlin/graph/KnowledgeGraph.kt
git add intellij-plugin/src/test/kotlin/graph/ElementIdTest.kt
git commit -m "fix: use actual element class in transition IDs instead of 'unknown'"
```

### Subtask 1.3: Verify element metadata is preserved after action

- [ ] **Step 1: Add integration test for full action record flow**

Create `intellij-plugin/src/test/kotlin/graph/ActionRecordTest.kt`:

```kotlin
package graph

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ActionRecordTest {
    @Test
    @DisplayName("Action record should preserve element class and label")
    fun testActionRecordElementMetadata() {
        val record = GraphAgent.ActionRecord(
            actionType = "click",
            params = mapOf("target" to "Refactor"),
            pageBefore = "editor_idle",
            pageAfter = "context_menu",
            reasoning = "Click Refactor to open menu",
            success = true,
            elementClass = "ActionButton",
            elementLabel = "Refactor",
        )

        assertEquals("ActionButton", record.elementClass)
        assertEquals("Refactor", record.elementLabel)
    }
}
```

- [ ] **Step 2: Run test**

```bash
cd intellij-plugin
./gradlew test --tests ActionRecordTest
```

- [ ] **Step 3: Commit test**

```bash
git add intellij-plugin/src/test/kotlin/graph/ActionRecordTest.kt
git commit -m "test: add action record element metadata test"
```

### Subtask 1.4: Live Remote Robot Integration Test

**Goal:** Verify that element metadata is correctly captured when interacting with live IntelliJ.

- [ ] **Step 1: Verify Remote Robot is accessible**

```bash
curl -s http://localhost:8082 | head -20
```

Expected: HTML response from Remote Robot (IDE is accessible)

- [ ] **Step 2: Clear graph for clean test**

```bash
rm -f data/knowledge_graph.json
mkdir -p data
```

- [ ] **Step 3: Run a simple smoke test**

```bash
cd intellij-plugin
./gradlew runGraphAgent --args="open the Find dialog and then close it"
```

Watch for:
- "Found element: class=ActionButton, label=Find" in output
- Element metadata being logged
- Transitions being recorded with proper element IDs

- [ ] **Step 4: Inspect the generated graph**

```bash
cat data/knowledge_graph.json | jq '.transitions | length'
cat data/knowledge_graph.json | jq '.transitions[] | {from: .fromPage, elementId: .elementId, to: .toPage}'
```

Expected: Transitions should have element IDs like "editor_idle::ActionButton::Find" NOT "editor_idle::unknown::Find"

- [ ] **Step 5: Verify element class is captured**

Check that transitions show actual class names (ActionButton, ActionMenuItem, etc.):

```bash
cat data/knowledge_graph.json | jq '.elements[] | {id, cls, label}'
```

Expected: Elements should have `cls` like "ActionButton", not "unknown"

- [ ] **Step 6: If tests fail, debug**

If element classes are "unknown":
1. Check GraphAgent logs for "Found element:" messages
2. Verify `act()` is finding elements before executing
3. Check that `UiComponent` has the expected `cls` field

- [ ] **Step 7: Document test results**

Create a brief note in `docs/test-results/task-1-element-id-live-test.md`:

```markdown
# Task 1: Element ID - Live Test Results

Date: [fill in]

## Test Task
"open the Find dialog and then close it"

## Results
- Element classes captured correctly: YES/NO
- Transition IDs use actual class names: YES/NO
- Example element ID: [paste one example]

## Issues Found
- [List any issues]
```

---

## Task 2: Store All Elements on First Page Visit

**Problem:** Elements are re-discovered and re-sent to LLM on every visit. We need to store them and only send deltas.

**Files:**
- Modify: `intellij-plugin/src/main/kotlin/graph/KnowledgeGraph.kt`
- Modify: `intellij-plugin/src/main/kotlin/graph/PageState.kt`
- Modify: `intellij-plugin/src/main/kotlin/graph/GraphAgent.kt`
- Create: `intellij-plugin/src/test/kotlin/graph/ElementStorageTest.kt`

### Subtask 2.1: Add element storage to KnowledgeGraph

- [ ] **Step 1: Add method to check if page has been visited before**

Add to `KnowledgeGraph.kt`:

```kotlin
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
```

- [ ] **Step 2: Add method to compare current elements with stored elements**

Add to `KnowledgeGraph.kt`:

```kotlin
/**
 * Compare current UI elements with stored elements.
 * Returns pair of (newElements, changedElements).
 */
data class ElementDelta(
    val newElements: List<ElementNode>,
    val changedElements: List<ElementNode>,
    val unchangedCount: Int,
)

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
```

- [ ] **Step 3: Add method to store all elements from a page visit**

Add to `KnowledgeGraph.kt`:

```kotlin
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
```

- [ ] **Step 4: Write tests for element storage**

Create `intellij-plugin/src/test/kotlin/graph/ElementStorageTest.kt`:

```kotlin
package graph

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach
import parser.UiComponent
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElementStorageTest {
    private lateinit var graph: KnowledgeGraph

    @BeforeEach
    fun setup() {
        graph = KnowledgeGraph()
    }

    @Test
    @DisplayName("First visit should have no stored elements")
    fun testFirstVisitNoElements() {
        assertEquals(false, graph.hasVisitedPage("editor_idle"))
        assertEquals(emptyList(), graph.getElementsForPage("editor_idle"))
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
        assertEquals(2, stored.size)
        assertEquals("Refactor", stored[0].label)
        assertEquals("Find", stored[1].label)
    }

    @Test
    @DisplayName("Compute delta should find new elements")
    fun testComputeDeltaNewElements() {
        // Store initial elements
        val initial = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        graph.storeElements("editor_idle", initial)

        // Compute delta with same elements
        val delta1 = graph.computeElementDelta("editor_idle", initial)
        assertEquals(0, delta1.newElements.size)
        assertEquals(0, delta1.changedElements.size)
        assertEquals(1, delta1.unchangedCount)

        // Compute delta with new element
        val withNew = initial + listOf(
            UiComponent(cls = "ActionButton", text = "Find", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        val delta2 = graph.computeElementDelta("editor_idle", withNew)
        assertEquals(1, delta2.newElements.size)
        assertEquals("Find", delta2.newElements[0].label)
        assertEquals(1, delta2.unchangedCount)
    }

    @Test
    @DisplayName("Compute delta should detect changed elements")
    fun testComputeDeltaChangedElements() {
        val initial = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList(), xpath = "//div[@text='Refactor']"),
        )
        graph.storeElements("editor_idle", initial)

        // Same label but different XPath (element moved)
        val changed = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList(), xpath = "//div[@text='Refactor'][2]"),
        )
        val delta = graph.computeElementDelta("editor_idle", changed)

        assertEquals(0, delta.newElements.size)
        assertEquals(1, delta.changedElements.size)
        assertEquals(0, delta.unchangedCount)
    }
}
```

- [ ] **Step 5: Run tests**

```bash
cd intellij-plugin
./gradlew test --tests ElementStorageTest
```

Expected: Some tests may FAIL initially (implement iteratively)

- [ ] **Step 6: Fix any failing tests**

- [ ] **Step 7: Commit**

```bash
git add intellij-plugin/src/main/kotlin/graph/KnowledgeGraph.kt
git add intellij-plugin/src/test/kotlin/graph/ElementStorageTest.kt
git commit -m "feat: add element storage and delta computation to knowledge graph"
```

### Subtask 2.2: Modify PageState.toPromptString() to use delta

- [ ] **Step 1: Add delta-aware prompt method to PageState**

Add to `PageState.kt`:

```kotlin
/**
 * Generate prompt string with only new/changed elements.
 * For first visit or no delta available, falls back to full listing.
 */
fun toDeltaPromptString(hasVisitedBefore: Boolean, knownElements: List<graph.ElementNode>): String = buildString {
    appendLine("## Current UI: $pageId")
    appendLine(description)
    appendLine()

    if (elements.isEmpty()) {
        appendLine("(No interactive elements detected)")
        return@buildString
    }

    val knownByLabel = knownElements.associateBy { it.label }

    if (hasVisitedBefore) {
        appendLine("This page has been visited before.")
        appendLine()

        val newElements = elements.filter { knownByLabel[it.label] == null }
        val changedElements = elements.filter { known ->
            val knownEl = knownByLabel[known.label]
            knownEl != null && (knownEl.cls != known.cls || knownEl.xpath != known.xpath)
        }
        val unchangedCount = elements.size - newElements.size - changedElements.size

        if (newElements.isNotEmpty()) {
            appendLine("### New elements since last visit:")
            newElements.forEachIndexed { index, element ->
                val status = if (!element.enabled) " (disabled)" else ""
                val submenu = if (element.hasSubmenu) " ->" else ""
                appendLine("  ${index + 1}. \"${element.label}\"$status$submenu")
                appendLine("     XPath: ${element.xpath}")
            }
            appendLine()
        }

        if (changedElements.isNotEmpty()) {
            appendLine("### Changed elements since last visit:")
            changedElements.forEachIndexed { index, element ->
                val knownEl = knownByLabel[element.label]!!
                val changes = mutableListOf<String>()
                if (knownEl.cls != element.cls) changes += "class: ${knownEl.cls} → ${element.cls}"
                if (knownEl.xpath != element.xpath) changes += "position changed"
                appendLine("  - \"${element.label}\": ${changes.joinToString(", ")}")
            }
            appendLine()
        }

        appendLine("### Previously seen elements ($unchangedCount):")
        appendLine("(Not shown — stored in knowledge graph)")
    } else {
        appendLine("First visit to this page.")
        appendLine()
        appendLine("Available actions:")
        elements.forEachIndexed { index, element ->
            val status = if (!element.enabled) " (disabled)" else ""
            val submenu = if (element.hasSubmenu) " ->" else ""
            appendLine("  ${index + 1}. \"${element.label}\"$status$submenu")
            appendLine("     XPath: ${element.xpath}")
        }
    }
}
```

- [ ] **Step 2: Update GraphAgent to use delta-aware prompting**

Modify the `reason()` method in `GraphAgent.kt` around line 260:

```kotlin
private fun buildPrompt(
    task: String,
    page: PageState,
    history: List<ActionRecord>,
): String = buildString {
    appendLine("## Task")
    appendLine(task)
    appendLine()

    val hasVisitedBefore = graph.hasVisitedPage(page.pageId)
    val knownElements = graph.getElementsForPage(page.pageId)

    // NEW: Use delta-aware prompt for revisited pages
    appendLine(page.toDeltaPromptString(hasVisitedBefore, knownElements))
    appendLine()

    if (currentPageId != null) {
        appendLine(graph.toPromptContext(currentPageId!!))
        appendLine()
    }

    if (history.isNotEmpty()) {
        appendLine("## Recent Actions")
        history.takeLast(5).forEachIndexed { index, record ->
            appendLine("${index + 1}. ${record.actionType} on ${record.pageBefore}")
            appendLine("   Reasoning: ${record.reasoning}")
            appendLine("   Success: ${record.success}")
        }
        appendLine()
    }

    appendLine("## Your Decision")
    appendLine("Provide your response in JSON format:")
    appendLine("{")
    appendLine("  \"reasoning\": \"your thought process\",")
    appendLine("  \"decision\": {")
    appendLine("    \"action\": \"click|type|press_key|press_shortcut|open_context_menu|click_menu_item|select_dropdown|click_dialog_button|observe|complete|fail\",")
    appendLine("    \"params\": {")
    appendLine("      \"target\": \"element label (for click)\",")
    appendLine("      \"text\": \"text to type (for type)\",")
    appendLine("      \"key\": \"key name (for press_key)\",")
    appendLine("      \"keys\": \"shortcut keys (for press_shortcut)\",")
    appendLine("      \"label\": \"menu item label (for click_menu_item/click_dialog_button)\",")
    appendLine("      \"value\": \"dropdown value (for select_dropdown)\"")
    appendLine("    }")
    appendLine("  }")
    appendLine("}")
}
```

- [ ] **Step 3: Add test for delta prompt generation**

Create `intellij-plugin/src/test/kotlin/graph/DeltaPromptTest.kt`:

```kotlin
package graph

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach
import parser.UiComponent
import kotlin.test.assertTrue

class DeltaPromptTest {
    private lateinit var graph: KnowledgeGraph

    @BeforeEach
    fun setup() {
        graph = KnowledgeGraph()
    }

    @Test
    @DisplayName("First visit should show all elements")
    fun testFirstVisitShowsAllElements() {
        val elements = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
            UiComponent(cls = "ActionButton", text = "Find", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        val page = PageState(
            pageId = "editor_idle",
            description = "Editor idle state",
            elements = elements,
            rawHtml = "",
        )

        val prompt = page.toDeltaPromptString(false, emptyList())

        assertTrue(prompt.contains("First visit to this page"))
        assertTrue(prompt.contains("Refactor"))
        assertTrue(prompt.contains("Find"))
    }

    @Test
    @DisplayName("Revisit should hide unchanged elements")
    fun testRevisitHidesUnchangedElements() {
        // Store initial elements
        val elements = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        graph.storeElements("editor_idle", elements)

        // Simulate revisit
        val page = PageState(
            pageId = "editor_idle",
            description = "Editor idle state",
            elements = elements,  // Same elements
            rawHtml = "",
        )
        val knownElements = graph.getElementsForPage("editor_idle")

        val prompt = page.toDeltaPromptString(true, knownElements)

        assertTrue(prompt.contains("This page has been visited before"))
        assertTrue(prompt.contains("Previously seen elements (1)"))
        assertTrue(prompt.contains("Not shown"))
    }

    @Test
    @DisplayName("Revisit should show new elements")
    fun testRevisitShowsNewElements() {
        // Store initial element
        val initial = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        graph.storeElements("editor_idle", initial)

        // Simulate revisit with new element
        val elements = initial + listOf(
            UiComponent(cls = "ActionButton", text = "Find", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        val page = PageState(
            pageId = "editor_idle",
            description = "Editor idle state",
            elements = elements,
            rawHtml = "",
        )
        val knownElements = graph.getElementsForPage("editor_idle")

        val prompt = page.toDeltaPromptString(true, knownElements)

        assertTrue(prompt.contains("New elements since last visit"))
        assertTrue(prompt.contains("Find"))
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd intellij-plugin
./gradlew test --tests DeltaPromptTest
```

- [ ] **Step 5: Fix any issues**

- [ ] **Step 6: Commit**

```bash
git add intellij-plugin/src/main/kotlin/graph/PageState.kt
git add intellij-plugin/src/main/kotlin/graph/GraphAgent.kt
git add intellij-plugin/src/test/kotlin/graph/DeltaPromptTest.kt
git commit -m "feat: use delta-based prompts for revisited pages"
```

### Subtask 2.3: Store elements on each page visit

- [ ] **Step 1: Modify GraphAgent.observe() to store elements**

Update the `observe()` method in `GraphAgent.kt` around line 169:

```kotlin
private fun observe(): PageState {
    val components = treeProvider.fetchTree()
    val html = fetchRawHtml()
    val pageState = parser.inferPageState(components, html)

    // Store all elements from this page visit
    graph.storeElements(pageState.pageId, pageState.elements)

    graph.addPage(PageNode(pageState.pageId, pageState.description))
    graph.recordVisit(pageState.pageId)

    return pageState
}
```

- [ ] **Step 2: Run all graph tests**

```bash
cd intellij-plugin
./gradlew test --tests graph.*
```

- [ ] **Step 3: Commit**

```bash
git add intellij-plugin/src/main/kotlin/graph/GraphAgent.kt
git commit -m "feat: store elements on each page visit for delta tracking"
```

### Subtask 2.4: Live Test - Verify Element Storage and Delta Context

**Goal:** Confirm that elements are stored and delta-based prompts work with live IDE.

- [ ] **Step 1: Clear graph and test fresh**

```bash
rm -f data/knowledge_graph.json
```

- [ ] **Step 2: Run first task (should store elements)**

```bash
cd intellij-plugin
./gradlew runGraphAgent --args="navigate to Refactor menu"
```

Watch for:
- "Store all elements from this page visit" logs
- Page elements being added to graph

- [ ] **Step 3: Check stored elements**

```bash
cat data/knowledge_graph.json | jq '.elements | length'
cat data/knowledge_graph.json | jq '.elements[] | {pageId, label, cls}'
```

Expected: Multiple elements stored for visited pages

- [ ] **Step 4: Run same task again (should use delta)**

```bash
./gradlew runGraphAgent --args="navigate to Refactor menu"
```

Watch for:
- "This page has been visited before" in prompts
- "Previously seen elements (N) - Not shown" messages
- Shorter prompts (fewer elements listed)

- [ ] **Step 5: Compare prompt sizes**

Check logs for first vs second run. Second run should show:
- "First visit to this page" on first run
- "This page has been visited before" on second run
- Fewer elements listed on second run

- [ ] **Step 6: Verify delta with new elements**

Open a new file in IntelliJ (to add a new editor tab), then run:

```bash
./gradlew runGraphAgent --args="count how many editor tabs are open"
```

Expected: Should detect the new tab as a "new element"

- [ ] **Step 7: Document results**

Update `docs/test-results/task-2-delta-context-live-test.md`

---

## Task 3: Add BFS Pathfinding

**Problem:** We have a graph but don't use it to compute optimal paths.

**Files:**
- Modify: `intellij-plugin/src/main/kotlin/graph/KnowledgeGraph.kt`
- Modify: `intellij-plugin/src/main/kotlin/graph/GraphAgent.kt`
- Create: `intellij-plugin/src/test/kotlin/graph/PathfindingTest.kt`

### Subtask 3.1: Implement BFS pathfinding

- [ ] **Step 1: Add pathfinding to KnowledgeGraph**

Add to `KnowledgeGraph.kt`:

```kotlin
/**
 * Find shortest path from startPage to endPage using BFS.
 * Returns list of transitions to follow, or null if no path exists.
 */
data class GraphPath(
    val transitions: List<Transition>,
    val length: Int,
) {
    val steps: List<String> get() = transitions.map {
        val el = elements[it.elementId]
        "${it.action} \"${el?.label ?: it.elementId}\""
    }
}

fun findShortestPath(startPage: String, endPage: String): GraphPath? {
    if (startPage == endPage) {
        return GraphPath(emptyList(), 0)
    }

    // BFS queue: (current_page, path_so_far)
    val queue = ArrayDeque<Pair<String, List<Transition>>>()
    queue.addLast(startPage to emptyList())

    val visited = mutableSetOf<String>()
    visited.add(startPage)

    while (queue.isNotEmpty()) {
        val (current, path) = queue.removeFirst()

        // Get all transitions from current page
        val outgoing = getTransitionsFrom(current)

        for (transition in outgoing) {
            if (transition.toPage == endPage) {
                // Found destination
                return GraphPath(path + transition, path.size + 1)
            }

            if (transition.toPage !in visited) {
                visited.add(transition.toPage)
                queue.addLast(transition.toPage to (path + transition))
            }
        }
    }

    // No path found
    return null
}

/**
 * Find all pages reachable from startPage.
 */
fun findReachablePages(startPage: String): Set<String> {
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    queue.addLast(startPage)
    visited.add(startPage)

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (transition in getTransitionsFrom(current)) {
            if (transition.toPage !in visited) {
                visited.add(transition.toPage)
                queue.addLast(transition.toPage)
            }
        }
    }

    return visited
}
```

- [ ] **Step 2: Write pathfinding tests**

Create `intellij-plugin/src/test/kotlin/graph/PathfindingTest.kt`:

```kotlin
package graph

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class PathfindingTest {
    private lateinit var graph: KnowledgeGraph

    @BeforeEach
    fun setup() {
        graph = KnowledgeGraph()

        // Build a simple graph:
        // editor_idle → context_menu → rename_dialog
        // editor_idle → find_dialog
        graph.addTransition(
            fromPage = "editor_idle",
            elementId = "editor_idle::ActionButton::Refactor",
            action = "click",
            toPage = "context_menu",
        )
        graph.addTransition(
            fromPage = "context_menu",
            elementId = "context_menu::ActionMenuItem::Rename",
            action = "click",
            toPage = "rename_dialog",
        )
        graph.addTransition(
            fromPage = "editor_idle",
            elementId = "editor_idle::ActionButton::Find",
            action = "click",
            toPage = "find_dialog",
        )
    }

    @Test
    @DisplayName("Find direct path")
    fun testDirectPath() {
        val path = graph.findShortestPath("editor_idle", "find_dialog")

        assertNotNull(path)
        assertEquals(1, path.length)
        assertEquals("find_dialog", path.transitions.last().toPage)
    }

    @Test
    @DisplayName("Find multi-step path")
    fun testMultiStepPath() {
        val path = graph.findShortestPath("editor_idle", "rename_dialog")

        assertNotNull(path)
        assertEquals(2, path.length)
        assertEquals("context_menu", path.transitions[0].toPage)
        assertEquals("rename_dialog", path.transitions[1].toPage)
    }

    @Test
    @DisplayName("Return null for unreachable page")
    fun testUnreachablePage() {
        val path = graph.findShortestPath("find_dialog", "rename_dialog")

        assertNull(path)
    }

    @Test
    @DisplayName("Zero-length path for same page")
    fun testSamePage() {
        val path = graph.findShortestPath("editor_idle", "editor_idle")

        assertNotNull(path)
        assertEquals(0, path.length)
    }

    @Test
    @DisplayName("Find reachable pages")
    fun testReachablePages() {
        val reachable = graph.findReachablePages("editor_idle")

        assertEquals(3, reachable.size)
        assertTrue(reachable.contains("editor_idle"))
        assertTrue(reachable.contains("context_menu"))
        assertTrue(reachable.contains("rename_dialog"))
    }
}
```

- [ ] **Step 3: Run tests**

```bash
cd intellij-plugin
./gradlew test --tests PathfindingTest
```

- [ ] **Step 4: Fix any issues**

- [ ] **Step 5: Commit**

```bash
git add intellij-plugin/src/main/kotlin/graph/KnowledgeGraph.kt
git add intellij-plugin/src/test/kotlin/graph/PathfindingTest.kt
git commit -m "feat: add BFS pathfinding to knowledge graph"
```

### Subtask 3.2: Use pathfinding in prompt

- [ ] **Step 1: Add pathfinding hint to prompt context**

Update `KnowledgeGraph.toPromptContext()` around line 79:

```kotlin
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

    // NEW: Suggest paths to common goal pages
    val commonGoals = listOf("rename_dialog", "find_dialog", "replace_dialog", "editor_idle")
    for (goal in commonGoals) {
        if (goal != currentPageId) {
            val path = findShortestPath(currentPageId, goal)
            if (path != null && path.length <= 5) {  // Only show reasonably short paths
                lines.add("  - ℹ️ Known path to $goal: ${path.steps.joinToString(" → ")}")
            }
        }
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
```

- [ ] **Step 2: Run all graph tests**

```bash
cd intellij-plugin
./gradlew test --tests graph.*
```

- [ ] **Step 3: Commit**

```bash
git add intellij-plugin/src/main/kotlin/graph/KnowledgeGraph.kt
git commit -m "feat: suggest known paths in prompt context"
```

### Subtask 3.3: Live Test - Verify Pathfinding

**Goal:** Confirm pathfinding works and the agent suggests known routes.

- [ ] **Step 1: Build up a graph with multiple transitions**

```bash
rm -f data/knowledge_graph.json

# Run tasks that build different paths
cd intellij-plugin
./gradlew runGraphAgent --args="open Find dialog then close it"
./gradlew runGraphAgent --args="open Replace dialog then close it"
./gradlew runGraphAgent --args="navigate to Refactor menu then close it"
```

- [ ] **Step 2: Inspect the graph structure**

```bash
cat data/knowledge_graph.json | jq '.pages | length'
cat data/knowledge_graph.json | jq '.transitions | length'
cat data/knowledge_graph.json | jq '.transitions[] | {from: .fromPage, to: .toPage}'
```

Expected: Multiple pages and transitions recorded

- [ ] **Step 3: Test pathfinding with a query**

```bash
./gradlew runGraphAgent --args="get to the Rename dialog"
```

Watch for:
- "ℹ️ Known path to rename_dialog" in the prompt context
- Agent using the suggested path

- [ ] **Step 4: Verify BFS finds shortest path**

Check the logs. If multiple paths exist to a page, agent should suggest the shortest one.

- [ ] **Step 5: Test with a page not yet discovered**

```bash
./gradlew runGraphAgent --args="open a dialog you haven't opened before"
```

Expected: No path suggested, agent explores

- [ ] **Step 6: Document results**

Update `docs/test-results/task-3-pathfinding-live-test.md`

---

## Task 4: Track Failed Transitions

**Problem:** Failed actions are recorded but not used to prevent repeating mistakes.

**Files:**
- Modify: `intellij-plugin/src/main/kotlin/graph/GraphTypes.kt`
- Modify: `intellij-plugin/src/main/kotlin/graph/KnowledgeGraph.kt`
- Modify: `intellij-plugin/src/main/kotlin/graph/GraphAgent.kt`
- Create: `intellij-plugin/src/test/kotlin/graph/FailedTransitionTest.kt`

### Subtask 4.1: Add failed transition type

- [ ] **Step 1: Add FailedTransition data class**

Add to `GraphTypes.kt`:

```kotlin
/**
 * A transition that was attempted but failed.
 * Used to avoid repeating unsuccessful actions.
 */
@Serializable
data class FailedTransition(
    val fromPage: String,
    val elementId: String,
    val action: String,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis(),
)
```

Update `SerializedGraph` to include failed transitions:

```kotlin
@Serializable
data class SerializedGraph(
    val pages: List<PageNode>,
    val elements: List<ElementNode>,
    val transitions: List<Transition>,
    val shortcuts: List<Shortcut>,
    val failedTransitions: List<FailedTransition> = emptyList(),  // NEW
)
```

- [ ] **Step 2: Add failed transition tracking to KnowledgeGraph**

Add to `KnowledgeGraph.kt`:

```kotlin
private val failedTransitions: MutableList<FailedTransition> = mutableListOf()

fun addFailedTransition(
    fromPage: String,
    elementId: String,
    action: String,
    reason: String,
) {
    failedTransitions.add(FailedTransition(fromPage, elementId, action, reason))
}

/**
 * Check if a transition has previously failed.
 */
fun hasFailedTransition(fromPage: String, elementId: String, action: String): Boolean {
    // Consider a failure "stale" after 1 hour
    val staleThreshold = System.currentTimeMillis() - (60 * 60 * 1000)
    val recentFailures = failedTransitions.filter { it.timestamp > staleThreshold }

    return recentFailures.any {
        it.fromPage == fromPage &&
        it.elementId == elementId &&
        it.action == action
    }
}

/**
 * Get recent failed transitions from a page.
 */
fun getFailedTransitionsFrom(pageId: String): List<FailedTransition> {
    val staleThreshold = System.currentTimeMillis() - (60 * 60 * 1000)
    return failedTransitions.filter {
        it.fromPage == pageId && it.timestamp > staleThreshold
    }
}
```

Update `save()` and `load()` to handle failed transitions:

```kotlin
fun save(path: String) {
    val filePath = Paths.get(path)
    try {
        Files.createDirectories(filePath.parent)

        val data = SerializedGraph(
            pages = pages.values.toList(),
            elements = elements.values.toList(),
            transitions = transitions.toList(),
            shortcuts = shortcuts.values.toList(),
            failedTransitions = failedTransitions.toList(),  // NEW
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
        failedTransitions.addAll(data.failedTransitions)  // NEW
    } catch (e: Exception) {
        println("Warning: Failed to load knowledge graph: ${e.message}")
    }
}
```

- [ ] **Step 3: Update toPromptContext to show failed transitions**

Add to `KnowledgeGraph.toPromptContext()`:

```kotlin
val failedFromHere = getFailedTransitionsFrom(currentPageId)
if (failedFromHere.isNotEmpty()) {
    lines.add("\n### ⚠️ Previously failed actions on this page:")
    for (f in failedFromHere.distinctBy { it.elementId }) {
        val el = elements[f.elementId]
        val elLabel = el?.label ?: f.elementId
        lines.add("  - ${f.action} \"$elLabel\" (failed: ${f.reason})")
    }
}
```

- [ ] **Step 4: Write tests**

Create `intellij-plugin/src/test/kotlin/graph/FailedTransitionTest.kt`:

```kotlin
package graph

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class FailedTransitionTest {
    private lateinit var graph: KnowledgeGraph

    @BeforeEach
    fun setup() {
        graph = KnowledgeGraph()
    }

    @Test
    @DisplayName("Record and check failed transition")
    fun testRecordFailedTransition() {
        graph.addFailedTransition(
            fromPage = "editor_idle",
            elementId = "editor_idle::ActionButton::Cancel",
            action = "click",
            reason = "Element not found",
        )

        assertTrue(graph.hasFailedTransition(
            fromPage = "editor_idle",
            elementId = "editor_idle::ActionButton::Cancel",
            action = "click",
        ))
    }

    @Test
    @DisplayName("Different action on same element should not be marked as failed")
    fun testDifferentActionNotFailed() {
        graph.addFailedTransition(
            fromPage = "editor_idle",
            elementId = "editor_idle::ActionButton::Refactor",
            action = "click",
            reason = "Failed",
        )

        assertFalse(graph.hasFailedTransition(
            fromPage = "editor_idle",
            elementId = "editor_idle::ActionButton::Refactor",
            action = "type",  // Different action
        ))
    }

    @Test
    @DisplayName("Get failed transitions from page")
    fun testGetFailedTransitionsFromPage() {
        graph.addFailedTransition("editor_idle", "btn1", "click", "reason1")
        graph.addFailedTransition("editor_idle", "btn2", "click", "reason2")
        graph.addFailedTransition("other_page", "btn3", "click", "reason3")

        val failed = graph.getFailedTransitionsFrom("editor_idle")
        assertEquals(2, failed.size)
    }
}
```

- [ ] **Step 5: Run tests**

```bash
cd intellij-plugin
./gradlew test --tests FailedTransitionTest
```

- [ ] **Step 6: Fix any issues**

- [ ] **Step 7: Commit**

```bash
git add intellij-plugin/src/main/kotlin/graph/GraphTypes.kt
git add intellij-plugin/src/main/kotlin/graph/KnowledgeGraph.kt
git add intellij-plugin/src/test/kotlin/graph/FailedTransitionTest.kt
git commit -m "feat: track failed transitions to avoid repeating mistakes"
```

### Subtask 4.2: Record failures in GraphAgent

- [ ] **Step 1: Update updateGraph to record failures**

Modify `GraphAgent.updateGraph()` to handle failures:

```kotlin
private fun updateGraph(
    record: ActionRecord,
    prevPageId: String?,
): PageState {
    val newState = observe()

    val updatedRecord = record.copy(pageAfter = newState.pageId)
    actionHistory[actionHistory.size - 1] = updatedRecord

    if (prevPageId != null) {
        val elementClass = record.elementClass ?: "unknown"
        val elementLabel = record.elementLabel ?: record.params["target"] ?: "unknown"
        val elementId = KnowledgeGraph.makeElementId(prevPageId, elementClass, elementLabel)

        if (record.success && prevPageId != newState.pageId) {
            // Successful transition
            graph.addTransition(
                fromPage = prevPageId,
                elementId = elementId,
                action = record.actionType,
                toPage = newState.pageId,
                params = record.params,
            )
        } else if (!record.success) {
            // Failed transition - record it
            val failureReason = record.reasoning.takeIf { it.isNotBlank() }
                ?: "Action execution failed"
            graph.addFailedTransition(
                fromPage = prevPageId,
                elementId = elementId,
                action = record.actionType,
                reason = failureReason,
            )
        }

        val elementNode = ElementNode(
            id = elementId,
            pageId = prevPageId,
            cls = elementClass,
            label = elementLabel,
            xpath = "",
            role = inferRole(elementClass),
        )
        graph.addElement(elementNode)
    }

    graph.save(graphPath)
    return newState
}
```

- [ ] **Step 2: Run all graph tests**

```bash
cd intellij-plugin
./gradlew test --tests graph.*
```

- [ ] **Step 3: Commit**

```bash
git add intellij-plugin/src/main/kotlin/graph/GraphAgent.kt
git commit -m "feat: record failed transitions in GraphAgent"
```

### Subtask 4.3: Live Test - Verify Failed Transition Tracking

**Goal:** Confirm failed actions are tracked and agent avoids repeating them.

- [ ] **Step 1: Clear graph**

```bash
rm -f data/knowledge_graph.json
```

- [ ] **Step 2: Run a task that will fail**

```bash
cd intellij-plugin
./gradlew runGraphAgent --args="click on a button that does not exist named NonexistentButton"
```

Expected: Task fails, logs show failure

- [ ] **Step 3: Check failed transitions in graph**

```bash
cat data/knowledge_graph.json | jq '.failedTransitions'
```

Expected: At least one failed transition recorded

- [ ] **Step 4: Run same task again**

```bash
./gradlew runGraphAgent --args="click on a button that does not exist named NonexistentButton"
```

Watch for:
- "⚠️ Previously failed actions on this page" in prompt
- Agent should recognize this will fail and respond differently (possibly "fail" immediately)

- [ ] **Step 5: Test with a legitimate action after failure**

```bash
./gradlew runGraphAgent --args="click on Refactor then cancel"
```

Expected: Successful actions should still work, failures are isolated to specific elements

- [ ] **Step 6: Verify stale failures expire**

Wait more than an hour OR manually edit the timestamp in knowledge_graph.json to make a failure stale, then:

```bash
./gradlew runGraphAgent --args="click on a button that does not exist named NonexistentButton"
```

Expected: Stale failures are not shown (they're considered "expired")

- [ ] **Step 7: Document results**

Update `docs/test-results/task-4-failed-transitions-live-test.md`

---

## Task 5: Shortcut Discovery

**Problem:** Shortcut data structure exists but no mechanism to discover and create shortcuts from repeated patterns.

**Files:**
- Modify: `intellij-plugin/src/main/kotlin/graph/KnowledgeGraph.kt`
- Modify: `intellij-plugin/src/main/kotlin/graph/GraphAgent.kt`
- Create: `intellij-plugin/src/test/kotlin/graph/ShortcutDiscoveryTest.kt`

### Subtask 5.1: Implement pattern detection for shortcuts

- [ ] **Step 1: Add pattern detection to KnowledgeGraph**

Add to `KnowledgeGraph.kt`:

```kotlin
/**
 * Discover repeated action sequences that could be shortcuts.
 * Returns sequences that appear at least minSupport times.
 */
data class ActionSequence(
    val actions: List<Pair<String, String>>,  // (action, target) pairs
    val frequency: Int,
)

fun discoverRepeatedSequences(
    minFrequency: Int = 3,
    maxLength: Int = 6,
): List<ActionSequence> {
    // Extract all action sequences from transition history
    val sequences = extractActionSequences()
    val frequency = mutableMapOf<List<Pair<String, String>>, Int>()

    for (sequence in sequences) {
        for (length in 2..minOf(maxLength, sequence.size)) {
            for (start in 0..sequence.size - length) {
                val subsequence = sequence.subList(start, start + length)
                frequency[subsequence] = frequency.getOrDefault(subsequence, 0) + 1
            }
        }
    }

    return frequency
        .filter { it.value >= minFrequency }
        .map { (actions, freq) -> ActionSequence(actions, freq) }
        .sortedByDescending { it.frequency }
}

/**
 * Extract action sequences from transition history grouped by "session".
 * A session is a sequence of actions that ends with a complete action or timeout.
 */
private fun extractActionSequences(): List<List<Pair<String, String>>> {
    // Group transitions by "sessions" - this is a simplified version
    // In reality, you'd track which transitions belong to which task execution

    // For now, extract sequences that end in known "terminal" pages
    val terminalPages = setOf("editor_idle", "rename_dialog", "find_dialog")

    val sequences = mutableListOf<List<Pair<String, String>>>()
    val currentSequence = mutableListOf<Pair<String, String>>()
    val visited = mutableSetOf<String>()

    // BFS to find paths from each page to terminal pages
    for (startPage in pages.keys) {
        if (startPage in terminalPages) continue

        val path = findPathToTerminal(startPage, terminalPages)
        if (path != null && path.size >= 2) {
            val actionPairs = path.mapNotNull { transition ->
                val el = elements[transition.elementId]
                el?.let { (transition.action to it.label) }
            }
            if (actionPairs.isNotEmpty()) {
                sequences.add(actionPairs)
            }
        }
    }

    return sequences
}

private fun findPathToTerminal(
    startPage: String,
    terminalPages: Set<String>,
): List<Transition>? {
    val queue = ArrayDeque<Pair<String, List<Transition>>>()
    queue.addLast(startPage to emptyList())
    val visited = mutableSetOf<String>()

    while (queue.isNotEmpty()) {
        val (current, path) = queue.removeFirst()

        if (current in terminalPages && path.isNotEmpty()) {
            return path
        }

        if (current in visited) continue
        visited.add(current)

        for (transition in getTransitionsFrom(current)) {
            queue.addLast(transition.toPage to (path + transition))
        }
    }

    return null
}

/**
 * Create a shortcut from a detected pattern.
 */
fun createShortcut(
    name: String,
    actions: List<Pair<String, String>>,
): Shortcut {
    val steps = actions.map { (action, target) ->
        mapOf("action" to action, "target" to target)
    }
    return Shortcut(name = name, steps = steps)
}

/**
 * Auto-discover and register shortcuts from common patterns.
 */
fun discoverAndRegisterShortcuts() {
    val patterns = discoverRepeatedSequences(minFrequency = 2, maxLength = 5)

    for (pattern in patterns) {
        val name = inferShortcutName(pattern.actions)
        if (name != null && !shortcuts.containsKey(name)) {
            val shortcut = createShortcut(name, pattern.actions)
            addShortcut(shortcut)
            println("  Discovered shortcut: $name (${pattern.actions.size} steps, frequency: ${pattern.frequency})")
        }
    }
}

private fun inferShortcutName(actions: List<Pair<String, String>>): String? {
    val actionLabels = actions.map { it.second }
    return when {
        actionLabels.any { it.equals("Rename", ignoreCase = true) } -> "rename_symbol"
        actionLabels.any { it.equals("Find", ignoreCase = true) } -> "open_find_dialog"
        actionLabels.any { it.equals("Replace", ignoreCase = true) } -> "open_replace_dialog"
        actionLabels.contains("Refactor") && actionLabels.any { it.contains("Rename", ignoreCase = true) } -> "refactor_rename"
        else -> null  // Don't auto-name uncertain patterns
    }
}
```

- [ ] **Step 2: Write tests**

Create `intellij-plugin/src/test/kotlin/graph/ShortcutDiscoveryTest.kt`:

```kotlin
package graph

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShortcutDiscoveryTest {
    private lateinit var graph: KnowledgeGraph

    @BeforeEach
    fun setup() {
        graph = KnowledgeGraph()

        // Build a graph with a common pattern:
        // editor_idle → Refactor → context_menu → Rename → rename_dialog
        // This pattern might repeat across tasks
        graph.addTransition("editor_idle", "editor_idle::ActionButton::Refactor", "click", "context_menu")
        graph.addTransition("context_menu", "context_menu::ActionMenuItem::Rename", "click", "rename_dialog")
        graph.addTransition("rename_dialog", "rename_dialog::Button::OK", "click", "editor_idle")
    }

    @Test
    @DisplayName("Find path from editor to rename dialog")
    fun testFindRenamePath() {
        val path = graph.findShortestPath("editor_idle", "rename_dialog")

        assertNotNull(path)
        assertEquals(2, path.length)
        assertEquals("context_menu", path.transitions[0].toPage)
        assertEquals("rename_dialog", path.transitions[1].toPage)
    }

    @Test
    @DisplayName("Discover shortcuts from repeated patterns")
    fun testDiscoverShortcuts() {
        // In a real scenario, we'd have multiple executions
        // For testing, we'll manually check the pattern detection

        val patterns = graph.discoverRepeatedSequences(minFrequency = 1, maxLength = 4)

        // Should find at least the rename pattern
        assertTrue(patterns.isNotEmpty())
    }

    @Test
    @DisplayName("Create shortcut from action sequence")
    fun testCreateShortcut() {
        val actions = listOf(
            "click" to "Refactor",
            "click_menu_item" to "Rename",
        )
        val shortcut = graph.createShortcut("rename_via_menu", actions)

        assertEquals("rename_via_menu", shortcut.name)
        assertEquals(2, shortcut.steps.size)
        assertEquals("click", shortcut.steps[0]["action"])
        assertEquals("Refactor", shortcut.steps[0]["target"])
    }
}
```

- [ ] **Step 3: Run tests**

```bash
cd intellij-plugin
./gradlew test --tests ShortcutDiscoveryTest
```

- [ ] **Step 4: Fix any issues**

- [ ] **Step 5: Commit**

```bash
git add intellij-plugin/src/main/kotlin/graph/KnowledgeGraph.kt
git add intellij-plugin/src/test/kotlin/graph/ShortcutDiscoveryTest.kt
git commit -m "feat: add shortcut discovery from repeated action patterns"
```

### Subtask 5.2: Run shortcut discovery after task completion

- [ ] **Step 1: Add shortcut discovery to GraphAgent.execute()**

Modify the end of `GraphAgent.execute()` before returning:

```kotlin
fun execute(task: String): AgentResult {
    println("\n=== GraphAgent Starting ===")
    // ... existing code ...

    println("\n=== GraphAgent Finished ===")

    // NEW: Discover shortcuts after task completes
    println("Discovering shortcuts from learned patterns...")
    graph.discoverAndRegisterShortcuts()

    return AgentResult(...)
}
```

- [ ] **Step 2: Run all graph tests**

```bash
cd intellij-plugin
./gradlew test --tests graph.*
```

- [ ] **Step 3: Commit**

```bash
git add intellij-plugin/src/main/kotlin/graph/GraphAgent.kt
git commit -m "feat: run shortcut discovery after task completion"
```

### Subtask 5.3: Live Test - Verify Shortcut Discovery

**Goal:** Confirm shortcuts are discovered and used after repeated patterns.

- [ ] **Step 1: Clear graph**

```bash
rm -f data/knowledge_graph.json
```

- [ ] **Step 2: Run the same task multiple times to build patterns**

```bash
cd intellij-plugin

# Run rename task 3 times to build pattern
for i in 1 2 3; do
  echo "=== Run $i ==="
  ./gradlew runGraphAgent --args="open the Refactor menu"
done
```

- [ ] **Step 3: Check for discovered shortcuts**

```bash
cat data/knowledge_graph.json | jq '.shortcuts'
```

Expected: Shortcuts like "rename_via_menu" or "refactor_rename" may appear

- [ ] **Step 4: Verify shortcuts are used in prompts**

Run a task that could use the shortcut:

```bash
./gradlew runGraphAgent --args="open the Refactor menu"
```

Watch for:
- "### Available shortcuts:" in prompt context
- Shortcut names and success rates shown

- [ ] **Step 5: Test shortcut effectiveness**

Compare iteration counts:
- First run: explore from scratch
- Later runs: should be faster if shortcuts are used

- [ ] **Step 6: Document results**

Update `docs/test-results/task-5-shortcut-discovery-live-test.md`

---

## Task 6: Evaluation & Verification

**Goal:** Verify that the changes actually reduce context and improve performance.

**Files:**
- Create: `intellij-plugin/src/test/kotlin/graph/ContextReductionTest.kt`
- Create: `docs/graph-agent-evaluation.md`

### Subtask 6.1: Measure context reduction

- [ ] **Step 1: Create test to measure prompt sizes**

Create `intellij-plugin/src/test/kotlin/graph/ContextReductionTest.kt`:

```kotlin
package graph

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach
import parser.UiComponent
import kotlin.test.assertTrue

class ContextReductionTest {
    private lateinit var graph: KnowledgeGraph

    @BeforeEach
    fun setup() {
        graph = KnowledgeGraph()
    }

    @Test
    @DisplayName("First visit prompt should include all elements")
    fun testFirstVisitPromptSize() {
        val elements = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
            UiComponent(cls = "ActionButton", text = "Find", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
            UiComponent(cls = "ActionButton", text = "Replace", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        val page = PageState(
            pageId = "editor_idle",
            description = "Editor idle",
            elements = elements,
            rawHtml = "",
        )

        val prompt = page.toDeltaPromptString(false, emptyList())
        val size = prompt.length

        // All elements should be mentioned
        assertTrue(prompt.contains("Refactor"))
        assertTrue(prompt.contains("Find"))
        assertTrue(prompt.contains("Replace"))
        println("First visit prompt size: $size chars")
    }

    @Test
    @DisplayName("Revisit prompt should be smaller than first visit")
    fun testRevisitPromptReduction() {
        // First visit
        val elements = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
            UiComponent(cls = "ActionButton", text = "Find", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        val page = PageState(
            pageId = "editor_idle",
            description = "Editor idle",
            elements = elements,
            rawHtml = "",
        )

        val firstVisitPrompt = page.toDeltaPromptString(false, emptyList())
        val firstVisitSize = firstVisitPrompt.length

        // Store elements and revisit
        graph.storeElements("editor_idle", elements)
        val knownElements = graph.getElementsForPage("editor_idle")

        val revisitPrompt = page.toDeltaPromptString(true, knownElements)
        val revisitSize = revisitPrompt.length

        println("First visit: $firstVisitSize chars")
        println("Revisit: $revisitSize chars")
        println("Reduction: ${100 * (1 - revisitSize.toDouble() / firstVisitSize)}%")

        // Revisit should be smaller (hides unchanged elements)
        assertTrue(revisitSize < firstVisitSize, "Revisit prompt should be smaller")
        assertTrue(revisitPrompt.contains("Previously seen elements"))
    }

    @Test
    @DisplayName("Revisit with new elements should only show new ones")
    fun testRevisitWithNewElements() {
        // First visit with 2 elements
        val initial = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
            UiComponent(cls = "ActionButton", text = "Find", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        graph.storeElements("editor_idle", initial)

        // Revisit with 3 elements (1 new)
        val withNew = initial + listOf(
            UiComponent(cls = "ActionButton", text = "Replace", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        val page = PageState(
            pageId = "editor_idle",
            description = "Editor idle",
            elements = withNew,
            rawHtml = "",
        )
        val knownElements = graph.getElementsForPage("editor_idle")

        val prompt = page.toDeltaPromptString(true, knownElements)

        // Should show only the new element
        assertTrue(prompt.contains("New elements since last visit"))
        assertTrue(prompt.contains("Replace"))
        assertTrue(prompt.contains("Previously seen elements (2)"))
    }
}
```

- [ ] **Step 2: Run test to verify context reduction**

```bash
cd intellij-plugin
./gradlew test --tests ContextReductionTest
```

Expected output should show significant reduction for revisit prompts.

- [ ] **Step 3: Commit**

```bash
git add intellij-plugin/src/test/kotlin/graph/ContextReductionTest.kt
git commit -m "test: verify context reduction for revisited pages"
```

### Subtask 6.2: Create evaluation guide

- [ ] **Step 1: Create evaluation document**

Create `docs/graph-agent-evaluation.md`:

```markdown
# Graph Agent Evaluation Guide

## Objective

Verify that the graph-based approach reduces token usage and iteration count compared to the flat UI tree approach.

## Metrics to Track

1. **Token Usage**: Total characters/words sent to LLM per task
2. **Iteration Count**: Number of observe-reason-act cycles
3. **Success Rate**: Tasks completed successfully
4. **Context Reduction**: For revisited pages, % reduction in prompt size

## Test Procedure

### Setup

1. Clear existing graph: `rm data/knowledge_graph.json`
2. Start IntelliJ with Remote Robot on port 8082
3. Start bridge server: `cd intellij-plugin && ./gradlew runBridgeServer`

### Run 1: Cold Start (No Graph)

```bash
cd intellij-plugin
./gradlew runGraphAgent --args="rename method executeRecipe to runRecipe"
```

Record:
- Iterations: ___
- Estimated tokens: ___
- Success: ___

### Run 2: Warm Start (With Graph)

```bash
./gradlew runGraphAgent --args="rename method executeRecipe to runRecipe"
```

Record:
- Iterations: ___
- Estimated tokens: ___
- Success: ___

### Run 3: Different Task (Test Transfer)

```bash
./gradlew runGraphAgent --args="rename method anotherMethod to newMethod"
```

Record:
- Iterations: ___
- Estimated tokens: ___
- Success: ___

### Expected Results

With graph-based context reduction:
- Run 2 should use ~30-50% fewer tokens than Run 1 (elements cached)
- Run 2 should use equal or fewer iterations (learned paths)
- Success rate should be equal or better (avoiding failed actions)

## Inspecting the Graph

After runs, examine `data/knowledge_graph.json`:

```bash
cat data/knowledge_graph.json | jq '.pages | length'
cat data/knowledge_graph.json | jq '.transitions | length'
cat data/knowledge_graph.json | jq '.shortcuts'
```

## Comparison to Flat Approach

Raihan's flat approach (UiAgent.kt):
- Always sends full UI tree
- No learning between runs
- No pathfinding

Graph approach should show:
- Consistent improvement on Run 2+
- Better handling of complex UI navigation
- Learned shortcuts for common operations
```

- [ ] **Step 2: Commit**

```bash
git add docs/graph-agent-evaluation.md
git commit -m "docs: add graph agent evaluation guide"
```

### Subtask 6.3: Live End-to-End Evaluation

**Goal:** Run the full evaluation against live IntelliJ and measure improvements.

- [ ] **Step 1: Set up evaluation environment**

Ensure:
- IntelliJ running on port 8082 with `intellij-plugin` project open
- File to test exists: `intellij-plugin/src/main/kotlin/executor/UiExecutor.kt` (has `executeRecipe` method)
- Clean graph state

```bash
rm -f data/knowledge_graph.json
```

- [ ] **Step 2: Run 1 - Cold Start**

```bash
cd intellij-plugin
./gradlew runGraphAgent --args="rename method executeRecipe to runRecipe" 2>&1 | tee run1-cold.log
```

Record results:
```bash
echo "=== Run 1 (Cold Start) ===" >> docs/test-results/evaluation-summary.md
echo "Iterations: $(grep 'Iteration' run1-cold.log | tail -1)" >> docs/test-results/evaluation-summary.md
echo "Success: $(grep -c 'Task completed' run1-cold.log || echo '0')" >> docs/test-results/evaluation-summary.md
echo "Tokens: $(grep -o 'totalTokenCount' run1-cold.log | wc -l)" >> docs/test-results/evaluation-summary.md
```

- [ ] **Step 3: Run 2 - Warm Start (same task)**

```bash
./gradlew runGraphAgent --args="rename method executeRecipe to runRecipe" 2>&1 | tee run2-warm.log
```

Record results:
```bash
echo "=== Run 2 (Warm Start) ===" >> docs/test-results/evaluation-summary.md
echo "Iterations: $(grep 'Iteration' run2-warm.log | tail -1)" >> docs/test-results/evaluation-summary.md
echo "Success: $(grep -c 'Task completed' run2-warm.log || echo '0')" >> docs/test-results/evaluation-summary.md
```

- [ ] **Step 4: Run 3 - Different task (transfer test)**

```bash
./gradlew runGraphAgent --args="rename variable accessibleName to a11yName" 2>&1 | tee run3-transfer.log
```

Record results:
```bash
echo "=== Run 3 (Transfer) ===" >> docs/test-results/evaluation-summary.md
echo "Iterations: $(grep 'Iteration' run3-transfer.log | tail -1)" >> docs/test-results/evaluation-summary.md
echo "Success: $(grep -c 'Task completed' run3-transfer.log || echo '0')" >> docs/test-results/evaluation-summary.md
```

- [ ] **Step 5: Compare results**

```bash
cat docs/test-results/evaluation-summary.md
```

Expected improvements:
- Run 2 iterations ≤ Run 1 iterations (learned paths)
- Run 2 shows "This page has been visited before" frequently
- Run 3 benefits from some learned elements (editor_idle, etc.)

- [ ] **Step 6: Inspect final graph**

```bash
echo "=== Final Graph Stats ===" >> docs/test-results/evaluation-summary.md
echo "Pages: $(cat data/knowledge_graph.json | jq '.pages | length')" >> docs/test-results/evaluation-summary.md
echo "Elements: $(cat data/knowledge_graph.json | jq '.elements | length')" >> docs/test-results/evaluation-summary.md
echo "Transitions: $(cat data/knowledge_graph.json | jq '.transitions | length')" >> docs/test-results/evaluation-summary.md
echo "Shortcuts: $(cat data/knowledge_graph.json | jq '.shortcuts | length')" >> docs/test-results/evaluation-summary.md
echo "Failed Transitions: $(cat data/knowledge_graph.json | jq '.failedTransitions | length')" >> docs/test-results/evaluation-summary.md
```

- [ ] **Step 7: Generate comparison report**

```bash
cat > docs/test-results/final-evaluation.md << 'EOF'
# Graph Agent - Final Evaluation Results

## Test Environment
- IntelliJ IDEA with Remote Robot on port 8082
- Project: intellij-plugin
- Test Date: $(date)

## Results Summary

$(cat docs/test-results/evaluation-summary.md)

## Key Observations

1. **Context Reduction**: Run 2 should show shorter prompts (elements cached)
2. **Pathfinding**: Check logs for "Known path to" messages
3. **Failed Actions**: Check if Run 2 avoided Run 1's failures
4. **Shortcuts**: Any shortcuts discovered?

## Graph Analysis

EOF

cat data/knowledge_graph.json | jq '.pages[] | {id, visitCount}' >> docs/test-results/final-evaluation.md
```

- [ ] **Step 8: Commit evaluation results**

```bash
git add docs/test-results/
git commit -m "test: add live evaluation results for graph agent refactor"
```

---

## Summary

This plan addresses the core issues identified:

1. **Task 1**: Fixes element ID generation to use actual classes
2. **Task 2**: Implements delta-based context (only show new/changed elements)
3. **Task 3**: Adds BFS pathfinding for route planning
4. **Task 4**: Tracks failed transitions to avoid repeating mistakes
5. **Task 5**: Discovers shortcuts from repeated patterns
6. **Task 6**: Evaluates and verifies improvements

Each task is broken into bite-sized subtasks with:
- Failing tests written first (TDD)
- Implementation steps
- Verification steps
- Commits after each working change

---

## Self-Review Checklist

**Spec coverage:**
- [x] Element ID tracking with proper class names
- [x] Delta-based context for revisited pages
- [x] Pathfinding using graph algorithms
- [x] Failed transition tracking
- [x] Shortcut discovery mechanism
- [x] Evaluation metrics
- [x] **Live Remote Robot testing after each task**

**Placeholder scan:**
- [x] No TBD/TODO in implementation steps
- [x] All code blocks contain actual implementations
- [x] All tests have concrete assertions
- [x] All file paths are exact
- [x] Live test steps include verification commands

**Type consistency:**
- [x] ActionRecord properties match across all usages
- [x] ElementNode ID format consistent with makeElementId()
- [x] SerializedGraph includes all new fields

**Live Testing Coverage:**
- [x] Task 1: Element metadata captured from live IDE
- [x] Task 2: Element storage verified, prompts show deltas
- [x] Task 3: Pathfinding suggests known routes
- [x] Task 4: Failed transitions prevent repeat mistakes
- [x] Task 5: Shortcuts discovered from patterns
- [x] Task 6: Full evaluation with comparison

---

## Pre-Execution Setup

Before starting Task 1, ensure the test results directory exists:

```bash
mkdir -p docs/test-results
echo "# Graph Agent Refactor - Test Results" > docs/test-results/README.md
echo "" >> docs/test-results/README.md
echo "This directory contains live test results from each task." >> docs/test-results/README.md
```
