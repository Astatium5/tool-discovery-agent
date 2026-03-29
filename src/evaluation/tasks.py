"""
Evaluation task definitions for the AppAgentX graph agent.

Each task mirrors Raihan's Kotlin test cases so results are comparable.
Tasks use files from the tool-discovery-agent project itself (open in IntelliJ).

IMPORTANT: Before running evaluation, ensure IntelliJ has one of these files
open in the editor. The agent will navigate to it via OpenFile if needed.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Callable


@dataclass
class EvalTask:
    name: str
    intent: str                         # natural language instruction for the agent
    file_path: str                      # path relative to project root (for verification)
    expected_change: str                # text that should appear in file after success
    unexpected_before: str = ""         # text that should NOT be there before (sanity check)
    verification_fn: Callable[[str], bool] | None = None  # custom verify function

    def verify(self, file_content: str) -> bool:
        if self.verification_fn:
            return self.verification_fn(file_content)
        return self.expected_change in file_content


# ── Task definitions ──────────────────────────────────────────────────────────
# These use real files in the intellij-plugin Kotlin source.
# Adjust file_path and symbol names to match what you have open in IntelliJ.

TASKS: list[EvalTask] = [
    EvalTask(
        name="rename_method",
        intent=(
            "In the file intellij-plugin/src/main/kotlin/executor/UiExecutor.kt, "
            "rename the method 'executeRecipe' to 'runRecipe'. "
            "Move the caret to 'executeRecipe' and use the Refactor > Rename action."
        ),
        file_path="intellij-plugin/src/main/kotlin/executor/UiExecutor.kt",
        expected_change="fun runRecipe(",
        unexpected_before="fun runRecipe(",
    ),
    EvalTask(
        name="rename_variable",
        intent=(
            "In the file intellij-plugin/src/main/kotlin/parser/UiModels.kt, "
            "rename the property 'accessibleName' to 'a11yName' in the UiComponent data class. "
            "Use Refactor > Rename."
        ),
        file_path="intellij-plugin/src/main/kotlin/parser/UiModels.kt",
        expected_change="a11yName",
        unexpected_before="a11yName",
    ),
    EvalTask(
        name="rename_class",
        intent=(
            "In the file intellij-plugin/src/main/kotlin/model/RecipeStep.kt, "
            "rename the sealed class 'RecipeStep' to 'ActionStep'. "
            "Use Refactor > Rename."
        ),
        file_path="intellij-plugin/src/main/kotlin/model/RecipeStep.kt",
        expected_change="sealed class ActionStep",
        unexpected_before="sealed class ActionStep",
    ),
]


def get_task(name: str) -> EvalTask | None:
    return next((t for t in TASKS if t.name == name), None)
