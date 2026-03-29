"""
HTTP client for the JetBrains Remote Robot server.

Remote Robot exposes IntelliJ's Swing/AWT UI component tree over HTTP and
allows JavaScript execution (Rhino ES5) on components and the robot itself.

Server runs on the MacBook at http://localhost:8082, exposed via tunnel.
"""

import time
from typing import Any

import httpx


class RemoteRobotClient:
    """Thin HTTP client for the Remote Robot REST API."""

    def __init__(self, base_url: str, timeout: float = 30.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def get_tree(self) -> str:
        """Fetch the full UI component tree as raw HTML."""
        with httpx.Client(timeout=self.timeout) as client:
            resp = client.get(self.base_url)
            resp.raise_for_status()
            return resp.text

    def call_js(self, xpath: str, script: str) -> Any:
        """Execute JavaScript (Rhino ES5) on a component located by XPath.

        The script runs inside the IDE process. Use ES5 syntax only — no
        const/let, no arrow functions, no optional chaining.

        Example:
            client.call_js("//div[@class='EditorComponentImpl']",
                           "component.getDocument().getText(0, component.getDocument().getLength())")
        """
        with httpx.Client(timeout=self.timeout) as client:
            resp = client.post(
                f"{self.base_url}/component/execute",
                json={"xpath": xpath, "script": script},
            )
            resp.raise_for_status()
            data = resp.json()
            return data.get("result")

    def robot_call_js(self, script: str) -> Any:
        """Execute JavaScript on the RemoteRobot instance (global keyboard/mouse).

        Use for actions not tied to a specific component — key presses,
        mouse moves at absolute coordinates.
        """
        with httpx.Client(timeout=self.timeout) as client:
            resp = client.post(
                f"{self.base_url}/robot/execute",
                json={"script": script},
            )
            resp.raise_for_status()
            data = resp.json()
            return data.get("result")

    # ── High-level actions ────────────────────────────────────────────────────

    def click(self, xpath: str) -> None:
        """Click the center of the component located by XPath."""
        self.call_js(xpath, "component.click()")

    def right_click(self, xpath: str) -> None:
        """Right-click the component to open a context menu."""
        self.call_js(
            xpath,
            (
                "var e = new java.awt.event.MouseEvent("
                "component, java.awt.event.MouseEvent.MOUSE_PRESSED, "
                "System.currentTimeMillis(), "
                "java.awt.event.InputEvent.BUTTON3_DOWN_MASK, "
                "component.width / 2, component.height / 2, 1, true);"
                "component.dispatchEvent(e);"
            ),
        )

    def type_text(self, text: str) -> None:
        """Type text at the current focus point using the robot keyboard."""
        escaped = text.replace("\\", "\\\\").replace('"', '\\"')
        self.robot_call_js(
            f'robot.keyboard.enterText("{escaped}");'
        )

    def press_key(self, key: str) -> None:
        """Press a named key. Supported: Enter, Escape, Tab, Backspace, context_menu."""
        key_map = {
            "Enter": "java.awt.event.KeyEvent.VK_ENTER",
            "Escape": "java.awt.event.KeyEvent.VK_ESCAPE",
            "Tab": "java.awt.event.KeyEvent.VK_TAB",
            "Backspace": "java.awt.event.KeyEvent.VK_BACK_SPACE",
            "context_menu": "java.awt.event.KeyEvent.VK_CONTEXT_MENU",
        }
        vk = key_map.get(key, f"java.awt.event.KeyEvent.VK_{key.upper()}")
        self.robot_call_js(
            f"robot.keyboard.pressAndReleaseKey({vk});"
        )

    def get_document_text(self, editor_xpath: str = "//div[@class='EditorComponentImpl']") -> str:
        """Read the full text of the focused editor document (ignores scroll position)."""
        result = self.call_js(
            editor_xpath,
            (
                "var doc = component.getDocument();"
                "doc.getText(0, doc.getLength());"
            ),
        )
        return result or ""

    def is_alive(self) -> bool:
        """Return True if the Remote Robot server is reachable."""
        try:
            with httpx.Client(timeout=5.0) as client:
                resp = client.get(self.base_url)
                return resp.status_code == 200
        except Exception:
            return False

    def wait(self, seconds: float = 0.5) -> None:
        """Sleep to let the IDE settle after an action."""
        time.sleep(seconds)
