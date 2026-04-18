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
            val root = spans.single { it.name == "graph_agent.rename_local_variable" }
            val child = spans.single { it.name == "observe.fetch_html" }

            assertTrue(root.parentSpanContext.isValid.not())
            assertEquals(root.spanId, child.parentSpanContext.spanId)
            assertEquals(root.traceId, child.traceId)
            assertEquals("graph-agent", root.resource.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("service.name")))
        } finally {
            telemetry.close()
        }
    }
}
