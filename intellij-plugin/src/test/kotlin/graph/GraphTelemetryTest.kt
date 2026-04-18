package graph

import graph.telemetry.GraphTelemetryFactory
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GraphTelemetryTest {
    @Test
    fun `root span and child span are exported`() {
        val exporter = InMemorySpanExporter.create()
        val telemetry = GraphTelemetryFactory.createForTests(exporter)

        telemetry.rootSpan("graph_agent.rename_local_variable") {
            telemetry.childSpan("observe.fetch_html") {}
        }
        try {
            val spans = exporter.finishedSpanItems
            assertEquals(2, spans.size)
            assertTrue(spans.any { it.name == "graph_agent.rename_local_variable" })
            assertTrue(spans.any { it.name == "observe.fetch_html" })
        } finally {
            telemetry.close()
        }
    }
}
