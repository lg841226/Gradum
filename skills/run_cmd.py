"""Skill for executing shell commands."""

import subprocess
import sys
from typing import Any
from .base import Skill

TIMEOUT = 45


class RunCmdSkill(Skill):
    """Skill for executing shell commands."""
    name = "run_cmd"
    alias = "Ran"
    description = "Execute shell commands"

    def get_schema(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": {
                    "type": "object",
                    "properties": {
                        "command": {
                            "type": "string",
                            "description": "The shell command to execute (e.g., 'ls', 'dir', 'cat', 'python script.py')"
                        },
                        "reason": {
                            "type": "string",
                            "description": "Brief explanation of why this command is needed"
                        }
                    },
                    "required": ["command"]
                }
            }
        }

    def execute(self, command: str = "", reason: str = "") -> dict:
        """Execute shell command and return structured result."""
        if not command:
            return {
                "success": False,
                "error": {
                    "code": "INVALID_PARAMETER",
                    "message": "Missing 'command' parameter"
                }
            }

        encoding = 'utf-8' if sys.platform != 'win32' else 'gbk'

        try:
            result = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=TIMEOUT, encoding=encoding, errors='replace')
            
            return {
                "success": True,
                "tool": "run_cmd",
                "command": command,
                "exit_code": result.returncode,
                "stdout": result.stdout,
                "stderr": result.stderr,
                "timed_out": False
            }

        except subprocess.TimeoutExpired:
            return {
                "success": False,
                "error": {
                    "code": "TIMEOUT",
                    "message": f"Command timed out after {TIMEOUT} seconds"
                },
                "command": command,
                "timed_out": True
            }
        except (IOError, OSError) as e:
            return {
                "success": False,
                "error": {
                    "code": "IO_ERROR",
                    "message": str(e)
                },
                "command": command
            }
