package graph

import executor.UiExecutor
import org.junit.jupiter.api.Test
import test.BaseTest

class GraphActionExecutorRenameScriptTest : BaseTest() {
    @Test
    fun `rename script renames the originalName binding`() {
        val uiExecutor = UiExecutor(robot)
        uiExecutor.openFile("src/test/kotlin/fixtures/GraphAgentRenameFixture.kt")

        val graphActionExecutor = GraphActionExecutor(uiExecutor)
        val result = graphActionExecutor.renameSymbol("originalName", "renamedName")

        check(result.success) { result.message }

        val document = uiExecutor.getDocumentText()
            ?: error("Expected document text after rename flow")

        check(document.contains("val renamedName = \"Ada\"")) {
            "Expected renamed declaration in document, but found:\n$document"
        }
    }
}
