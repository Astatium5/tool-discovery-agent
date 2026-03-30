package graph

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach
import parser.UiComponent

/**
 * Task 6.1: Measure Context Reduction
 *
 * This test verifies that the delta-based prompting system actually reduces
 * the amount of context sent to the LLM on revisited pages.
 *
 * Key assertions:
 * 1. First visit prompt includes all element labels
 * 2. Revisit prompt contains "Previously seen elements" message
 * 3. Revisit prompt.size < first visit prompt.size (context reduction)
 * 4. New elements are shown on revisit
 * 5. Actual character counts and percentage reduction are printed
 */
class ContextReductionTest {
    private lateinit var graph: KnowledgeGraph

    @BeforeEach
    fun setup() {
        graph = KnowledgeGraph()
    }

    @Test
    @DisplayName("First visit prompt should include all element labels")
    fun testFirstVisitPromptContainsAllElements() {
        val elements = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
            UiComponent(cls = "ActionButton", text = "Find", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
            UiComponent(cls = "ActionButton", text = "Replace", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        val page = PageState(
            pageId = "editor_idle",
            description = "Editor idle state with toolbar buttons",
            elements = elements,
            rawHtml = "",
        )

        val firstVisitPrompt = page.toDeltaPromptString(false, emptyList())

        // All element labels should be present
        assert(firstVisitPrompt.contains("Refactor")) {
            "First visit prompt should contain 'Refactor'"
        }
        assert(firstVisitPrompt.contains("Find")) {
            "First visit prompt should contain 'Find'"
        }
        assert(firstVisitPrompt.contains("Replace")) {
            "First visit prompt should contain 'Replace'"
        }

        // Should indicate first visit
        assert(firstVisitPrompt.contains("First visit to this page")) {
            "First visit prompt should contain 'First visit to this page'"
        }

        // Should show "Available actions:" header
        assert(firstVisitPrompt.contains("Available actions:")) {
            "First visit prompt should contain 'Available actions:' header"
        }
    }

    @Test
    @DisplayName("Revisit prompt should be smaller than first visit")
    fun testRevisitPromptIsSmaller() {
        val elements = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
            UiComponent(cls = "ActionButton", text = "Find", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
            UiComponent(cls = "ActionButton", text = "Replace", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        val page = PageState(
            pageId = "editor_idle",
            description = "Editor idle state",
            elements = elements,
            rawHtml = "",
        )

        // First visit prompt
        val firstVisitPrompt = page.toDeltaPromptString(false, emptyList())
        val firstVisitSize = firstVisitPrompt.length

        // Store elements to simulate having visited before
        graph.storeElements("editor_idle", elements)
        val knownElements = graph.getElementsForPage("editor_idle")

        // Revisit prompt
        val revisitPrompt = page.toDeltaPromptString(true, knownElements)
        val revisitSize = revisitPrompt.length

        // Print actual sizes and reduction
        println("=== Context Reduction Test Results ===")
        println("First visit prompt size: $firstVisitSize characters")
        println("Revisit prompt size: $revisitSize characters")
        println("Absolute reduction: ${firstVisitSize - revisitSize} characters")
        val reductionPercent = 100 * (1 - revisitSize.toDouble() / firstVisitSize)
        println("Percentage reduction: ${"%.2f".format(reductionPercent)}%")
        println("=====================================")

        // Revisit should be smaller (hides unchanged elements)
        assert(revisitSize < firstVisitSize) {
            "Revisit prompt ($revisitSize chars) should be smaller than first visit ($firstVisitSize chars)"
        }

        // Should indicate this page has been visited before
        assert(revisitPrompt.contains("This page has been visited before")) {
            "Revisit prompt should contain 'This page has been visited before'"
        }

        // Should contain "Previously seen elements" message
        assert(revisitPrompt.contains("Previously seen elements")) {
            "Revisit prompt should contain 'Previously seen elements' message"
        }

        // Should indicate elements are not shown
        assert(revisitPrompt.contains("Not shown") || revisitPrompt.contains("stored in knowledge graph")) {
            "Revisit prompt should indicate unchanged elements are not shown"
        }

        // Element labels should NOT appear in revisit prompt (they're hidden)
        // This is the key optimization - we're not sending the same elements again
        assert(!revisitPrompt.contains("Refactor") || revisitPrompt.contains("New elements")) {
            "Revisit prompt should NOT list unchanged element 'Refactor' unless there are new elements"
        }
    }

    @Test
    @DisplayName("Revisit with new elements should only show new ones")
    fun testRevisitShowsOnlyNewElements() {
        // First visit with 2 elements
        val initialElements = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
            UiComponent(cls = "ActionButton", text = "Find", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        graph.storeElements("editor_idle", initialElements)

        // Revisit with 3 elements (1 new)
        val withNewElements = initialElements + listOf(
            UiComponent(cls = "ActionButton", text = "Replace", accessibleName = "", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        val page = PageState(
            pageId = "editor_idle",
            description = "Editor idle state",
            elements = withNewElements,
            rawHtml = "",
        )
        val knownElements = graph.getElementsForPage("editor_idle")

        val revisitPrompt = page.toDeltaPromptString(true, knownElements)

        // Should show only the new element
        assert(revisitPrompt.contains("New elements since last visit")) {
            "Revisit prompt should contain 'New elements since last visit'"
        }
        assert(revisitPrompt.contains("Replace")) {
            "Revisit prompt should show new element 'Replace'"
        }

        // Should count unchanged elements
        assert(revisitPrompt.contains("Previously seen elements (2)")) {
            "Revisit prompt should indicate 2 previously seen elements"
        }

        // Unchanged elements should not be listed
        // (We verify Replace is shown because it's new, but Refactor/Find should not appear in the listing)
        val lines = revisitPrompt.lines()
        val newElementsSection = lines.dropWhile { !it.contains("New elements") }
            .takeWhile { !it.contains("Previously seen") }

        // Only "Replace" should appear in the new elements section
        val hasRefactorInListing = newElementsSection.any { it.contains("\"Refactor\"") && it.trim().startsWith("1.") || it.trim().startsWith("2.") || it.trim().startsWith("3.") }
        assert(!hasRefactorInListing) {
            "Unchanged element 'Refactor' should not appear in listing"
        }
    }

    @Test
    @DisplayName("Revisit with changed elements should show changes")
    fun testRevisitShowsChangedElements() {
        // First visit
        val initialElements = listOf(
            UiComponent(cls = "ActionButton", text = "Refactor", accessibleName = "Refactor", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        graph.storeElements("editor_idle", initialElements)

        // Revisit with changed element (same label/text, different class)
        val changedElements = listOf(
            UiComponent(cls = "MenuButton", text = "Refactor", accessibleName = "Refactor", tooltip = "", enabled = true, hasSubmenu = false, children = emptyList()),
        )
        val page = PageState(
            pageId = "editor_idle",
            description = "Editor idle state",
            elements = changedElements,
            rawHtml = "",
        )
        val knownElements = graph.getElementsForPage("editor_idle")

        val revisitPrompt = page.toDeltaPromptString(true, knownElements)

        // Debug: print the prompt to see actual format
        println("=== Changed Elements Prompt ===")
        println(revisitPrompt)
        println("================================")

        // Should show changed elements section
        assert(revisitPrompt.contains("Changed elements since last visit")) {
            "Revisit prompt should contain 'Changed elements since last visit'"
        }

        // Should mention the class change - the format is "class: ActionButton -> MenuButton"
        assert(revisitPrompt.contains("Refactor")) {
            "Revisit prompt should mention 'Refactor' element"
        }
        assert(revisitPrompt.contains("ActionButton") && revisitPrompt.contains("MenuButton")) {
            "Revisit prompt should show both old (ActionButton) and new (MenuButton) classes"
        }

        // The arrow format used is "→" (Unicode RIGHT ARROW)
        assert(revisitPrompt.contains("→") || revisitPrompt.contains("->")) {
            "Revisit prompt should use arrow notation for change"
        }
    }

    @Test
    @DisplayName("Large page should show significant context reduction")
    fun testLargePageReduction() {
        // Simulate a page with many elements (e.g., a toolbar with 20 buttons)
        val largeElementList = listOf(
            UiComponent("ActionButton", "New", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Open", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Save", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Save All", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Close", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Undo", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Redo", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Cut", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Copy", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Paste", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Find", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Replace", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Find in Files", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Refactor", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Build", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Debug", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Run", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Stop", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Settings", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Plugins", "", "", true, false, emptyList()),
        )
        val page = PageState(
            pageId = "main_toolbar",
            description = "Main toolbar with all actions",
            elements = largeElementList,
            rawHtml = "",
        )

        val firstVisitPrompt = page.toDeltaPromptString(false, emptyList())
        val firstVisitSize = firstVisitPrompt.length

        graph.storeElements("main_toolbar", largeElementList)
        val knownElements = graph.getElementsForPage("main_toolbar")

        val revisitPrompt = page.toDeltaPromptString(true, knownElements)
        val revisitSize = revisitPrompt.length

        val reductionPercent = 100 * (1 - revisitSize.toDouble() / firstVisitSize)

        println("=== Large Page Context Reduction ===")
        println("Element count: ${largeElementList.size}")
        println("First visit prompt size: $firstVisitSize characters")
        println("Revisit prompt size: $revisitSize characters")
        println("Reduction: ${firstVisitSize - revisitSize} characters (${"%.2f".format(reductionPercent)}%)")
        println("===================================")

        // For a large page with 20 elements, we expect significant reduction
        // (each element is roughly 2-3 lines in the prompt)
        assert(reductionPercent > 50) {
            "Expected >50% reduction for large page, got ${"%.2f".format(reductionPercent)}%"
        }
    }

    @Test
    @DisplayName("Empty page should handle gracefully")
    fun testEmptyPage() {
        val page = PageState(
            pageId = "empty_page",
            description = "A page with no interactive elements",
            elements = emptyList(),
            rawHtml = "",
        )

        // First visit with no elements
        val firstVisitPrompt = page.toDeltaPromptString(false, emptyList())
        assert(firstVisitPrompt.contains("No interactive elements detected")) {
            "Empty page should show 'No interactive elements detected'"
        }

        // Revisit with no elements
        val revisitPrompt = page.toDeltaPromptString(true, emptyList())
        assert(revisitPrompt.contains("No interactive elements detected")) {
            "Empty page revisit should show 'No interactive elements detected'"
        }
    }

    @Test
    @DisplayName("Percentage reduction is printed and verifiable")
    fun testPercentageReductionCalculation() {
        val elements = listOf(
            UiComponent("ActionButton", "Button1", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Button2", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Button3", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Button4", "", "", true, false, emptyList()),
            UiComponent("ActionButton", "Button5", "", "", true, false, emptyList()),
        )
        val page = PageState(
            pageId = "test_page",
            description = "Test page",
            elements = elements,
            rawHtml = "",
        )

        val firstVisitPrompt = page.toDeltaPromptString(false, emptyList())
        val firstVisitSize = firstVisitPrompt.length

        graph.storeElements("test_page", elements)
        val knownElements = graph.getElementsForPage("test_page")

        val revisitPrompt = page.toDeltaPromptString(true, knownElements)
        val revisitSize = revisitPrompt.length

        val reductionPercent = 100 * (1 - revisitSize.toDouble() / firstVisitSize)

        // This test prints the actual values for manual verification
        println("\n=========================================")
        println("PERCENTAGE REDUCTION VERIFICATION")
        println("=========================================")
        println("First visit:  $firstVisitSize characters")
        println("Revisit:       $revisitSize characters")
        println("Reduction:     ${firstVisitSize - revisitSize} characters")
        println("Percentage:    ${"%.2f".format(reductionPercent)}%")
        println("=========================================\n")

        // Verify the math is correct
        val expectedReduction = firstVisitSize - revisitSize
        val calculatedSize = (firstVisitSize * (1 - reductionPercent / 100)).toInt()
        assert(calculatedSize == revisitSize || calculatedSize == revisitSize - 1 || calculatedSize == revisitSize + 1) {
            "Percentage calculation should be accurate (expected ~$revisitSize, got $calculatedSize)"
        }
    }
}
