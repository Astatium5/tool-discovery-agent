package graph

import kotlinx.serialization.Serializable

/**
 * Core data types for the knowledge graph.
 */

/**
 * Represents a distinct UI state (page) in the application.
 *
 * @param id Unique identifier for this page state (e.g., "editor_idle", "rename_dialog")
 * @param description Human-readable description of what this page represents
 * @param visitCount Number of times this page has been visited during execution
 */
@Serializable
data class PageNode(
    val id: String,
    val description: String,
    var visitCount: Int = 0,
)

/**
 * Represents an interactive UI component within a page.
 *
 * @param id Unique element ID (format: "{pageId}::{cls}::{label}")
 * @param pageId Which page this element belongs to
 * @param cls Component class name
 * @param label Visible text label
 * @param xpath XPath query to find this component
 * @param role Component role (button, menu, textfield, etc.)
 */
@Serializable
data class ElementNode(
    val id: String,
    val pageId: String,
    val cls: String,
    val label: String,
    val xpath: String,
    val role: String,
)

/**
 * Represents a transition from one page to another via an action.
 *
 * @param fromPage Starting page ID
 * @param elementId ID of the element that was acted upon
 * @param action Action type (click, type, press_key, etc.)
 * @param toPage Destination page ID after the action
 * @param params Additional parameters passed with the action
 */
@Serializable
data class Transition(
    val fromPage: String,
    val elementId: String,
    val action: String,
    val toPage: String,
    val params: Map<String, String> = emptyMap(),
)

/**
 * A learned multi-step sequence promoted to a single graph hop.
 *
 * Shortcuts are discovered patterns that can be executed in one step
 * instead of repeating the individual actions each time.
 *
 * @param name Human-readable name for this shortcut (e.g., "rename_symbol")
 * @param steps Ordered list of actions that make up this shortcut
 * @param usageCount Total times this shortcut has been used
 * @param successCount Number of times this shortcut succeeded
 */
@Serializable
data class Shortcut(
    val name: String,
    val steps: List<Map<String, String>>,
    var usageCount: Int = 0,
    var successCount: Int = 0,
)

/**
 * Serialized format for persisting the knowledge graph to JSON.
 *
 * All collections use concrete types for kotlinx-serialization compatibility.
 */
@Serializable
data class SerializedGraph(
    val pages: List<PageNode>,
    val elements: List<ElementNode>,
    val transitions: List<Transition>,
    val shortcuts: List<Shortcut>,
)
