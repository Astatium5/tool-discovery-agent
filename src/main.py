"""
CLI entry point for the Tool Discovery Agent.

Usage:
    python -m src status                          # check tunnel + LLM connectivity
    python -m src run --task "rename foo to bar"  # run a single task
    python -m src eval                            # run full evaluation (3 runs × 3 tasks)
    python -m src eval --runs 1 --tasks rename_method
    python -m src eval --clear-graph              # ablation: no graph benefit between runs
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path


def _get_base_url() -> str:
    return os.environ.get("REMOTE_ROBOT_URL", "https://intellij.sudhanva.dev")


def _get_graph_path() -> str:
    return os.environ.get("GRAPH_PATH", "data/knowledge_graph.json")


def cmd_status(args: argparse.Namespace) -> None:
    from rich.console import Console
    from src.perception.remote_robot import RemoteRobotClient

    console = Console()
    url = _get_base_url()
    console.print(f"Checking Remote Robot at [bold]{url}[/bold] …")

    client = RemoteRobotClient(url)
    if client.is_alive():
        console.print("[green]✓ Remote Robot is reachable[/green]")
        html = client.get_tree()
        console.print(f"  UI tree: {len(html):,} bytes")
    else:
        console.print("[red]✗ Remote Robot not reachable. Is IntelliJ running with the plugin?[/red]")
        sys.exit(1)

    llm_key = os.environ.get("LLM_API_KEY", "")
    llm_url = os.environ.get("LLM_BASE_URL", "https://coding-intl.dashscope.aliyuncs.com/v1")
    llm_model = os.environ.get("LLM_MODEL", "MiniMax-M2.5")
    console.print(f"\nLLM: [bold]{llm_model}[/bold] @ {llm_url}")
    console.print(f"LLM_API_KEY: {'set ✓' if llm_key else 'NOT SET ✗'}")

    graph_path = Path(_get_graph_path())
    if graph_path.exists():
        from src.agent.knowledge_graph import KnowledgeGraph
        kg = KnowledgeGraph()
        kg.load(graph_path)
        stats = kg.stats()
        console.print(f"\nKnowledge graph ({graph_path}): {stats}")
    else:
        console.print(f"\nKnowledge graph: not yet created ({_get_graph_path()})")


def cmd_run(args: argparse.Namespace) -> None:
    from rich.console import Console
    from src.agent.graph_agent import GraphAgent
    from src.perception.remote_robot import RemoteRobotClient

    console = Console()
    url = _get_base_url()
    task = args.task

    console.print(f"[bold]Task:[/bold] {task}")
    console.print(f"Remote Robot: {url}")

    client = RemoteRobotClient(url)
    agent = GraphAgent(client=client, graph_path=_get_graph_path())

    result = agent.execute(task)

    console.print(f"\n{'[green]✓ Success' if result.success else '[red]✗ Failed'}[/]")
    console.print(f"Iterations: {result.iterations}  |  Tokens: {result.token_count}")
    console.print(f"\nAction log:")
    for i, action in enumerate(result.action_history, 1):
        console.print(f"  {i}. {action.action_type}({action.params}) — {action.reasoning[:60]}")


def cmd_eval(args: argparse.Namespace) -> None:
    from src.evaluation.runner import run_evaluation

    task_names = args.tasks.split(",") if args.tasks else None

    run_evaluation(
        base_url=_get_base_url(),
        project_root=str(Path(__file__).parent.parent),
        graph_path=_get_graph_path(),
        results_path=os.environ.get("RESULTS_PATH", "data/eval_results.jsonl"),
        n_runs=args.runs,
        task_names=task_names,
        clear_graph_each_run=args.clear_graph,
    )


def main() -> None:
    parser = argparse.ArgumentParser(prog="src", description="Tool Discovery Agent")
    sub = parser.add_subparsers(dest="command")

    sub.add_parser("status", help="Check tunnel and LLM connectivity")

    run_p = sub.add_parser("run", help="Run a single task")
    run_p.add_argument("--task", required=True, help="Natural language task description")

    eval_p = sub.add_parser("eval", help="Run evaluation suite")
    eval_p.add_argument("--runs", type=int, default=3, help="Number of runs per task")
    eval_p.add_argument("--tasks", default="", help="Comma-separated task names (default: all)")
    eval_p.add_argument("--clear-graph", action="store_true", help="Clear graph before each run (ablation)")

    args = parser.parse_args()

    if args.command == "status":
        cmd_status(args)
    elif args.command == "run":
        cmd_run(args)
    elif args.command == "eval":
        cmd_eval(args)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
