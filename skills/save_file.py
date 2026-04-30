"""Skill for saving content to a file."""

import os
from typing import Any

from .base import Skill


class SaveFileSkill(Skill):
    """Skill for saving content to a file."""
    name = "save_file"
    alias = "Saved"
    description = "Save content to file on local filesystem"

    def get_schema(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": {
                    "type": "object",
                    "properties": {
                        "path": {
                            "type": "string",
                            "description": "The file path where content should be saved"
                        },
                        "content": {
                            "type": "string",
                            "description": "The content to write to the file"
                        }
                    },
                    "required": ["path", "content"]
                }
            }
        }

    def execute(self, path: str = "", content: str = "") -> str:
        if not path:
            return "Error: Missing 'path' parameter."
        if not content:
            return "Error: Missing 'content' parameter."

        try:
            directory = os.path.dirname(path)
            if directory and not os.path.exists(directory):
                os.makedirs(directory, exist_ok=True)

            with open(path, 'w', encoding='utf-8') as f:
                f.write(content)
            return f"Success: File saved to {path}"
        except (IOError, OSError) as e:
            return f"Error: {str(e)}"

