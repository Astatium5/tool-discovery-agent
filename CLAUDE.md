# Tool Discovery Agent — Agent Context

## What This Project Is

We are evaluating whether the **AppAgentX graph-based approach** (arxiv 2503.02268) improves
IntelliJ UI automation compared to the current flat-UI-tree approach.

**Raihan** (teammate) built a working Observe-Reason-Act Kotlin agent that flattens the IntelliJ
UI tree and feeds it to an LLM. It struggles with complex tool windows.

**Our job**: implement AppAgentX graph approach in Python, run it against live IntelliJ, measure
whether it outperforms the flat approach (fewer actions, better success rate across repeated runs).

---

## Repo Structure

This is a **monorepo** with two separate codebases:

```
tool-discovery-agent/
├── intellij-plugin/          ← Kotlin (Raihan's working agent + our bridge server)
│   └── src/main/kotlin/
│       ├── agent/UiAgent.kt          Raihan's Observe-Reason-Act loop
│       ├── executor/UiExecutor.kt    UI action execution (click, type, keys)
│       ├── parser/HtmlUiTreeProvider.kt  fetches HTML from Remote Robot
│       ├── parser/UiTreeParser.kt    parses HTML → UiComponent tree
│       ├── llm/LlmClient.kt          OpenAI-compatible LLM client
│       ├── reasoner/LLMReasoner.kt   LLM decision prompt + JSON parsing
│       └── server/AgentBridgeServer.kt  ← OUR ADDITION (bridge for Python)
│
├── src/                      ← Python (our AppAgentX graph agent)
│   ├── perception/
│   │   ├── remote_robot.py   HTTP client → Kotlin bridge server
│   │   └── ui_tree.py        HTML parser → PageState (page_id, elements)
│   ├── agent/
│   │   ├── knowledge_graph.py  AppAgentX graph (NetworkX, persisted to JSON)
│   │   └── graph_agent.py      LangGraph agent (observe→reason→act→update_graph)
│   ├── evaluation/
│   │   ├── tasks.py            3 eval tasks (rename_method, rename_variable, rename_class)
│   │   └── runner.py           runs N tasks × N rounds, records metrics
│   └── main.py                 CLI entry point
│
├── .env                      ← fill in your LLM_API_KEY here (gitignored)
├── requirements.txt
└── data/                     ← knowledge graph + eval results land here
```

---

## Branch

**Active branch**: `graph-approach` (based on `uiAgent-raihan`)

- Contains all of Raihan's Kotlin code + our Python additions + Kotlin bridge server
- Do all new work on this branch

---

## Architecture

```
IntelliJ IDEA (Mac, port 8082)
    ↕ Remote Robot plugin (JetBrains Java library)
Kotlin Bridge Server (Mac, port 7070)   ← ./gradlew runBridgeServer
    ↕ HTTP  GET /tree  POST /action
Python Graph Agent (Mac, localhost)
    ↕ LLM API (dashscope / MiniMax)
data/knowledge_graph.json               ← persists between runs
```

The bridge server exists because the Remote Robot HTTP API is non-trivial to call from
Python. The Kotlin bridge wraps the working Java library and exposes simple endpoints.

---

## How To Run

### Prerequisites
1. IntelliJ IDEA running with Remote Robot plugin on port 8082
   - Plugin installed: `~/.gradle/caches/.../robot-server-plugin-0.11.23.zip`
   - `idea.vmoptions` contains: `-Drobot-server.port=8082`
   - Verify: `curl http://localhost:8082` returns HTML

2. Kotlin bridge server running:
   ```bash
   cd intellij-plugin
   ./gradlew runBridgeServer
   # Runs on port 7070, connects to localhost:8082
   ```

3. `.env` filled in:
   ```
   REMOTE_ROBOT_URL=http://localhost:7070
   LLM_BASE_URL=https://coding-intl.dashscope.aliyuncs.com/v1
   LLM_MODEL=MiniMax-M2.5
   LLM_API_KEY=your_key_here
   ```

4. Python deps:
   ```bash
   pip install -r requirements.txt
   ```

### Commands
```bash
python3 -m src status          # check bridge + LLM connectivity
python3 -m src run --task "..."  # run a single task
python3 -m src eval            # full eval: 3 tasks × 3 runs
python3 -m src eval --clear-graph  # ablation baseline (no graph benefit)
```

---

## LLM Configuration

Same API as Raihan's Kotlin agent:
- `LLM_BASE_URL`: OpenAI-compatible base URL
- `LLM_MODEL`: model name
- `LLM_API_KEY`: API key

Set these in `.env` (see `.env.example`).

---

## Key Design: AppAgentX Graph

Instead of dumping the full UI tree to the LLM every step (Raihan's approach), we maintain
a **knowledge graph** of UI states:

- **PageNode**: a distinct UI state (e.g. `editor_idle`, `context_menu`, `rename_dialog`)
- **ElementNode**: interactive component within a page
- **LEADS_TO edge**: clicking element X on page A transitions to page B
- **Shortcut**: learned multi-step sequence (e.g. `rename_symbol` = 5 steps)

The graph persists to `data/knowledge_graph.json`. On run 1 it's empty (cold start, like
Raihan's flat approach). By run 3, the LLM gets compact graph context instead of raw HTML.

**Evaluation hypothesis**: run 3 should need fewer actions and tokens than run 1.

---

## Evaluation Tasks

Defined in `src/evaluation/tasks.py`. Currently 3 rename tasks on Kotlin files in
`intellij-plugin/src/main/kotlin/`:
- `rename_method`: rename `executeRecipe` → `runRecipe` in `UiExecutor.kt`
- `rename_variable`: rename `accessibleName` → `a11yName` in `UiModels.kt`
- `rename_class`: rename `RecipeStep` → `ActionStep` in `RecipeStep.kt`

**Important**: after each task the runner does `git checkout HEAD -- <file>` to reset
the file for the next run. This requires the repo to be clean before starting eval.

---

## Current State / What Works

- [x] Remote Robot is live at `https://intellij.sudhanva.dev` (tunnel) and `localhost:8082`
- [x] Python UIStateParser correctly parses Remote Robot HTML into PageState
- [x] Kotlin GraphAgent complete (see docs/graph_agent_evaluation.md for metrics)
- [x] KnowledgeGraph with NetworkX, JSON persistence, LLM context generation
- [x] LangGraph agent loop (observe→reason→act→update_graph→check_complete)
- [x] LLM integration (OpenAI-compatible, reads env vars)
- [x] Per-iteration progress logging
- [x] All 61 unit tests passing (run: `./gradlew test --tests "*graph*"`)

## Quick Start

**Run smoke tests** (works with any open project): `cd intellij-plugin && ./run-smoke-test.sh`
**Check Remote Robot connection:** `curl -s http://localhost:8082 | grep -o 'title="[^"]*"'`
**See detailed implementation status:** `docs/superpowers/plans/2026-03-29-graph-agent-refactor.md`

---

## Known Issues / Watch Out For

1. **iter 1 often returns `observe`** — the LLM sometimes observes before acting on iter 1.
   This is fine (1 wasted step), but if it loops on observe for many steps the prompt or
   JSON parsing may need adjustment.

2. **XPath mismatches** — the element XPaths generated by `UIStateParser` use `accessiblename`
   and `visible_text`. If the bridge server can't find a component, the XPath is likely wrong.
   Check `data/knowledge_graph.json` to see what elements were observed.

3. **Page ID inference** — `UIStateParser` classifies pages by heuristic (HeavyWeightWindow →
   context_menu, DialogRootPane → dialog, etc). If misclassified, the LLM will get wrong
   context. Add print debugging to `_infer_page()` if needed.

4. **Document text diffing** — `get_document_text()` currently returns `""` (the bridge
   doesn't expose this yet). Diff-based completion won't trigger; the agent relies on LLM
   returning `complete: true`. This is OK for initial testing.

5. **`shift_f6` shortcut** — Rename in IntelliJ can also be triggered with Shift+F6. This
   is more reliable than navigating menus. The prompt mentions it as available via `press_key`.

6. **IDE focus required for UI tests** — Tests use AppleScript to focus IntelliJ. If tests
   fail, manually click the IDE window and retry. See `intellij-plugin/run-smoke-test.sh`.

---

## Files NOT to Touch

- `src/agent/discovery.py`, `planner.py`, `executor.py` — old stubs, unrelated to our work
- `intellij-plugin/src/test/kotlin/` — Raihan's tests, don't modify
- `intellij-plugin/src/main/kotlin/agent/`, `executor/`, `parser/`, etc. — Raihan's agent code
