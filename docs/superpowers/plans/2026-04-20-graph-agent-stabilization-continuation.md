# GraphAgent Stabilization Continuation Guide

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to continue this work. Treat this file as the primary handoff document. Keep checkbox state current as you progress.

**Goal:** Continue stabilizing `GraphAgent` toward reliable code refactorings by extending the now-working local-variable rename workflow in small, validated phases.

**Current Branch:** `graph-agent-stabilization`

**Current Head:** `1a128e9` (`feat: add constrained graph-agent rename policy`)

**Working Principle:** Do not widen autonomy until the current narrower phase is reliable and debuggable.

---

## Why This Approach Works

The repo changed too much at once during the graph migration. The failure mode was not “one bug”; it was “too many moving parts with poor isolation.” The successful strategy has been:

1. Freeze the product target to one concrete task.
2. Decompose the system into seams.
3. Make each seam independently testable.
4. Add observability and artifacts before broadening scope.
5. Only replace deterministic control with real agent behavior after the seam below it is stable.

This is not a generic “agent benchmark” effort. It is a staged reliability program.

The canonical task remains:

1. Open a known file containing a known local variable.
2. Put the caret on the variable.
3. Right-click to open the context menu.
4. Choose `Rename`.
5. Type the replacement name.
6. Confirm with `Enter`.
7. Verify source changes.

Everything so far has been organized around making this one flow trustworthy before moving to anything broader.

---

## Operating Rules For The Next Agent

Follow these rules exactly:

1. Do not jump to “full free-form agent” behavior.
2. Keep one canonical task in focus until the current phase is repeatably green.
3. When something fails, identify which seam failed:
   - harness
   - perception
   - executor
   - decision policy
   - LLM response parsing
   - orchestration
   - completion detection
4. Add or update tests before changing runtime behavior.
5. Prefer constrained decision policies over unrestricted LLM action sets.
6. Preserve HTML artifacts and trace evidence whenever live behavior changes.
7. Do not mutate shared fixtures in-place during live IDE tests.
8. If a live test depends on editor state, create a fresh temporary fixture path for that test.
9. Keep `UiAgent` as reference-only. Do not route `GraphAgent` through it.
10. If a wider phase is tempting, first ask: what exact narrower proof is still missing?

---

## What Has Been Completed

### Stage Ladder Progress

- [x] Stage 0: Harness sanity gate
- [x] Stage 1: Graph-core baseline preserved
- [x] Stage 2: OpenTelemetry infrastructure added
- [x] Stage 3: Fixture-driven parser/perception coverage
- [x] Stage 4: Scripted rename executor seam
- [x] Stage 5: Canonical GraphAgent orchestration E2E with deterministic decision engine
- [x] Stage 6: Constrained real LLM-backed rename flow with policy fence

### Major Outcomes

- [x] Live IDE harness failures are separated from agent failures via `BaseTest`
- [x] GraphAgent writes HTML artifacts for each observed iteration
- [x] Canonical deterministic GraphAgent rename E2E passes
- [x] Scripted rename executor passes against the live IDE
- [x] Parser correctly classifies IntelliJ rename lookup popup as `inline_widget`
- [x] Tests now use fresh temporary fixture copies instead of mutating a shared open file
- [x] `UiExecutor` can open absolute filesystem paths in the IntelliJ instance even when the IDE project is a different repo
- [x] Real LLM-backed GraphAgent can complete the rename task while constrained to a rename-only action policy
- [x] LLM prompt is policy-aware, not only post-validated
- [x] LLM fallback parser understands MiniMax-style tool-call output
- [x] LLM config loading works from worktree-based test runs and ignores bogus Gradle placeholder environment values

---

## Commits That Matter

These are the important commits on this branch, in order of the stabilization ladder:

- `99da5f4` `feat: add graph telemetry infrastructure`
- `5a4c240` `fix: tighten graph telemetry semantics`
- `1c9b6d9` `Add UI tree parser fixture coverage`
- `88c4f28` `test: add rename script executor coverage`
- `d5810e0` `fix: harden scripted rename executor`
- `6db1748` `Refactor graph agent orchestration seams`
- `6b8c1ac` `fix: preserve graph baselines across orchestration loops`
- `bbf4e06` `Add canonical graph-agent rename e2e trace`
- `7e10e9e` `test: align smoke coverage with graph artifacts`
- `b3bb430` `fix: stabilize live graph-agent rename validation`
- `1a128e9` `feat: add constrained graph-agent rename policy`

When picking up the work, review at least the last two commits first.

---

## Files That Now Define The Current Approach

### Runtime

- `intellij-plugin/src/main/kotlin/graph/GraphAgent.kt`
- `intellij-plugin/src/main/kotlin/graph/LlmDecisionEngine.kt`
- `intellij-plugin/src/main/kotlin/graph/PolicyConstrainedDecisionEngine.kt`
- `intellij-plugin/src/main/kotlin/parser/UiTreeParser.kt`
- `intellij-plugin/src/main/kotlin/executor/UiExecutor.kt`
- `intellij-plugin/src/main/kotlin/llm/LlmClient.kt`

### Tests

- `intellij-plugin/src/test/kotlin/test/HarnessSanityTest.kt`
- `intellij-plugin/src/test/kotlin/graph/GraphActionExecutorRenameScriptTest.kt`
- `intellij-plugin/src/test/kotlin/test/GraphAgentRenameE2ETest.kt`
- `intellij-plugin/src/test/kotlin/test/GraphAgentRenameConstrainedLlmE2ETest.kt`
- `intellij-plugin/src/test/kotlin/graph/PolicyConstrainedDecisionEngineTest.kt`
- `intellij-plugin/src/test/kotlin/graph/LlmDecisionEnginePolicyTest.kt`

### Design/Plan Inputs

- `docs/superpowers/specs/2026-04-18-graph-agent-stabilization-design.md`
- `docs/superpowers/plans/2026-04-18-graph-agent-stabilization.md`

This continuation file is intended to supersede the need to reconstruct intent from the full chat.

---

## Verified Passing Tests

These passed together in the live IDE-backed environment:

```bash
cd intellij-plugin
./gradlew test \
  --tests graph.PolicyConstrainedDecisionEngineTest \
  --tests graph.LlmDecisionEnginePolicyTest \
  --tests graph.GraphActionExecutorRenameScriptTest \
  --tests test.GraphAgentRenameE2ETest \
  --tests test.GraphAgentRenameConstrainedLlmE2ETest \
  --rerun-tasks
```

Meaning of each:

- `graph.PolicyConstrainedDecisionEngineTest`
  Confirms the policy wrapper injects rename-only instructions and rejects off-policy actions.

- `graph.LlmDecisionEnginePolicyTest`
  Confirms `LlmDecisionEngine` can build a policy-aware prompt and parse MiniMax-style tool-call outputs.

- `graph.GraphActionExecutorRenameScriptTest`
  Confirms the live executor seam can complete rename deterministically without LLM reasoning.

- `test.GraphAgentRenameE2ETest`
  Confirms the full GraphAgent loop works with deterministic canonical decision logic.

- `test.GraphAgentRenameConstrainedLlmE2ETest`
  Confirms the real LLM-backed GraphAgent can complete the rename flow while fenced to the rename-only policy.

---

## Essence Of The Current Architecture

There are now three meaningful control levels for the same rename task:

### 1. Scripted Executor

`GraphActionExecutorRenameScriptTest`

Purpose:
- prove the UI action seam itself works live

Properties:
- no LLM
- no free-form policy
- deterministic sequence

### 2. Deterministic GraphAgent

`GraphAgentRenameE2ETest`

Purpose:
- prove the real GraphAgent loop works over live perception, graph updates, and executor behavior

Properties:
- real GraphAgent orchestration
- deterministic test-only decision engine
- still strongly controlled

### 3. Constrained LLM GraphAgent

`GraphAgentRenameConstrainedLlmE2ETest`

Purpose:
- prove the real decision layer can drive the same task without escaping the allowed rename sandbox

Properties:
- real GraphAgent orchestration
- real `LlmDecisionEngine`
- policy-aware prompt
- policy validator wrapper
- still not unrestricted autonomy

This is the current frontier. The next work is about reliability and controlled broadening, not about skipping to unrestricted tool use.

---

## Remaining Plan

### Phase A: Reliability On The Same Task

The next immediate objective is not a new feature. It is repeated success.

- [x] Add a repeat-run harness for `test.GraphAgentRenameConstrainedLlmE2ETest`
- [x] Run the constrained LLM rename flow at least 10 times
- [x] Capture per-run outcome:
  - pass/fail
  - iteration count
  - action history
  - failure seam
  - artifact directory
- [x] Write a small reliability summary doc or test report under `docs/superpowers/plans` or `build/reports`
- [x] If flakiness appears, classify it before fixing anything

Latest result on 2026-04-20:
- `10/10` constrained LLM rename runs passed
- every run completed in `5` iterations with failure seam `none`
- report written to `intellij-plugin/build/reports/graph-agent/reliability/rename-constrained-llm-reliability.md`
- JSON detail written to `intellij-plugin/build/reports/graph-agent/reliability/rename-constrained-llm-reliability.json`
- total token count across all runs: `24391` (`~2439` per run)

Success criterion:
- the constrained LLM rename path should pass repeatedly without widening policy

### Phase B: Rename Fixture Expansion

Once repeated runs on the base fixture are stable, broaden the fixture space while keeping the same policy fence.

- [x] Add fixture: multiple local variables in the same function
- [x] Add fixture: variables with similar names (`originalName`, `originalNameSuffix`)
- [x] Add fixture: usage-site pattern that could confuse completion/rename verification
- [x] Add fixture: file with extra editor noise but same local rename task
- [x] Add deterministic GraphAgent E2Es for each fixture
- [x] Add constrained LLM E2Es for each fixture

Latest result on 2026-04-20:
- deterministic GraphAgent rename passed across `4/4` Phase B fixtures
- constrained LLM rename passed across `4/4` Phase B fixtures under the same rename-only policy fence
- fixture-aware verification now checks symbol-specific before/after expectations instead of assuming every `originalName` substring disappears globally
- regression batch passed for canonical deterministic, canonical constrained LLM, `GRAPH_AGENT_RELIABILITY_RUNS=2` reliability, and both new Phase B fixture matrices

Success criterion:
- rename remains reliable across a small family of structurally different local-variable cases

### Phase C: Strengthen Policy And Decision Observability

- [x] Add explicit telemetry around LLM invocation inside `LlmDecisionEngine`
- [x] Consider adding spans such as:
  - `decide.build_prompt`
  - `decide.llm_call`
  - `decide.parse_response`
- [x] Persist the final effective policy instructions per run as an artifact
- [x] Persist the raw LLM response per run where safe and practical
- [x] Add assertions for those spans/artifacts in the constrained LLM E2E

Latest result on 2026-04-20:
- `LlmDecisionEngine` now emits dedicated `decide.build_prompt`, `decide.llm_call`, and `decide.parse_response` spans under the existing `decide.next_action` span
- constrained runs now persist per-decision `decision-XX-effective-policy.txt` and `decision-XX-raw-response.txt` artifacts alongside the existing observed HTML snapshots
- `GraphAgentRenameConstrainedLlmE2ETest` now asserts the dedicated spans and artifact files in the live IDE-backed constrained rename flow
- verification passed for `graph.LlmDecisionEnginePolicyTest` in `8s`, `test.GraphAgentRenameConstrainedLlmE2ETest --rerun-tasks` in `1m 19s`, and `test.GraphAgentRenameConstrainedLlmFixtureMatrixE2ETest --rerun-tasks` in `4m 8s`

Success criterion:
- every constrained LLM failure can be attributed to prompt, model output, parse, policy validation, or execution

### Phase D: Controlled Widening Of The Rename Policy

Only do this after Phase A and B are green.

- [x] Decide whether the next widening is:
  - more rename fixtures, or
  - a slightly broader action surface
- [x] If widening action surface, do it minimally
- [ ] Good candidates:
  - allow one additional preparatory action only if justified by live evidence
  - allow very limited fallback action sequences for popup variants
- [x] Keep the policy wrapper in place
- [x] Update tests first, then runtime

Latest result on 2026-04-20:
- live evidence justified a minimal action-surface widening instead of more rename-only fixtures first
- rename-only fixture coverage now also includes a `captured-by-local-function` case to keep widening pressure on symbol-update semantics
- deterministic Stage 5 rename runs exposed a one-loop `editor_idle` gap immediately after `click_menu_item(Rename)` on existing Phase B fixtures before the inline rename widget appeared
- constrained LLM runs exposed two additional attributable seams:
  - missing completion hint after `press_key(ENTER)` returned the editor to `editor_idle`, leading the model to ask for an unnecessary `observe`
  - malformed JSON responses with trailing wrapper noise, which fell into text fallback and were misclassified
- the rename policy now allows `observe` only in one context: immediately after `click_menu_item` when the page is still `editor_idle`
- the policy prompt now tells the model to `complete` immediately once `press_key(ENTER)` has returned the editor to idle
- `LlmDecisionEngine` now extracts the first balanced JSON object from raw responses, recovering valid decisions even when the model appends trailing wrapper characters
- verification passed for:
  - `./gradlew test --tests graph.PolicyConstrainedDecisionEngineTest --tests graph.LlmDecisionEnginePolicyTest` in `6s`
  - `./gradlew test --tests test.GraphAgentRenameFixtureMatrixE2ETest --rerun-tasks` in `2m 28s`
  - `./gradlew test --tests test.GraphAgentRenameConstrainedLlmFixtureMatrixE2ETest --rerun-tasks` in `5m 58s`
  - `./gradlew test --tests test.GraphAgentRenameConstrainedLlmE2ETest --rerun-tasks` in `1m 25s`

Success criterion:
- autonomy increases without losing failure localization

### Phase E: Next Refactoring Task

Do not start this until rename is reliable across multiple fixtures and repeated runs.

Candidate next tasks:

- [ ] rename a method via context menu
- [ ] rename a parameter
- [ ] introduce variable
- [ ] extract method

Recommendation:
- rename a method is the most natural next step after local-variable rename

Success criterion:
- the exact same staged approach is reused, not reinvented

---

## Concrete TODO List

This section is meant to be updated directly by future agents.

### Stability

- [x] Stabilize harness gate
- [x] Stabilize parser for rename lookup state
- [x] Stabilize scripted rename executor
- [x] Stabilize deterministic GraphAgent rename E2E
- [x] Stabilize constrained LLM rename E2E
- [x] Measure repeat-run reliability for constrained LLM rename
- [x] Document repeat-run reliability results

### Fixture Coverage

- [x] Base rename fixture
- [x] Multi-local-variable fixture
- [x] Similar-name collision fixture
- [x] Noisy editor-state fixture
- [x] Broader rename verification fixture set

### Observability

- [x] Root and iteration telemetry
- [x] HTML artifact capture
- [x] Dedicated `decide.build_prompt` span
- [x] Dedicated `decide.llm_call` span
- [x] Dedicated `decide.parse_response` span
- [x] Dedicated raw-response artifact
- [x] Dedicated effective-policy artifact

### Policy Work

- [x] Policy validator wrapper
- [x] Policy-aware LLM prompt
- [x] MiniMax tool-call parsing
- [x] Repeat-run evidence that the current rename policy is stable
- [x] Decide whether any minimal policy widening is necessary

### Expansion

- [x] Add more rename fixtures
- [x] Keep rename policy fence while broadening fixtures
- [ ] Choose next refactoring target
- [ ] Write the next-phase design/plan only after rename reliability is measured

---

## Known Pitfalls

These already happened. Do not repeat them.

1. Do not mutate the shared canonical fixture file while IntelliJ has it open.
   This caused file-cache conflicts and poisoned the live editor state.

2. Do not assume the IDE project is this repo.
   The live `runIdeForUiTests` session may have a different project open, such as `questdb`.
   Always prefer absolute file opening for test fixtures.

3. Do not assume IntelliJ exposes `Rename` as a direct first-level context menu item.
   In the observed environment it appears under `Refactor -> Rename`.

4. Do not assume the rename UI looks like a plain text field.
   IntelliJ may expose it as an inline template plus lookup popup.

5. Do not treat LLM failures as agent-design failures until config and parsing are ruled out.
   This already surfaced once via Gradle placeholder environment values and once via MiniMax tool-call output.

---

## How A New Agent Should Start

If you are a fresh agent picking this up in a new chat, do this in order:

1. Read this file completely.
2. Confirm branch/worktree state:

```bash
git status
git log --oneline -5
```

3. If the live IDE harness is required, ensure `runIdeForUiTests` is running.
4. Re-run the current verification batch:

```bash
cd intellij-plugin
./gradlew test \
  --tests graph.PolicyConstrainedDecisionEngineTest \
  --tests graph.LlmDecisionEnginePolicyTest \
  --tests graph.GraphActionExecutorRenameScriptTest \
  --tests test.GraphAgentRenameE2ETest \
  --tests test.GraphAgentRenameConstrainedLlmE2ETest \
  --rerun-tasks
```

5. If green, start Phase A: repeated constrained LLM rename runs.
6. If not green, do not widen scope. Fix the failing seam only.

---

## Current Recommendation

The next best use of time is:

1. add repeated constrained LLM rename runs
2. measure reliability
3. add 2-3 more rename fixtures
4. keep the same constrained policy

Do not move to the next refactoring until that is done.
