"""Skill for executing shell commands."""

import re
import subprocess
import sys
from typing import Any
from .base import Skill

TIMEOUT = 45

HIGH_RISK_COMMANDS = {'rm -rf', 'format', 'fdisk', 'dd', 'sudo', 'su', 'runas', 'reg'}

HIGH_RISK_PATTERNS = [r'rm\s+(-[rf]+\s+)?/', r'format\s+[cdef]:', r'dd\s+.*of=', r'>\s*/dev/', r'\|\s*(bash|sh)\s']


class RunCmdSkill(Skill):
    """Skill for executing shell commands."""
    name = "run_cmd"
    alias = "Ran"
    description = "Execute shell commands safely (dangerous commands blocked)"

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

    def execute(self, command: str = "", reason: str = "") -> str:
        if not command:
            return "Error: Missing 'command' parameter."

        security_result = RunCmdSkill._check_command_security(command)

        if security_result['level'] == 'high':
            return (f"Error: Command blocked due to high security risk.\n\n"
                    f"Command: {command}\n"
                    f"Reason: {security_result['reason']}\n\n"
                    f"This command could potentially harm the system or compromise security. "
                    f"If you believe this is a false positive, please use an alternative approach.")

        encoding = 'utf-8' if sys.platform != 'win32' else 'gbk'

        try:
            result = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=TIMEOUT, encoding=encoding, errors='replace')
            output = ""

            if result.stdout:   
                output += result.stdout
            if result.stderr:
                output += "\n[stderr]\n" + result.stderr
            if not output:
                output = "(no output - this is normal for many commands, operation succeeded)"

            return f"Success: Command executed\n{output.strip()}"

        except subprocess.TimeoutExpired:
            return f"Error: Command timed out after {TIMEOUT} seconds"
        except (IOError, OSError) as e:
            return f"Error: {str(e)}"

    @staticmethod
    def _check_command_security(command: str) -> dict:
        """Check command security level and return risk assessment."""
        command_lower = command.lower().strip()

        for pattern in HIGH_RISK_PATTERNS:
            if re.search(pattern, command_lower):
                return {
                    'level': 'high',
                    'reason': f"Command matches dangerous pattern: {pattern}"
                }

        for high_risk in HIGH_RISK_COMMANDS:
            if high_risk in command_lower:
                return {
                    'level': 'high',
                    'reason': f"Command contains high-risk operation: '{high_risk}'"
                }

        return {
            'level': 'low',
            'reason': "Command appears to be safe"
        }
