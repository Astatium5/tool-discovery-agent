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
├── llm/             # LLM client & reasoning
├── model/           # Data models
├── perception/      # UI tree formatting
│   └── parser/      # UI tree parsing
├── profile/         # Application profiles
└── recipe/          # Verified recipes
```

---

## Dependencies

IntelliJ Platform SDK 2023.2, Remote Robot, JSoup, Gson, Kotlinx Coroutines, ktlint, langchain4j, langgraph4j

---

## Pre-commit

`ktlintCheck` → `buildPlugin` → `verifyPlugin`
