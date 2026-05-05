"""Skill for reading file content."""

from typing import Any
from .base import Skill


class ReadFileSkill(Skill):
    """Skill for reading file content."""
    name = "read_file"
    alias = "Read"
    description = "Read file content (entire file or specific line range)"

    def get_schema(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": "Read file content. If line_range is not specified, reads the entire file. If show is not specified, no highlighting.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "path": {
                            "type": "string",
                            "description": "File path to read. Use relative path from current directory, e.g. 'skills.py', 'prompts/system_prompt.txt'"
                        },
                        "line_range": {
                            "type": "string",
                            "description": "Specific line range to read. Format: 'start-end' (e.g., '12-22'). Optional - if not specified, reads entire file."
                        },
                        "show": {
                            "type": "string",
                            "description": "Keyword to highlight in yellow background. Optional - if not specified, no highlighting."
                        }
                    },
                    "required": ["path"]
                }
            }
        }

    def execute(self, path: str = "", line_range: str = "", show: str = "", **kwargs: Any) -> str:
        if not path:
            return "Error: Missing 'path' parameter."

        try:
            with open(path, 'r', encoding='utf-8', errors='ignore') as f:
                lines = f.readlines()
        except FileNotFoundError:
            return f"Error: File not found: {path}"
        except (IOError, OSError) as e:
            return f"Error: {str(e)}"

        total_lines = len(lines)

        if not line_range:
            content = ''.join(lines)
            title = f"{path} (1-{total_lines})"
        else:
            try:
                parts = line_range.split('-')
                start = int(parts[0].strip())
                end = int(parts[1].strip())
            except (ValueError, IndexError):
                return f"Error: Invalid line_range format. Use 'start-end' (e.g., '12-22')"

            if start < 1 or end > total_lines or start > end:
                return f"Error: Line range {start}-{end} is out of bounds (file has {total_lines} lines)"

            content = ''.join(lines[start-1:end])
            title = f"{path}:{start}-{end}"

        extras = []
        if show:
            extras.append(show)
        if extras:
            title += " (" + ", ".join(extras) + ")"

        result_parts = [f"Success: Read {title}", f"\nContent:\n{content}"]

        return '\n'.join(result_parts)

