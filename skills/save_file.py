"""Skill for saving content to a file."""

from pathlib import Path
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

    def execute(self, path: str = "", content: str = "") -> dict:
        """Execute file save and return structured result."""
        if not path:
            return {
                "success": False,
                "error": {
                    "code": "INVALID_PARAMETER",
                    "message": "Missing 'path' parameter"
                }
            }
        if not content:
            return {
                "success": False,
                "error": {
                    "code": "INVALID_PARAMETER",
                    "message": "Missing 'content' parameter"
                },
                "path": path
            }

        try:
            file_path = Path(path).expanduser()  # Expand ~ to home directory
            existed = file_path.exists()
            file_path.parent.mkdir(parents=True, exist_ok=True)

            with open(file_path, 'w', encoding='utf-8') as f:
                bytes_written = f.write(content)

            return {
                "success": True,
                "tool": "save_file",
                "path": str(file_path),  # Return expanded path
                "bytes_written": bytes_written,
                "created": not existed
            }
        except (IOError, OSError) as e:
            return {
                "success": False,
                "error": {
                    "code": "IO_ERROR",
                    "message": str(e)
                },
                "path": path
            }

