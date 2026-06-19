# Copyright (c) 2026 Gradum Authors, Ge Wangyang. Licensed under MIT.
# See LICENSE for details.

"""Skill for reading file content."""

import hashlib
import os
from pathlib import Path
from typing import Any

from .base import Skill, make_error, make_success

# Maximum file size in bytes (1 MB)
MAX_FILE_SIZE = 1 * 1024 * 1024

# Maximum lines to read
MAX_LINES = 10000


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
                            "description": "File path to read. Use relative path from current directory, e.g. 'skills.py', 'prompts/system_prompt.txt'",
                        },
                        "line_range": {
                            "type": "string",
                            "description": "Specific line range to read. Format: 'start-end' (e.g., '12-22'). Optional - if not specified, reads entire file.",
                        },
                        "show": {
                            "type": "string",
                            "description": "Keyword to highlight in yellow background. Optional - if not specified, no highlighting.",
                        },
                    },
                    "required": ["path"],
                },
            },
        }

    def execute(self, path: str = "", line_range: str = "", show: str = "", **kwargs: Any) -> dict:
        """Execute file read and return structured result."""
        if not path:
            return make_error(
                self.name,
                "INVALID_PARAMETER",
                "Missing 'path' parameter",
            )

        try:
            file_path = Path(path).expanduser()  # Expand ~ to home directory

            # Check file size before reading
            try:
                file_size = os.path.getsize(file_path)
            except OSError:
                file_size = 0

            if file_size > MAX_FILE_SIZE:
                return make_error(
                    self.name,
                    "FILE_TOO_LARGE",
                    f"File too large: {file_size:,} bytes (max: {MAX_FILE_SIZE:,} bytes). "
                    f"Use line_range to read specific sections.",
                    path=path,
                    file_size=file_size,
                )

            with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
                lines = f.readlines()
        except FileNotFoundError:
            return make_error(
                self.name,
                "FILE_NOT_FOUND",
                f"File not found: {path}",
                path=path,
            )
        except (IOError, OSError) as e:
            return make_error(
                self.name,
                "IO_ERROR",
                str(e),
                path=path,
            )

        total_lines = len(lines)

        # Check line count limit
        if total_lines > MAX_LINES and not line_range:
            return make_error(
                self.name,
                "FILE_TOO_LARGE",
                f"File has {total_lines:,} lines (max: {MAX_LINES:,}). "
                f"Use line_range to read specific sections (e.g., '1-100').",
                path=path,
                total_lines=total_lines,
            )

        if not line_range:
            content = "".join(lines)
            start_line = 1
            end_line = total_lines
        else:
            try:
                parts = line_range.split("-")
                start_line = int(parts[0].strip())
                end_line = int(parts[1].strip())
            except (ValueError, IndexError):
                return make_error(
                    self.name,
                    "INVALID_PARAMETER",
                    "Invalid line_range format. Use 'start-end' (e.g., '12-22')",
                    path=path,
                    line_range=line_range,
                )

            # Auto-adjust range to fit file bounds
            if start_line < 1:
                start_line = 1
            if end_line > total_lines:
                end_line = total_lines
            if start_line > end_line:
                start_line, end_line = end_line, start_line

            content = "".join(lines[start_line - 1 : end_line])

        return make_success(
            self.name,
            path=str(file_path),  # Return expanded path
            line_range=f"{start_line}-{end_line}",
            total_lines=total_lines,
            content_hash=hashlib.md5(content.encode("utf-8")).hexdigest(),
            content=content,
        )
