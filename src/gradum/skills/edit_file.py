"""Skill for editing file content using search and replace (like human select-paste)."""

from typing import Any

from .base import Skill


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
                            "description": "File path to edit (e.g., 'main.py')."
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
                                                       "the current content."
                                    },
                                    "replace": {
                                        "type": "string",
                                        "description": "The new text to insert in place of the search text. "
                                                       "Use empty string \"\" to delete the matched block. "
                                                       "Preserve indentation exactly as you want it to appear."
                                    }
                                },
                                "required": ["search", "replace"]
                            },
                            "description": "One or more search-replace operations to apply in order. "
                                           "Required: at least one edit. "
                                           "Example: edits: [{\"search\": \"old code\", \"replace\": \"new code\"}]"
                        },
                        "mode": {
                            "type": "string",
                            "enum": ["sequential", "atomic"],
                            "description": "'sequential' (default): apply as many edits as possible, report "
                                           "which failed. 'atomic': all edits succeed or all roll back. "
                                           "IGNORED when 'edits' has a single element."
                        }
                    },
                    "required": ["path", "edits"]
                }
            }
        }

    def execute(self, path: str = "", edits: list = None, mode: str = "sequential") -> dict:
        """Execute file edit operation using search and replace."""
        if not path:
            return {
                "success": False,
                "error": {
                    "code": "INVALID_PARAMETER",
                    "message": "Missing 'path' parameter"
                }
            }

        if not isinstance(edits, list) or len(edits) == 0:
            return {
                "success": False,
                "error": {
                    "code": "INVALID_PARAMETER",
                    "message": "Missing 'edits' parameter. Provide at least one search-replace pair, "
                               "e.g. edits: [{\"search\": \"old\", \"replace\": \"new\"}]."
                },
                "path": path
            }

        return EditFileSkill._execute_edits(path, edits, mode)

    @staticmethod
    def _execute_edits(path: str, edits: list, mode: str) -> dict:
        """Execute one or more search-replace edits in a unified flow."""
        for i, edit in enumerate(edits):
            if (not isinstance(edit, dict)
                    or 'search' not in edit
                    or 'replace' not in edit
                    or not isinstance(edit['search'], str)
                    or not isinstance(edit['replace'], str)):
                return {
                    "success": False,
                    "error": {
                        "code": "INVALID_PARAMETER",
                        "message": f"Edit {i} must be a dict with string 'search' and 'replace' fields."
                    },
                    "path": path
                }

        try:
            with open(path, 'r', encoding='utf-8') as file:
                content = file.read()
        except FileNotFoundError:
            return {
                "success": False,
                "error": {
                    "code": "FILE_NOT_FOUND",
                    "message": f"File not found: {path}"
                },
                "path": path
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

        original = content
        content.count('\n') + 1
        diff_list = []

        for i, edit in enumerate(edits):
            search, replace = edit['search'], edit['replace']
            occurrences = content.count(search)

            if occurrences == 0:
                return EditFileSkill._handle_batch_error(
                    mode, path, content, original, i, len(edits),
                    "CODE_NOT_FOUND"
                )

            if occurrences > 1:
                return EditFileSkill._handle_batch_error(
                    mode, path, content, original, i, len(edits),
                    "MULTIPLE_MATCHES", occurrences
                )

            content = content.replace(search, replace, 1)
            diff_list.append({
                "index": i + 1,
                "removed_lines": search.count('\n') + 1,
                "added_lines": replace.count('\n') + 1
            })

        if not content.strip():
            EditFileSkill._write_file(path, original)
            return {
                "success": False,
                "error": {
                    "code": "EMPTY_RESULT",
                    "message": "Edits would result in an empty file. Original content restored."
                },
                "path": path
            }

        if not EditFileSkill._write_file(path, content):
            EditFileSkill._write_file(path, original)
            return {
                "success": False,
                "error": {
                    "code": "IO_ERROR",
                    "message": "Failed to write changes. Original content restored."
                },
                "path": path
            }

        return {
            "success": True,
            "path": path,
            "edits_applied": len(edits),
            "of": len(edits)
        }

    @staticmethod
    def _write_file(path: str, content: str) -> bool:
        try:
            with open(path, 'w', encoding='utf-8') as file:
                file.write(content)
            return True
        except (IOError, OSError):
            return False

    @staticmethod
    def _handle_batch_error(mode: str, path: str, current_content: str,
                            original_content: str, i: int, total: int,
                            error_code: str, occurrences: int = 0) -> dict:
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
            EditFileSkill._write_file(path, current_content)
            result = {
                "success": True,
                "path": path,
                "partial": True,
                "applied": i,
                "of": total,
                "failed_at": edit_num,
                "error": {"code": error_code, "message": msg}
            }
            if occurrences:
                result["occurrences"] = occurrences
            return result
        else:
            msg = base_msg + " Original content restored."
            EditFileSkill._write_file(path, original_content)
            return {
                "success": False,
                "error": {"code": error_code, "message": msg},
                "path": path,
                "failed_at": edit_num
            }
