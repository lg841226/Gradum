"""Command safety filter for run_cmd skill.

Binary classification using shlex tokenization (no regex) to avoid false
positives from substring rules. The agent has no confirmation mechanism,
so commands are either allowed or refused outright.

Blocked: filesystem destruction, system power control, privilege escalation,
         writing to raw devices, and destructive ops against critical paths.
Safe:    everything else, including downloads, package installs, device reads.
"""
import shlex
from dataclasses import dataclass
from enum import Enum
from pathlib import Path


class Risk(Enum):
    SAFE = "safe"
    BLOCKED = "blocked"


@dataclass
class Verdict:
    risk: Risk
    reason: str = ""
    rule: str = ""

    @property
    def is_blocked(self) -> bool:
        return self.risk == Risk.BLOCKED


# Executables that are never allowed. Matched by basename of token[0].
_BLOCKED_EXECUTABLES = frozenset({
    "mkfs", "mkfs.ext2", "mkfs.ext3", "mkfs.ext4", "mkfs.xfs",
    "mkfs.btrfs", "mkfs.vfat", "mkfs.ntfs", "mkswap",
    "fdisk", "sfdisk", "parted", "gdisk",
    "shutdown", "reboot", "halt", "poweroff", "init",
    "sudo", "su", "doas", "pkexec",
})

# Prefixes where any descendant is blocked (system dirs only).
# Note: home is intentionally NOT here — too broad. Use _PROTECTED_HOME_SUBDIRS.
_PROTECTED_PREFIXES = (
    "/etc", "/usr", "/var", "/boot", "/bin", "/sbin",
    "/lib", "/lib64", "/opt",
    "/System", "/Library", "/Applications", "/private",
)

# High-value subdirectories under $HOME that should never be removed.
# Keeping this list explicit avoids blocking ./build inside user projects.
_PROTECTED_HOME_SUBDIRS = (
    ".ssh", ".gnupg", ".aws", ".kube", ".netrc",
    ".pypirc", ".npmrc", ".docker",
)

# Paths that are always safe targets, even if a symlink resolves them
# under a protected prefix (e.g. macOS /temp -> /private/temp).
_SAFE_PREFIXES = ("/temp",)

# Paths that are protected as themselves only.
_EXACT_PROTECTED = (
    "/",
    "/dev", "/proc", "/sys",
)


def _executable_name(token: str) -> str:
    return Path(token).name


def _has_short_flag(tokens: list[str], flag: str) -> bool:
    """Detect -X or -XY (combined) flags, but not --long or -Xvalue."""
    for t in tokens[1:]:
        if t.startswith("--") or t == "-":
            continue
        if t.startswith("-") and flag in t:
            return True
    return False


def _has_long_flag(tokens: list[str], flag: str) -> bool:
    return any(t == f"--{flag}" for t in tokens[1:])


def _is_critical_path(target: str) -> bool:
    expanded = Path(target).expanduser()
    absolute = str(expanded.absolute())
    try:
        resolved = str(expanded.resolve())
    except (OSError, RuntimeError):
        resolved = absolute

    for safe in _SAFE_PREFIXES:
        if absolute == safe or absolute.startswith(safe + "/"):
            return False

    for prefix in _PROTECTED_PREFIXES:
        prefix_expanded = Path(prefix).expanduser()
        try:
            prefix_resolved = str(prefix_expanded.resolve())
        except (OSError, RuntimeError):
            prefix_resolved = str(prefix_expanded.absolute())
        if resolved == prefix_resolved or resolved.startswith(prefix_resolved + "/"):
            return True

    home = Path.home()
    for sub in _PROTECTED_HOME_SUBDIRS:
        protected = home / sub
        try:
            protected_resolved = str(protected.resolve())
        except (OSError, RuntimeError):
            protected_resolved = str(protected.absolute())
        if resolved == protected_resolved or resolved.startswith(protected_resolved + "/"):
            return True

    for exact in _EXACT_PROTECTED:
        exact_expanded = Path(exact).expanduser()
        try:
            exact_resolved = str(exact_expanded.resolve())
        except (OSError, RuntimeError):
            exact_resolved = str(exact_expanded.absolute())
        if resolved == exact_resolved:
            return True

    return False


def _path_arguments(tokens: list[str]) -> list[str]:
    """Return token[1:] entries that look like positional path arguments."""
    paths = []
    for t in tokens[1:]:
        if t.startswith("-"):
            continue
        if "=" in t and t.split("=", 1)[0].startswith("-"):
            continue
        paths.append(t)
    return paths


def _classify_dd(tokens: list[str]) -> Verdict:
    """dd: writing to a raw device is blocked. Everything else is safe."""
    for t in tokens:
        if t.startswith("of=") and t[3:].startswith("/dev/"):
            return Verdict(
                Risk.BLOCKED,
                f"dd writing to device is not allowed ({t})",
                "dd:device_output",
            )
    return Verdict(Risk.SAFE)


def _classify_rm(tokens: list[str]) -> Verdict:
    """rm: only blocked when targeting a critical path."""
    for arg in _path_arguments(tokens):
        if _is_critical_path(arg):
            return Verdict(
                Risk.BLOCKED,
                f"rm targeting critical path '{arg}' is not allowed",
                "rm:critical_path",
            )
    return Verdict(Risk.SAFE)


def _classify_chmod(tokens: list[str]) -> Verdict:
    """chmod -R: only blocked when targeting a critical path."""
    if not (_has_short_flag(tokens, "R") or _has_long_flag(tokens, "recursive")):
        return Verdict(Risk.SAFE)
    for arg in _path_arguments(tokens):
        if _is_critical_path(arg):
            return Verdict(
                Risk.BLOCKED,
                f"chmod -R targeting critical path '{arg}' is not allowed",
                "chmod:critical_path_recursive",
            )
    return Verdict(Risk.SAFE)


def classify(command: str) -> Verdict:
    """Classify a shell command string as safe or blocked."""
    if not command or not command.strip():
        return Verdict(Risk.SAFE)

    try:
        tokens = shlex.split(command)
    except ValueError:
        return Verdict(Risk.SAFE)

    if not tokens:
        return Verdict(Risk.SAFE)

    exe = _executable_name(tokens[0])

    if exe in _BLOCKED_EXECUTABLES:
        return Verdict(
            Risk.BLOCKED,
            f"'{exe}' is not allowed",
            f"executable:{exe}",
        )

    if exe == "dd":
        return _classify_dd(tokens)

    if exe == "rm":
        return _classify_rm(tokens)

    if exe == "chmod":
        return _classify_chmod(tokens)

    return Verdict(Risk.SAFE)
