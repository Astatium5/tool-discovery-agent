package graph

import kotlinx.serialization.Serializable

/**
 * Core data types for the AppAgentX knowledge graph.
 *
 * These types represent:
 * - PageNode: a distinct UI state (editor_idle, context_menu, rename_dialog)
 * - ElementNode: an interactive component within a page
 * - Transition: a LEADS_TO edge between pages via an element action
 * - Shortcut: a learned multi-step sequence promoted to a single graph hop
 */

@Serializable
data class PageNode(
    val id: String,          // e.g. "editor_idle", "context_menu"
    val description: String,
    var visitCount: Int = 0
)

@Serializable
data class ElementNode(
    val id: String,          // f"{page_id}::{cls}::{label}"
    val pageId: String,
    val cls: String,
    val label: String,
    val xpath: String,
    val role: String
)

@Serializable
data class Transition(
    val fromPage: String,
    val elementId: String,
    val action: String,      // "click", "type", "press_key"
    val toPage: String,
    val params: Map<String, String> = emptyMap()
)

@Serializable
data class Shortcut(
    val name: String,                      // e.g. "rename_symbol"
    val steps: List<Map<String, String>>,  // ordered list of {action, element_id/xpath, params}
    var usageCount: Int = 0,
    var successCount: Int = 0
)
