# Tool Discovery Agent

An agentic system that discovers and interacts with IntelliJ IDEA development tools through GUI perception rather than APIs.

## Overview

This project implements an agent that can:

- **Discover Tools**: Automatically identify and catalog development tools available in IntelliJ IDEA through visual inspection
- **Plan Refactorings**: Analyze codebases and plan refactoring operations using discovered tools
- **Execute Actions**: Interact with IDE UI elements to perform automated refactoring tasks

The agent leverages JetBrains' Starter + Driver framework for IntelliJ UI testing, enabling interaction with IDE components without relying on internal APIs. Built with LangChain and LangGraph for agentic workflows.

### Components

#### Agent Layer (`src/agent/`)

- **DiscoveryAgent**: LangGraph agent that explores IDE menus, tool windows, and context actions to discover available development tools
- **PlannerAgent**: LangGraph agent that analyzes codebases and creates refactoring plans using discovered tools
- **ExecutorAgent**: LangGraph agent that executes planned actions through the interaction layer

#### Perception Layer (`src/perception/`)

- **UITreeParser**: Parses JetBrains Driver UI tree to understand IDE component structure
- **ScreenshotCapture**: Provides visual fallback for UI elements not accessible via tree

#### Interaction Layer (`src/interaction/`)

- **ActionHandler**: Wraps JetBrains Driver actions (click, type, keystroke) with high-level API

#### API Layer (`src/api.py`)

- **FastAPI Server**: HTTP and WebSocket endpoints for IntelliJ plugin communication
- **REST Endpoints**: `/discover`, `/tools`, `/plan`, `/execute`
- **WebSocket**: Real-time communication at `/ws`

## Setup

### Prerequisites

- Python 3.11 or higher
- [uv](https://docs.astral.sh/uv/) package manager (recommended) or pip
- Docker (for containerized deployment)
- IntelliJ IDEA (Community or Ultimate)
- JetBrains Driver framework (to be integrated)

### Quick Start with Docker

```bash
# Build and run the agent service
docker-compose up -d

# Check agent status
curl http://localhost:8080/health
```

### Installation with uv

```bash
# Clone the repository
git clone https://github.com/your-org/tool-discovery-agent.git
cd tool-discovery-agent

# Create virtual environment and install dependencies
uv venv
source .venv/bin/activate  # On Windows: .venv\Scripts\activate

# Install dependencies
uv pip install -e ".[dev]"
```

### Alternative: Installation with pip

```bash
# Clone the repository
git clone https://github.com/your-org/tool-discovery-agent.git
cd tool-discovery-agent

# Create virtual environment
python -m venv .venv
source .venv/bin/activate  # On Windows: .venv\Scripts\activate

# Install dependencies
pip install -e ".[dev]"
```

### Configuration

Copy the default configuration and customize:

```bash
cp configs/default.yaml configs/local.yaml
```

Edit `configs/local.yaml` with your IDE connection settings.

## IntelliJ Plugin

The IntelliJ plugin provides a UI for interacting with the agent service.

### Building the Plugin

```bash
cd intellij-plugin

# Build with Gradle
./gradlew buildPlugin

# The plugin will be in build/distributions/
```

### Installing the Plugin

1. Open IntelliJ IDEA
2. Go to Settings → Plugins → ⚙️ → Install Plugin from Disk
3. Select the built plugin ZIP file

### Plugin Development

```bash
# Run in development mode
gradle runIde
```
## Development

### Code Quality

```bash
# Run linter
uv run ruff check src/

# Run type checker
uv run mypy src/

# Format code
uv run ruff format src/
```

## License

MIT License - see [LICENSE](LICENSE) for details.

## References

- [JetBrains Driver Framework](https://github.com/JetBrains/driver)
- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/)
- [LangChain Documentation](https://python.langchain.com/)
- [LangGraph Documentation](https://langchain-ai.github.io/langgraph/)
