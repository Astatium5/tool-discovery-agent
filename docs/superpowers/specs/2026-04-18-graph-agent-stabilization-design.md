# GraphAgent Stabilization Design

**Date:** 2026-04-18
**Scope:** Stabilize the `GraphAgent` workflow so it can reliably rename a local variable by right-clicking the variable, choosing `Rename`, entering the new name, and confirming the refactoring.

## Goal

Turn the current graph-based automation flow into a debuggable, staged system whose failures can be localized to a specific seam. The first supported end-to-end task is:

- Rename a local variable using the right-click context menu `Rename` action.

`UiAgent` remains out of the runtime path. It is only a reference source for interaction tactics if `GraphAgent` gets stuck.

## Out Of Scope

- Keeping the legacy `UiAgent` stack production-ready in parallel
- Generalizing immediately to all refactorings
- Adding LangGraph as a runtime dependency
- Building a multi-task benchmark before the canonical rename task is reliable

## Why This Scope

The repository currently contains two automation stacks, but only the graph-based path is the target. Existing tests show that graph-core logic is mostly healthy while many failures are live-environment or orchestration failures. The system is currently hard to debug because perception, decision-making, execution, and graph mutation are too tightly coupled inside the live loop.

The stabilization strategy is therefore:

1. Freeze the product target to one concrete refactoring task.
2. Decompose the system into clear seams.
3. Validate those seams in isolation before wiring them together.
4. Add observability so every later failure is attributable to a stage.

## Canonical Task

The canonical task for all stages is:

1. Open a known file containing a known local variable.
2. Target the local variable.
3. Right-click to open the context menu.
4. Choose `Rename`.
5. Type the replacement variable name.
6. Confirm the rename.
7. Verify that the code changed as expected.

This task stays fixed until the staged ladder is green. New tasks should not be added until this flow is reliable.

## System Decomposition

### 1. Graph Memory

Files:

- `intellij-plugin/src/main/kotlin/graph/KnowledgeGraph.kt`
- `intellij-plugin/src/main/kotlin/graph/GraphTypes.kt`

Responsibilities:

- Page storage
- Element storage
- Element IDs
- Deltas between visits
- Successful transitions
- Failed transitions
- Pathfinding
- Shortcut discovery
- Persistence

Desired property:

- Deterministic and testable with no IDE, no LLM, and no network.

### 2. Prompt And State Formatting

Files:

- `intellij-plugin/src/main/kotlin/graph/PageState.kt`
- prompt-building logic inside `GraphAgent.kt`

Responsibilities:

- Render current UI state for the decision layer
- Render delta-aware prompts on revisits
- Render graph context for the current page

Desired property:

- Given fixed inputs, prompt output is deterministic and compact.

### 3. Perception

Files:

- `intellij-plugin/src/main/kotlin/parser/HtmlUiTreeProvider.kt`
- `intellij-plugin/src/main/kotlin/parser/UiTreeParser.kt`
- `intellij-plugin/src/main/kotlin/parser/UiTreeProvider.kt`

Responsibilities:

- Fetch raw UI HTML from Remote Robot
- Parse component trees
- Infer high-level page state

Required page states for the canonical task:

- `editor_idle`
- `context_menu`
- `refactor_submenu` if IntelliJ exposes the submenu in the captured UI
- `inline_widget` or the equivalent rename dialog state

Desired property:

- Page classification can be verified from saved HTML fixtures without a live IDE.

### 4. Execution

Files:

- `intellij-plugin/src/main/kotlin/executor/UiExecutor.kt`

Responsibilities for the canonical task:

- Focus the editor
- Move to or otherwise target the local variable
- Open the context menu
- Click `Rename`
- Type the new variable name
- Confirm the action

Desired property:

- Each physical action is callable directly and returns a structured success or failure result.

### 5. Decision Boundary

Files:

- `intellij-plugin/src/main/kotlin/llm/LlmClient.kt`
- decision parsing inside `GraphAgent.kt`

Responsibilities:

- Build the decision prompt
- Request the next action
- Parse structured responses

Desired property:

- Replaceable with a stub or deterministic policy in tests so the loop can be exercised without a live model.

### 6. GraphAgent Orchestration

Files:

- `intellij-plugin/src/main/kotlin/graph/GraphAgent.kt`

Responsibilities:

- Observe
- Decide
- Act
- Update graph
- Check completion
- Return structured result

Desired property:

- Orchestrates already-testable seams instead of owning their internals.

### 7. Live Harness

Files:

- `intellij-plugin/src/test/kotlin/BaseTest.kt`
- `intellij-plugin/src/test/kotlin/UiIntegrationTest.kt`
- `intellij-plugin/src/test/kotlin/GraphAgentSmokeTest.kt`
- `intellij-plugin/src/test/kotlin/QuestDBFocusTest.kt`

Responsibilities:

- Verify the IDE is running and reachable
- Verify Remote Robot connectivity
- Provide a known file and known variable fixture
- Fail fast when the environment is not available

Desired property:

- Environment failures are classified before agent logic runs.

## Hard Architectural Boundaries

The following boundaries are required for the stabilization effort:

1. `GraphAgent` is an orchestrator, not the place where raw HTTP fetching, prompt formatting, element lookup, and graph mutation all blur together.
2. Perception accepts raw HTML or parsed components and returns a deterministic `PageState`.
3. Execution performs one physical action at a time and reports a structured result.
4. The decision layer can be replaced with a stubbed or mocked policy.
5. `UiAgent` is not called by `GraphAgent`; it is only a reference implementation.

## Known Immediate Design Problems

These should be treated as early cleanup targets because they weaken isolation:

1. Environment failures currently show up as agent failures instead of stage-0 harness failures.
2. `GraphAgent` re-enters observation during action execution to recover element metadata, which adds unnecessary live coupling.
3. `GraphAgent.observe()` both reads state and mutates graph state, which makes it harder to reason about first-visit behavior.
4. Live tests currently mix harness checks, executor checks, and agent checks in ways that obscure root cause.

## Staged Validation Ladder

### Stage 0: Harness Sanity

Purpose:

- Prove the live IDE environment is reachable and stable before any GraphAgent test starts.

Scope:

- Remote Robot connectivity
- IDE focus
- Known project and file fixture
- Minimal observe-current-page check

Exit criteria:

- Failures here are clearly environment failures, not agent failures.

### Stage 1: Pure Graph Core

Purpose:

- Trust the graph memory layer independently.

Scope:

- IDs
- Element storage
- Deltas
- Failed transitions
- Pathfinding
- Shortcuts
- Persistence
- Prompt context generation

Exit criteria:

- No IDE, no LLM, and all graph-memory behavior is deterministic.

### Stage 2: Perception In Isolation

Purpose:

- Make page-state detection deterministic from saved inputs.

Scope:

- Feed saved HTML fixtures into the provider/parser path or directly into parser helpers.
- Cover every required state in the canonical rename flow.

Exit criteria:

- The parser correctly classifies `editor_idle`, `context_menu`, `refactor_submenu` if present, and rename UI state from fixtures.

### Stage 3: Execution In Isolation

Purpose:

- Prove the physical interaction layer can execute the rename flow without full agent reasoning.

Scope:

- Direct scripted calls to executor methods only.
- No LLM-driven decisions.

Exit criteria:

- Given a known IDE state, executor methods can perform the next needed physical step reliably.

### Stage 4: Partial GraphAgent Integrations

Purpose:

- Wire a few seams at a time so failures remain attributable.

Suggested sequence:

1. observe + parse
2. observe + parse + graph update
3. observe + parse + scripted action + graph update
4. observe + parse + mocked decision + action + graph update

Exit criteria:

- Each failure can be localized to a single seam or handoff.

### Stage 5: Narrow End-To-End Task

Purpose:

- Prove the full GraphAgent loop on one canonical refactoring.

Scope:

- One known file
- One known local variable
- One expected rename outcome

Exit criteria:

- GraphAgent completes the rename repeatedly and records useful traces and artifacts when it fails.

## Success Criteria

### Harness Success

- The test environment can prove IDE availability and fixture readiness before agent execution begins.

### Perception Success

- The system can distinguish the required UI states both from saved fixtures and from live runs.

### Execution Success

- The executor can reliably focus the target, open the context menu, click `Rename`, type the new name, and confirm.

### Agent Success

- GraphAgent completes the canonical rename flow and records transitions that are useful for future runs.

### Reliability Success

- Repeated runs produce enough signal to tell whether a regression came from harness, perception, execution, decision, or orchestration.

## Observability Design

OpenTelemetry is the observability standard for this effort.

### Why OpenTelemetry

- It provides step-level traces without introducing another orchestration framework.
- It fits the Kotlin/Java runtime directly.
- It can be exported to multiple backends later without reinstrumenting the system.

### Root Trace

- One root trace per canonical rename attempt.

Recommended root span name:

- `graph_agent.rename_local_variable`

### Span Model

Each iteration should have an iteration span such as:

- `graph_agent.iteration`

Recommended child spans:

- `observe.fetch_html`
- `observe.parse_tree`
- `observe.infer_page_state`
- `decide.build_prompt`
- `decide.llm_call`
- `decide.parse_response`
- `act.execute`
- `graph.update`
- `completion.check`

Executor-level spans may be added below `act.execute` when useful:

- `act.focus_target`
- `act.open_context_menu`
- `act.click_rename`
- `act.type_new_name`
- `act.confirm`

### Required Span Attributes

Every major span should attach structured metadata where available:

- `task_id`
- `iteration`
- `page_before`
- `page_after`
- `action_type`
- `target_label`
- `element_class`
- `success`
- `exception.type`
- `exception.message`
- `graph.pages`
- `graph.transitions`
- `graph.failed_transitions`

### Artifacts

Each iteration should be able to point to artifacts for debugging:

- raw HTML snapshot path
- parsed page summary path or serialized summary
- decision JSON path
- executor result path
- optional screenshot path if screenshots are added later

These artifacts do not need to be embedded in the trace payload. Paths or IDs are sufficient.

### Backend Strategy

- Start with a simple local OpenTelemetry backend and trace UI.
- Keep instrumentation backend-agnostic.
- Allow future export to Jaeger, Grafana Tempo, or LangSmith without changing span semantics.

## Testing Strategy

The testing strategy mirrors the ladder:

1. Keep pure graph-core tests fast and deterministic.
2. Add fixture-based perception tests next.
3. Add direct executor tests for the canonical task only.
4. Add partial integration tests that use stubs for the decision layer.
5. Keep one canonical end-to-end rename test as the final gate.

Tests should be named by seam and stage so a failure tells us where to look first.

## Decision On Legacy Code

- `UiAgent` remains available in the repository as a fallback reference.
- `UiAgent` is not in scope for runtime stabilization.
- Borrowed tactics from `UiAgent` must be copied into GraphAgent-owned boundaries rather than introducing cross-stack runtime coupling.

## Implementation Guidance

The first implementation steps should prioritize:

1. Stage-0 harness classification
2. OpenTelemetry trace scaffolding
3. Perception fixture tests for rename-flow states
4. Executor isolation for the canonical rename flow
5. Refactoring `GraphAgent` to orchestrate those seams cleanly

## Expected Outcome

After this work, the project should have:

- A single canonical GraphAgent task
- A staged test ladder from pure logic to end-to-end execution
- Explicit boundaries between memory, perception, decision, execution, and orchestration
- OpenTelemetry traces that show each step and where it failed
- A practical path to expand from local-variable rename to additional refactorings later
