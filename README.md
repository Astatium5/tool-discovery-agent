# Tool Discovery Agent

An agentic IntelliJ IDEA plugin that discovers and interacts with IDE tools through GUI perception rather than APIs.

## Overview

This project implements a pure Kotlin agent that can:

- **Discover Tools**: Automatically identify and catalog development tools available in IntelliJ IDEA through visual inspection
- **Plan Refactorings**: Analyze codebases and plan refactoring operations using discovered tools
- **Execute Actions**: Interact with IDE UI elements to perform automated refactoring tasks

The agent leverages JetBrains' Remote Robot framework for IntelliJ UI testing, enabling interaction with IDE components without relying on internal APIs.

## Architecture

```
IntelliJ Plugin (Pure Kotlin)
├── Parser       - UI tree parsing from JetBrains HTML tree
├── Observer     - UI state monitoring
├── Agent        - UiAgent main loop (observe → reason → act)
├── Reasoner     - LLM-based reasoning (LLMReasoner)
├── Executor     - Action execution via Remote Robot
├── Action       - Action generation from LLM output
├── Profile      - Application profile & component roles
├── Recipe       - Verified recipe management
├── Model        - Data models (RecipeStep, etc.)
├── LLM          - LLM client for reasoning
└── Formatter    - UI tree formatting
```

## Prerequisites

- JDK 17+
- IntelliJ IDEA 2023.2+
- Kotlin 1.9+

## Building

```bash
./gradlew buildPlugin
```

The plugin ZIP will be in `build/distributions/`.

## Development

```bash
./gradlew runIde        # Launch dev IDE
./gradlew ktlintCheck   # Lint
./gradlew ktlintFormat  # Auto-fix formatting
./gradlew test          # Run tests
```

## Installing

1. Open IntelliJ IDEA
2. Settings → Plugins → ⚙️ → Install Plugin from Disk
3. Select the built plugin ZIP from `build/distributions/`

## License

MIT License - see [LICENSE](LICENSE) for details.

## References

- [JetBrains Remote Robot](https://github.com/JetBrains/intellij-ui-test-robot)
- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/)
- [langchain4j](https://github.com/langchain4j/langchain4j)
- [langgraph4j](https://github.com/langgraph4j/langgraph4j)
