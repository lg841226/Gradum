# Copyright (c) 2026 Gradum Authors, Ge Wangyang. Licensed under MIT.
# See LICENSE for details.

"""Skill for editing file content using search and replace (like human select-paste)."""

import os
from typing import Any, Optional, Tuple

from .base import Skill, make_error, make_success

# Maximum file size in bytes (1 MB)
MAX_FILE_SIZE = 1 * 1024 * 1024


class EditFileSkill(Skill):
    """Skill for editing file content using search and replace."""

    name = "edit_file"
    alias = "Edited"
    description = "Replace code using search-replace. Supports single or batch edits."

    def get_schema(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": "Edit a file by applying one or more search-replace operations. "
                "Each edit replaces the FIRST occurrence of 'search' with 'replace' "
                "(match is exact, byte-for-byte). Use the 'edits' array even for a "
                "single edit. For multiple edits to the same file, include them all "
                "in one 'edits' array — they are applied in order.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "path": {
                            "type": "string",
                            "description": "File path to edit (e.g., 'main.py').",
                        },
                        "edits": {
                            "type": "array",
                            "minItems": 1,
                            "items": {
                                "type": "object",
                                "properties": {
                                    "search": {
                                        "type": "string",
                                        "description": "The EXACT text to find. Must match byte-for-byte "
                                        "(whitespace, indentation, newlines all matter). "
                                        "Include 2-3 lines of surrounding context to ensure "
                                        "uniqueness. If unsure, call read_file first to see "
                                        "the current content.",
                                    },
                                    "replace": {
                                        "type": "string",
                                        "description": "The new text to insert in place of the search text. "
                                        'Use empty string "" to delete the matched block. '
                                        "Preserve indentation exactly as you want it to appear.",
                                    },
                                },
                                "required": ["search", "replace"],
                            },
                            "description": "One or more search-replace operations to apply in order. "
                            "Required: at least one edit. "
                            'Example: edits: [{"search": "old code", "replace": "new code"}]',
                        },
                        "mode": {
                            "type": "string",
                            "enum": ["sequential", "atomic"],
                            "description": "'sequential' (default): apply as many edits as possible, report "
                            "which failed. 'atomic': all edits succeed or all roll back. "
                            "IGNORED when 'edits' has a single element.",
                        },
                    },
                    "required": ["path", "edits"],
                },
            },
        }

    def execute(self, path: str = "", edits: list = None, mode: str = "sequential") -> dict:
        """Execute file edit operation using search and replace."""
        if not path:
            return make_error(
                self.name,
                "INVALID_PARAMETER",
                "Missing 'path' parameter",
            )

        if not isinstance(edits, list) or len(edits) == 0:
            return make_error(
                self.name,
                "INVALID_PARAMETER",
                "Missing 'edits' parameter. Provide at least one search-replace pair, "
                'e.g. edits: [{"search": "old", "replace": "new"}].',
                path=path,
            )

        return EditFileSkill._execute_edits(path, edits, mode)

    @staticmethod
    def _execute_edits(path: str, edits: list, mode: str) -> dict:
        """Execute one or more search-replace edits in a unified flow."""
        # Validate all edits first
        for i, edit in enumerate(edits):
            if (
                not isinstance(edit, dict)
                or "search" not in edit
                or "replace" not in edit
                or not isinstance(edit["search"], str)
                or not isinstance(edit["replace"], str)
            ):
                return make_error(
                    EditFileSkill.name,
                    "INVALID_PARAMETER",
                    f"Edit {i} must be a dict with string 'search' and 'replace' fields.",
                    path=path,
                )

        # Read file with encoding detection
        content, encoding = EditFileSkill._read_file(path)
        if content is None:
            return make_error(
                EditFileSkill.name,
                "FILE_NOT_FOUND",
                f"File not found: {path}",
                path=path,
            )
        if encoding is None:
            return make_error(
                EditFileSkill.name,
                "IO_ERROR",
                f"Could not read file with any supported encoding: {path}",
                path=path,
            )

        # Check file size
        try:
            file_size = os.path.getsize(path)
            if file_size > MAX_FILE_SIZE:
                return make_error(
                    EditFileSkill.name,
                    "FILE_TOO_LARGE",
                    f"File too large for editing: {file_size:,} bytes (max: {MAX_FILE_SIZE:,} bytes).",
                    path=path,
                    file_size=file_size,
                )
        except OSError:
            pass

        original = content
        diff_list = []

        # Apply each edit
        for i, edit in enumerate(edits):
            search, replace = edit["search"], edit["replace"]
            occurrences = content.count(search)

            if occurrences == 0:
                return EditFileSkill._handle_batch_error(
                    mode,
                    path,
                    content,
                    original,
                    i,
                    len(edits),
                    "CODE_NOT_FOUND",
                    encoding=encoding,
                )

            if occurrences > 1:
                return EditFileSkill._handle_batch_error(
                    mode,
                    path,
                    content,
                    original,
                    i,
                    len(edits),
                    "MULTIPLE_MATCHES",
                    occurrences,
                    encoding=encoding,
                )

            content = content.replace(search, replace, 1)
            diff_list.append(
                {
                    "index": i + 1,
                    "removed_lines": search.count("\n") + 1,
                    "added_lines": replace.count("\n") + 1,
                }
            )

        # Check for empty result
        if not content.strip():
            EditFileSkill._write_file(path, original, encoding)
            return make_error(
                EditFileSkill.name,
                "EMPTY_RESULT",
                "Edits would result in an empty file. Original content restored.",
                path=path,
            )

        # Write changes with detected encoding
        if not EditFileSkill._write_file(path, content, encoding):
            EditFileSkill._write_file(path, original, encoding)
            return make_error(
                EditFileSkill.name,
                "IO_ERROR",
                "Failed to write changes. Original content restored.",
                path=path,
            )

        return make_success(
            EditFileSkill.name,
            path=path,
            edits_applied=len(edits),
            of=len(edits),
        )

    @staticmethod
    def _read_file(path: str) -> Tuple[Optional[str], Optional[str]]:
        """Read file content with encoding detection.

        Tries multiple encodings in order. Returns (content, encoding) on success,
        or (None, None) if file not found, (content, None) if no encoding works.

        Args:
            path: File path to read

        Returns:
            Tuple of (content, encoding) or (None, None) if file not found
        """
        try:
            # Try UTF-8 first (most common)
            with open(path, "r", encoding="utf-8") as file:
                return file.read(), "utf-8"
        except FileNotFoundError:
            return None, None
        except (IOError, OSError):
            return None, None
        except UnicodeDecodeError:
            pass

        # UTF-8 failed, try other encodings
        for enc in ["gbk", "gb2312", "big5", "iso-8859-1"]:
            try:
                with open(path, "r", encoding=enc) as file:
                    return file.read(), enc
            except (UnicodeDecodeError, IOError, OSError):
                continue

        # File exists but no encoding worked
        return "", None

    @staticmethod
    def _write_file(path: str, content: str, encoding: str = "utf-8") -> bool:
        """Write content to file using specified encoding.

        Args:
            path: File path to write
            content: Content to write
            encoding: Encoding to use (default: utf-8)

        Returns:
            True on success, False on failure
        """
        try:
            with open(path, "w", encoding=encoding) as file:
                file.write(content)
            return True
        except (IOError, OSError):
            return False

    @staticmethod
    def _handle_batch_error(
        mode: str,
        path: str,
        current_content: str,
        original_content: str,
        i: int,
        total: int,
        error_code: str,
        occurrences: int = 0,
        encoding: str = "utf-8",
    ) -> dict:
        """Handle errors during batch edits."""
        edit_num = i + 1

        if error_code == "CODE_NOT_FOUND":
            base_msg = f"Edit {edit_num} failed: Code not found."
        elif error_code == "MULTIPLE_MATCHES":
            base_msg = f"Edit {edit_num} failed: Found {occurrences} matches."
        else:
            base_msg = f"Edit {edit_num} failed."

        if mode == "sequential":
            msg = base_msg
            if i > 0:
                msg += f" {i} edit(s) applied before failure."
            EditFileSkill._write_file(path, current_content, encoding)
            result = make_error(
                EditFileSkill.name,
                error_code,
                msg,
                path=path,
                partial=True,
                applied=i,
                of=total,
                failed_at=edit_num,
            )
            if occurrences:
                result["occurrences"] = occurrences
            return result
        else:
            msg = base_msg + " Original content restored."
            EditFileSkill._write_file(path, original_content, encoding)
            return make_error(
                EditFileSkill.name,
                error_code,
                msg,
                path=path,
                failed_at=edit_num,
            )
