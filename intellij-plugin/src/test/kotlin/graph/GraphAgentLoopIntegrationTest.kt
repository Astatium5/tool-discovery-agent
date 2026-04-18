package graph

import graph.telemetry.GraphTelemetryFactory
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import parser.UiComponent
import parser.UiTreeParser
import parser.UiTreeProvider
import profile.UIProfiler
import java.nio.file.Files

class GraphAgentLoopIntegrationTest {
    @Test
    fun `graph agent orchestrates provider decision action seams and emits iteration spans`() {
        val provider = FakeUiTreeProvider(
            rawHtmlSequence = listOf(
                editorHtml(),
                contextMenuHtml(),
                contextMenuHtml(),
            ),
        )
        val decisionEngine = FakeDecisionEngine()
        val actionExecutor = FakeActionExecutor(provider)
        val exporter = InMemorySpanExporter.create()
        val telemetry = GraphTelemetryFactory.createForTests(exporter)
        val graphDir = Files.createTempDirectory("graph-agent-loop")
        val graphPath = graphDir.resolve("graph.json")

        try {
            val agent = GraphAgent(
                provider = provider,
                parser = UiTreeParser,
                decisionEngine = decisionEngine,
                actionHandler = actionExecutor,
                graphPath = graphPath.toString(),
                maxIterations = 4,
                telemetry = telemetry,
            )

            val result = agent.execute("Open the Refactor menu")

            assertTrue(result.success)
            assertEquals(2, result.iterations)
            assertEquals(listOf("editor_idle", "context_menu"), decisionEngine.pageIds)
            assertEquals(2, decisionEngine.calls)
            assertEquals(1, actionExecutor.calls)
            assertEquals(3, provider.rawHtmlCalls)
            assertEquals("click", result.actionHistory.first().actionType)
            assertEquals("ActionButton", result.actionHistory.first().elementClass)
            assertEquals("Refactor", result.actionHistory.first().elementLabel)

            val spans = exporter.finishedSpanItems
            assertTrue(spans.any { it.name == "graph_agent.iteration" })
            assertEquals(2, spans.count { it.name == "graph_agent.iteration" })
        } finally {
            telemetry.close()
            Files.deleteIfExists(graphPath)
            Files.deleteIfExists(graphDir)
        }
    }

    private class FakeUiTreeProvider(
        private val rawHtmlSequence: List<String>,
    ) : UiTreeProvider {
        var rawHtmlCalls: Int = 0
            private set

        override fun fetchRawHtml(): String {
            val index = rawHtmlCalls
            rawHtmlCalls++
            return rawHtmlSequence.getOrElse(index) {
                error("Unexpected fetchRawHtml call #${index + 1}")
            }
        }

        override fun fetchTree(): List<UiComponent> = error("GraphAgent should drive parsing from fetchRawHtml()")

        override fun fetchClassContexts(): Map<String, UIProfiler.ClassContext> = emptyMap()
    }

    private class FakeDecisionEngine : GraphDecisionEngine {
        val pageIds = mutableListOf<String>()
        var calls: Int = 0
            private set

        override fun decide(
            task: String,
            page: PageState,
            history: List<GraphAgent.ActionRecord>,
            graph: KnowledgeGraph,
            currentPageId: String?,
        ): GraphDecisionResult {
            calls++
            pageIds += page.pageId

            return when (calls) {
                1 -> {
                    assertEquals("editor_idle", page.pageId)
                    assertTrue(page.rawHtml.contains("ActionButton"))
                    GraphDecisionResult(
                        reasoning = "Use the toolbar button",
                        decision = GraphDecision(action = "click", params = mapOf("target" to "Refactor")),
                    )
                }
                2 -> {
                    assertEquals("context_menu", page.pageId)
                    assertTrue(history.any { it.actionType == "click" })
                    GraphDecisionResult(
                        reasoning = "Menu is open",
                        decision = GraphDecision(action = "complete"),
                    )
                }
                else -> error("Unexpected decision request #$calls")
            }
        }
    }

    private class FakeActionExecutor(
        private val provider: FakeUiTreeProvider,
    ) : GraphActionHandler {
        var calls: Int = 0
            private set

        override fun execute(request: GraphActionRequest): GraphActionResult {
            calls++
            assertEquals(1, provider.rawHtmlCalls, "act() should not re-observe before executing the action")
            assertEquals("editor_idle", request.page.pageId)
            assertEquals("click", request.decision.action)
            assertEquals("Refactor", request.targetElement?.label)
            assertEquals("ActionButton", request.targetElement?.cls)
            return GraphActionResult(success = true)
        }
    }

    private companion object {
        fun editorHtml(): String =
            """
            <html>
              <body>
                <div class="IdeFrameImpl">
                  <div class="ActionButton" accessiblename="Refactor" visible="true" enabled="true"></div>
                  <div class="EditorComponentImpl" accessiblename="Editor for Main.kt" visible="true" enabled="true"></div>
                </div>
              </body>
            </html>
            """.trimIndent()

        fun contextMenuHtml(): String =
            """
            <html>
              <body>
                <div class="HeavyWeightWindow" visible="true" enabled="true">
                  <div class="ActionMenuItem" accessiblename="Rename" visible="true" enabled="true"></div>
                  <div class="ActionMenuItem" accessiblename="Extract Method" visible="true" enabled="true"></div>
                </div>
              </body>
            </html>
            """.trimIndent()
    }
}
