"""
AppAgentX-style knowledge graph for IntelliJ UI navigation.

Nodes:
  - PageNode  : a distinct UI state (editor_idle, context_menu, rename_dialog…)
  - ElementNode: an interactive component within a page

Edges:
  - LEADS_TO  : taking an action on an element transitions from one page to another

Shortcuts: learned multi-step sequences that get promoted to single graph hops.

The graph persists to JSON between runs so knowledge accumulates.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

import networkx as nx


@dataclass
class PageNode:
    id: str          # e.g. "editor_idle", "context_menu"
    description: str
    visit_count: int = 0


@dataclass
class ElementNode:
    id: str          # f"{page_id}::{cls}::{label}"
    page_id: str
    cls: str
    label: str
    xpath: str
    role: str


@dataclass
class Transition:
    from_page: str
    element_id: str
    action: str      # "click", "type", "press_key"
    to_page: str
    params: dict[str, Any] = field(default_factory=dict)


@dataclass
class Shortcut:
    name: str                      # e.g. "rename_symbol"
    steps: list[dict[str, Any]]    # ordered list of {action, element_id/xpath, params}
    usage_count: int = 0
    success_count: int = 0


class KnowledgeGraph:
    """Directed graph of UI page states and transitions."""

    def __init__(self) -> None:
        self._graph: nx.DiGraph = nx.DiGraph()
        self._pages: dict[str, PageNode] = {}
        self._elements: dict[str, ElementNode] = {}
        self._transitions: list[Transition] = []
        self._shortcuts: dict[str, Shortcut] = {}

    # ── Pages ─────────────────────────────────────────────────────────────────

    def get_page(self, page_id: str) -> PageNode | None:
        return self._pages.get(page_id)

    def add_page(self, page: PageNode) -> None:
        if page.id not in self._pages:
            self._pages[page.id] = page
            self._graph.add_node(page.id, type="page")
        else:
            self._pages[page.id].visit_count += 1

    def record_visit(self, page_id: str) -> None:
        if page_id in self._pages:
            self._pages[page_id].visit_count += 1

    # ── Elements ──────────────────────────────────────────────────────────────

    def get_element(self, element_id: str) -> ElementNode | None:
        return self._elements.get(element_id)

    def add_element(self, element: ElementNode) -> None:
        self._elements[element.id] = element

    @staticmethod
    def make_element_id(page_id: str, cls: str, label: str) -> str:
        return f"{page_id}::{cls}::{label[:40]}"

    # ── Transitions ───────────────────────────────────────────────────────────

    def add_transition(
        self,
        from_page: str,
        element_id: str,
        action: str,
        to_page: str,
        params: dict[str, Any] | None = None,
    ) -> None:
        t = Transition(from_page, element_id, action, to_page, params or {})
        self._transitions.append(t)
        # Use element_id as edge key so parallel edges (same pages, diff element) coexist
        self._graph.add_edge(from_page, to_page, element_id=element_id, action=action, key=element_id)

    def get_transitions_from(self, page_id: str) -> list[Transition]:
        return [t for t in self._transitions if t.from_page == page_id]

    # ── Shortcuts ─────────────────────────────────────────────────────────────

    def get_shortcut(self, name: str) -> Shortcut | None:
        return self._shortcuts.get(name)

    def add_shortcut(self, shortcut: Shortcut) -> None:
        self._shortcuts[shortcut.name] = shortcut

    def record_shortcut_used(self, name: str, success: bool) -> None:
        if name in self._shortcuts:
            self._shortcuts[name].usage_count += 1
            if success:
                self._shortcuts[name].success_count += 1

    # ── Context for LLM ──────────────────────────────────────────────────────

    def to_prompt_context(self, current_page_id: str) -> str:
        """Return a compact string the LLM sees instead of the raw UI tree."""
        lines: list[str] = []

        transitions = self.get_transitions_from(current_page_id)
        if transitions:
            lines.append("### Known transitions from this page (learned):")
            for t in transitions:
                el = self._elements.get(t.element_id)
                el_label = el.label if el else t.element_id
                lines.append(f"  - {t.action} \"{el_label}\" → page: {t.to_page}")
        else:
            lines.append("### No known transitions from this page yet (first visit).")

        if self._shortcuts:
            lines.append("\n### Available shortcuts (learned sequences):")
            for s in self._shortcuts.values():
                rate = f"{s.success_count}/{s.usage_count}" if s.usage_count else "untested"
                lines.append(f"  - \"{s.name}\" ({len(s.steps)} steps, success: {rate})")

        return "\n".join(lines) if lines else "(graph is empty — exploring for the first time)"

    def stats(self) -> dict[str, int]:
        return {
            "pages": len(self._pages),
            "elements": len(self._elements),
            "transitions": len(self._transitions),
            "shortcuts": len(self._shortcuts),
        }

    # ── Persistence ───────────────────────────────────────────────────────────

    def save(self, path: str | Path) -> None:
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "pages": {k: asdict(v) for k, v in self._pages.items()},
            "elements": {k: asdict(v) for k, v in self._elements.items()},
            "transitions": [asdict(t) for t in self._transitions],
            "shortcuts": {k: asdict(v) for k, v in self._shortcuts.items()},
        }
        path.write_text(json.dumps(data, indent=2))

    def load(self, path: str | Path) -> None:
        path = Path(path)
        if not path.exists():
            return
        data = json.loads(path.read_text())

        self._pages = {k: PageNode(**v) for k, v in data.get("pages", {}).items()}
        self._elements = {k: ElementNode(**v) for k, v in data.get("elements", {}).items()}
        self._transitions = [Transition(**t) for t in data.get("transitions", [])]
        self._shortcuts = {k: Shortcut(**v) for k, v in data.get("shortcuts", {}).items()}

        # Rebuild networkx graph
        self._graph = nx.DiGraph()
        for page_id in self._pages:
            self._graph.add_node(page_id, type="page")
        for t in self._transitions:
            self._graph.add_edge(t.from_page, t.to_page, element_id=t.element_id, action=t.action)
