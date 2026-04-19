package test

import executor.UiExecutor
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class HarnessSanityTest : BaseTest() {
    @Test
    @DisplayName("Stage 0 - IDE is reachable and canonical fixture can be opened")
    fun canonicalFixtureCanBeOpened() {
        val executor = UiExecutor(robot)
        openFreshCanonicalRenameFixture(executor)

        val document = executor.getDocumentText()
        check(document != null) { "Expected document text after opening canonical fixture" }
        check(document.contains("class GraphAgentRenameFixture")) {
            "Canonical fixture class declaration was not present"
        }
        check(document.contains("val originalName = \"Ada\"")) {
            "Canonical fixture did not open or contents were unexpected"
        }
    }
}
