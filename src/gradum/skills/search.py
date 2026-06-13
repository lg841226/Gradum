"""Skill for searching text in files, plus filename and dirname discovery."""

import fnmatch
import os
import time
from pathlib import Path
from typing import Any

from .base import Skill


def _get_dir_depth(path: str, root: Path) -> int:
    """
    Calculate the directory depth relative to the search root.

    Returns -1 if the path is outside the root.
    """
    try:
        rel = Path(path).relative_to(root)
        return len(rel.parts) - 1  # -1 to exclude the root directory itself
    except ValueError:
        return -1


def _sort_key(match: dict, root_path: Path) -> tuple:
    """
    Sort key for search results.

    Sort order (multi-level):
    1. By directory depth (ascending) - shallower first
    2. By parent directory path (lexicographic)
    3. By file path (lexicographic)
    4. By line number (for content matches)

    This ensures results are grouped by directory hierarchy,
    making it easier for the LLM to understand project structure.
    """
    result_path = match.get("path", "")
    result_type = match.get("type", "")

    # Calculate depth relative to root
    depth = _get_dir_depth(result_path, root_path)

    # Files in excluded dirs (depth < 0) are sorted to the end
    if depth < 0:
        depth = 0

    # Get the directory component for grouping
    if result_type == "file":
        parent_dir = str(Path(result_path).parent)
    else:
        parent_dir = result_path

    return depth, parent_dir, result_path, match.get("line", 0)


class SearchSkill(Skill):
    """Skill for searching text in files, plus filename and dirname discovery."""
    name = "search"
    alias = "Explored"
    description = "Find text in file content, file names, or directory names (recursive)."

    TIMEOUT = 120
    MAX_FILES = 600
    MAX_DEPTH = 6
    MAX_KEYWORDS = 5
    HARD_MAX_RESULTS = 20
    CONTEXT_LINES = 2

    EXCLUDE_DIRS = {
        '__pycache__', 'node_modules', 'venv', '.venv', 'ENV',
        'build', 'dist', 'output', 'target',
        'vendor', 'Pods', '.gradle', 'bin', 'obj',
        '.mypy_cache', '.pytest_cache', '.tox', '.nox',
        'coverage', '.coverage', '__snapshots__',
    }
    DOTDIR_WHITELIST = {'.vscode', '.idea', '.github', '.gitlab'}

    def get_schema(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": (
                    "Find text in file content, file names, or directory names. "
                    "Recursive by default; skips common build/dependency directories "
                    "(node_modules, venv, __pycache__, dist, etc.). "
                    "Use this when you don't know which file contains what you're looking for, "
                    "or when you want to discover files by name. "
                    "For reading a file you already know the path of, use read_file. "
                    "For listing directory contents, use run_cmd with 'ls'. "
                    f"Returns up to {self.HARD_MAX_RESULTS} matches; on larger result sets the "
                    "response sets truncated=true with a hint on how to narrow the query."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "keyword": {
                            "anyOf": [
                                {"type": "string", "minLength": 1},
                                {
                                    "type": "array",
                                    "items": {"type": "string", "minLength": 1},
                                    "minItems": 1,
                                    "maxItems": 5
                                }
                            ],
                            "description": (
                                "Text to search for inside file content (case-insensitive substring match). "
                                "Pass a string for one term (e.g. 'UserService', 'def main', 'NullPointer'), "
                                "or an array of up to 5 strings for OR-logic multi-search "
                                "(e.g. ['error', 'exception']). "
                                "If more than 5 are passed, only the first 5 are used and the response "
                                "sets truncated=true with a hint."
                            )
                        },
                        "filename": {
                            "type": "string",
                            "description": (
                                "Find files whose name contains this substring "
                                "(case-insensitive, partial match). "
                                "Examples: filename='test' matches 'test.py', 'test_utils.py', 'latest_test.txt'. "
                                "For exact extension or glob filtering, use file_pattern instead."
                            )
                        },
                        "dirname": {
                            "type": "string",
                            "description": (
                                "Find directories whose name contains this substring "
                                "(case-insensitive, partial match, recursive). "
                                "Example: dirname='src' matches 'src/' and 'src/utils/'."
                            )
                        },
                        "file_pattern": {
                            "type": "string",
                            "description": (
                                "Restrict to files matching this glob pattern (fnmatch syntax: "
                                "'*' wildcard, '?' single char). "
                                "Examples: '*.py' for Python files, '*.test.js' for test files, "
                                "'package.json' for exact name. "
                                "Applies to both keyword and filename search. "
                                "Recommended on large codebases to keep results focused."
                            )
                        },
                        "root": {
                            "type": "string",
                            "description": (
                                "Directory to start searching from (relative to project root). "
                                "Default is the project root. "
                                "Example: root='src/gradum' to only search within that subtree."
                            )
                        }
                    }
                }
            }
        }

    def execute(self, **kwargs: Any) -> dict:
        """Execute search and return structured result."""
        keyword = kwargs.get("keyword", "")
        filename = kwargs.get("filename", "")
        dirname = kwargs.get("dirname", "")
        file_pattern = kwargs.get("file_pattern", "")
        root = kwargs.get("root", ".")

        search_keywords, keyword_overflow = self._normalize_keywords(keyword)
        search_keywords_lower = [kw.lower() for kw in search_keywords]

        has_keyword = bool(search_keywords)
        has_filename = bool(filename and filename.strip())
        has_dirname = bool(dirname and dirname.strip())

        if not has_keyword and not has_filename and not has_dirname:
            return {
                "success": False,
                "error": {
                    "code": "INVALID_PARAMETER",
                    "message": "Provide at least one of: keyword, filename, dirname."
                }
            }

        root_path = Path(root).resolve()
        if not root_path.is_dir():
            return {
                "success": False,
                "error": {
                    "code": "INVALID_PARAMETER",
                    "message": f"Root directory not found: {root}"
                }
            }

        start_time = time.time()
        results: list[dict] = []
        seen: set[str] = set()
        files_searched = 0
        timed_out = False
        hit_file_cap = False

        def matches_file_pattern(name: str) -> bool:
            if not file_pattern:
                return True
            return fnmatch.fnmatch(name, file_pattern)

        def emit(match: dict, key: str) -> None:
            if key not in seen:
                seen.add(key)
                results.append(match)

        def _read_lines_around(filepath: str, target_line: int) -> str:
            """Read lines around target_line and return a text block."""
            try:
                with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
                    lines = f.readlines()
            except (IOError, OSError):
                return ""
            start = max(0, target_line - 1 - self.CONTEXT_LINES)
            end = min(len(lines), target_line + self.CONTEXT_LINES)
            block = lines[start:end]
            return "".join(line.rstrip() + "\n" for line in block).strip()

        def search_content(filepath: str) -> None:
            nonlocal files_searched, timed_out
            try:
                with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
                    for line_no, line in enumerate(f, 1):
                        if time.time() - start_time > self.TIMEOUT:
                            timed_out = True
                            return
                        line_lower = line.lower()
                        for sk in search_keywords_lower:
                            if sk in line_lower:
                                context_text = _read_lines_around(filepath, line_no)
                                emit(
                                    {
                                        "type": "content",
                                        "path": filepath,
                                        "line": line_no,
                                        "text": line.rstrip(),
                                        "context": context_text,
                                    },
                                    f"content:{filepath}:{line_no}",
                                )
                                break
            except (IOError, OSError):
                pass
            files_searched += 1

        def process_directory(dirpath: str, depth: int = 0) -> None:
            nonlocal files_searched, timed_out, hit_file_cap
            if time.time() - start_time > self.TIMEOUT:
                timed_out = True
                return
            if files_searched >= self.MAX_FILES:
                hit_file_cap = True
                return
            if depth > self.MAX_DEPTH:
                return

            try:
                entries = os.listdir(dirpath)
            except (IOError, OSError):
                return

            for entry in entries:
                if time.time() - start_time > self.TIMEOUT:
                    timed_out = True
                    break
                filepath = os.path.join(dirpath, entry)
                if not os.path.isfile(filepath):
                    continue
                if not matches_file_pattern(entry):
                    continue

                if has_filename and filename.lower() in entry.lower():
                    emit({"type": "file", "path": filepath}, f"file:{filepath}")

                if has_keyword:
                    search_content(filepath)
                else:
                    files_searched += 1

                if files_searched >= self.MAX_FILES:
                    hit_file_cap = True
                    break

            for entry in entries:
                if time.time() - start_time > self.TIMEOUT:
                    timed_out = True
                    break
                subpath = os.path.join(dirpath, entry)
                if not os.path.isdir(subpath):
                    continue
                if entry in self.EXCLUDE_DIRS:
                    continue
                if entry.startswith('.') and entry not in self.DOTDIR_WHITELIST:
                    continue

                if has_dirname and dirname.lower() in entry.lower():
                    emit({"type": "dir", "path": subpath}, f"dir:{subpath}")

                process_directory(subpath, depth + 1)

        process_directory(str(root_path))

        results.sort(key=lambda m: _sort_key(m, root_path))

        hints: list[str] = []
        truncation_reasons: list[str] = []
        if keyword_overflow:
            hints.append(
                f"Only the first {self.MAX_KEYWORDS} keywords were used; "
                f"pass fewer or split into multiple calls."
            )
            truncation_reasons.append("keyword_overflow")
        if timed_out:
            hints.append(
                f"Search exceeded the {self.TIMEOUT}s time limit; "
                f"narrow with file_pattern or a more specific keyword."
            )
            truncation_reasons.append("timeout")
        if hit_file_cap:
            hints.append(
                f"Search hit the {self.MAX_FILES}-file cap; "
                f"narrow with file_pattern or a more specific keyword."
            )
            truncation_reasons.append("file_cap")
        if len(results) > self.HARD_MAX_RESULTS:
            hints.append(
                f"More than {self.HARD_MAX_RESULTS} matches exist; "
                f"narrow with file_pattern or a more specific keyword."
            )
            truncation_reasons.append("result_cap")

        truncated = bool(hints)
        response: dict = {
            "success": True,
            "matches": results[:self.HARD_MAX_RESULTS],
            "truncated": truncated,
            "files_searched": files_searched,
        }
        if truncated:
            response["truncation_reason"] = truncation_reasons[0]
            response["hint"] = " ".join(hints)
        return response

    @staticmethod
    def _normalize_keywords(keyword: Any) -> tuple[list[str], bool]:
        """Normalize keyword input to a list of strings. Returns (list, was_overflow)."""
        if isinstance(keyword, str):
            k = keyword.strip()
            return [k] if k else [], False
        if isinstance(keyword, list):
            cleaned = [k.strip() for k in keyword if isinstance(k, str) and k.strip()]
            overflow = len(cleaned) > SearchSkill.MAX_KEYWORDS
            return cleaned[:SearchSkill.MAX_KEYWORDS], overflow
        return [], False
