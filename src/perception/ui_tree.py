"""
Parse the Remote Robot HTML component tree into structured PageState objects.

Remote Robot returns an HTML document where every Swing/AWT component is a
<div> with attributes like class, accessiblename, visible_text, tooltiptext,
enabled, visible.  We strip out the Remote Robot UI chrome (img, xpathEditor,
input, label tags) and extract only meaningful component data.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from bs4 import BeautifulSoup, Tag

# Swing classes we treat as interactive / meaningful to the agent.
INTERACTIVE_CLASSES = {
    "ActionButton",
    "ActionButtonWithText",
    "ActionMenu",
    "ActionMenuItem",
    "JButton",
    "EditorComponentImpl",
    "JTextField",
    "JBTextField",
    "ComboBox",
    "JBComboBox",
    "JCheckBox",
    "JBCheckBox",
    "ToolbarComboButton",
    "CWMNewUIButton",
    "SquareStripeButton",
    "EditorTabLabel",
    "WithIconAndArrows",
}

# Classes that signal a popup/overlay window.
POPUP_CLASSES = {"HeavyWeightWindow"}

# Classes that signal a modal dialog root.
DIALOG_CLASSES = {"DialogRootPane"}

# Classes that signal a text input in a popup (inline rename widget etc.).
TEXT_INPUT_CLASSES = {"EditorComponentImpl", "JTextField", "JBTextField"}


@dataclass
class UIElement:
    """A single interactive component extracted from the UI tree."""

    cls: str
    label: str        # best human-readable label (accessiblename > visible_text > tooltip)
    xpath: str        # XPath that uniquely locates this element
    role: str         # button | menu_item | submenu | text_field | dropdown | checkbox | editor | tab
    enabled: bool = True
    has_submenu: bool = False

    def to_prompt_line(self) -> str:
        disabled = " (disabled)" if not self.enabled else ""
        arrow = " →" if self.has_submenu else ""
        return f"  [{self.role}] \"{self.label}\"{arrow}{disabled}  xpath={self.xpath}"


@dataclass
class PageState:
    """Structured representation of the current IntelliJ UI state."""

    page_id: str              # e.g. "editor_idle", "context_menu", "rename_dialog"
    description: str          # one-line human/LLM-readable summary
    elements: list[UIElement] = field(default_factory=list)
    raw_html: str = field(default="", repr=False)

    def to_prompt_string(self) -> str:
        lines = [f"## Current UI: {self.page_id}", self.description, ""]
        if self.elements:
            lines.append("### Interactive elements")
            lines.extend(el.to_prompt_line() for el in self.elements)
        else:
            lines.append("(no interactive elements detected)")
        return "\n".join(lines)


# ── Parser ────────────────────────────────────────────────────────────────────

class UIStateParser:
    """Parse Remote Robot HTML into a PageState."""

    def parse(self, html: str) -> PageState:
        soup = BeautifulSoup(html, "html.parser")

        # Collect all <div> nodes that carry a Swing class attribute.
        all_divs = [
            tag for tag in soup.find_all("div")
            if isinstance(tag, Tag) and tag.get("class")
        ]

        # BeautifulSoup turns class into a list; Remote Robot uses it as a
        # single string (the Swing class name), so join just in case.
        def cls_of(tag: Tag) -> str:
            raw = tag.get("class", "")
            return " ".join(raw) if isinstance(raw, list) else raw

        page_id, description = self._infer_page(all_divs, cls_of)
        elements = self._extract_elements(all_divs, cls_of)

        return PageState(
            page_id=page_id,
            description=description,
            elements=elements,
            raw_html=html,
        )

    # ── Page identification ───────────────────────────────────────────────────

    def _infer_page(
        self, divs: list[Tag], cls_of: "function"
    ) -> tuple[str, str]:
        popup_divs = [d for d in divs if cls_of(d) in POPUP_CLASSES]
        dialog_divs = [d for d in divs if cls_of(d) in DIALOG_CLASSES]

        if dialog_divs:
            # Modal dialog — use the accessible name if available.
            name = dialog_divs[0].get("accessiblename", "").strip()
            page_id = f"dialog_{name.lower().replace(' ', '_')}" if name else "dialog"
            return page_id, f"Modal dialog open: {name or 'unknown'}"

        if popup_divs:
            # Popup/menu open — check if it contains a text input (inline widget).
            popup = popup_divs[-1]  # last = frontmost
            has_text_input = any(
                cls_of(d) in TEXT_INPUT_CLASSES
                for d in popup.find_all("div")
                if isinstance(d, Tag)
            )
            if has_text_input:
                return "inline_widget", "Inline input widget open (e.g. rename field)"

            # Check for submenu indicator: multiple popups coexist.
            if len(popup_divs) > 1:
                return "refactor_submenu", "Refactor/action submenu open"

            return "context_menu", "Context menu open"

        # No popup or dialog — plain editor view.
        return "editor_idle", "Editor focused, no popups or dialogs"

    # ── Element extraction ────────────────────────────────────────────────────

    def _extract_elements(
        self, divs: list[Tag], cls_of: "function"
    ) -> list[UIElement]:
        elements: list[UIElement] = []
        seen_xpaths: set[str] = set()

        for div in divs:
            cls = cls_of(div)
            if cls not in INTERACTIVE_CLASSES:
                continue
            if div.get("visible") == "false":
                continue

            label = self._best_label(div)
            if not label:
                continue

            xpath = self._make_xpath(cls, div)
            if xpath in seen_xpaths:
                continue
            seen_xpaths.add(xpath)

            elements.append(UIElement(
                cls=cls,
                label=label,
                xpath=xpath,
                role=self._role_of(cls, div),
                enabled=div.get("enabled", "true") != "false",
                has_submenu=cls == "ActionMenu",
            ))

        return elements

    def _best_label(self, div: Tag) -> str:
        for attr in ("accessiblename", "visible_text", "tooltiptext"):
            val = div.get(attr, "").strip()
            if val:
                # Truncate very long visible_text (editor content etc.)
                return val[:80]
        return ""

    def _make_xpath(self, cls: str, div: Tag) -> str:
        name = div.get("accessiblename", "").strip()
        text = div.get("visible_text", "").strip()
        tooltip = div.get("tooltiptext", "").strip()

        def _esc(s: str) -> str:
            return s.replace("'", "\\'")

        if name:
            return f"//div[@class='{cls}' and @accessiblename='{_esc(name)}']"
        if text:
            return f"//div[@class='{cls}' and @visible_text='{_esc(text[:60])}']"
        if tooltip:
            return f"//div[@class='{cls}' and @tooltiptext='{_esc(tooltip[:60])}']"
        return f"//div[@class='{cls}']"

    def _role_of(self, cls: str, div: Tag) -> str:
        role_map = {
            "ActionMenuItem": "menu_item",
            "ActionMenu": "submenu",
            "ActionButton": "button",
            "ActionButtonWithText": "button",
            "CWMNewUIButton": "button",
            "SquareStripeButton": "button",
            "JButton": "button",
            "ToolbarComboButton": "button",
            "WithIconAndArrows": "button",
            "EditorComponentImpl": "editor",
            "JTextField": "text_field",
            "JBTextField": "text_field",
            "ComboBox": "dropdown",
            "JBComboBox": "dropdown",
            "JCheckBox": "checkbox",
            "JBCheckBox": "checkbox",
            "EditorTabLabel": "tab",
        }
        return role_map.get(cls, "widget")
