"""Skill for marking to-do item completion."""

from typing import Any, Optional

from .base import Skill
from .todo import get_todo_manager


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

    def execute(self, to_do_items_completed: Optional[int] = None, completed_count: Optional[int] = None, **kwargs: Any) -> dict:
        pm = get_todo_manager()

        if not pm.initialized:
            return {
                "success": False,
                "error": {
                    "code": "NOT_INITIALIZED",
                    "message": "No to-do item list initialized. Call to_do() first."
                }
            }

        total = len(pm.tasks)

        # Batch mode
        if completed_count is not None:
            completed_count = int(completed_count)
            if completed_count <= 0:
                return {
                    "success": False,
                    "error": {
                        "code": "INVALID_PARAMETER",
                        "message": "completed_count must be a positive integer"
                    }
                }

            new_total = pm.completed + completed_count

            if new_total > total:
                return {
                    "success": False,
                    "error": {
                        "code": "OUT_OF_BOUNDS",
                        "message": f"Cannot complete {completed_count} items. Only {total - pm.completed} items remaining"
                    },
                    "total_tasks": total,
                    "completed": pm.completed,
                    "remaining": total - pm.completed
                }

            for i in range(pm.completed, new_total):
                pm.completed = i + 1

            tasks_with_status = [
                {"index": i + 1, "name": t, "status": "completed" if i < pm.completed else "pending"}
                for i, t in enumerate(pm.tasks)
            ]

            return {
                "success": True,
                "tool": "finish_to_do_item",
                "total_tasks": total,
                "completed": pm.completed,
                "remaining": total - pm.completed,
                "current_task": pm.completed + 1 if pm.completed < total else None,
                "current_task_name": pm.get_current_task(),
                "tasks": tasks_with_status
            }

        # Sequential mode
        if to_do_items_completed is not None:
            to_do_items_completed = int(to_do_items_completed)

            if to_do_items_completed == pm.completed:
                current_task = pm.get_current_task()
                return {
                    "success": True,
                    "tool": "finish_to_do_item",
                    "total_tasks": total,
                    "completed": pm.completed,
                    "remaining": total - pm.completed,
                    "current_task": pm.completed + 1 if pm.completed < total else None,
                    "current_task_name": current_task,
                    "message": f"No new tasks completed. Current progress: {pm.completed}/{total}"
                }

            pm.last_complete_call = to_do_items_completed

            expected = pm.completed
            if to_do_items_completed > expected + 1:
                skipped = to_do_items_completed - expected
                return {
                    "success": False,
                    "error": {
                        "code": "SKIP_NOT_ALLOWED",
                        "message": f"Cannot skip {skipped} items directly. Use completed_count={skipped} to mark multiple items as done at once."
                    },
                    "total_tasks": total,
                    "completed": pm.completed
                }
            if to_do_items_completed < expected:
                return {
                    "success": False,
                    "error": {
                        "code": "CANNOT_GO_BACK",
                        "message": f"Cannot go backwards. You already completed {expected} to-do item(s)."
                    },
                    "total_tasks": total,
                    "completed": pm.completed
                }

            max_completed = len(pm.tasks)
            if to_do_items_completed > max_completed:
                to_do_items_completed = max_completed
            pm.completed = to_do_items_completed

            tasks_with_status = [
                {"index": i + 1, "name": t, "status": "completed" if i < pm.completed else "pending"}
                for i, t in enumerate(pm.tasks)
            ]

            return {
                "success": True,
                "tool": "finish_to_do_item",
                "total_tasks": total,
                "completed": pm.completed,
                "remaining": total - pm.completed,
                "current_task": pm.completed + 1 if pm.completed < total else None,
                "current_task_name": pm.get_current_task(),
                "tasks": tasks_with_status
            }

        return {
            "success": False,
            "error": {
                "code": "INVALID_PARAMETER",
                "message": "Must provide either 'to_do_items_completed' (sequential mode) or 'completed_count' (batch mode)"
            }
        }
