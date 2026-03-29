"""
AppAgentX-style LangGraph agent for IntelliJ UI automation.

Loop: observe → reason → act → update_graph → check_complete → (repeat or END)

The key difference from Raihan's flat approach: instead of dumping the entire
UI tree to the LLM every step, the agent uses a knowledge graph of known page
transitions and shortcuts.  As the agent runs more tasks, the graph fills in
and the LLM gets richer, more precise context (fewer tokens, better decisions).
"""

from __future__ import annotations

import json
import os
import re
import time
from dataclasses import dataclass, field
from typing import Any, Literal

from langchain_openai import ChatOpenAI
from langgraph.graph import END, StateGraph
from langgraph.graph.state import CompiledStateGraph
from typing_extensions import TypedDict

from src.agent.knowledge_graph import ElementNode, KnowledgeGraph, PageNode, Shortcut
from src.perception.remote_robot import RemoteRobotClient
from src.perception.ui_tree import PageState, UIStateParser

MAX_ITERATIONS = 30
SETTLE_DELAY = 0.5  # seconds after each action (same as Kotlin)


# ── State ─────────────────────────────────────────────────────────────────────

@dataclass
class ActionRecord:
    action_type: str
    params: dict[str, Any]
    page_before: str
    page_after: str
    reasoning: str
    success: bool = True


class AgentState(TypedDict):
    task: str
    current_page: PageState | None
    prev_page_id: str | None
    last_action: ActionRecord | None
    action_history: list[ActionRecord]
    knowledge_graph: KnowledgeGraph
    iteration: int
    complete: bool
    success: bool
    token_count: int
    doc_text_before: str  # snapshot of editor text before task starts


# ── Result ────────────────────────────────────────────────────────────────────

@dataclass
class AgentResult:
    success: bool
    message: str
    action_history: list[ActionRecord] = field(default_factory=list)
    token_count: int = 0
    iterations: int = 0


# ── Agent ─────────────────────────────────────────────────────────────────────

class GraphAgent:
    """AppAgentX-style agent using a knowledge graph for UI navigation."""

    def __init__(
        self,
        client: RemoteRobotClient,
        graph_path: str = "data/knowledge_graph.json",
        llm_base_url: str | None = None,
        llm_model: str | None = None,
        llm_api_key: str | None = None,
    ) -> None:
        self.client = client
        self.parser = UIStateParser()
        self.graph_path = graph_path

        base_url = llm_base_url or os.environ.get(
            "LLM_BASE_URL", "https://coding-intl.dashscope.aliyuncs.com/v1"
        )
        model = llm_model or os.environ.get("LLM_MODEL", "MiniMax-M2.5")
        api_key = llm_api_key or os.environ.get("LLM_API_KEY", "")

        self.llm = ChatOpenAI(
            base_url=base_url,
            model=model,
            api_key=api_key,
            temperature=0,
        )

        self._workflow: CompiledStateGraph = self._build_graph()

    # ── LangGraph construction ─────────────────────────────────────────────────

    def _build_graph(self) -> CompiledStateGraph:
        wf = StateGraph(AgentState)
        wf.add_node("observe", self._observe)
        wf.add_node("reason", self._reason)
        wf.add_node("act", self._act)
        wf.add_node("update_graph", self._update_graph)
        wf.add_node("check_complete", self._check_complete)

        wf.set_entry_point("observe")
        wf.add_edge("observe", "reason")
        wf.add_edge("reason", "act")
        wf.add_edge("act", "update_graph")
        wf.add_edge("update_graph", "check_complete")
        wf.add_conditional_edges(
            "check_complete",
            self._should_continue,
            {"continue": "observe", "end": END},
        )
        return wf.compile()

    # ── Nodes ──────────────────────────────────────────────────────────────────

    def _observe(self, state: AgentState) -> dict[str, Any]:
        html = self.client.get_tree()
        page = self.parser.parse(html)
        return {"current_page": page}

    def _reason(self, state: AgentState) -> dict[str, Any]:
        page: PageState = state["current_page"]
        kg: KnowledgeGraph = state["knowledge_graph"]
        history = state["action_history"][-5:]  # last 5 actions

        prompt = self._build_prompt(state["task"], page, kg, history)
        response = self.llm.invoke(prompt)

        token_count = state["token_count"]
        if hasattr(response, "usage_metadata") and response.usage_metadata:
            token_count += response.usage_metadata.get("total_tokens", 0)

        decision = self._parse_decision(response.content)
        decision["_token_count"] = token_count
        return {"_decision": decision, "token_count": token_count}

    def _act(self, state: AgentState) -> dict[str, Any]:
        decision: dict[str, Any] = state.get("_decision", {})  # type: ignore[assignment]
        action_type = decision.get("action_type", "observe")
        params = decision.get("params", {})
        reasoning = decision.get("reasoning", "")
        page_before = state["current_page"].page_id if state["current_page"] else "unknown"

        if action_type == "click":
            xpath = params.get("xpath", "")
            if xpath:
                self.client.click(xpath)
        elif action_type == "right_click":
            xpath = params.get("xpath", "")
            if xpath:
                self.client.right_click(xpath)
        elif action_type == "type":
            self.client.type_text(params.get("text", ""))
        elif action_type == "press_key":
            self.client.press_key(params.get("key", "Enter"))
        elif action_type in ("complete", "fail", "observe"):
            pass  # no-op; check_complete will handle terminal states

        self.client.wait(SETTLE_DELAY)

        record = ActionRecord(
            action_type=action_type,
            params=params,
            page_before=page_before,
            page_after="",  # filled in update_graph after next observe
            reasoning=reasoning,
        )
        return {
            "last_action": record,
            "action_history": state["action_history"] + [record],
            "iteration": state["iteration"] + 1,
            "_decision": decision,
        }

    def _update_graph(self, state: AgentState) -> dict[str, Any]:
        """Record the transition we just made into the knowledge graph."""
        kg: KnowledgeGraph = state["knowledge_graph"]
        last_action: ActionRecord | None = state["last_action"]
        prev_page = state["current_page"]

        # Re-observe to get page_after
        html = self.client.get_tree()
        page_after = self.parser.parse(html)

        if last_action and prev_page:
            page_before_id = last_action.page_before
            page_after_id = page_after.page_id

            # Ensure both pages are in the graph
            for pg_id, pg_desc in [
                (page_before_id, prev_page.description),
                (page_after_id, page_after.description),
            ]:
                if not kg.get_page(pg_id):
                    kg.add_page(PageNode(id=pg_id, description=pg_desc))

            # Add elements from the page we were on
            for el in (prev_page.elements if prev_page else []):
                el_id = KnowledgeGraph.make_element_id(page_before_id, el.cls, el.label)
                if not kg.get_element(el_id):
                    kg.add_element(ElementNode(
                        id=el_id,
                        page_id=page_before_id,
                        cls=el.cls,
                        label=el.label,
                        xpath=el.xpath,
                        role=el.role,
                    ))

            # Record transition if page changed
            if page_before_id != page_after_id and last_action.action_type not in ("complete", "fail", "observe"):
                action_params = last_action.params
                xpath = action_params.get("xpath", "")
                el_id = KnowledgeGraph.make_element_id(
                    page_before_id,
                    action_params.get("cls", ""),
                    action_params.get("label", xpath),
                )
                kg.add_transition(
                    from_page=page_before_id,
                    element_id=el_id,
                    action=last_action.action_type,
                    to_page=page_after_id,
                )

            last_action.page_after = page_after_id

        kg.record_visit(page_after.page_id)
        kg.save(self.graph_path)

        return {
            "current_page": page_after,
            "prev_page_id": prev_page.page_id if prev_page else None,
            "last_action": last_action,
            "knowledge_graph": kg,
        }

    def _check_complete(self, state: AgentState) -> dict[str, Any]:
        decision: dict[str, Any] = state.get("_decision", {})  # type: ignore[assignment]
        action_type = decision.get("action_type", "")

        if action_type == "complete":
            return {"complete": True, "success": True}
        if action_type == "fail":
            return {"complete": True, "success": False}
        if state["iteration"] >= MAX_ITERATIONS:
            return {"complete": True, "success": False}

        # Diff-based completion: doc changed and no dialogs/popups open
        if state["doc_text_before"]:
            try:
                current_text = self.client.get_document_text()
                page = state["current_page"]
                no_popup = page and page.page_id == "editor_idle"
                if current_text != state["doc_text_before"] and no_popup:
                    return {"complete": True, "success": True}
            except Exception:
                pass

        return {"complete": False, "success": False}

    def _should_continue(
        self, state: AgentState
    ) -> Literal["continue", "end"]:
        return "end" if state["complete"] else "continue"

    # ── LLM prompt ────────────────────────────────────────────────────────────

    def _build_prompt(
        self,
        task: str,
        page: PageState,
        kg: KnowledgeGraph,
        history: list[ActionRecord],
    ) -> str:
        history_str = ""
        if history:
            lines = []
            for r in history:
                lines.append(f"  - {r.action_type}({r.params}) on {r.page_before} → {r.page_after}")
            history_str = "\n".join(lines)
        else:
            history_str = "  (none yet)"

        graph_ctx = kg.to_prompt_context(page.page_id)

        return f"""You are automating IntelliJ IDEA to accomplish a task.

## Task
{task}

## Current UI State
{page.to_prompt_string()}

## Graph Knowledge (from previous runs)
{graph_ctx}

## Recent Action History
{history_str}

## Available Actions
- click: click a UI element by xpath
- right_click: right-click a UI element by xpath (opens context menu)
- type: type text at current focus
- press_key: press a key (Enter, Escape, Tab, Backspace, context_menu)
- observe: just observe, take no action
- complete: mark task as successfully done
- fail: mark task as failed (cannot proceed)

## Recommended workflow for refactoring:
1. Use right_click on EditorComponentImpl to open context menu
2. click "Refactor" submenu
3. click the specific tool (e.g. "Rename...")
4. type the new value
5. press_key Enter to confirm

Return ONLY valid JSON (no markdown, no explanation):
{{
  "reasoning": "what you see and why you chose this action",
  "action_type": "click|right_click|type|press_key|observe|complete|fail",
  "params": {{
    "xpath": "//div[...]",   // for click/right_click
    "text": "...",           // for type
    "key": "Enter",          // for press_key
    "label": "...",          // human label of clicked element (for graph tracking)
    "cls": "..."             // Swing class of clicked element (for graph tracking)
  }},
  "confidence": 0.0,
  "complete": false
}}"""

    def _parse_decision(self, content: str) -> dict[str, Any]:
        # Strip markdown code fences if present
        content = re.sub(r"```(?:json)?\s*", "", content).strip()
        try:
            data = json.loads(content)
            return {
                "action_type": data.get("action_type", "observe"),
                "params": data.get("params", {}),
                "reasoning": data.get("reasoning", ""),
                "confidence": data.get("confidence", 0.5),
                "complete": data.get("complete", False),
            }
        except json.JSONDecodeError:
            # Fallback: just observe
            return {"action_type": "observe", "params": {}, "reasoning": content[:200], "confidence": 0.1, "complete": False}

    # ── Public API ─────────────────────────────────────────────────────────────

    def execute(self, task: str) -> AgentResult:
        """Run the agent on a task. Loads and saves the knowledge graph."""
        kg = KnowledgeGraph()
        kg.load(self.graph_path)

        # Snapshot doc text for diff-based completion
        try:
            doc_text_before = self.client.get_document_text()
        except Exception:
            doc_text_before = ""

        initial_state: AgentState = {
            "task": task,
            "current_page": None,
            "prev_page_id": None,
            "last_action": None,
            "action_history": [],
            "knowledge_graph": kg,
            "iteration": 0,
            "complete": False,
            "success": False,
            "token_count": 0,
            "doc_text_before": doc_text_before,
        }

        final_state = self._workflow.invoke(initial_state)

        kg.save(self.graph_path)

        return AgentResult(
            success=final_state["success"],
            message="Task completed" if final_state["success"] else "Task failed or timed out",
            action_history=final_state["action_history"],
            token_count=final_state["token_count"],
            iterations=final_state["iteration"],
        )
