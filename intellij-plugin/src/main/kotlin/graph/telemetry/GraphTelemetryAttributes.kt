package graph.telemetry

import io.opentelemetry.api.common.AttributeKey

object GraphTelemetryAttributes {
    val TASK_ID: AttributeKey<String> = AttributeKey.stringKey("task_id")
    val ITERATION: AttributeKey<Long> = AttributeKey.longKey("iteration")
    val PAGE_BEFORE: AttributeKey<String> = AttributeKey.stringKey("page_before")
    val PAGE_AFTER: AttributeKey<String> = AttributeKey.stringKey("page_after")
    val ACTION_TYPE: AttributeKey<String> = AttributeKey.stringKey("action_type")
    val TARGET_LABEL: AttributeKey<String> = AttributeKey.stringKey("target_label")
    val ELEMENT_CLASS: AttributeKey<String> = AttributeKey.stringKey("element_class")
    val SUCCESS: AttributeKey<Boolean> = AttributeKey.booleanKey("success")
    val EXCEPTION_TYPE: AttributeKey<String> = AttributeKey.stringKey("exception.type")
    val EXCEPTION_MESSAGE: AttributeKey<String> = AttributeKey.stringKey("exception.message")
    val GRAPH_PAGES: AttributeKey<Long> = AttributeKey.longKey("graph.pages")
    val GRAPH_TRANSITIONS: AttributeKey<Long> = AttributeKey.longKey("graph.transitions")
    val GRAPH_FAILED_TRANSITIONS: AttributeKey<Long> = AttributeKey.longKey("graph.failed_transitions")
}
