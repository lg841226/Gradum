"""Base class for all skills."""

from typing import Any


class Skill:
    """Base class for all skills."""

    name: str = ""
    description: str = ""
    alias: str = ""

    def execute(self, **kwargs: Any) -> dict:
        raise NotImplementedError

    def get_schema(self) -> dict[str, Any]:
        raise NotImplementedError
