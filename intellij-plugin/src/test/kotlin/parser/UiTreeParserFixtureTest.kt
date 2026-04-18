package parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiTreeParserFixtureTest {
    @Test
    fun `editor idle fixture infers editor idle and includes editor component`() {
        val html = loadFixture("editor_idle.html")
        val components = UiTreeParser.parse(html)
        val pageState = UiTreeParser.inferPageState(components, html)

        assertEquals("editor_idle", pageState.pageId)
        assertTrue(pageState.elements.any { it.cls == "EditorComponentImpl" }, "Expected editor_idle fixture to include an EditorComponentImpl element")
    }

    @Test
    fun `context menu fixture infers context menu and includes rename action`() {
        val html = loadFixture("context_menu.html")
        val components = UiTreeParser.parse(html)
        val pageState = UiTreeParser.inferPageState(components, html)

        assertEquals("context_menu", pageState.pageId)
        assertTrue(pageState.elements.any { it.label.contains("Rename", ignoreCase = true) }, "Expected context_menu fixture to include a label containing Rename")
    }

    @Test
    fun `rename inline widget fixture infers inline widget and includes inline editor element`() {
        val html = loadFixture("rename_inline_widget.html")
        val components = UiTreeParser.parse(html)
        val pageState = UiTreeParser.inferPageState(components, html)

        assertEquals("inline_widget", pageState.pageId)
        assertTrue(pageState.elements.any {
            it.cls.contains("TextField", ignoreCase = true) || it.cls == "EditorComponentImpl"
        }, "Expected inline_widget fixture to include a TextField or EditorComponentImpl element")
    }

    private fun loadFixture(name: String): String {
        val resource = requireNotNull(javaClass.getResource("/ui-fixtures/$name")) {
            "Missing fixture: $name"
        }
        return resource.readText()
    }
}
