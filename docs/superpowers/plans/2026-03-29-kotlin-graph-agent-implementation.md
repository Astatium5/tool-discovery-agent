# Kotlin-Only Graph-Based Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-step. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a minimal Kotlin-only AppAgentX graph-based agent to test whether maintaining a knowledge graph of UI states improves automation performance compared to the flat UI tree approach.

**Architecture:** Single Kotlin codebase. Agent uses NetworkX-like graph structure (Kotlin implementation) to track UI states (pages, elements, transitions). On first run: cold start (flat approach). On subsequent runs: uses learned graph context.

**Tech Stack:** Kotlin, IntelliJ Remote Robot, existing UI infrastructure (UiTreeParser, UiExecutor, etc.)

**Key Simplification:** No Python, no bridge server, no HTTP overhead. Direct Kotlin → IntelliJ Remote Robot.

---

## Phase 1: Cleanup & Setup

### Task 1: Remove Python/Infrastructure Code

**Files:**
- Delete: `src/` directory (entire Python codebase)
- Delete: `intellij-plugin/src/main/kotlin/server/AgentBridgeServer.kt` (no longer needed)
- Delete: `intellij-plugin/src/main/kotlin/main/RunGraphAgent.kt` (will rewrite)

- [ ] **Step 1: Remove Python codebase**

```bash
cd /Users/sudhanva/Documents/grad-school/csci-7000-005/tool-discovery-agent
rm -rf src/
rm -f requirements.txt .env.example
rm -f intellij-plugin/src/main/kotlin/server/AgentBridgeServer.kt
```

- [ ] **Step 2: Verify clean Kotlin structure**

```bash
ls -la intellij-plugin/src/main/kotlin/
```

Expected: Should show agent/, executor/, parser/, reasoner/, graph/ (we'll create this)

- [ ] **Step 3: Commit cleanup**

```bash
git add .
git commit -m "chore: remove Python codebase, focus on pure Kotlin implementation"
```

---

### Task 2: Create Graph Data Structures

**Files:**
- Create: `intellij-plugin/src/main/kotlin/graph/GraphTypes.kt`
- Create: `intellij-plugin/src/main/kotlin/graph/KnowledgeGraph.kt`

- [ ] **Step 1: Create graph data types**

```kotlin
// intellij-plugin/src/main/kotlin/graph/GraphTypes.kt
package graph

import kotlinx.serialization.Serializable

@Serializable
data class PageNode(
    val id: String,          // e.g. "editor_idle", "context_menu"
    val description: String,
    var visitCount: Int = 0
)

@Serializable
data class ElementNode(
    val id: String,          // f"{pageId}::{cls}::{label}"
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
    val steps: List<Map<String, String>>,  // ordered list of actions
    var usageCount: Int = 0,
    var successCount: Int = 0
)
```

- [ ] **Step 2: Create knowledge graph class**

```kotlin
// intellij-plugin/src/main/kotlin/graph/KnowledgeGraph.kt
package graph
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.math.max

class KnowledgeGraph(private val filePath: String = "data/knowledge_graph.json") {
    private val pages = mutableMapOf<String, PageNode>()
    private val elements = mutableMapOf<String, ElementNode>()
    private val transitions = mutableListOf<Transition>()
    private val shortcuts = mutableMapOf<String, Shortcut>()

    // Pages
    fun getPage(pageId: String): PageNode? = pages[pageId]

    fun addPage(page: PageNode) {
        if (page.id !in pages) {
            pages[page.id] = page
        } else {
            pages[page.id]!!.visitCount++
        }
    }

    fun recordVisit(pageId: String) {
        pages[pageId]?.visitCount = (pages[pageId]?.visitCount ?: 0) + 1
    }

    // Elements
    fun getElement(elementId: String): ElementNode? = elements[elementId]

    fun addElement(element: ElementNode) {
        elements[element.id] = element
    }

    fun makeElementId(pageId: String, cls: String, label: String): String {
        val truncatedLabel = label.take(40)
        return "${pageId}::${cls}::${truncatedLabel}"
    }

    // Transitions
    fun addTransition(fromPage: String, elementId: String, action: String, toPage: String, params: Map<String, String> = emptyMap()) {
        transitions.add(Transition(fromPage, elementId, action, toPage, params))
    }

    fun getTransitionsFrom(pageId: String): List<Transition> {
        return transitions.filter { it.fromPage == pageId }
    }

    // Shortcuts
    fun getShortcut(name: String): Shortcut? = shortcuts[name]

    fun addShortcut(shortcut: Shortcut) {
        shortcuts[shortcut.name] = shortcut
    }

    fun recordShortcutUsed(name: String, success: Boolean) {
        shortcuts[name]?.let {
            it.usageCount++
            if (success) it.successCount++
        }
    }

    // Context for LLM
    fun toPromptContext(currentPageId: String): String {
        val lines = mutableListOf<String>()

        val transitionsFrom = getTransitionsFrom(currentPageId)
        if (transitionsFrom.isNotEmpty()) {
            lines.add("### Known transitions from this page (learned):")
            transitionsFrom.forEach { t ->
                val el = elements[t.elementId]
                val elLabel = el?.label ?: t.elementId
                lines.add("  - ${t.action} \"$elLabel\" → page: ${t.toPage}")
            }
        } else {
            lines.add("### No known transitions from this page yet (first visit).")
        }

        if (shortcuts.isNotEmpty()) {
            lines.add("\n### Available shortcuts (learned sequences):")
            shortcuts.values.forEach { s ->
                val rate = if (s.usageCount > 0) "${s.successCount}/${s.usageCount}" else "untested"
                lines.add("  - \"${s.name}\" (${s.steps.size} steps, success: $rate)")
            }
        }

        return if (lines.isNotEmpty()) lines.joinToString("\n") else "(graph is empty — exploring for the first time)"
    }

    fun stats(): Map<String, Int> = mapOf(
        "pages" to pages.size,
        "elements" to elements.size,
        "transitions" to transitions.size,
        "shortcuts" to shortcuts.size
    )

    // Persistence
    fun save() {
        val data = mapOf(
            "pages" to pages,
            "elements" to elements,
            "transitions" to transitions,
            "shortcuts" to shortcuts
        )
        val json = Json { prettyPrint = true }
        File(filePath).parentFile?.mkdirs()
        File(filePath).writeText(json.encodeToString(data))
    }

    fun load() {
        val file = File(filePath)
        if (!file.exists()) return

        val json = Json { prettyPrint = true }
        val data = json.parseToJsonElement(file.readText())
        // Parse and populate maps... (implementation detail)
    }
}
```

- [ ] **Step 3: Create test file**

```bash
mkdir -p intellij-plugin/src/test/kotlin/graph/
```

Create basic test to verify graph serialization works.

- [ ] **Step 4: Commit graph infrastructure**

```bash
git add intellij-plugin/src/main/kotlin/graph/
git commit -m "feat: add knowledge graph data structures (PageNode, ElementNode, Transition, Shortcut)"
```

---

## Phase 2: Graph-Enhanced Agent

### Task 3: Create PageState from UiTree

**Files:**
- Create: `intellij-plugin/src/main/kotlin/graph/PageState.kt`
- Modify: `intellij-plugin/src/main/kotlin/parser/UiTreeParser.kt` (add page inference)

- [ ] **Step 1: Create PageState class**

```kotlin
// intellij-plugin/src/main/kotlin/graph/PageState.kt
package graph

import parser.UiComponent

data class PageState(
    val pageId: String,              // e.g. "editor_idle", "context_menu"
    val description: String,
    val elements: List<UiComponent>,
    val rawHtml: String = ""
) {
    fun toPromptString(): String {
        val lines = mutableListOf<String>()
        lines.add("## Current UI: $pageId")
        lines.add(description)
        lines.add("")

        if (elements.isNotEmpty()) {
            lines.add("### Interactive elements")
            elements.forEach { el ->
                val disabled = if (!el.enabled) " (disabled)" else ""
                val arrow = if (el.hasSubmenu) " →" else ""
                lines.add("  [${el.role}] \"${el.label}\"$arrow$disabled  xpath=${el.xpath}")
            }
        } else {
            lines.add("(no interactive elements detected)")
        }

        return lines.joinToString("\n")
    }
}
```

- [ ] **Step 2: Add page inference to UiTreeParser**

```kotlin
// In parser/UiTreeParser.kt, add method:

fun inferPageState(components: List<UiComponent>): PageState {
    // Check for dialogs
    val dialogs = components.filter { it.cls.contains("DialogRootPane") }
    if (dialogs.isNotEmpty()) {
        val name = dialogs.firstOrNull { it.accessibleName.isNotEmpty() }?.accessibleName ?: "unknown"
        val pageId = if (name.isNotEmpty()) "dialog_${name.lowercase().replace(' ', '_')}" else "dialog"
        return PageState(pageId, "Modal dialog open: $name", components)
    }

    // Check for popups/menus
    val popups = components.filter { it.cls.contains("HeavyWeightWindow") }
    if (popups.isNotEmpty()) {
        val hasTextField = components.any { it.cls.contains("TextField") }
        if (hasTextField) {
            return PageState("inline_widget", "Inline input widget open (e.g. rename field)", components)
        }
        if (popups.size > 1) {
            return PageState("refactor_submenu", "Refactor/action submenu open", components)
        }
        return PageState("context_menu", "Context menu open", components)
    }

    // Default: editor idle
    return PageState("editor_idle", "Editor focused, no popups or dialogs", components)
}
```

- [ ] **Step 3: Commit page state tracking**

```bash
git add intellij-plugin/src/main/kotlin/graph/PageState.kt intellij-plugin/src/main/kotlin/parser/UiTreeParser.kt
git commit -m "feat: add PageState with page inference logic"
```

---

### Task 4: Implement GraphAgent (LangGraph-style in Kotlin)

**Files:**
- Create: `intellij-plugin/src/main/kotlin/graph/GraphAgent.kt`

- [ ] **Step 1: Create agent state and result classes**

```kotlin
// intellij-plugin/src/main/kotlin/graph/GraphAgent.kt
package graph

import executor.UiExecutor
import llm.LlmClient
import parser.HtmlUiTreeProvider
import parser.UiTreeParser
import kotlinx.coroutines.runBlocking
import kotlin.math.max

data class ActionRecord(
    val actionType: String,
    val params: Map<String, String>,
    val pageBefore: String,
    var pageAfter: String = "",
    val reasoning: String,
    val success: Boolean = true
)

data class AgentResult(
    val success: Boolean,
    val message: String,
    val actionHistory: List<ActionRecord> = emptyList(),
    val tokenCount: Int = 0,
    val iterations: Int = 0
)

class GraphAgent(
    private val executor: UiExecutor,
    private val llmClient: LlmClient,
    private val treeProvider: HtmlUiTreeProvider,
    private val parser: UiTreeParser,
    private val graphPath: String = "data/knowledge_graph.json",
    private val maxIterations: Int = 30
) {
    private val graph = KnowledgeGraph(graphPath)

    init {
        graph.load()
    }

    suspend fun execute(task: String): AgentResult {
        val actionHistory = mutableListOf<ActionRecord>()
        var tokenCount = 0
        var currentPage = observe()

        repeat(maxIterations) { iteration ->
            println("\n[iter ${iteration + 1}] page: ${currentPage.pageId} — ${currentPage.elements.size} elements")

            // Reason
            val (reasoning, decision) = reason(task, currentPage, actionHistory)
            tokenCount += decision["tokenCount"]?.toIntOrNull() ?: 0

            println("[iter ${iteration + 1}] ${decision["actionType"]} — ${reasoning.take(80)}")

            // Act
            val record = act(decision, currentPage.pageId, reasoning)
            actionHistory.add(record)

            // Update graph
            currentPage = updateGraph(record, currentPage)

            // Check complete
            if (decision["actionType"] == "complete" || decision["actionType"] == "fail") {
                graph.save()
                return AgentResult(
                    success = decision["actionType"] == "complete",
                    message = if (decision["actionType"] == "complete") "Task completed" else "Task failed",
                    actionHistory = actionHistory,
                    tokenCount = tokenCount,
                    iterations = iteration + 1
                )
            }
        }

        graph.save()
        return AgentResult(
            success = false,
            message = "Max iterations reached",
            actionHistory = actionHistory,
            tokenCount = tokenCount,
            iterations = maxIterations
        )
    }

    private suspend fun observe(): PageState {
        val html = treeProvider.fetchHtml()
        val components = parser.parse(html)
        return parser.inferPageState(components)
    }

    private suspend fun reason(task: String, page: PageState, history: List<ActionRecord>): Pair<String, Map<String, String>> {
        val graphContext = graph.toPromptContext(page.pageId)
        val historyStr = history.takeLast(5).joinToString("\n") { r ->
            "  - ${r.actionType}(${r.params}) on ${r.pageBefore} → ${r.pageAfter}"
        }

        val prompt = """
            You are automating IntelliJ IDEA to accomplish a task.

            ## Task
            $task

            ## Current UI State
            ${page.toPromptString()}

            ## Graph Knowledge (from previous runs)
            $graphContext

            ## Recent Action History
            ${if (historyStr.isNotEmpty()) historyStr else "  (none yet)"}

            ## Available Actions
            - click: click a UI element by xpath
            - right_click: right-click a UI element by xpath (opens context menu)
            - type: type text at current focus
            - press_key: press a key (Enter, Escape, Tab, Backspace, context_menu, shift_f6)
            - complete: mark task as successfully done
            - fail: mark task as failed

            Return ONLY valid JSON (no markdown):
            {
              "reasoning": "what you see and why you chose this action",
              "actionType": "click|right_click|type|press_key|complete|fail",
              "params": {"xpath": "...", "text": "...", "key": "..."},
              "complete": false
            }
        """.trimIndent()

        val response = llmClient.sendMessage(prompt)
        // Parse JSON response... (implementation detail)

        return Pair("reasoning extracted", mapOf("actionType" to "click", "params" to emptyMap()))
    }

    private suspend fun act(decision: Map<String, String>, pageBefore: String, reasoning: String): ActionRecord {
        val actionType = decision["actionType"] ?: "observe"
        val params = decision["params"]?.let { parseParams(it) } ?: emptyMap()

        when (actionType) {
            "click" -> params["xpath"]?.let { executor.click(it) }
            "right_click" -> params["xpath"]?.let { executor.rightClick(it) }
            "type" -> params["text"]?.let { executor.type(it) }
            "press_key" -> params["key"]?.let { executor.pressKey(it) }
            "complete", "fail", "observe" -> { /* no-op */ }
        }

        kotlinx.coroutines.delay(500)

        return ActionRecord(actionType, params, pageBefore, reasoning = reasoning)
    }

    private suspend fun updateGraph(record: ActionRecord, prevPage: PageState): PageState {
        val newPage = observe()

        // Add pages
        if (graph.getPage(prevPage.pageId) == null) {
            graph.addPage(PageNode(prevPage.pageId, prevPage.description))
        }
        if (graph.getPage(newPage.pageId) == null) {
            graph.addPage(PageNode(newPage.pageId, newPage.description))
        }

        // Add elements from previous page
        prevPage.elements.forEach { el ->
            val elId = graph.makeElementId(prevPage.pageId, el.cls, el.label)
            if (graph.getElement(elId) == null) {
                graph.addElement(ElementNode(elId, prevPage.pageId, el.cls, el.label, el.xpath, el.role))
            }
        }

        // Record transition if page changed
        if (prevPage.pageId != newPage.pageId && record.actionType !in listOf("complete", "fail", "observe")) {
            val xpath = record.params["xpath"] ?: ""
            val elId = graph.makeElementId(prevPage.pageId, record.params["cls"] ?: "", record.params["label"] ?: xpath)
            graph.addTransition(prevPage.pageId, elId, record.actionType, newPage.pageId)
        }

        record.pageAfter = newPage.pageId
        graph.recordVisit(newPage.pageId)
        graph.save()

        return newPage
    }

    private fun parseParams(paramsStr: String): Map<String, String> {
        // Parse JSON params... (implementation detail)
        return emptyMap()
    }
}
```

- [ ] **Step 2: Commit agent implementation**

```bash
git add intellij-plugin/src/main/kotlin/graph/GraphAgent.kt
git commit -m "feat: implement GraphAgent with observe-reason-act-update_graph loop"
```

---

## Phase 3: Main Entry Point

### Task 5: Create Main Entry Point

**Files:**
- Create: `intellij-plugin/src/main/kotlin/main/Main.kt`
- Modify: `intellij-plugin/build.gradle.kts` (add runGraphAgent task)

- [ ] **Step 1: Create main function**

```kotlin
// intellij-plugin/src/main/kotlin/main/Main.kt
package main

import executor.UiExecutor
import graph.GraphAgent
import llm.LlmClient
import parser.HtmlUiTreeProvider
import parser.UiTreeParser
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    val robotUrl = System.getenv("ROBOT_URL") ?: "http://localhost:8082"
    val llmBaseUrl = System.getenv("LLM_BASE_URL") ?: "https://coding-intl.dashscope.aliyuncs.com/v1"
    val llmModel = System.getenv("LLM_MODEL") ?: "MiniMax-M2.5"
    val llmApiKey = System.getenv("LLM_API_KEY") ?: ""

    val task = args.firstOrNull() ?: "help"

    val executor = UiExecutor(robotUrl)
    val llmClient = LlmClient(llmBaseUrl, llmModel, llmApiKey)
    val treeProvider = HtmlUiTreeProvider(robotUrl)
    val parser = UiTreeParser()

    val agent = GraphAgent(executor, llmClient, treeProvider, parser)

    val result = agent.execute(task)

    println("\n${if (result.success) "✓ Success" else "✗ Failed"}")
    println("Iterations: ${result.iterations}  |  Tokens: ${result.tokenCount}")
    println("\nAction log:")
    result.actionHistory.forEachIndexed { i, action ->
        println("  ${i + 1}. ${action.actionType}(${action.params}) — ${action.reasoning.take(60)}")
    }
}
```

- [ ] **Step 2: Update build.gradle.kts**

```kotlin
// In build.gradle.kts, update the runGraphAgent task:
tasks.register<JavaExec>("runGraphAgent") {
    group = "application"
    description = "Run the Graph-Enhanced UI Agent"
    dependsOn(tasks.compileKotlin)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("main.MainKt")
    // Pass task as argument: ./gradlew runGraphAgent --args="your task here"
}
```

- [ ] **Step 3: Commit main entry point**

```bash
git add intellij-plugin/src/main/kotlin/main/Main.kt intellij-plugin/build.gradle.kts
git commit -m "feat: add main entry point for GraphAgent"
```

---

## Phase 4: Testing & Evaluation

### Task 6: Smoke Test

**Files:**
- None (execution only)

- [ ] **Step 1: Ensure IntelliJ is running with Remote Robot**

Start IntelliJ with Remote Robot on port 8082 (same as Raihan's setup).

- [ ] **Step 2: Set environment variables**

```bash
export ROBOT_URL=http://localhost:8082
export LLM_BASE_URL=https://coding-intl.dashscope.aliyuncs.com/v1
export LLM_MODEL=MiniMax-M2.5
export LLM_API_KEY=sk-...
```

- [ ] **Step 3: Run simple test**

```bash
cd intellij-plugin
./gradlew runGraphAgent --args="In the editor, right-click to open context menu"
```

Expected: Agent observes, reasons, acts, and updates graph.

- [ ] **Step 4: Verify graph was created**

```bash
cat data/knowledge_graph.json | jq '.pages | length'
```

Expected: Should show pages count > 0

---

### Task 7: Run Refactoring Task

**Files:**
- None (execution only)

- [ ] **Step 1: Open target file in IntelliJ**

Open `intellij-plugin/src/main/kotlin/executor/UiExecutor.kt`

- [ ] **Step 2: Run rename task**

```bash
./gradlew runGraphAgent --args="In intellij-plugin/src/main/kotlin/executor/UiExecutor.kt, rename executeRecipe to runRecipe using Refactor > Rename"
```

- [ ] **Step 3: Verify rename succeeded**

```bash
grep "fun runRecipe" intellij-plugin/src/main/kotlin/executor/UiExecutor.kt
```

Expected: Should find renamed method

- [ ] **Step 4: Reset file**

```bash
git checkout HEAD -- intellij-plugin/src/main/kotlin/executor/UiExecutor.kt
```

---

### Task 8: Compare Cold Start vs Learned

**Files:**
- None (execution only)

- [ ] **Step 1: Run task 3 times (graph accumulates)**

```bash
# Run 1 (cold start)
rm -f data/knowledge_graph.json
./gradlew runGraphAgent --args="rename executeRecipe to runRecipe in intellij-plugin/src/main/kotlin/executor/UiExecutor.kt"
git checkout HEAD -- intellij-plugin/src/main/kotlin/executor/UiExecutor.kt

# Run 2 (graph has context)
./gradlew runGraphAgent --args="rename executeRecipe to runRecipe in intellij-plugin/src/main/kotlin/executor/UiExecutor.kt"
git checkout HEAD -- intellij-plugin/src/main/kotlin/executor/UiExecutor.kt

# Run 3 (graph more learned)
./gradlew runGraphAgent --args="rename executeRecipe to runRecipe in intellij-plugin/src/main/kotlin/executor/UiExecutor.kt"
git checkout HEAD -- intellij-plugin/src/main/kotlin/executor/UiExecutor.kt
```

- [ ] **Step 2: Compare results**

Record for each run:
- Iteration count
- Token usage
- Success/failure

**Expected**: Run 3 should use fewer iterations and tokens than Run 1.

---

### Task 9: Document Findings

**Files:**
- Create: `docs/graph_agent_evaluation.md`

- [ ] **Step 1: Create evaluation results document**

```markdown
# Graph-Based Agent Evaluation Results

## Hypothesis
Maintaining a knowledge graph of UI states (pages, elements, transitions) improves automation performance compared to flat UI tree approach.

## Method
- Single refactoring task (rename method)
- 3 runs: Run 1 (cold start, empty graph), Run 2-3 (graph accumulates)
- Metrics: iterations, token usage, success rate

## Results

| Run | Iterations | Tokens | Success | Notes |
|-----|------------|--------|---------|-------|
| 1   | ...        | ...    | ...     | Cold start, no graph context |
| 2   | ...        | ...    | ...     | Graph has 1 run of context |
| 3   | ...        | ...    | ...     | Graph has 2 runs of context |

## Conclusions

### Performance
- Does iteration count decrease? [YES/NO]
- Does token usage decrease? [YES/NO]
- Success rate stability? [...]

### Graph Quality
- Pages learned: X
- Transitions learned: Y
- Useful shortcuts: Z

### Next Steps
[What to improve or test next]

## Comparison to Flat Approach (Raihan's)
- Raihan's approach: [describe flat approach performance]
- Graph approach: [describe observed performance]
- Improvement: [X% better/worse]
```

- [ ] **Step 2: Commit documentation**

```bash
git add docs/
git commit -m "docs: add graph agent evaluation results"
```

---

## Phase 5: Final Cleanup

### Task 10: Verify Code Quality

**Files:**
- Modify: Any files that need cleanup

- [ ] **Step 1: Run tests**

```bash
cd intellij-plugin
./gradlew test
```

Expected: All tests pass

- [ ] **Step 2: Run ktlint**

```bash
./gradlew ktlintCheck
```

Fix any lint issues.

- [ ] **Step 3: Final commit**

```bash
git add .
git commit -m "feat: complete graph-based agent implementation"
```

---

### Task 11: Create Summary

**Files:**
- Create: `README_GRAPH_AGENT.md` (in project root)

- [ ] **Step 1: Create usage documentation**

```markdown
# Graph-Based UI Automation Agent

Minimal Kotlin implementation testing whether maintaining a knowledge graph of UI states improves automation performance.

## Architecture

- **GraphAgent**: LangGraph-style agent with observe→reason→act→update_graph loop
- **KnowledgeGraph**: Tracks pages, elements, transitions, shortcuts
- **Persistence**: Graph saved to `data/knowledge_graph.json`

## Hypothesis

On first run (cold start), agent behaves like flat UI tree approach. On subsequent runs, graph provides learned context → fewer iterations, fewer tokens.

## Usage

```bash
# Set env vars
export ROBOT_URL=http://localhost:8082
export LLM_BASE_URL=https://coding-intl.dashscope.aliyuncs.com/v1
export LLM_MODEL=MiniMax-M2.5
export LLM_API_KEY=sk-...

# Run agent
cd intellij-plugin
./gradlew runGraphAgent --args="your task here"

# Example
./gradlew runGraphAgent --args="rename executeRecipe to runRecipe in intellij-plugin/src/main/kotlin/executor/UiExecutor.kt"
```

## Results

[Link to docs/graph_agent_evaluation.md]

## Key Files

- `graph/GraphAgent.kt` - Main agent loop
- `graph/KnowledgeGraph.kt` - Graph data structure
- `graph/PageState.kt` - UI state representation
- `main/Main.kt` - Entry point
```

- [ ] **Step 2: Final commit**

```bash
git add README_GRAPH_AGENT.md
git commit -m "docs: add graph agent usage documentation"
```

---

## Summary

This plan creates a **minimal, pure-Kotlin** implementation testing the graph-based approach hypothesis:

**Key simplifications from Python version:**
- No bridge server (direct Kotlin → Remote Robot)
- No Python dependencies
- Single codebase
- ~500 lines of Kotlin (vs ~2000 lines of mixed Python/Kotlin)

**What gets tested:**
- Does graph learning reduce iteration count over runs?
- Does graph context reduce token usage?
- Is the graph-based approach better than flat UI tree?

**Timeline:**
- Phase 1-2: Core infrastructure (1-2 hours)
- Phase 3: Agent implementation (2-3 hours)
- Phase 4-5: Testing and evaluation (1-2 hours)
- **Total: ~1 day**
