# Graph-Based UI Automation Agent

Minimal Kotlin implementation testing whether maintaining a knowledge graph of UI states improves automation performance.

## Architecture

- **GraphAgent**: LangGraph-style agent with observe→reason→act→update_graph loop
- **KnowledgeGraph**: Tracks pages, elements, transitions, shortcuts
- **Persistence**: Graph saved to `data/knowledge_graph.json`

## Hypothesis

On first run (cold start), agent behaves like flat UI tree approach. On subsequent runs, graph provides learned context → fewer iterations, fewer tokens.

## Implementation Status

**✅ Completed:**
- Pure-Kotlin AppAgentX implementation (~1,200 lines)
- Graph data structures with kotlinx.serialization
- PageState with UI page inference logic
- LangGraph-style observe-reason-act-update_graph loop
- LLM integration (OpenAI-compatible APIs)
- CLI entry point with environment configuration

**⚠️ Known Limitations:**
- Remote Robot API compatibility issue (v0.11.23) blocks full end-to-end testing
- Component discovery fails with serialization errors
- Cannot measure hypothesis without working UI element discovery

## Usage

### Prerequisites
- IntelliJ IDEA running with Remote Robot plugin on port 8082
- Kotlin JVM 17
- LLM API credentials (OpenAI-compatible)

### Environment Setup
```bash
export ROBOT_URL=http://localhost:8082
export LLM_BASE_URL=https://coding-intl.dashscope.aliyuncs.com/v1
export LLM_MODEL=MiniMax-M2.5
export LLM_API_KEY=sk-...
```

### Running the Agent
```bash
cd intellij-plugin
./gradlew runGraphAgent --args="your task here"
```

### Example
```bash
./gradlew runGraphAgent --args="In the editor, right-click to open context menu"
```

## Results

### Smoke Test (✓ Passed)
- **Connection**: Successfully connected to Remote Robot
- **UI Observation**: Parsed HTML into PageState with 36 elements
- **LLM Integration**: Successfully reasoned with MiniMax-M2.5
- **Action Execution**: Executed keyboard shortcuts via AppleScript
- **Graph Persistence**: Knowledge graph saved to JSON
- **Metrics**: 2 pages discovered, 3-6 iterations per task, ~500-1000 tokens

### Refactoring Test (✗ Blocked)
- **Task**: Rename method `executeRecipe` to `runRecipe`
- **Result**: Failed due to Remote Robot API incompatibility
- **Issue**: "Unable to create converter for FindComponentsResponse"
- **Iterations**: 30 (max limit), 0 actions succeeded
- **Root Cause**: Remote Robot v0.11.23 serialization incompatibility

See [docs/graph_agent_evaluation.md](docs/graph_agent_evaluation.md) for full details.

## Key Files

- `intellij-plugin/src/main/kotlin/graph/GraphAgent.kt` - Main agent loop
- `intellij-plugin/src/main/kotlin/graph/KnowledgeGraph.kt` - Graph data structure
- `intellij-plugin/src/main/kotlin/graph/PageState.kt` - UI state representation
- `intellij-plugin/src/main/kotlin/main/Main.kt` - Entry point
- `intellij-plugin/src/main/kotlin/graph/GraphTypes.kt` - Data types

## Next Steps

To enable full hypothesis testing, choose one of:

1. **Fix Remote Robot Integration** (Recommended)
   - Investigate Remote Robot API compatibility
   - Consider upgrading to different version
   - Test component discovery with simple script first

2. **Alternative: Direct IntelliJ API**
   - Use IntelliJ's internal APIs directly
   - Avoid Remote Robot network serialization issues
   - More complex but more reliable

3. **Keyboard-Only Approach**
   - Focus on keyboard shortcuts (which work)
   - Learn keyboard-based workflows instead of UI components
   - Limited but functional subset

## Technical Details

### Graph Learning
- **PageNode**: Represents UI state (editor_idle, context_menu, dialog_rename)
- **ElementNode**: Interactive components with XPath references
- **Transition**: Page A → Page B via element + action
- **Shortcut**: Learned multi-step sequences

### LLM Integration
- **Structured Decisions**: JSON responses with reasoning + action + params
- **Graph Context**: Agent receives learned transitions/shortcuts as context
- **Token Tracking**: Monitors LLM usage for efficiency metrics

### Agent Loop
```
observe() → Fetch UI tree, parse to PageState
reason() → Call LLM with graph context + history
act() → Execute action (click, type, press_key)
update_graph() → Record pages/elements/transitions
check_complete() → Return success/failure or continue
```

## Comparison to Flat Approach

**Original Hypothesis**: Graph-based approach should outperform flat UI tree approach on:
- **Iteration count**: Lower as graph learns shortcuts
- **Token usage**: Lower as graph provides compact context
- **Success rate**: Higher as graph avoids exploration

**Current Status**: Cannot be measured due to Remote Robot API blocker. Both approaches would fail at component discovery stage.

## Development

### Build
```bash
cd intellij-plugin
./gradlew compileKotlin
```

### Test
```bash
./gradlew test  # Requires running IntelliJ with Remote Robot
```

### Lint
```bash
./gradlew ktlintCheck  # Some violations in existing codebase
```

## Contributors

- **Original Agent**: Raihan (flat UI tree approach)
- **Graph Implementation**: Sudhanva (AppAgentX graph approach)

## License

Same as parent project.
