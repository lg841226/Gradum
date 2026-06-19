# Copyright (c) 2026 Gradum Authors, Ge Wangyang. Licensed under MIT.
# See LICENSE for details.

"""Base class for all skills."""

from typing import Any


def make_error(tool: str, code: str, message: str, **kwargs: Any) -> dict:
    """Create a standardized error response.

    Args:
        tool: Skill name (e.g., "read_file").
        code: Error code (e.g., "FILE_NOT_FOUND").
        message: Human-readable error message.
        **kwargs: Additional context (path, command, etc.).

    Returns:
        Dict with success=False, tool, error, and context fields.
    """
    result: dict[str, Any] = {
        "success": False,
        "tool": tool,
        "error": {"code": code, "message": message},
    }
    result.update(kwargs)
    return result


def make_success(tool: str, **kwargs: Any) -> dict:
    """Create a standardized success response.

    Args:
        tool: Skill name (e.g., "read_file").
        **kwargs: Response data (path, content, command, etc.).

    Returns:
        Dict with success=True, tool, and data fields.
    """
    result: dict[str, Any] = {
        "success": True,
        "tool": tool,
    }
    result.update(kwargs)
    return result


class Skill:
    """Base class for all skills."""

    name: str = ""
    description: str = ""
    alias: str = ""

    def execute(self, **kwargs: Any) -> dict:
        raise NotImplementedError

    def get_schema(self) -> dict[str, Any]:
        raise NotImplementedError
