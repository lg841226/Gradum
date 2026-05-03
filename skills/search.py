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
                "description": "Search for text in files, or find files/directories by name",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "keyword": {
                            "type": "string",
                            "description": "Text to search inside files (class names, function names, code snippets, error messages)"
                        },
                        "keywords": {
                            "type": "array",
                            "items": {"type": "string"},
                            "description": "Multiple keywords (OR logic, max 5)"
                        },
                        "filename": {
                            "type": "string",
                            "description": "Find files by name (partial match, e.g. 'test' finds 'test.py')"
                        },
                        "dirname": {
                            "type": "string",
                            "description": "Find directories by name (partial match, e.g. 'src' finds 'src/')"
                        },
                        "recursive": {
                            "type": "boolean",
                            "description": "Search subdirectories (default: false)"
                        },
                        "file_pattern": {
                            "type": "string",
                            "description": "Filter by extension (e.g., '*.py', '*.js')"
                        }
                    }
                }
            }
        }

    def execute(self, **kwargs: Any) -> str:
        keyword = kwargs.get("keyword", "")
        keywords = kwargs.get("keywords", [])
        filename = kwargs.get("filename", "")
        dirname = kwargs.get("dirname", "")
        recursive = kwargs.get("recursive", False)
        file_pattern = kwargs.get("file_pattern", "")

        search_keywords = []
        if keywords and isinstance(keywords, list):
            search_keywords = [k.strip() for k in keywords[:5] if k.strip()]
        elif keyword:
            search_keywords = [keyword.strip()]

        has_keyword_search = bool(search_keywords)
        has_filename_search = bool(filename.strip())
        has_dirname_search = bool(dirname.strip())

        if not has_keyword_search and not has_filename_search and not has_dirname_search:
            return "Error: Missing 'keyword', 'keywords', 'filename', or 'dirname' parameter."

        results = []
        seen = set()
        start_time = time.time()
        files_processed = 0

        timeout = self.TIMEOUT
        max_files = self.MAX_FILES
        max_depth = self.MAX_DEPTH
        exclude_dirs = self.EXCLUDE_DIRS

        def process_file(filename: str) -> bool:
            if not file_pattern:
                return True
            import fnmatch
            return fnmatch.fnmatch(filename, file_pattern)

        def search_file(filepath: str) -> None:
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

        def search_filename_in_path(filepath: str, entry: str) -> bool:
            if filename.lower() in entry.lower():
                result_key = f"file:{filepath}"
                if result_key not in seen:
                    seen.add(result_key)
                    results.append(f"file: {filepath}")
                    return True
            return False

        def process_directory(dirpath: str, depth: int = 0) -> None:
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

                if has_filename_search and os.path.isfile(filepath):
                    if process_file(entry) and search_filename_in_path(filepath, entry):
                        if not has_keyword_search:
                            files_processed += 1

                if has_keyword_search and os.path.isfile(filepath) and process_file(entry):
                    search_file(filepath)

                if has_dirname_search and os.path.isdir(filepath):
                    if dirname.lower() in entry.lower():
                        result_key = f"dir:{filepath}"
                        if result_key not in seen:
                            seen.add(result_key)
                            results.append(f"dir: {filepath}")

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
            search_type = []
            if has_keyword_search:
                search_type.append('content')
            if has_filename_search:
                search_type.append('filename')
            if has_dirname_search:
                search_type.append('dirname')
            type_str = ' or '.join(search_type)
            return f"Error: No matches found for {type_str}: {', '.join(filter(None, [filename, dirname] + search_keywords))}{extra_str}"

        return f"Success: Found {len(results)} matches{extra_str}:\n" + "\n".join(results[:20])

    def format_content(self, arguments: dict, result: str) -> str:
        lines = result.split("\n")
        return "\n".join(lines[:5])

    def format_args(self, arguments: dict) -> str:
        parts = []
        keywords = arguments.get("keywords", [])
        keyword = arguments.get("keyword", "")
        filename = arguments.get("filename", "")
        dirname = arguments.get("dirname", "")

        if keyword:
            parts.append(f"content: {keyword}")
        elif keywords:
            parts.append(f"content: {' · '.join(keywords[:5])}")
        if filename:
            parts.append(f"file: {filename}")
        if dirname:
            parts.append(f"dir: {dirname}")

        return ", ".join(parts) if parts else str(arguments)
