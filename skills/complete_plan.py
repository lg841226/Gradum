"""Skill for marking to-do item completion."""

from typing import Any

from .base import Skill
from .plan_task import get_plan_manager


class CompletePlanSkill(Skill):
    """Skill for marking to-do item completion."""
    name = "finish_to_do_item"
    alias = "Completed"
    description = "Mark to-do item as completed. Call in order: after 1st item → to_do_items_completed=1, after 2nd item → to_do_items_completed=2, etc. Never skip numbers. Must call to_do() first."

    def get_schema(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": {
                    "type": "object",
                    "properties": {
                        "to_do_items_completed": {
                            "type": "integer",
                            "description": "Total number of to-do items completed so far. 0 = none done, 1 = first item done, 2 = first two done, etc."
                        }
                    },
                    "required": ["to_do_items_completed"]
                }
            }
        }

    def execute(self, to_do_items_completed: int = None, **kwargs: Any) -> str:
        if to_do_items_completed is None:
            return "Error: Missing 'to_do_items_completed' parameter."

        to_do_items_completed = int(to_do_items_completed)
        pm = get_plan_manager()

        if not pm.initialized:
            return "Error: No to-do item list initialized. Call to_do() first."

        if to_do_items_completed == pm.completed:
            total = len(pm.tasks)
            current_task = pm.get_current_task()
            if current_task:
                return f"No new tasks completed. Current progress: {pm.completed} out of {total} tasks. Complete the next task ({current_task}) before updating your progress."
            else:
                return f"No new tasks completed. Current progress: {pm.completed} out of {total} tasks. Complete the next task before updating your progress."
        
        pm.last_complete_call = to_do_items_completed
        
        expected = pm.completed
        if to_do_items_completed > expected + 1:
            next_to_do_item = pm.tasks[expected] if expected < len(pm.tasks) else "unknown"
            tasks_list = ", ".join(f"{i+1}. {t}" for i, t in enumerate(pm.tasks))
            return f"Error: Cannot skip items. You only completed {expected} to-do item(s). Next item is '{next_to_do_item}'. Complete it before marking more as done.\n\nComplete to-do list: {tasks_list}"
        if to_do_items_completed < expected:
            current_item = pm.tasks[to_do_items_completed] if to_do_items_completed < len(pm.tasks) else "unknown"
            tasks_list = ", ".join(f"{i+1}. {t}" for i, t in enumerate(pm.tasks))
            return f"Error: Cannot go backwards. You already completed {expected} to-do item(s). Current item is '{current_item}'. Move forward, not backward.\n\nComplete to-do list: {tasks_list}"

        max_completed = len(pm.tasks)
        if to_do_items_completed > max_completed:
            to_do_items_completed = max_completed
        pm.completed = to_do_items_completed

        return f"Success: Completed to-do item {to_do_items_completed}\n{pm.build_status_info()}"

    def format_content(self, arguments: dict, result: str) -> str:
        return ""
