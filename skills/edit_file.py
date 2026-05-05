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
                "description": "Replace code using search-replace. Supports single or batch edits.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "path": {
                            "type": "string",
                            "description": "File path to edit (e.g., 'main.py')"
                        },
                        "search": {
                            "type": "string",
                            "description": "Exact code to find (include context for uniqueness)"
                        },
                        "replace": {
                            "type": "string",
                            "description": "New code to insert"
                        },
                        "edits": {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "search": {
                                        "type": "string",
                                        "description": "Exact code to find"
                                    },
                                    "replace": {
                                        "type": "string",
                                        "description": "New code to insert"
                                    }
                                },
                                "required": ["search", "replace"]
                            },
                            "description": "Batch edits: array of search-replace pairs"
                        }
                    },
                    "required": ["path"]
                }
            }
        }

    def execute(self, path: str = "", search: str = "", replace: str = "", 
                edits: list = None, **kwargs: Any) -> str:
        """Execute file edit operation using search and replace.
        
        Args:
            path: File path to edit
            search: The exact code to find and replace (for single edit mode)
            replace: The new code to insert (for single edit mode)
            edits: List of search-replace pairs (for batch mode)
            
        Returns:
            Success message with details of what was changed, or error description
        """
        if not path:
            return "Error: Missing 'path' parameter."

        if edits is not None and isinstance(edits, list) and len(edits) > 0:
            return EditFileSkill._execute_batch_edits(path, edits)

        if not search:
            return "Error: Missing 'search' parameter. Provide the exact code to find."
        if replace is None:
            return "Error: Missing 'replace' parameter. Use empty string to delete code."
        if not isinstance(replace, str):
            return "Error: 'replace' parameter must be a string."

        return EditFileSkill._execute_single_edit(path, search, replace)

    @staticmethod
    def _execute_single_edit(path: str, search: str, replace: str) -> str:
        """Execute a single search-replace edit."""
        try:
            with open(path, 'r', encoding='utf-8') as file:
                content = file.read()
        except FileNotFoundError:
            return f"Error: File not found: {path}"
        except (IOError, OSError) as e:
            return f"Error: {str(e)}"

        occurrences = content.count(search)
        
        if occurrences == 0:
            return (f"Error: Code not found in {path}\n"
                   f"Reason: Search text doesn't match file content exactly.\n"
                   f"Fix: Call read_file first, then include more context (2-3 surrounding lines).")
        
        if occurrences > 1:
            return (f"Error: Found {occurrences} matches in {path}\n"
                   f"Reason: Search text appears multiple times.\n"
                   f"Fix: Include more unique context in 'search' parameter.")
        
        new_content = content.replace(search, replace)
        
        if not new_content.strip():
            return "Error: Replacement would result in an empty file. Operation cancelled."
        
        try:
            with open(path, 'w', encoding='utf-8') as file:
                file.write(new_content)
            
            search_lines = search.count('\n') + 1
            replace_lines = replace.count('\n') + 1
            
            def clean_preview(text, max_len=60):
                if not text.strip():
                    return "(deleted)"
                first_line = text.strip().split('\n')[0].strip()
                return (first_line[:max_len] + "...") if len(first_line) > max_len else first_line
            
            result_lines = [
                f"Success: Modified {path}",
                f"Lines: {search_lines} → {replace_lines}",
                f"Change: {clean_preview(search)} → {clean_preview(replace)}",
                "\nEdit complete! No need to repeatedly check the content - it may waste unnecessary time."
            ]
            
            return '\n'.join(result_lines)
            
        except (IOError, OSError) as e:
            try:
                with open(path, 'w', encoding='utf-8') as file:
                    file.write(content)
            except (IOError, OSError) as restore_err:
                return (
                    f"Error: Failed to write changes: {e}. "
                    f"Additionally, failed to restore original content: {restore_err}"
                )
            return f"Error: Failed to write changes: {e}. Original content restored."

    @staticmethod
    def _execute_batch_edits(path: str, edits: list) -> str:
        """Execute multiple search-replace edits in batch mode.
        
        All edits are applied to the original file content in sequence.
        """
        if not isinstance(edits, list):
            return "Error: 'edits' must be a list."
        
        if len(edits) == 0:
            return "Error: 'edits' list is empty."

        try:
            with open(path, 'r', encoding='utf-8') as file:
                content = file.read()
        except FileNotFoundError:
            return f"Error: File not found: {path}"
        except (IOError, OSError) as e:
            return f"Error: {str(e)}"

        original_content = content
        changes_summary = []

        for i, edit in enumerate(edits):
            if not isinstance(edit, dict):
                return f"Error: Edit {i} must be a dictionary."
            
            if 'search' not in edit or 'replace' not in edit:
                return f"Error: Edit {i} missing required fields ('search' and 'replace')."
            
            search = edit['search']
            replace = edit['replace']
            
            occurrences = content.count(search)
            
            if occurrences == 0:
                try:
                    with open(path, 'w', encoding='utf-8') as file:
                        file.write(original_content)
                except (IOError, OSError) as restore_err:
                    return (
                        f"Error: Edit {i+1} failed: Code not found. "
                        f"Additionally, failed to restore original content: {restore_err}"
                    )
                return f"Error: Edit {i+1} failed: Code not found. Original content restored."
            
            if occurrences > 1:
                try:
                    with open(path, 'w', encoding='utf-8') as file:
                        file.write(original_content)
                except (IOError, OSError) as restore_err:
                    return (
                        f"Error: Edit {i+1} failed: Found {occurrences} matches. "
                        f"Additionally, failed to restore original content: {restore_err}"
                    )
                return f"Error: Edit {i+1} failed: Found {occurrences} matches. Original content restored."
            
            content = content.replace(search, replace)
            
            search_lines = search.count('\n') + 1
            replace_lines = replace.count('\n') + 1
            changes_summary.append(f"Edit {i+1}: {search_lines}→{replace_lines} lines")
        
        if not content.strip():
            try:
                with open(path, 'w', encoding='utf-8') as file:
                    file.write(original_content)
            except (IOError, OSError) as restore_err:
                return (
                    "Error: Edits would result in an empty file. "
                    f"Additionally, failed to restore original content: {restore_err}"
                )
            return "Error: Edits would result in an empty file. Operation cancelled. Original content restored."
        
        try:
            with open(path, 'w', encoding='utf-8') as file:
                file.write(content)
            
            result_lines = [f"Success: Batch edited {path} ({len(edits)} ops)"]
            result_lines.extend(changes_summary)
            result_lines.append("\nEdit complete! No need to repeatedly check the content, it may waste unnecessary time.")
            
            return '\n'.join(result_lines)
            
        except (IOError, OSError) as e:
            try:
                with open(path, 'w', encoding='utf-8') as file:
                    file.write(original_content)
            except (IOError, OSError) as restore_err:
                return (
                    f"Error: Failed to write changes: {e}. "
                    f"Additionally, failed to restore original content: {restore_err}"
                )
            return f"Error: Failed to write changes: {e}. Original content restored."
