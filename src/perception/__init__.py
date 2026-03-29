"""
Perception module for GUI-Grounded Agent.

Handles UI tree parsing and visual perception for IDE interaction.
"""

from .remote_robot import RemoteRobotClient
from .ui_tree import UIStateParser, PageState, UIElement

__all__ = ["RemoteRobotClient", "UIStateParser", "PageState", "UIElement"]