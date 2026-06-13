# Gradum Coding Standards

This document defines the coding standards for the Gradum project. All new code and modifications must follow these conventions.

---

## 1. Quote Style

Use **double quotes** `"` throughout.

```python
# Correct
name = "gradum"
message = f"Skill '{tool_name}' not found"

# Wrong
name = 'gradum'
```

The only exception: single quotes are acceptable when the string itself contains double quotes.

---

## 2. Import Conventions

### 2.1 Ordering

Group imports in the following order, separated by blank lines:

1. Standard library
2. Third-party packages
3. Local/project modules

```python
import json
import time
from pathlib import Path

import requests

from gradum.client import OllamaClient
from gradum.skills import Skills
```

Sort alphabetically within each group.

### 2.2 No Inline Imports

**All imports must be at the top of the file.** Do not import modules inside functions, methods, or conditional blocks.

```python
# Correct — top-level import
from gradum.skills.todo import get_todo_manager

def _execute_tool(self, ...):
    reminder = get_todo_manager().get_reminder()

# Wrong — import inside function
def _execute_tool(self, ...):
    from gradum.skills.todo import get_todo_manager
    reminder = get_todo_manager().get_reminder()
```

The only exception: a documented case to break a circular dependency (none exist currently).

### 2.3 Package Imports

Use relative imports within the same package, absolute imports across packages:

```python
# Inside skills/
from .base import Skill

# Elsewhere
from gradum.skills import Skills
```

---

## 3. Type Annotations

### 3.1 Use the `typing` Module

```python
from typing import Any, Optional

def execute(self, command: str = "", detached: bool = False) -> dict:
    ...

def get(self, name: str) -> Optional[Skill]:
    ...
```

### 3.2 Required Annotations

- Parameters and return values of all public methods
- `execute()` methods (required on every skill)
- Dataclass fields
- Class attributes (non-constants)

### 3.3 Optional Annotations

- Simple internal helper methods
- Local variables (when the type is obvious)

---

## 4. Naming Conventions

| Type                 | Style                | Example                                   |
|:---------------------| -------------------- | ----------------------------------------- |
| Functions / methods  | `snake_case`           | `get_schema()`, `_execute_tool()`           |
| Classes              | `CamelCase`            | `RunCmdSkill`, `AgentConfig`                |
| Constants            | `UPPER_SNAKE_CASE`     | `DEFAULT_MODEL`, `TIMEOUT`                  |
| Private members      | `_underscore_prefix`   | `_skills`, `_todo_manager`                  |
| Module-level private | `_underscore_prefix`   | `_BLOCKED_EXECUTABLES`, `_PROJECT_ROOT`     |
| Enum members         | `UPPER_SNAKE_CASE`     | `Risk.SAFE`, `Risk.BLOCKED`                 |

---

## 5. Docstrings

Use **Google style**:

```python
class RunCmdSkill(Skill):
    """Skill for executing shell commands."""

    def execute(self, command: str = "", reason: str = "", detached: bool = False) -> dict:
        """Execute a shell command.

        Args:
            command: The shell command to execute
            reason: Why this command is needed
            detached: Run in background mode

        Returns:
            dict with 'success' key and command results
        """
```

### Rules

- Every `.py` file must have a module-level docstring.
- Every class must have a class docstring.
- All public methods must have docstrings.
- Internal methods need docstrings only when the logic is non-obvious.
- `Args:` and `Returns:` / `Yields:` must list all parameters.

---

## 6. Error Handling

### 6.1 Uniform Return Format

All `execute()` methods return a `dict` that must contain a `success` field:

```python
# Success
return {
    "success": True,
    "path": path,
    "content": content,
}

# Failure
return {
    "success": False,
    "error": {
        "code": "FILE_NOT_FOUND",
        "message": f"File not found: {path}"
    }
}
```

### 6.2 Error Code Naming

Use `SCREAMING_SNAKE_CASE`:

`INVALID_PARAMETER`, `FILE_NOT_FOUND`, `CODE_NOT_FOUND`, `MULTIPLE_MATCHES`, `EMPTY_RESULT`, `COMMAND_BLOCKED`, `IO_ERROR`, `TIMEOUT`

### 6.3 Exception Catching

Catch specific exceptions first; always catch `IOError` and `OSError` together:

```python
try:
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
except FileNotFoundError:
    return {"success": False, "error": {"code": "FILE_NOT_FOUND", ...}}
except (IOError, OSError) as e:
    return {"success": False, "error": {"code": "IO_ERROR", "message": str(e)}}
```

---

## 7. String Formatting

Use f-strings exclusively:

```python
# Correct
message = f"Skill '{tool_name}' not found"
error_msg = f"HTTP {e.response.status_code} - {str(e)}"

# Wrong
message = "Skill '{}' not found".format(tool_name)
message = "Skill '%s' not found" % tool_name
```

Exception: use `%s` in `logging` calls (avoids deferred formatting overhead):

```python
_logger.error("Failed to save context: %s", e)
```

---

## 8. Path Handling

Use `pathlib.Path` throughout:

```python
from pathlib import Path

# Correct
log_dir = Path("output") / "run_cmd"
file_path = Path(__file__).resolve().parent

# Wrong
import os
log_dir = os.path.join("output", "run_cmd")
```

---

## 9. File I/O

Always specify `encoding="utf-8"` and use the `with` context manager:

```python
# Correct
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# Correct — reading files with potential encoding issues
with open(path, "r", encoding="utf-8", errors="ignore") as f:
    content = f.read()

# Wrong
f = open(path, "r")
content = f.read()
f.close()
```

---

## 10. Blank Lines

- Between top-level definitions (classes, functions): **2 blank lines**
- Between methods inside a class: **1 blank line**
- No blank line after a class docstring — code starts immediately

```python
class Foo:
    """Class docstring."""

    def method_a(self):
        pass

    def method_b(self):
        pass


class Bar:
    """Another class."""
```

---

## 11. Comments

```python
# Correct — explain "why"
# Track files read without line_range (full file read)
if tool_name == "read_file" and not arguments.get("line_range"):
    self.full_read_files.add(arguments.get("path", ""))

# Wrong — explain "what" (the code already says that)
# Check if tool_name is read_file and line_range is not in arguments
if tool_name == "read_file" and not arguments.get("line_range"):
```

- One space after `#`.
- Comments on their own line.
- No trailing comments.

---

## 12. Line Length

Keep lines under **100 characters**. Break long lines inside parentheses:

```python
return {
    "success": False,
    "error": {
        "code": "COMMAND_BLOCKED",
        "message": f"Blocked by safety filter: {verdict.reason}",
        "rule": verdict.rule
    }
}
```

---

## 13. Adding a New Skill

1. Create `your_skill.py` under `src/gradum/skills/`.
2. Subclass `Skill`.
3. Define `name`, `description`, and `alias` class attributes.
4. Implement `execute(**kwargs) -> dict`.
5. Return the standard `{success, ...}` or `{success: false, error: {code, message}}` format.
6. Return a JSON Schema in `get_schema()` describing the parameters.

```python
from .base import Skill

class YourSkill(Skill):
    """Your skill description."""

    name = "your_skill"
    description = "What this skill does"
    alias = "YourAlias"

    def get_schema(self) -> dict:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": {
                    "type": "object",
                    "properties": {
                        "param": {"type": "string", "description": "..."},
                    },
                    "required": ["param"],
                },
            },
        }

    def execute(self, param: str = "", **kwargs) -> dict:
        # Implementation
        return {"success": True, "result": "..."}
```

No manual registration needed — the auto-discovery mechanism in `skills/__init__.py` scans and registers all skills at startup.

---

## 14. Linting

The project uses [ruff](https://docs.astral.sh/ruff/) for static analysis. Before committing, run:

```bash
ruff check src/
ruff format --check src/
```

Auto-fix:

```bash
ruff check --fix src/
ruff format src/
```
