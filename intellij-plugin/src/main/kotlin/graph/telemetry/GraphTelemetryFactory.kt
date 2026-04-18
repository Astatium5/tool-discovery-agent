package graph.telemetry

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter

object GraphTelemetryFactory {
    private const val DEFAULT_SERVICE_NAME = "graph-agent"

    fun create(
        serviceName: String = DEFAULT_SERVICE_NAME,
    ): GraphTelemetry = create(serviceName, LoggingSpanExporter.create())

    fun createForTests(
        spanExporter: SpanExporter,
        serviceName: String = DEFAULT_SERVICE_NAME,
    ): GraphTelemetry = create(serviceName, spanExporter)

    fun createOtlp(
        serviceName: String = DEFAULT_SERVICE_NAME,
        endpoint: String? = null,
    ): GraphTelemetry {
        val exporterBuilder = OtlpGrpcSpanExporter.builder()
        if (!endpoint.isNullOrBlank()) {
            exporterBuilder.setEndpoint(endpoint)
        }
        return create(serviceName, exporterBuilder.build())
    }

    private fun resource(serviceName: String): Resource =
        Resource.getDefault().merge(
            Resource.create(
                Attributes.builder()
                    .put("service.name", serviceName)
                    .build(),
            ),
        )

    private fun create(
        serviceName: String,
        spanExporter: SpanExporter,
    ): GraphTelemetry {
        val tracerProvider =
            SdkTracerProvider.builder()
                .setResource(resource(serviceName))
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build()

        val openTelemetry =
            OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build()

        return GraphTelemetry(
            tracerProvider = tracerProvider,
            tracer = openTelemetry.getTracer(serviceName),
        )
    }
}
