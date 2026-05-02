"""Skill for searching keyword in files."""

import os
import time
from typing import Any
from .base import Skill


class SearchSkill(Skill):
    """Skill for searching keyword in files."""
    name = "search"
    alias = "Searched"
    description = "Search for keywords in files (smart recursion with file filtering)"
    
    TIMEOUT = 120
    MAX_FILES = 600
    MAX_DEPTH = 6
    
    EXCLUDE_DIRS = {
        '__pycache__', 'node_modules', 'venv', '.venv', 'ENV',
        'build', 'dist', 'output', 'target',
        'vendor', 'Pods', '.gradle', 'bin', 'obj'
    }

    def get_schema(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": {
                    "type": "object",
                    "properties": {
                        "keyword": {
                            "type": "string",
                            "description": "Single keyword to search for (for backward compatibility)"
                        },
                        "keywords": {
                            "type": "array",
                            "items": {"type": "string"},
                            "description": "List of keywords to search for (OR logic, max 5)"
                        },
                        "recursive": {
                            "type": "boolean",
                            "description": "Search subdirectories recursively (default: false, with smart filtering)"
                        },
                        "file_pattern": {
                            "type": "string",
                            "description": "File pattern to filter (e.g., '*.py', '*.md', default: all files)"
                        }
                    }
                }
            }
        }

    def execute(self, **kwargs: Any) -> str:
        keyword = kwargs.get("keyword", "")
        keywords = kwargs.get("keywords", [])
        recursive = kwargs.get("recursive", False)
        file_pattern = kwargs.get("file_pattern", "")

        search_keywords = []
        if keywords and isinstance(keywords, list):
            search_keywords = [k.strip() for k in keywords[:5] if k.strip()]
        elif keyword:
            search_keywords = [keyword.strip()]

        if not search_keywords:
            return "Error: Missing 'keyword' or 'keywords' parameter."

        results = []

        seen = set()

        start_time = time.time()

        files_processed = 0
        
        # Use class constants
        timeout = self.TIMEOUT
        max_files = self.MAX_FILES
        max_depth = self.MAX_DEPTH
        exclude_dirs = self.EXCLUDE_DIRS

        def process_file(filename: str) -> bool:
            """Check if file should be processed based on pattern."""
            if not file_pattern:
                return True
            import fnmatch
            return fnmatch.fnmatch(filename, file_pattern)

        def search_file(filepath: str) -> None:
            """Search keyword in a single file."""
            nonlocal files_processed
            try:
                with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
                    for line_no, line in enumerate(f, 1):
                        if time.time() - start_time > timeout:
                            break
                        for kw in search_keywords:
                            if kw.lower() in line.lower():
                                result_key = f"{filepath}:{line_no}"
                                if result_key not in seen:
                                    seen.add(result_key)
                                    results.append(f"{filepath}:{line_no}: {line.strip()}")
                                break
            except (IOError, OSError):
                pass
            files_processed += 1

        def process_directory(dirpath: str, depth: int = 0) -> None:
            """Process a directory with depth limit and filtering."""
            nonlocal files_processed
            if time.time() - start_time > timeout or files_processed >= max_files:
                return
            
            if depth > max_depth:
                return

            try:
                entries = os.listdir(dirpath)
            except (IOError, OSError):
                return

            for entry in entries:
                if time.time() - start_time > timeout or files_processed >= max_files:
                    break
                    
                filepath = os.path.join(dirpath, entry)
                if os.path.isfile(filepath) and process_file(entry):
                    search_file(filepath)

            if recursive:
                for entry in entries:
                    if time.time() - start_time > timeout or files_processed >= max_files:
                        break
                        
                    dirpath_entry = os.path.join(dirpath, entry)
                    if os.path.isdir(dirpath_entry) and entry not in exclude_dirs:
                        if entry.startswith('.') and entry not in {'.vscode', '.idea'}:
                            continue
                        process_directory(dirpath_entry, depth + 1)

        process_directory('.')

        extra_info = []
        if files_processed >= max_files:
            extra_info.append(f"limited to {max_files} files")
        if time.time() - start_time > timeout:
            extra_info.append("timeout")
        if recursive:
            extra_info.append("recursive")
        
        extra_str = f" ({', '.join(extra_info)})" if extra_info else ""

        if not results:
            return f"Error: No matches found for: {' · '.join(search_keywords)}{extra_str}"

        return f"Success: Found {len(results)} matches{extra_str}:\n" + "\n".join(results[:10])

    def format_content(self, arguments: dict, result: str) -> str:
        lines = result.split("\n")
        return "\n".join(lines[:5])

    def format_args(self, arguments: dict) -> str:
        keywords = arguments.get("keywords", [])
        keyword = arguments.get("keyword", "")
        if keywords:
            return " · ".join(keywords[:5])
        return keyword
