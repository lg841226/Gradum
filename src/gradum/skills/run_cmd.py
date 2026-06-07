"""Skill for executing shell commands."""

import os
import shlex
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Any
from .base import Skill
from gradum.utils.command_filter import classify

TIMEOUT = 45
DETACHED_LOG_DIR = Path("output/run_cmd")
EMPTY_OUTPUT_MESSAGE = "Command executed successfully with no output."

_GUI_LAUNCHERS = frozenset({"open", "osascript"})

_DEV_SERVER_PATTERNS = (
    "npm run dev", "npm start",
    "yarn dev", "yarn start",
    "pnpm dev", "pnpm start",
    "vite", "next dev", "nuxt dev", "gatsby develop",
    "python -m http.server", "flask run", "django runserver",
    "manage.py runserver", "rails server", "rails s",
    "php artisan serve",
    "node server.js", "node app.js", "node index.js",
    "cargo run", "cargo watch",
    "docker compose up", "ngrok",
    "tail -f", "watch ",
)

_BACKGROUND_TOKENS = frozenset({"&", "disown"})


def _should_detach(command: str) -> bool:
    try:
        tokens = shlex.split(command)
    except ValueError:
        return False
    if not tokens:
        return False
    if Path(tokens[0]).name.lower() in _GUI_LAUNCHERS:
        return True
    if tokens[0] == "nohup" or tokens[-1] in _BACKGROUND_TOKENS:
        return True
    cmd_lower = command.lower()
    return any(pat in cmd_lower for pat in _DEV_SERVER_PATTERNS)


def _kill_process_tree(proc: subprocess.Popen, grace_seconds: float = 5.0) -> None:
    if proc.poll() is not None:
        return
    try:
        os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
    except (OSError, ProcessLookupError, AttributeError):
        try:
            proc.terminate()
        except (OSError, ProcessLookupError):
            pass
        return
    try:
        proc.wait(timeout=grace_seconds)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
        except (OSError, ProcessLookupError, AttributeError):
            try:
                proc.kill()
            except (OSError, ProcessLookupError):
                pass


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
                        },
                        "detached": {
                            "type": "boolean",
                            "description": "Run in background and return immediately. Auto-detected for GUI launchers and dev servers.",
                            "default": False
                        }
                    },
                    "required": ["command"]
                }
            }
        }

    def execute(self, command: str = "", reason: str = "", detached: bool = False) -> dict:
        if not command:
            return {
                "success": False,
                "error": {"code": "INVALID_PARAMETER", "message": "Missing 'command' parameter"}
            }

        verdict = classify(command)
        if verdict.is_blocked:
            return {
                "success": False,
                "error": {
                    "code": "COMMAND_BLOCKED",
                    "message": f"Blocked by safety filter: {verdict.reason}",
                    "rule": verdict.rule
                },
                "command": command
            }

        if not detached and _should_detach(command):
            detached = True

        encoding = 'utf-8' if sys.platform != 'win32' else 'gbk'

        if detached:
            return self._run_detached(command, encoding)
        return self._run_blocking(command, encoding)

    def _run_detached(self, command: str, encoding: str) -> dict:
        DETACHED_LOG_DIR.mkdir(parents=True, exist_ok=True)
        tmp_log = DETACHED_LOG_DIR / f"pending_{os.getpid()}_{int(time.time()*1000)}.log"
        log_file = open(tmp_log, "w", encoding=encoding, buffering=1)
        try:
            proc = subprocess.Popen(
                command,
                shell=True,
                stdout=log_file,
                stderr=subprocess.STDOUT,
                start_new_session=True,
                encoding=encoding,
                errors="replace",
            )
        except (IOError, OSError) as e:
            log_file.close()
            tmp_log.unlink(missing_ok=True)
            return {
                "success": False,
                "error": {"code": "IO_ERROR", "message": str(e)},
                "command": command
            }
        final_log = DETACHED_LOG_DIR / f"{proc.pid}.log"
        try:
            tmp_log.rename(final_log)
        except OSError:
            final_log = tmp_log
        return {
            "success": True,
            "command": command,
            "detached": True,
            "pid": proc.pid,
            "log_path": str(final_log),
            "message": EMPTY_OUTPUT_MESSAGE
        }

    def _run_blocking(self, command: str, encoding: str) -> dict:
        try:
            proc = subprocess.Popen(
                command,
                shell=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                start_new_session=True,
                text=True,
                encoding=encoding,
                errors="replace",
            )
        except (IOError, OSError) as e:
            return {
                "success": False,
                "error": {"code": "IO_ERROR", "message": str(e)},
                "command": command
            }
        try:
            stdout, stderr = proc.communicate(timeout=TIMEOUT)
        except subprocess.TimeoutExpired:
            _kill_process_tree(proc)
            return {
                "success": False,
                "error": {
                    "code": "TIMEOUT",
                    "message": f"Command timed out after {TIMEOUT} seconds"
                },
                "command": command,
                "timed_out": True
            }
        result = {
            "success": True,
            "tool": "run_cmd",
            "command": command,
            "exit_code": proc.returncode,
            "stdout": stdout,
            "stderr": stderr,
            "timed_out": False
        }
        if not stdout and not stderr:
            result["message"] = EMPTY_OUTPUT_MESSAGE
        return result
