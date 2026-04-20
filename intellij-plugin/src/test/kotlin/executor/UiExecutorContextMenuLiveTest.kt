package executor

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import parser.HtmlUiTreeProvider
import parser.UiTreeParser
import test.BaseTest
import java.nio.file.Files
import java.nio.file.Path

class UiExecutorContextMenuLiveTest : BaseTest() {
    @Test
    fun `openContextMenu exposes a live popup over the editor selection`() {
        val executor = UiExecutor(robot)
        openFreshCanonicalRenameFixture(executor)

        executor.focusEditor()
        executor.moveCaret("originalName")
        executor.openContextMenu()

        val html = HtmlUiTreeProvider(robotUrl).fetchRawHtml()
        val artifactDir = Path.of("build", "reports", "ui-executor-tests")
        Files.createDirectories(artifactDir)
        Files.writeString(artifactDir.resolve("context-menu-live.html"), html)
        val pageState = UiTreeParser.inferPageState(UiTreeParser.parse(html), html)

        assertTrue(
            pageState.pageId in setOf("context_menu", "refactor_submenu"),
            "Expected a live context menu popup after openContextMenu(), got ${pageState.pageId}",
        )
    }

    @Test
    fun `live context menu exposes rename or refactor labels`() {
        val executor = UiExecutor(robot)
        openFreshCanonicalRenameFixture(executor)

        executor.focusEditor()
        executor.moveCaret("originalName")
        executor.openContextMenu()

        val html = HtmlUiTreeProvider(robotUrl).fetchRawHtml()
        val pageState = UiTreeParser.inferPageState(UiTreeParser.parse(html), html)
        val labels = pageState.elements.map { it.label }

        assertTrue(
            labels.any { it.contains("Rename", ignoreCase = true) || it.contains("Refactor", ignoreCase = true) },
            "Expected live popup labels to contain Rename or Refactor, got: $labels",
        )
    }

    @Test
    fun `clicking Refactor opens a submenu that exposes Rename`() {
        val executor = UiExecutor(robot)
        openFreshCanonicalRenameFixture(executor)

        executor.focusEditor()
        executor.moveCaret("originalName")
        executor.openContextMenu()
        executor.clickMenuItem("Refactor")

        val html = HtmlUiTreeProvider(robotUrl).fetchRawHtml()
        val pageState = UiTreeParser.inferPageState(UiTreeParser.parse(html), html)
        val labels = pageState.elements.map { it.label }

        assertTrue(
            pageState.pageId == "refactor_submenu",
            "Expected refactor submenu after clicking Refactor, got ${pageState.pageId} with labels $labels",
        )
        assertTrue(
            labels.any { it.contains("Rename", ignoreCase = true) },
            "Expected live refactor submenu to contain Rename, got: $labels",
        )
    }

    @Test
    fun `clicking Rename through the popup flow opens the inline rename widget`() {
        val executor = UiExecutor(robot)
        openFreshCanonicalRenameFixture(executor)

        executor.focusEditor()
        executor.moveCaret("originalName")
        executor.openContextMenu()
        executor.clickMenuItem("Rename")

        val html = HtmlUiTreeProvider(robotUrl).fetchRawHtml()
        val pageState = UiTreeParser.inferPageState(UiTreeParser.parse(html), html)
        val labels = pageState.elements.map { it.label }

        assertTrue(
            pageState.pageId == "inline_widget",
            "Expected inline rename widget after clicking Rename, got ${pageState.pageId} with labels $labels",
        )
    }
}
