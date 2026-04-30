"""Skill for executing shell commands."""

import subprocess
import sys
from typing import Any
from .base import Skill

# High-risk commands that should be blocked
HIGH_RISK_COMMANDS = {
    # Dangerous file operations
    'rm -rf', 'deltree', 'format', 'fdisk',
    'dd', 'shred',
    
    # System modification
    'chmod', 'chown', 'icacls', 'reg',
    'sudo', 'su', 'runas',
    
    # Process control
    'kill', 'taskkill', 'pkill',
    
    # Network changes
    'netsh', 'iptables', 'firewall',
    
    # Dangerous utilities
    'mkfs', 'mke2fs',
}

# Medium-risk commands (will execute with warning)
MEDIUM_RISK_COMMANDS = {
    # File operations (write)
    'touch', 'mkdir', 'rm', 'rmdir', 'del', 'copy', 'cp',
    'move', 'mv', 'rename',
    
    # Archive
    'zip', 'unzip', 'tar',
    
    # Download
    'curl', 'wget',
    
    # Package managers
    'pip', 'npm', 'apt', 'yum', 'dnf', 'brew', 'choco', 'winget',
    
    # Script execution
    'python -c', 'python3 -c', 'bash -c', 'sh -c',
    'powershell -c', 'cmd /c',
}

# Patterns for high-risk commands (regex)
HIGH_RISK_PATTERNS = [
    r'rm\s+(-[rf]+\s+)?/',  # rm -rf /
    r'deltree\s+/',  # deltree /
    r'format\s+[cdef]:',  # format c:
    r'dd\s+.*of=',  # dd of=/dev/...
    r'>\s*/dev/',  # redirect to /dev/
    r'\|\s*(bash|sh)\s',  # pipe to bash/sh
]


class RunCommandSkill(Skill):
    """Skill for executing shell commands."""
    name = "run_command"
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

        # Security check
        security_result = self._check_command_security(command)
        
        if security_result['level'] == 'high':
            return (f"Error: Command blocked due to high security risk!\n\n"
                    f"Command: {command}\n"
                    f"Reason: {security_result['reason']}\n\n"
                    f"This command could potentially harm the system or compromise security. "
                    f"If you believe this is a false positive, please use an alternative approach.")
        
        if security_result['level'] == 'medium':
            warning = (f"Warning: Command may modify system state!\n\n"
                      f"Command: {command}\n"
                      f"Risk Level: MEDIUM\n"
                      f"Reason: {security_result['reason']}\n\n"
                      f"The command will still execute, but be cautious of its effects.\n\n")
        else:
            warning = ""

        encoding = 'utf-8' if sys.platform != 'win32' else 'gbk'

        try:
            result = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=30, encoding=encoding, errors='replace')
            output = ""

            if result.stdout:
                output += result.stdout
            if result.stderr:
                output += "\n[stderr]\n" + result.stderr
            if not output:
                output = "(command executed with no output)"

            if warning:
                return f"Success: Command executed\n{warning}{output.strip()}"
            else:
                return f"Success: Command executed\n{output.strip()}"

        except subprocess.TimeoutExpired:
            return "Error: Command timed out after 30 seconds"
        except (IOError, OSError) as e:
            return f"Error: {str(e)}"

    def _check_command_security(self, command: str) -> dict:
        """Check command security level and return risk assessment."""
        command_lower = command.lower().strip()
        first_word = command_lower.split()[0] if command_lower.split() else ""
        
        # Check high-risk patterns first
        import re
        for pattern in HIGH_RISK_PATTERNS:
            if re.search(pattern, command_lower):
                return {
                    'level': 'high',
                    'reason': f"Command matches dangerous pattern: {pattern}"
                }
        
        # Check high-risk commands
        for high_risk in HIGH_RISK_COMMANDS:
            if high_risk in command_lower:
                return {
                    'level': 'high',
                    'reason': f"Command contains high-risk operation: '{high_risk}'"
                }
        
        # Check medium-risk commands
        for medium_risk in MEDIUM_RISK_COMMANDS:
            if medium_risk == first_word or medium_risk in command_lower:
                return {
                    'level': 'medium',
                    'reason': f"Command may modify system state: '{medium_risk}'"
                }
        
        # Default to low risk for unknown commands
        return {
            'level': 'low',
            'reason': "Command appears to be safe"
        }
