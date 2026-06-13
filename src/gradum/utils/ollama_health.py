"""Ollama health check and auto-start helper.

When Gradum cannot reach the Ollama server, this module tries to
detect whether Ollama is installed (binary on PATH, or .app on macOS)
and, if so, launches it and waits for the HTTP API to become
reachable.
"""

import platform
import shutil
import subprocess
import sys
import time
from pathlib import Path

import requests

_TAGS_ENDPOINT = "/api/tags"

_INITIAL_CHECK_TIMEOUT = 1.5
_POLL_INTERVAL = 0.5
_READY_TIMEOUT = 8.0
_READY_PROBE_TIMEOUT = 1.0


def _server_reachable(base_url: str, timeout: float) -> bool:
    try:
        r = requests.get(f"{base_url}{_TAGS_ENDPOINT}", timeout=timeout)
        return r.status_code == 200
    except requests.exceptions.RequestException:
        return False


def _find_ollama() -> tuple[bool, str]:
    if platform.system() == "Darwin" and Path("/Applications/Ollama.app").exists():
        return True, "app"
    if shutil.which("ollama"):
        return True, "binary"
    return False, ""


def _start_ollama(kind: str) -> bool:
    try:
        if kind == "app":
            subprocess.Popen(
                ["open", "-a", "Ollama"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            return True
        if kind == "binary":
            ollama_path = shutil.which("ollama")
            if not ollama_path:
                return False
            kwargs = {
                "stdout": subprocess.DEVNULL,
                "stderr": subprocess.DEVNULL,
            }
            if sys.platform != "win32":
                kwargs["start_new_session"] = True
            subprocess.Popen([ollama_path, "serve"], **kwargs)
            return True
    except (OSError, FileNotFoundError):
        return False
    return False


def ensure_ollama_running(
    base_url: str = "http://localhost:11434",
    ready_timeout: float = _READY_TIMEOUT,
) -> tuple[bool, str]:
    """Make sure the Ollama HTTP API is reachable.

    Returns:
        (ok, message):
        - (True, "running")  - already reachable
        - (True, "started")  - was down, auto-started successfully
        - (False, "<reason>") - cannot start; reason is human-readable
    """
    if _server_reachable(base_url, timeout=_INITIAL_CHECK_TIMEOUT):
        return True, "running"

    found, kind = _find_ollama()
    if not found:
        return False, (
            "Ollama server is not reachable and no Ollama installation "
            "was found. Install it from https://ollama.com/download."
        )

    if not _start_ollama(kind):
        return False, (
            f"Ollama is installed ({kind}) but could not be launched."
        )

    deadline = time.time() + ready_timeout
    while time.time() < deadline:
        time.sleep(_POLL_INTERVAL)
        if _server_reachable(base_url, timeout=_READY_PROBE_TIMEOUT):
            return True, "started"

    return False, (
        f"Ollama was launched ({kind}) but did not become reachable "
        f"within {ready_timeout:.0f}s. Check 'ollama serve' output for errors."
    )
