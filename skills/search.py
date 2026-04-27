"""Skill for searching keyword in files."""

import os
import time
from typing import Any

from .base import Skill


class SearchSkill(Skill):
    """Skill for searching keyword in files."""
    name = "search"
    description = "Search for keywords in all files (current dir only, no sub dirs, 2 min timeout)"
    alias = "Searched"

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
                        }
                    }
                }
            }
        }

    def execute(self, **kwargs: Any) -> str:
        keyword = kwargs.get("keyword", "")
        keywords = kwargs.get("keywords", [])

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
        timeout = 120

        for kw in search_keywords:
            if time.time() - start_time > timeout:
                break

            for filename in os.listdir('.'):
                if time.time() - start_time > timeout:
                    break

                if os.path.isfile(filename):
                    try:
                        with open(filename, 'r', encoding='utf-8', errors='ignore') as f:
                            for line_no, line in enumerate(f, 1):
                                if time.time() - start_time > timeout:
                                    break
                                if kw.lower() in line.lower():
                                    result_key = f"{filename}:{line_no}"
                                    if result_key not in seen:
                                        seen.add(result_key)
                                        results.append(f"{filename}:{line_no}: {line.strip()}")
                    except (IOError, OSError):
                        pass

        if time.time() - start_time > timeout:
            return f"Success: Search timeout, found {len(results)} matches:\n" + "\n".join(results[:5])

        if not results:
            return f"Error: No matches found for: {' · '.join(search_keywords)}"

        return f"Success: Found {len(results)} matches:\n" + "\n".join(results[:5])

    def format_content(self, arguments: dict, result: str) -> str:
        lines = result.split("\n")
        return "\n".join(lines[:5])

    def format_args(self, arguments: dict) -> str:
        keywords = arguments.get("keywords", [])
        keyword = arguments.get("keyword", "")
        if keywords:
            return " · ".join(keywords[:5])
        return keyword
