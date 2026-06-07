"""Skills module - Auto-discovery and management of all skills."""

import importlib
import inspect
from pathlib import Path
from typing import Optional

from .base import Skill

__all__ = ["Skills", "Skill"]


class Skills:
    """Manages all available skills with auto-discovery."""

    def __init__(self):
        self._skills: dict[str, Skill] = {}
        self._discover_skills()

    def _discover_skills(self) -> None:
        """Auto-discover and register all Skill subclasses."""
        skills_dir = Path(__file__).parent
        for file in skills_dir.glob("*.py"):
            if file.stem.startswith("_") or file.stem == "base":
                continue
            
            module = importlib.import_module(f"gradum.skills.{file.stem}")
            for _, obj in inspect.getmembers(module):
                if inspect.isclass(obj) and issubclass(obj, Skill) and obj is not Skill:
                    instance = obj()
                    self.register(instance)

    def register(self, skill: Skill) -> None:
        """Register a new skill."""
        self._skills[skill.name] = skill

    def get(self, name: str) -> Optional[Skill]:
        """Get a skill by name."""
        return self._skills.get(name)

    def get_schemas(self) -> list[dict]:
        """Get JSON schemas for all skills."""
        return [skill.get_schema() for skill in self._skills.values()]

    def list_skills(self) -> list[str]:
        """List all registered skill names."""
        return list(self._skills.keys())


__all__ = ["Skills", "Skill"]
