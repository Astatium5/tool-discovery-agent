# AGENTS.md - Guidelines for Agentic Coding Agents

## Project Overview

Pure Kotlin IntelliJ IDEA plugin for IDE tool discovery via GUI perception.

---

## Commands

```bash
./gradlew buildPlugin          # Build
./gradlew ktlintCheck          # Lint
./gradlew ktlintFormat         # Auto-fix
./gradlew test                 # Tests
./gradlew runIde               # Dev mode
./gradlew verifyPlugin         # Compatibility
```

---

## Style

- **Imports**: Alphabetically sorted, no wildcards
- **Naming**: Classes=PascalCase, functions/properties=camelCase, data classes=PascalCase
- **Class order**: Package → Imports → KDoc → Class → Companion → Init → Public → Private → Nested
- **Annotations**: Separate line above: `@Service(Service.Level.APP)`
- **Coroutines**: `CoroutineScope(Dispatchers.Main + SupervisorJob())` for UI, `Dispatchers.IO` for network
- **Result**: Use `Result<T>` for fallible operations
- **IntelliJ patterns**: `ApplicationManager.getApplication().getService()`, `SwingUtilities.invokeLater`, `JBLabel/JBList/JBScrollPane`

---

## Project Structure

```
src/main/kotlin/
├── agent/           # UiAgent main loop
├── execution/       # Action execution & generation
│   ├── InProcessGuiExecutor.kt   # Swing-based GUI interactions (preferred)
│   ├── ComponentTreeWalker.kt    # Recursive Swing component search
│   ├── Edt.kt                    # EDT dispatch helpers
│   ├── UiExecutor.kt             # Legacy Remote Robot executor (fallback)
│   ├── ActionGenerator.kt        # LLM-driven action dispatch
│   └── LaunchAgentAction.kt      # Plugin entry point
├── llm/             # LLM client & reasoning
├── model/           # Data models
├── perception/      # UI tree formatting
│   └── parser/      # UI tree parsing (still uses Remote Robot for HTML)
├── profile/         # Application profiles
└── recipe/          # Verified recipes
```

---

## GUI Interaction Architecture

The plugin uses a **two-layer** approach for IDE interaction:

1. **Perception** (read-only) — UI tree fetching remains HTTP-based via
   Remote Robot's HTML endpoint. See `perception/parser/HtmlUiTreeProvider.kt`.
2. **Action** (write) — All GUI actions (menu clicks, button clicks, text
   input, focus changes) are executed **in-process** by manipulating Swing
   components directly. See `execution/InProcessGuiExecutor.kt`.

**Do NOT add new methods to `UiExecutor.kt` that use `robot.find`,
`robot.keyboard`, or `component.callJs`.** New code must go through
`InProcessGuiExecutor` and `ComponentTreeWalker` instead. The legacy methods
in `UiExecutor.kt` exist only as a fallback for environments where the
in-process approach cannot reach a component.

---

## Dependencies

IntelliJ Platform SDK 2023.2, Remote Robot (perception only), JSoup, Gson, Kotlinx Coroutines, ktlint, langchain4j, langgraph4j

---

## Pre-commit

`ktlintCheck` → `buildPlugin` → `verifyPlugin`
