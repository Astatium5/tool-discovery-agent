package graph.telemetry

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.trace.SdkTracerProvider
import java.util.concurrent.TimeUnit

class GraphTelemetry internal constructor(
    private val tracerProvider: SdkTracerProvider,
    private val tracer: Tracer,
) : AutoCloseable {
    fun <T> rootSpan(
        name: String,
        attributes: Attributes = Attributes.empty(),
        block: () -> T,
    ): T = span(tracer.spanBuilder(name).setNoParent(), attributes, block)

    fun <T> childSpan(
        name: String,
        attributes: Attributes = Attributes.empty(),
        block: () -> T,
    ): T = span(tracer.spanBuilder(name), attributes, block)

    fun <T> span(
        name: String,
        attributes: Attributes = Attributes.empty(),
        block: () -> T,
    ): T = span(tracer.spanBuilder(name), attributes, block)

    override fun close() {
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS)
        tracerProvider.shutdown().join(5, TimeUnit.SECONDS)
    }

    private fun <T> span(
        builder: SpanBuilder,
        attributes: Attributes,
        block: () -> T,
    ): T {
        val span = builder.setAllAttributes(attributes).startSpan()

        return try {
            span.makeCurrent().use {
                block()
            }
        } catch (t: Throwable) {
            span.recordException(t)
            span.setStatus(StatusCode.ERROR)
            throw t
        } finally {
            span.end()
        }
    }
}
