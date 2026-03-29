"""
HTTP client for the Kotlin Agent Bridge Server.

The bridge server runs on the Mac alongside IntelliJ and wraps the Remote
Robot Java library (which handles the actual UI interaction correctly).

Endpoints the bridge exposes:
  GET  /ping              → health check
  GET  /tree              → full IntelliJ UI tree as HTML
  POST /action            → execute a UI action

Action payload examples:
  {"type": "click",       "xpath": "//div[@class='ActionButton' and ...]"}
  {"type": "right_click", "xpath": "//div[@class='EditorComponentImpl']"}
  {"type": "type",        "text": "newName"}
  {"type": "press_key",   "key": "Enter"}   # Enter|Escape|Tab|Backspace|context_menu|shift_f6
"""

import time
from typing import Any

import httpx


class RemoteRobotClient:
    """Client for the Kotlin Agent Bridge Server."""

    def __init__(self, base_url: str, timeout: float = 30.0) -> None:
        # base_url points to the bridge server, e.g. http://localhost:7070
        # or its Cloudflare tunnel equivalent
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def get_tree(self) -> str:
        """Fetch the full UI component tree as HTML."""
        with httpx.Client(timeout=self.timeout) as client:
            resp = client.get(f"{self.base_url}/tree")
            resp.raise_for_status()
            return resp.text

    def _action(self, payload: dict[str, Any]) -> None:
        """Send an action to the bridge server."""
        with httpx.Client(timeout=self.timeout) as client:
            resp = client.post(f"{self.base_url}/action", json=payload)
            resp.raise_for_status()
            data = resp.json()
            if data.get("status") == "error":
                raise RuntimeError(f"Bridge error: {data.get('message')}")

    def click(self, xpath: str) -> None:
        self._action({"type": "click", "xpath": xpath})

    def right_click(self, xpath: str) -> None:
        self._action({"type": "right_click", "xpath": xpath})

    def type_text(self, text: str) -> None:
        self._action({"type": "type", "text": text})

    def press_key(self, key: str) -> None:
        """Press a key. Supported: Enter, Escape, Tab, Backspace, context_menu, shift_f6."""
        self._action({"type": "press_key", "key": key})

    def get_document_text(self, editor_xpath: str = "//div[@class='EditorComponentImpl']") -> str:
        """Not directly supported by bridge — returns empty string (use diff-based completion)."""
        return ""

    def is_alive(self) -> bool:
        """Return True if the bridge server is reachable."""
        try:
            with httpx.Client(timeout=15.0) as client:
                resp = client.get(f"{self.base_url}/ping")
                return resp.status_code == 200
        except Exception:
            return False

    def wait(self, seconds: float = 0.5) -> None:
        time.sleep(seconds)
