"""Base class for all skills."""

from typing import Any


class Skill:
    """Base class for all skills."""

    name: str = ""
    description: str = ""
    alias: str = ""

    def execute(self, **kwargs: Any) -> str:
        raise NotImplementedError

    def get_schema(self) -> dict[str, Any]:
        raise NotImplementedError

    def format_content(self, arguments: dict, result: str) -> str:
        """Format content for display. Override in subclasses for custom format."""
        return result

    def format_args(self, arguments: dict) -> str:
        """Format arguments for display. Override in subclasses for custom format."""
        return str(arguments)
