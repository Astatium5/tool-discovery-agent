"""
Evaluation runner for the AppAgentX graph agent.

Runs tasks N times and records: success, actions taken, tokens used, graph size.
Results land in data/eval_results.jsonl (one JSON object per run).

Run 1 = no graph knowledge (cold start, like Raihan's flat approach).
Run 2+ = graph accumulates — measures if the approach actually improves.
"""

from __future__ import annotations

import json
import os
import time
from datetime import datetime
from pathlib import Path

from rich.console import Console
from rich.table import Table

from src.agent.graph_agent import GraphAgent
from src.evaluation.tasks import TASKS, EvalTask
from src.perception.remote_robot import RemoteRobotClient

console = Console()


def _read_file_from_intellij(project_root: str, relative_path: str) -> str:
    """Try to read the file locally (works since this is the same repo)."""
    full = Path(project_root) / relative_path
    if full.exists():
        return full.read_text()
    return ""


def _verify_task(task: EvalTask, project_root: str) -> bool:
    content = _read_file_from_intellij(project_root, task.file_path)
    if not content:
        console.print(f"  [yellow]⚠ Cannot read {task.file_path} locally for verification[/yellow]")
        return False
    return task.verify(content)


def _reset_task(task: EvalTask, project_root: str) -> None:
    """Revert file to git HEAD so each run starts clean."""
    import subprocess
    full = Path(project_root) / task.file_path
    if full.exists():
        result = subprocess.run(
            ["git", "checkout", "HEAD", "--", str(full)],
            capture_output=True,
            cwd=project_root,
        )
        if result.returncode != 0:
            console.print(f"  [yellow]⚠ git checkout failed for {task.file_path}[/yellow]")


def run_evaluation(
    base_url: str,
    project_root: str = ".",
    graph_path: str = "data/knowledge_graph.json",
    results_path: str = "data/eval_results.jsonl",
    n_runs: int = 3,
    task_names: list[str] | None = None,
    clear_graph_each_run: bool = False,
) -> None:
    """
    Run the evaluation loop.

    Args:
        base_url: Remote Robot tunnel URL (e.g. https://intellij.sudhanva.dev)
        project_root: Local path to the tool-discovery-agent repo.
        graph_path: Where the knowledge graph is persisted.
        results_path: JSONL file to append results to.
        n_runs: How many times to repeat each task.
        task_names: Subset of task names to run (None = all).
        clear_graph_each_run: If True, delete the graph before each run (ablation baseline).
    """
    client = RemoteRobotClient(base_url)
    agent = GraphAgent(client=client, graph_path=graph_path)

    tasks = TASKS if not task_names else [t for t in TASKS if t.name in task_names]
    Path(results_path).parent.mkdir(parents=True, exist_ok=True)

    all_results: list[dict] = []

    console.print(f"\n[bold]AppAgentX Evaluation[/bold]  ({n_runs} runs × {len(tasks)} tasks)\n")

    for run_idx in range(n_runs):
        console.rule(f"Run {run_idx + 1} / {n_runs}")

        if clear_graph_each_run and Path(graph_path).exists():
            Path(graph_path).unlink()
            console.print("  [dim]Graph cleared (ablation mode)[/dim]")

        for task in tasks:
            console.print(f"\n  Task: [cyan]{task.name}[/cyan]")
            console.print(f"  Intent: {task.intent[:80]}...")

            # Sanity: check file hasn't already been modified
            if task.unexpected_before:
                content = _read_file_from_intellij(project_root, task.file_path)
                if task.unexpected_before in content:
                    console.print(f"  [yellow]⚠ File already contains expected change — resetting[/yellow]")
                    _reset_task(task, project_root)

            start_time = time.time()
            result = agent.execute(task.intent)
            elapsed = time.time() - start_time

            success = _verify_task(task, project_root)

            # Read graph stats
            from src.agent.knowledge_graph import KnowledgeGraph
            kg = KnowledgeGraph()
            kg.load(graph_path)
            stats = kg.stats()

            row = {
                "run": run_idx + 1,
                "task": task.name,
                "success": success,
                "agent_success": result.success,
                "actions": result.iterations,
                "tokens": result.token_count,
                "elapsed_s": round(elapsed, 1),
                "graph_pages": stats["pages"],
                "graph_transitions": stats["transitions"],
                "timestamp": datetime.utcnow().isoformat(),
                "clear_graph_mode": clear_graph_each_run,
            }
            all_results.append(row)

            with open(results_path, "a") as f:
                f.write(json.dumps(row) + "\n")

            status = "[green]✓[/green]" if success else "[red]✗[/red]"
            console.print(
                f"  {status} success={success}  actions={result.iterations}  "
                f"tokens={result.token_count}  elapsed={elapsed:.1f}s"
            )

            # Reset file for next run
            _reset_task(task, project_root)
            time.sleep(1.0)  # brief pause between tasks

    _print_summary(all_results, tasks)
    console.print(f"\nResults saved to [bold]{results_path}[/bold]")


def _print_summary(results: list[dict], tasks: list[EvalTask]) -> None:
    table = Table(title="\nEvaluation Summary", show_header=True)
    table.add_column("Task")
    table.add_column("Run")
    table.add_column("Success")
    table.add_column("Actions")
    table.add_column("Tokens")
    table.add_column("Graph pages")

    for r in results:
        table.add_row(
            r["task"],
            str(r["run"]),
            "[green]✓[/green]" if r["success"] else "[red]✗[/red]",
            str(r["actions"]),
            str(r["tokens"]),
            str(r["graph_pages"]),
        )

    console.print(table)

    # Per-task summary across runs
    console.print("\n[bold]Per-task success rate across runs:[/bold]")
    for task in tasks:
        task_results = [r for r in results if r["task"] == task.name]
        n_success = sum(1 for r in task_results if r["success"])
        avg_actions = sum(r["actions"] for r in task_results) / len(task_results) if task_results else 0
        avg_tokens = sum(r["tokens"] for r in task_results) / len(task_results) if task_results else 0
        console.print(
            f"  {task.name}: {n_success}/{len(task_results)} success, "
            f"avg {avg_actions:.1f} actions, avg {avg_tokens:.0f} tokens"
        )
