# Graph-Based Approach Implementation & Evaluation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-step. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement and evaluate the AppAgentX graph-based UI automation approach for IntelliJ, measuring whether it outperforms the flat UI tree approach on refactoring tasks.

**Architecture:** Python LangGraph agent maintains a knowledge graph of UI states (pages, elements, transitions). On first run (cold start), it behaves like the flat approach. On subsequent runs, it uses learned graph context to make faster, more efficient decisions.

**Tech Stack:** Python 3.13, LangGraph, NetworkX, httpx, BeautifulSoup4, Kotlin bridge server (IntelliJ Remote Robot wrapper)

---

## Phase 1: Cleanup & Environment Setup

### Task 1: Clean Up Unused Code

**Files:**
- Delete: `src/agent/discovery.py`
- Delete: `src/agent/planner.py`
- Delete: `src/agent/executor.py`
- Delete: `src/api.py`
- Delete: `src/__main__.py`

- [ ] **Step 1: Remove unused agent files**

These files are old stubs unrelated to the graph approach. They confuse the codebase structure.

```bash
cd /Users/sudhanva/Documents/grad-school/csci-7000-005/tool-discovery-agent
rm src/agent/discovery.py src/agent/planner.py src/agent/executor.py src/api.py src/__main__.py
```

- [ ] **Step 2: Verify clean structure**

```bash
ls -la src/agent/
ls -la src/
```

Expected: `src/agent/` should only show `__init__.py`, `graph_agent.py`, `knowledge_graph.py`

- [ ] **Step 3: Commit cleanup**

```bash
git add src/
git commit -m "chore: remove unused discovery/planner/executor stub files"
```

---

### Task 2: Verify Environment Configuration

**Files:**
- Check: `.env`
- Modify: `requirements.txt`

- [ ] **Step 1: Verify .env is configured**

```bash
cat .env
```

Expected output should show:
```
REMOTE_ROBOT_URL=http://localhost:7070
LLM_BASE_URL=https://coding-intl.dashscope.aliyuncs.com/v1
LLM_MODEL=MiniMax-M2.5
LLM_API_KEY=sk-...
```

If missing LLM_API_KEY, add it.

- [ ] **Step 2: Check Python dependencies installed**

```bash
source .venv/bin/activate
pip list | grep -E "httpx|beautifulsoup4|networkx|langgraph|rich|python-dotenv"
```

Expected: All packages should be listed. If not, run:

```bash
pip install -r requirements.txt
```

- [ ] **Step 3: Update requirements.txt (add missing if needed)**

Current `requirements.txt` should have all dependencies. Verify it includes:
```
httpx==0.28.1
beautifulsoup4==4.14.3
networkx==3.6.1
langchain==1.2.13
langchain-core==1.2.23
langchain-openai==1.1.12
langgraph==1.1.3
pydantic==2.12.5
python-dotenv==1.2.2
rich==14.3.3
```

---

## Phase 2: Bridge Server Verification

### Task 3: Build and Run Kotlin Bridge Server

**Files:**
- Modify: `intellij-plugin/build.gradle.kts` (if needed)
- Verify: `intellij-plugin/src/main/kotlin/server/AgentBridgeServer.kt`

- [ ] **Step 1: Verify gradle build is clean**

```bash
cd intellij-plugin
./gradlew clean
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Compile Kotlin code**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL, no compilation errors

- [ ] **Step 3: Start the bridge server**

```bash
./gradlew runBridgeServer
```

Expected output:
```
Agent Bridge Server on port 7070  (IDE: http://localhost:8082)
  GET  /ping
  GET  /tree
  POST /action  {type: click|right_click|type|press_key, ...}
```

- [ ] **Step 4: In another terminal, verify bridge responds**

```bash
curl http://localhost:7070/ping
```

Expected: `{"status":"ok"}`

---

### Task 4: Verify Remote Robot Connection

**Files:**
- None (verification only)

- [ ] **Step 1: Check IntelliJ is running with Remote Robot**

```bash
curl http://localhost:8082
```

Expected: HTML response (not error). If connection refused:
1. Start IntelliJ IDEA
2. Enable Remote Robot plugin (should be installed at `~/.gradle/caches/modules-2/files-2.1/com.intellij.remoterobot/remote-robot/0.11.23/...`)
3. Check `idea.vmoptions` contains `-Drobot-server.port=8082`

- [ ] **Step 2: Test bridge can fetch UI tree**

```bash
curl http://localhost:7070/tree | head -c 500
```

Expected: HTML response containing `<html>...` and UI tree structure

- [ ] **Step 3: Verify Python client can connect**

```bash
cd /Users/sudhanva/Documents/grad-school/csci-7000-005/tool-discovery-agent
source .venv/bin/activate
python3 -m src status
```

Expected:
```
Checking Remote Robot at http://localhost:7070 …
✓ Remote Robot is reachable
  UI tree: XXXX bytes

LLM: MiniMax-M2.5 @ https://coding-intl.dashscope.aliyuncs.com/v1
LLM_API_KEY: set ✓

Knowledge graph: not yet created (data/knowledge_graph.json)
```

---

## Phase 3: End-to-End Smoke Test

### Task 5: Fix Any Critical Issues Found

**Files:**
- Modify: `src/perception/ui_tree.py` (if XPath issues)
- Modify: `src/agent/graph_agent.py` (if LLM prompt issues)
- Modify: `src/perception/remote_robot.py` (if connection issues)

- [ ] **Step 1: Run a simple test task**

```bash
python3 -m src run --task "In the editor, right-click to open context menu"
```

Watch the output. Expected flow:
```
[iter 1] observe: fetching UI tree...
[iter 1] page: editor_idle — XX elements
[iter 1] reason: calling LLM...
[iter 1] act: right_click(...) — ...
```

- [ ] **Step 2: Identify and fix any failures**

Common issues to check:
- **XPath not found**: Check `ui_tree.py` `_make_xpath()` generates correct XPaths. Add debug logging to see what XPaths are being generated.
- **LLM returns malformed JSON**: Check prompt format in `graph_agent.py`. The model may need stricter JSON instructions.
- **Bridge server errors**: Check Kotlin server console for stack traces.

- [ ] **Step 3: Verify knowledge graph is created**

```bash
cat data/knowledge_graph.json | python3 -m json.tool | head -50
```

Expected: JSON with `"pages"`, `"elements"`, `"transitions"` keys

- [ ] **Step 4: Commit any fixes**

```bash
git add src/
git commit -m "fix: address issues found during smoke test"
```

---

### Task 6: Run First Complete Refactoring Task

**Files:**
- None (execution only)

- [ ] **Step 1: Ensure IntelliJ has target file open**

Open `intellij-plugin/src/main/kotlin/executor/UiExecutor.kt` in IntelliJ

- [ ] **Step 2: Run rename method task**

```bash
python3 -m src run --task "In the file intellij-plugin/src/main/kotlin/executor/UiExecutor.kt, rename the method 'executeRecipe' to 'runRecipe'. Move the caret to 'executeRecipe' and use the Refactor > Rename action."
```

- [ ] **Step 3: Verify the rename succeeded**

```bash
grep "fun runRecipe" intellij-plugin/src/main/kotlin/executor/UiExecutor.kt
```

Expected: Should find the renamed method

- [ ] **Step 4: Reset file for next run**

```bash
git checkout HEAD -- intellij-plugin/src/main/kotlin/executor/UiExecutor.kt
```

---

## Phase 4: Full Evaluation

### Task 7: Run Baseline Evaluation (No Graph)

**Files:**
- None (execution only)

- [ ] **Step 1: Clear any existing graph**

```bash
rm -f data/knowledge_graph.json
```

- [ ] **Step 2: Run single task to verify baseline**

```bash
python3 -m src run --task "rename executeRecipe to runRecipe in intellij-plugin/src/main/kotlin/executor/UiExecutor.kt"
```

Note the iteration count and token usage.

- [ ] **Step 3: Reset file**

```bash
git checkout HEAD -- intellij-plugin/src/main/kotlin/executor/UiExecutor.kt
```

---

### Task 8: Run Learning Evaluation (Graph Accumulates)

**Files:**
- None (execution only)

- [ ] **Step 1: Run the same task 3 times without clearing graph**

```bash
# Run 1
python3 -m src run --task "rename executeRecipe to runRecipe in intellij-plugin/src/main/kotlin/executor/UiExecutor.kt"
git checkout HEAD -- intellij-plugin/src/main/kotlin/executor/UiExecutor.kt
sleep 2

# Run 2
python3 -m src run --task "rename executeRecipe to runRecipe in intellij-plugin/src/main/kotlin/executor/UiExecutor.kt"
git checkout HEAD -- intellij-plugin/src/main/kotlin/executor/UiExecutor.kt
sleep 2

# Run 3
python3 -m src run --task "rename executeRecipe to runRecipe in intellij-plugin/src/main/kotlin/executor/UiExecutor.kt"
git checkout HEAD -- intellij-plugin/src/main/kotlin/executor/UiExecutor.kt
```

- [ ] **Step 2: Compare results**

Look at:
- Iteration count (should decrease as graph learns)
- Token usage (should decrease as graph provides more context)
- Action history (should become more direct)

---

### Task 9: Run Full Evaluation Suite

**Files:**
- Modify: `src/evaluation/tasks.py` (if file paths need adjustment)

- [ ] **Step 1: Verify task definitions are correct**

```bash
python3 -c "from src.evaluation.tasks import TASKS; print('\n'.join([f'{t.name}: {t.file_path}' for t in TASKS]))"
```

Ensure file paths exist:
```bash
ls -la intellij-plugin/src/main/kotlin/executor/UiExecutor.kt
ls -la intellij-plugin/src/main/kotlin/parser/UiModels.kt
ls -la intellij-plugin/src/main/kotlin/model/RecipeStep.kt
```

- [ ] **Step 2: Run full evaluation (3 runs × 3 tasks)**

```bash
rm -f data/knowledge_graph.json data/eval_results.jsonl
python3 -m src eval
```

This will take 10-15 minutes. Watch the output for each task.

- [ ] **Step 3: Review results**

```bash
cat data/eval_results.jsonl | python3 -m json.tool
```

- [ ] **Step 4: Run ablation baseline (clear graph each run)**

```bash
rm -f data/eval_results_baseline.jsonl
python3 -m src eval --clear-graph
```

Compare with `data/eval_results.jsonl` from Step 2.

---

## Phase 5: Analysis & Documentation

### Task 10: Analyze Results and Draw Conclusions

**Files:**
- Create: `docs/evaluation_results.md`

- [ ] **Step 1: Parse evaluation results**

```python
import json

results = []
with open('data/eval_results.jsonl') as f:
    for line in f:
        results.append(json.loads(line))

# Group by task and run
for task in ['rename_method', 'rename_variable', 'rename_class']:
    task_results = [r for r in results if r['task'] == task]
    print(f"\n{task}:")
    for run_num in [1, 2, 3]:
        run = [r for r in task_results if r['run'] == run_num][0]
        print(f"  Run {run_num}: {run['actions']} actions, {run['tokens']} tokens, success={run['success']}")
```

- [ ] **Step 2: Compare with ablation baseline**

```python
baseline = []
with open('data/eval_results_baseline.jsonl') as f:
    for line in f:
        baseline.append(json.loads(line))

# Average across tasks
graph_avg = sum(r['actions'] for r in results) / len(results)
baseline_avg = sum(r['actions'] for r in baseline) / len(baseline)

print(f"\nAverage actions per task:")
print(f"  With graph learning: {graph_avg:.1f}")
print(f"  Baseline (no graph): {baseline_avg:.1f}")
print(f"  Improvement: {(1 - graph_avg/baseline_avg) * 100:.1f}%")
```

- [ ] **Step 3: Document findings**

Create `docs/evaluation_results.md`:

```markdown
# AppAgentX Graph-Based Approach Evaluation Results

## Method
- 3 refactoring tasks × 3 runs each
- Run 1: cold start (empty graph)
- Run 2-3: graph accumulates knowledge
- Baseline: graph cleared before each run

## Results

### Task: rename_method
| Run | Actions | Tokens | Success |
|-----|---------|--------|--------|
| 1   | ...     | ...    | ...    |
| 2   | ...     | ...    | ...    |
| 3   | ...     | ...    | ...    |

[... repeat for other tasks ...]

## Conclusions
[Fill in based on observed data]
- Does the graph reduce action count over runs?
- Does token usage decrease?
- Success rate comparison?

## Next Steps
[Based on what worked/didn't work]
```

---

### Task 11: Final Verification and Cleanup

**Files:**
- Modify: `CLAUDE.md` (update with learnings)

- [ ] **Step 1: Verify all test files are reset**

```bash
git status
```

Should show no modified files in `intellij-plugin/src/main/kotlin/`

- [ ] **Step 2: Update CLAUDE.md with lessons learned**

Add to `CLAUDE.md` under "Known Issues / Watch Out For":

```markdown
6. **Evaluation findings** — After running the evaluation:
   - [Finding 1 from results]
   - [Finding 2 from results]
   - [What actually worked vs what we expected]
```

- [ ] **Step 3: Commit results and documentation**

```bash
git add data/ docs/
git commit -m "docs: add evaluation results and findings"
```

- [ ] **Step 4: Create summary of what's working**

```bash
cat <<'EOF'
=== Graph-Based Approach Implementation Summary ===

✓ Bridge server: Running on port 7070
✓ Python agent: Can observe and act on IntelliJ UI
✓ Knowledge graph: Persists to data/knowledge_graph.json
✓ Evaluation suite: 3 tasks × 3 runs

Key findings:
[Fill in from Task 10]

Next improvements:
[Based on what didn't work]
EOF
```
