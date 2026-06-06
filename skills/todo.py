"""Skill for initializing task list."""

from typing import Any, Optional
from .base import Skill


class TodoManager:
    """Manages todo state."""

    def __init__(self):
        self.tasks: list[str] = []
        self.completed: int = 0
        self.initialized: bool = False
        self.last_complete_call: Optional[int] = None

    def parse_arguments(self, arguments: dict) -> tuple[bool, str]:
        """Parse to_do arguments."""
        tasks = arguments.get("tasks", [])
        if not tasks:
            return False, ""

        new_to_do_list = [str(task).strip() for task in tasks if str(task).strip()]
        if not new_to_do_list:
            return False, "To-do item list cannot be empty."

        if self.initialized:
            if new_to_do_list == self.tasks:
                return False, "To-do item list already set, cannot call again with same items."
            to_do_list_str = ", ".join(f"'{t}'" for t in self.tasks)
            return False, f"Cannot change item list. Already initialized with {len(self.tasks)} items: [{to_do_list_str}]"

        self.tasks = new_to_do_list
        self.completed = 0
        self.initialized = True

        return True, ""

    def get_current_task(self) -> Optional[str]:
        """Get current to-do item name."""
        if self.completed < len(self.tasks):
            return self.tasks[self.completed]
        return None

    def get_reminder(self) -> str:
        """Get reminder text for current to-do item."""
        current_to_do_item = self.get_current_task()
        if current_to_do_item:
            return f"Current to-do item: '{current_to_do_item}'. Have you completed it? If yes, call finish_to_do_item(to_do_items_completed={self.completed + 1}) to update."
        return ""


_todo_manager = TodoManager()

class TodoSkill(Skill):
    """Skill for initializing to-do item list."""
    name = "to_do"
    alias = "Planned"
    description = "Create to-do list with all items. Call ONCE at start."

    def get_schema(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": {
                    "type": "object",
                    "properties": {
                        "tasks": {
                            "type": "array",
                            "items": {"type": "string"},
                            "description": "List of ALL to-do items in order. Do NOT add items later."
                        }
                    },
                    "required": ["tasks"]
                }
            }
        }

    def execute(self, tasks: Optional[list] = None, **kwargs: Any) -> dict:
        if not tasks or not isinstance(tasks, list):
            return {
                "success": False,
                "error": {
                    "code": "INVALID_PARAMETER",
                    "message": "Missing or invalid 'tasks' parameter"
                }
            }

        to_do_list = [str(task).strip() for task in tasks if str(task).strip()]
        if not to_do_list:
            return {
                "success": False,
                "error": {
                    "code": "INVALID_PARAMETER",
                    "message": "'tasks' list is empty"
                }
            }

        is_valid, error_msg = _todo_manager.parse_arguments({"tasks": to_do_list})
        if not is_valid:
            return {
                "success": False,
                "error": {
                    "code": "ALREADY_INITIALIZED",
                    "message": error_msg or "To-do item list already initialized"
                }
            }

        total = len(_todo_manager.tasks)
        tasks_with_status = [
            {"index": i + 1, "name": t, "status": "pending"}
            for i, t in enumerate(_todo_manager.tasks)
        ]

        return {
            "success": True,
            "tool": "to_do",
            "total_tasks": total,
            "current_task": 1,
            "current_task_name": _todo_manager.tasks[0],
            "tasks": tasks_with_status
        }


def get_todo_manager() -> TodoManager:
    """Get the global TodoManager instance."""
    return _todo_manager
