"""Skill for marking to-do item completion."""

from typing import Any, Optional

from .base import Skill
from .plan_task import get_plan_manager


class CompletePlanSkill(Skill):
    """Skill for marking to-do item completion."""
    name = "finish_to_do_item"
    alias = "Completed"
    description = "Mark to-do items as completed. Supports sequential or batch mode."

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
                            "description": "Total number of to-do items completed so far (sequential mode). 1 = first item done, 2 = first two done, etc."
                        },
                        "completed_count": {
                            "type": "integer",
                            "description": "Number of newly completed items to mark as done (batch mode). e.g., 2 = mark next 2 items as completed"
                        }
                    },
                    "required": []
                }
            }
        }

    def execute(self, to_do_items_completed: Optional[int] = None, completed_count: Optional[int] = None, **kwargs: Any) -> str:
        pm = get_plan_manager()

        if not pm.initialized:
            return "Error: No to-do item list initialized. Call to_do() first."

        # Batch mode: completed_count specified
        if completed_count is not None:
            completed_count = int(completed_count)
            if completed_count <= 0:
                return "Error: completed_count must be a positive integer."
            
            new_total = pm.completed + completed_count
            max_items = len(pm.tasks)
            
            if new_total > max_items:
                return f"Error: Cannot complete {completed_count} items. Only {max_items - pm.completed} items remaining (total: {max_items}, completed: {pm.completed})."
            
            # Mark all items as completed
            for i in range(pm.completed, new_total):
                pm.completed = i + 1
            
            return f"Success: Batch completed {completed_count} item(s) (total: {pm.completed}/{max_items})\n{pm.build_status_info()}"

        # Sequential mode: to_do_items_completed specified
        if to_do_items_completed is not None:
            to_do_items_completed = int(to_do_items_completed)
            
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

        # Error: neither parameter provided
        return "Error: Must provide either 'to_do_items_completed' (sequential mode) or 'completed_count' (batch mode)."

    def format_content(self, arguments: dict, result: str) -> str:
        return ""
