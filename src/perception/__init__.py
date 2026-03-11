"""
Perception module for GUI-Grounded Agent.

Handles UI tree parsing and visual perception for IDE interaction.
"""

from .ui_tree import UITreeParser
from .screenshot import ScreenshotCapture

__all__ = ["UITreeParser", "ScreenshotCapture"]