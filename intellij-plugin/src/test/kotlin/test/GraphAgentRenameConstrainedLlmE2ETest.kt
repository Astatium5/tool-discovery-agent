package test

import executor.UiExecutor
import org.junit.jupiter.api.Test

class GraphAgentRenameConstrainedLlmE2ETest : BaseTest() {
    @Test
    fun `constrained LLM GraphAgent renames the local variable through Rename menu`() {
        val run =
            GraphAgentRenameConstrainedLlmHarness.executeOnce(robot, robotUrl) { executor: UiExecutor ->
                openFreshCanonicalRenameFixture(executor)
            }
        GraphAgentRenameConstrainedLlmHarness.assertSuccessfulRename(run)
    }
}
