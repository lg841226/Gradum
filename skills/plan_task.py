"""Skill for initializing task list."""

from typing import Any, Optional
from .base import Skill


class PlanManager:
    """Manages plan state."""

    def __init__(self):
        self.tasks: list[str] = []
        self.completed: int = 0
        self.initialized: bool = False
        self.tools_since_last_complete: int = 0
        self.last_complete_call: int | None = None

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

    def build_status_info(self) -> str:
        """Build status info for to_do."""
        total = len(self.tasks)

        if self.completed == 0:
            to_do_list_list = ", ".join(f"{i+1}. {t}" for i, t in enumerate(self.tasks))
            return f"To-do item list initialized with {total} items: {to_do_list_list}. Current item: {self.tasks[0]} (1/{total}). If you finish this item, please call: finish_to_do_item(to_do_items_completed=1)"
        elif self.completed < total:
            next_to_do_item = self.tasks[self.completed]
            return f"Current item: {next_to_do_item} ({self.completed + 1}/{total}). If you finish this item, please call: finish_to_do_item(to_do_items_completed={self.completed + 1})"
        else:
            self.tasks = []
            self.completed = 0
            self.initialized = False
            return f"All {total} to-do items completed! To-do item list destroyed. You can now report the results to the user."

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


_plan_manager = PlanManager()

class PlanTaskSkill(Skill):
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

    def execute(self, tasks: list = None, **kwargs: Any) -> str:
        if not tasks or not isinstance(tasks, list):
            return "Error: Missing or invalid 'tasks' parameter."

        to_do_list = [str(task).strip() for task in tasks if str(task).strip()]
        if not to_do_list:
            return "Error: 'to-do items' list is empty."

        is_valid, error_msg = _plan_manager.parse_arguments({"tasks": to_do_list})
        if not is_valid:
            if error_msg:
                return f"Error: {error_msg}"
            return "Error: To-do item list already initialized. Cannot call to_do again."

        result = _plan_manager.build_status_info()
        result += "WARNING: START EXECUTING THE FIRST TASK NOW! After completing it, call finish_to_do_item(to_do_items_completed=1) IMMEDIATELY! DO NOT SKIP TO LATER TASKS!"
        return result

    def format_content(self, arguments: dict, result: str) -> str:
        return ""


def get_plan_manager() -> PlanManager:
    """Get the global PlanManager instance."""
    return _plan_manager
