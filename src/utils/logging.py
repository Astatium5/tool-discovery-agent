"""
Logging Module.

This module provides logging utilities using rich for
formatted console output and file logging.
"""

import logging
from pathlib import Path
from typing import Any
from rich.logging import RichHandler
from rich.console import Console


def setup_logger(
    name: str = "tool-discovery-agent",
    level: str = "INFO",
    log_file: str | None = None,
    rich_format: bool = True,
) -> logging.Logger:
    """
    Set up a logger with optional rich formatting.
    
    Args:
        name: Logger name.
        level: Log level (DEBUG, INFO, WARNING, ERROR).
        log_file: Optional path to log file.
        rich_format: Whether to use rich formatting for console output.
    
    Returns:
        Configured logger instance.
    
    Example:
        >>> logger = setup_logger("my-module", level="DEBUG")
        >>> logger.info("Operation completed")
    """
    logger = logging.getLogger(name)
    logger.setLevel(getattr(logging, level.upper()))
    
    # Clear existing handlers
    logger.handlers.clear()
    
    # Console handler with rich formatting
    if rich_format:
        console = Console()
        console_handler = RichHandler(
            console=console,
            show_time=True,
            show_path=True,
            rich_tracebacks=True,
        )
        console_handler.setFormatter(logging.Formatter("%(message)s"))
    else:
        console_handler = logging.StreamHandler()
        console_handler.setFormatter(
            logging.Formatter(
                "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
            )
        )
    
    logger.addHandler(console_handler)
    
    # File handler if specified
    if log_file:
        log_path = Path(log_file)
        log_path.parent.mkdir(parents=True, exist_ok=True)
        
        file_handler = logging.FileHandler(log_file)
        file_handler.setFormatter(
            logging.Formatter(
                "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
            )
        )
        logger.addHandler(file_handler)
    
    return logger


def get_logger(name: str) -> logging.Logger:
    """
    Get or create a logger by name.
    
    Args:
        name: Logger name.
    
    Returns:
        Logger instance.
    """
    return logging.getLogger(name)


class LoggerAdapter:
    """
    Adapter for adding context to log messages.
    
    Example:
        >>> logger = setup_logger("my-module")
        >>> adapter = LoggerAdapter(logger, {"tool": "Extract Method"})
        >>> adapter.info("Executing tool")
    """
    
    def __init__(
        self,
        logger: logging.Logger,
        context: dict[str, Any],
    ) -> None:
        """
        Initialize the adapter.
        
        Args:
            logger: Base logger instance.
            context: Context dictionary to include in messages.
        """
        self.logger = logger
        self.context = context
    
    def _format_message(
        self,
        message: str,
    ) -> str:
        """Format message with context."""
        context_str = " ".join(f"[{k}={v}]" for k, v in self.context.items())
        return f"{context_str} {message}" if context_str else message
    
    def debug(
        self,
        message: str,
    ) -> None:
        """Log debug message."""
        self.logger.debug(self._format_message(message))
    
    def info(
        self,
        message: str,
    ) -> None:
        """Log info message."""
        self.logger.info(self._format_message(message))
    
    def warning(
        self,
        message: str,
    ) -> None:
        """Log warning message."""
        self.logger.warning(self._format_message(message))
    
    def error(
        self,
        message: str,
    ) -> None:
        """Log error message."""
        self.logger.error(self._format_message(message))
    
    def exception(
        self,
        message: str,
    ) -> None:
        """Log exception with traceback."""
        self.logger.exception(self._format_message(message))