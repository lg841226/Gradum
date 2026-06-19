# Copyright (c) 2026 Gradum Authors, Ge Wangyang. Licensed under MIT.
# See LICENSE for details.

"""Skill for executing shell commands."""

import os
import shlex
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Optional, Union

from gradum.utils.command_filter import classify

from .base import Skill, make_error, make_success

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
    """Check if a command should be run in detached (background) mode."""
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
    """Kill a process and its children with SIGTERM, then SIGKILL after grace period."""
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
        """Return the JSON schema for this skill's parameters."""
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
                            "description": "The shell command to execute (e.g., 'ls', 'dir', 'cat', 'python script.py')",
                        },
                        "reason": {
                            "type": "string",
                            "description": "Brief explanation of why this command is needed",
                        },
                        "cwd": {
                            "type": "string",
                            "description": "Working directory for the command. If not specified, uses the project root. Example: 'src/utils' to run in that subdirectory.",
                        },
                        "env": {
                            "type": "object",
                            "description": "Environment variables to set for the command. These are merged with the current environment. Example: {\"DEBUG\": \"1\", \"PORT\": \"8080\"}",
                            "additionalProperties": {"type": "string"},
                        },
                        "detached": {
                            "type": "boolean",
                            "description": "Run in background and return immediately. Auto-detected for GUI launchers and dev servers.",
                            "default": False,
                        },
                    },
                    "required": ["command"],
                },
            },
        }

    def execute(self, command: str = "", reason: str = "", cwd: str = "",
                env: Optional[dict] = None, detached: bool = False) -> dict:
        """Execute a shell command and return structured result."""
        if not command:
            return make_error(self.name, "INVALID_PARAMETER", "Missing 'command' parameter")

        verdict = classify(command)
        if verdict.is_blocked:
            return make_error(
                self.name, "COMMAND_BLOCKED",
                f"Blocked by safety filter: {verdict.reason}",
                command=command, rule=verdict.rule,
            )

        # Validate and resolve working directory
        resolved_cwd = self._resolve_cwd(cwd, command)
        if isinstance(resolved_cwd, dict):
            return resolved_cwd

        # Build environment variables
        merged_env = self._build_env(env, command)
        if isinstance(merged_env, dict):
            return merged_env

        if not detached and _should_detach(command):
            detached = True

        encoding = "utf-8" if sys.platform != "win32" else "gbk"

        if detached:
            return self._run_detached(command, encoding, resolved_cwd, merged_env)
        return self._run_blocking(command, encoding, resolved_cwd, merged_env)

    def _resolve_cwd(self, cwd: str, command: str) -> Union[str, dict]:
        """Validate and resolve the working directory.

        Returns the resolved path string on success, or an error dict on failure.
        """
        if not cwd:
            return ""

        from gradum.paths import PROJECT_ROOT

        candidate = Path(cwd).expanduser()
        if not candidate.is_absolute():
            candidate = PROJECT_ROOT / candidate

        try:
            candidate = candidate.resolve()
            candidate.relative_to(PROJECT_ROOT)
        except ValueError:
            return make_error(
                self.name, "INVALID_PARAMETER",
                f"Working directory is outside the project: {cwd}",
                command=command,
            )

        if not candidate.is_dir():
            return make_error(
                self.name, "INVALID_PARAMETER",
                f"Working directory does not exist: {cwd}",
                command=command,
            )

        return str(candidate)

    def _build_env(self, env: Optional[dict], command: str) -> Optional[dict]:
        """Build merged environment variables.

        Returns the merged env dict on success, None if no env provided,
        or an error dict on failure.
        """
        if not env:
            return None

        if not isinstance(env, dict):
            return make_error(
                self.name, "INVALID_PARAMETER",
                "'env' must be a dictionary of string key-value pairs",
                command=command,
            )

        merged_env = os.environ.copy()
        for key, value in env.items():
            merged_env[str(key)] = str(value)
        return merged_env

    def _run_detached(self, command: str, encoding: str,
                      cwd: Optional[str] = None, env: Optional[dict] = None) -> dict:
        """Run a command in detached (background) mode."""
        DETACHED_LOG_DIR.mkdir(parents=True, exist_ok=True)

        tmp_log = DETACHED_LOG_DIR / f"pending_{os.getpid()}_{int(time.time() * 1000)}.log"
        log_file = open(tmp_log, "w", encoding=encoding, buffering=1)

        try:
            proc = subprocess.Popen(
                command, shell=True, stdout=log_file, stderr=subprocess.STDOUT,
                start_new_session=True, cwd=cwd, env=env,
                encoding=encoding, errors="replace",
            )
        except (IOError, OSError) as e:
            log_file.close()
            tmp_log.unlink(missing_ok=True)
            return make_error(self.name, "IO_ERROR", str(e), command=command)

        final_log = DETACHED_LOG_DIR / f"{proc.pid}.log"
        try:
            tmp_log.rename(final_log)
        except OSError:
            final_log = tmp_log

        result = make_success(
            self.name, command=command, detached=True, pid=proc.pid,
            log_path=str(final_log), message=EMPTY_OUTPUT_MESSAGE,
        )
        if cwd:
            result["cwd"] = cwd
        return result

    def _run_blocking(self, command: str, encoding: str,
                      cwd: Optional[str] = None, env: Optional[dict] = None) -> dict:
        """Run a command in blocking mode with timeout."""
        try:
            proc = subprocess.Popen(
                command, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                start_new_session=True, cwd=cwd, env=env,
                text=True, encoding=encoding, errors="replace",
            )
        except (IOError, OSError) as e:
            return make_error(self.name, "IO_ERROR", str(e), command=command)

        try:
            stdout, stderr = proc.communicate(timeout=TIMEOUT)
        except subprocess.TimeoutExpired:
            _kill_process_tree(proc)
            return make_error(
                self.name, "TIMEOUT",
                f"Command timed out after {TIMEOUT} seconds",
                command=command, timed_out=True,
            )

        result = make_success(
            self.name, command=command, exit_code=proc.returncode,
            stdout=stdout, stderr=stderr, timed_out=False,
        )
        if not stdout and not stderr:
            result["message"] = EMPTY_OUTPUT_MESSAGE
        if cwd:
            result["cwd"] = cwd
        return result
