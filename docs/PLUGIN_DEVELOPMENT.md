# Gradum Plugin Development Guide

This document explains how to create custom skills (plugins) for the Gradum agent framework.

---

## 1. Architecture Overview

Gradum uses an auto-discovery plugin system. Skills are Python modules located in `src/gradum/skills/`. At startup, the `Skills` registry scans this directory and automatically registers all `Skill` subclasses.

**Key properties:**
- No manifest or registry file required
- Zero coupling between skills
- Adding a skill = dropping a `.py` file in `skills/`
- Removing a skill = deleting the file

---

## 2. Skill Base Class

All skills inherit from `Skill` (defined in `skills/base.py`):

```python
class Skill:
    """Base class for all skills."""

    name: str = ""          # Unique identifier (snake_case)
    description: str = ""   # Short description
    alias: str = ""         # Past-tense verb for logging

    def execute(self, **kwargs: Any) -> dict:
        raise NotImplementedError

    def get_schema(self) -> dict[str, Any]:
        raise NotImplementedError
```

### Required Class Attributes

| Attribute     | Type  | Purpose                                  | Example          |
|---------------|-------|------------------------------------------|------------------|
| `name`        | `str` | Unique tool name for LLM function calls  | `"search"`       |
| `description` | `str` | Brief description                        | `"Search files"` |
| `alias`       | `str` | Past-tense verb for NDJSON event display | `"Explored"`     |

### Required Methods

| Method              | Returns          | Purpose                              |
|---------------------|------------------|--------------------------------------|
| `execute(**kwargs)` | `dict`           | Implement the skill logic            |
| `get_schema()`      | `dict[str, Any]` | Return OpenAI-compatible JSON schema |

---

## 3. Return Format

### Success Response

```python
return {
    "success": True,
    # ... skill-specific result fields
}
```

### Failure Response

```python
return {
    "success": False,
    "error": {
        "code": "SCREAMING_SNAKE_CASE_ERROR_CODE",
        "message": "Human-readable description"
    }
}
```

### Standard Error Codes

| Code                  | When to Use                        |
|-----------------------|------------------------------------|
| `INVALID_PARAMETER`   | Missing or malformed arguments     |
| `FILE_NOT_FOUND`      | Target file does not exist         |
| `IO_ERROR`            | Read/write failure                 |
| `COMMAND_BLOCKED`     | Safety filter rejected command     |
| `TIMEOUT`             | Operation exceeded time limit      |
| `ALREADY_INITIALIZED` | Skill called twice (e.g., `to_do`) |

---

## 4. JSON Schema Format

Return an OpenAI-compatible function schema from `get_schema()`:

```python
def get_schema(self) -> dict[str, Any]:
    return {
        "type": "function",
        "function": {
            "name": self.name,
            "description": "What the LLM sees",
            "parameters": {
                "type": "object",
                "properties": {
                    "param_name": {
                        "type": "string",
                        "description": "What this param does"
                    },
                },
                "required": ["param_name"],
            },
        },
    }
```

The `description` field is the primary way to guide the LLM on when and how to use the skill.

---

## 5. Step-by-Step: Creating a New Skill

### 5.1 Create the File

Create `src/gradum/skills/your_skill.py`:

```python
"""Skill for doing something useful."""

from typing import Any

from .base import Skill


class YourSkill(Skill):
    """Skill for doing something useful."""

    name = "your_skill"
    description = "Does something useful for the agent"
    alias = "Used"

    def get_schema(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": (
                    "Detailed description for the LLM. "
                    "Include when to use this skill, constraints, and examples."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "input": {
                            "type": "string",
                            "description": "The input to process"
                        },
                    },
                    "required": ["input"],
                },
            },
        }

    def execute(self, input: str = "", **kwargs: Any) -> dict:
        """Execute the skill."""
        if not input:
            return {
                "success": False,
                "error": {
                    "code": "INVALID_PARAMETER",
                    "message": "Missing 'input' parameter"
                }
            }

        # Your logic here
        result = input.upper()

        return {
            "success": True,
            "result": result
        }
```

### 5.2 No Registration Needed

The auto-discovery system in `skills/__init__.py` will find your skill automatically. Just restart the agent.

### 5.3 Test Your Skill

```bash
# Start the agent
gradum

# The LLM will see your skill in the tool list
# Try: "Use your_skill to process 'hello world'"
```

---

## 6. Complete Example: File Counter Skill

This skill counts lines, words, and characters in a file.

```python
"""Skill for counting file statistics."""

from pathlib import Path
from typing import Any

from .base import Skill


class FileCounterSkill(Skill):
    """Skill for counting file statistics."""

    name = "file_counter"
    description = "Count lines, words, and characters in a file"
    alias = "Counted"

    def get_schema(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": (
                    "Count lines, words, and characters in a file. "
                    "Returns detailed statistics about the file content. "
                    "Use this when you need to understand file size or complexity."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "path": {
                            "type": "string",
                            "description": "File path to analyze"
                        },
                    },
                    "required": ["path"],
                },
            },
        }

    def execute(self, path: str = "", **kwargs: Any) -> dict:
        """Execute file counter and return statistics."""
        if not path:
            return {
                "success": False,
                "error": {
                    "code": "INVALID_PARAMETER",
                    "message": "Missing 'path' parameter"
                }
            }

        try:
            file_path = Path(path)
            with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
                content = f.read()
        except FileNotFoundError:
            return {
                "success": False,
                "error": {
                    "code": "FILE_NOT_FOUND",
                    "message": f"File not found: {path}"
                }
            }
        except (IOError, OSError) as e:
            return {
                "success": False,
                "error": {
                    "code": "IO_ERROR",
                    "message": str(e)
                }
            }

        lines = content.splitlines()
        words = content.split()
        chars = len(content)

        return {
            "success": True,
            "path": str(file_path),
            "lines": len(lines),
            "words": len(words),
            "characters": chars
        }
```

---

## 7. Best Practices

### 7.1 Parameter Handling

- Always validate required parameters
- Use `kwargs.get("param", default)` for optional parameters
- Strip whitespace from string inputs

```python
def execute(self, path: str = "", **kwargs: Any) -> dict:
    path = path.strip()
    if not path:
        return {"success": False, "error": {"code": "INVALID_PARAMETER", ...}}
```

### 7.2 Error Handling

- Catch specific exceptions first
- Always catch `IOError` and `OSError` together
- Return structured error responses

```python
try:
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
except FileNotFoundError:
    return {"success": False, "error": {"code": "FILE_NOT_FOUND", ...}}
except (IOError, OSError) as e:
    return {"success": False, "error": {"code": "IO_ERROR", "message": str(e)}}
```

### 7.3 File I/O

- Always use `encoding="utf-8"`
- Always use `with` context manager
- Use `errors="ignore"` for robustness

```python
with open(path, "r", encoding="utf-8", errors="ignore") as f:
    content = f.read()
```

### 7.4 Path Handling

- Use `pathlib.Path` throughout
- Resolve paths when needed

```python
from pathlib import Path

file_path = Path(path).resolve()
```

### 7.5 LLM Guidance

The `description` in your schema is critical. Include:
- When to use this skill
- Constraints and limitations
- Usage examples
- Related skills to use instead

---

## 8. Coding Standards

Follow the project coding standards in `CODING_STANDARDS.md`:

- **Quotes**: Double quotes `"` exclusively
- **Imports**: stdlib / third-party / local groups, separated by blank lines
- **Type annotations**: Required on all public methods
- **Naming**: `snake_case` functions, `CamelCase` classes, `UPPER_SNAKE_CASE` constants
- **Docstrings**: Google style; module, class, and public method docstrings required
- **Line length**: 100 characters max
- **Error codes**: `SCREAMING_SNAKE_CASE`
- **String formatting**: f-strings exclusively

---

## 9. Existing Skills Reference

| Skill               | `name`              | Purpose                             |
|---------------------|---------------------|-------------------------------------|
| `SearchSkill`       | `search`            | Find text in files, filenames, dirs |
| `ReadFileSkill`     | `read_file`         | Read file content                   |
| `EditFileSkill`     | `edit_file`         | Search-replace editing              |
| `SaveFileSkill`     | `save_file`         | Write/create files                  |
| `RunCmdSkill`       | `run_cmd`           | Execute shell commands              |
| `TodoSkill`         | `to_do`             | Initialize to-do list               |
| `CompletePlanSkill` | `finish_to_do_item` | Mark to-do items completed          |

---

## 10. Troubleshooting

### Skill not discovered

- Check file is in `src/gradum/skills/`
- Check file doesn't start with `_`
- Check class inherits from `Skill`
- Check `name` attribute is set
- Restart the agent

### LLM doesn't use your skill

- Improve the `description` in `get_schema()`
- Add usage examples to the description
- Check parameter names are clear

### Skill crashes

- Add proper error handling
- Return `{"success": False, "error": {...}}` instead of raising exceptions
- Test with invalid inputs

---

## 11. Testing

Create tests in `tests/test_your_skill.py`:

```python
import pytest
from gradum.skills.your_skill import YourSkill


@pytest.fixture
def skill():
    return YourSkill()


def test_execute_success(skill):
    result = skill.execute(input="hello")
    assert result["success"] is True
    assert result["result"] == "HELLO"


def test_execute_missing_param(skill):
    result = skill.execute()
    assert result["success"] is False
    assert result["error"]["code"] == "INVALID_PARAMETER"


def test_get_schema(skill):
    schema = skill.get_schema()
    assert schema["type"] == "function"
    assert schema["function"]["name"] == "your_skill"
```

Run tests:

```bash
pytest tests/test_your_skill.py -v
```
