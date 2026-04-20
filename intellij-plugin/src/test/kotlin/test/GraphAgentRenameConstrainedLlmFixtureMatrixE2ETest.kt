package test

import executor.UiExecutor
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class GraphAgentRenameConstrainedLlmFixtureMatrixE2ETest : BaseTest() {
    @TestFactory
    fun `constrained LLM rename stays within policy across Phase B fixtures`(): List<DynamicTest> =
        GraphAgentRenameFixtureScenario.phaseB.map { fixture ->
            DynamicTest.dynamicTest(fixture.id) {
                val run =
                    GraphAgentRenameConstrainedLlmHarness.executeOnce(robot, robotUrl, fixture) { executor: UiExecutor ->
                        openFreshRenameFixture(executor, fixture)
                    }
                GraphAgentRenameConstrainedLlmHarness.assertSuccessfulRename(run)
            }
        }
}
