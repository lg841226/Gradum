#!/usr/bin/env python3

#  Copyright (c) 2026 Gradum Authors
#  For licensing terms and conditions, see the MIT LICENSE file.
#
#  gradum_env_doctor.py  2026-09-25 17:20:28 Changed by gwy

import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from rich.console import Console

ICON_START = "\u23A1"
ICON_COMPLETE = "\u23A3"
ICON_ARROW = "\u2502"
ICON_STEP = "\u25CF"
ICON_POINT = "\u25CB"
ICON_WARNING = "\u25B2"
ICON_DIAMOND = "\u25C6"
SEP = "-" * 46

CONSOLE = Console()

# Project-level constants, from the Gradum repo conventions.
GRADLE_PROPERTIES_REQUIRED = {"org.gradle.daemon=true", "kotlin.incremental=true"}
GRADLE_PROPERTIES_BANNED = "org.gradle.configuration-cache=true"

# Deterministic detekt failure threshold on JDK major version.
DETEKT_BAD_JDK_MAJOR = 25

PROVIDER_DOTTED_KEYS = {
    "ollama": ("http://localhost:11434", "http://localhost:11434"),
    "lmstudio": ("http://localhost:1234", "http://localhost:1234"),
    "zhipu": ("https://open.bigmodel.cn/api/paas/v4", "https://open.bigmodel.cn/api/paas/v4"),
    "deepseek": ("https://api.deepseek.com/v1", "https://api.deepseek.com/v1"),
    "minimax": ("https://api.minimaxi.com/v1", "https://api.minimaxi.com/v1"),
}

PROBE_BUILD_TIMEOUT_SECONDS = 180


class Status:
    PASS = "pass"
    WARN = "warn"
    FAIL = "fail"


STATUS_COLOR = {
    Status.PASS: "blue",
    Status.WARN: "yellow",
    Status.FAIL: "red",
}


class Finding:
    def __init__(self, status, title, detail=None):
        self.status = status
        self.title = title
        self.detail = detail


def run_capture(args, cwd=None, timeout=None):
    """Run a command and return (returncode, combined output tail)."""
    try:
        proc = subprocess.run(
            args,
            capture_output=True,
            text=True,
            cwd=cwd,
            timeout=timeout,
        )
        combined = (proc.stdout or "") + (proc.stderr or "")
        return proc.returncode, combined
    except FileNotFoundError:
        return None, ""
    except subprocess.TimeoutExpired:
        return -1, "timed out"


def detect_jdk():
    """Return (major_version, raw_line) from `java -version`, or (None, err)."""
    code, out = run_capture(["java", "-version"])
    if code != 0 or not out:
        return None, out.strip()
    first_line = out.strip().splitlines()[0]
    match = re.search(r'version "([0-9]+)', first_line)
    if not match:
        return None, first_line
    return int(match.group(1)), first_line


def print_node(status, title, lead=True):
    color = STATUS_COLOR[status]
    if lead:
        CONSOLE.print(f"[dim]{ICON_ARROW}[/dim]")
        CONSOLE.print(f"[dim]{ICON_ARROW}[/dim]")
    CONSOLE.print(f"[{color}]{ICON_STEP}[/{color}] [default]{title}[/default]")


def write_report(findings, project_root):
    # Dump every node's detail into a single log file instead of the console.
    log_path = Path(project_root) / "env-doctor_report.txt"
    blocks = []
    for index, fd in enumerate(findings, 1):
        blocks.append(f"[{index}] {fd.title} ({fd.status})")
        if fd.detail:
            blocks.append(fd.detail)
        blocks.append("")
    log_path.write_text("\n".join(blocks).strip() + "\n", encoding="utf-8")
    return log_path.name


def check_gradle_properties(project_root):
    path = Path(project_root) / "gradle.properties"
    if not path.exists():
        return Finding(Status.FAIL, "gradle.properties is missing",
                       f"expected at {path}")
    lines = path.read_text(encoding="utf-8").splitlines()
    normalized = {line.strip() for line in lines if line.strip()}

    missing = GRADLE_PROPERTIES_REQUIRED - normalized
    banned = [line for line in normalized if GRADLE_PROPERTIES_BANNED in line]

    problems = []
    if missing:
        problems.append("missing: " + ", ".join(sorted(missing)))
    if banned:
        problems.append("present but banned: " + ", ".join(banned))

    if problems:
        return Finding(Status.FAIL, "gradle.properties flags need attention",
                       "\n".join(problems))
    return Finding(Status.PASS, "gradle.properties flags are set correctly",
                   "daemon and incremental set, configuration-cache absent")


def check_settings_json():
    path = Path.home() / ".gradum" / "settings.json"
    if not path.exists():
        return Finding(Status.FAIL, "~/.gradum/settings.json is missing",
                       "server expects a settings.json under ~/.gradum")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as exc:
        return Finding(Status.FAIL, "settings.json could not be parsed", str(exc))

    missing_base = []
    invalid_base = []
    missing_key = []
    invalid_key = []
    for provider, _ in PROVIDER_DOTTED_KEYS.items():
        base_key = f"{provider}.baseUrl"
        api_key = f"{provider}.apiKey"
        if base_key not in data:
            missing_base.append(provider)
        elif not isinstance(data[base_key], str) or not data[base_key]:
            invalid_base.append(base_key)
        if api_key not in data:
            missing_key.append(provider)
        elif not (data[api_key] is None or isinstance(data[api_key], str)):
            invalid_key.append(api_key)

    lines = []
    if missing_base:
        lines.append(f"baseUrl missing for: {', '.join(missing_base)}")
    if invalid_base:
        lines.append(f"baseUrl not a non-empty string: {', '.join(invalid_base)}")
    if missing_key:
        lines.append(f"apiKey missing for: {', '.join(missing_key)}")
    if invalid_key:
        lines.append(f"apiKey must be string or null: {', '.join(invalid_key)}")

    if lines:
        return Finding(Status.FAIL, "settings.json provider config is invalid",
                       "\n".join(lines))
    return Finding(Status.PASS, "settings.json provider config is valid",
                   "all provider dotted keys present and well-typed")


def check_ollama():
    # Gradum talks to a local Ollama instance; a missing/unreachable one is a
    # soft warning since the server can fall back to other providers.
    base_url = "http://localhost:11434"
    try:
        with urllib.request.urlopen(base_url + "/api/tags", timeout=3) as resp:
            return Finding(Status.PASS, "Ollama service is reachable",
                           f"{base_url}/api/tags responded HTTP {resp.status}")
    except (urllib.error.URLError, OSError) as exc:
        reason = exc.reason if isinstance(exc, urllib.error.URLError) else str(exc)
        return Finding(Status.WARN, "Ollama service is not reachable",
                       f"{base_url}/api/tags unreachable: {reason}")


def probe_build(project_root):
    script = "gradlew.bat" if os.name == "nt" else "gradlew"
    gradlew = Path(project_root) / script
    if not gradlew.exists():
        return Finding(Status.FAIL, "gradlew script is missing",
                       f"expected at {gradlew}")
    args = [str(gradlew), ":compileKotlin", "-x", "detekt", "--console", "plain"]

    started = time.time()
    try:
        proc = subprocess.run(
            args, cwd=project_root,
            capture_output=True, text=True,
            timeout=PROBE_BUILD_TIMEOUT_SECONDS,
        )
    except FileNotFoundError:
        return Finding(Status.FAIL, "probe build could not start", "gradlew is not executable")
    except subprocess.TimeoutExpired:
        return Finding(Status.FAIL, "probe build timed out",
                       f"exceeded {PROBE_BUILD_TIMEOUT_SECONDS}s")

    code = proc.returncode
    elapsed = int(time.time() - started)

    # Full Gradle output goes into the detail report, not the console.
    detail = f"exit code {code} in {elapsed}s\n" + (proc.stdout or "") + (proc.stderr or "")

    status = Status.PASS if code == 0 else Status.FAIL
    return Finding(status, f"probe build :compileKotlin -x detekt (exit {code} in {elapsed}s)",
                   detail)


def main():
    project_root = os.getcwd()
    if "--project-root" in sys.argv:
        idx = sys.argv.index("--project-root")
        project_root = sys.argv[idx + 1]

    CONSOLE.print()
    CONSOLE.print("[default]Gradum Environment Doctor[/default]")
    CONSOLE.print(f"[default][dim]Version 1.0.0 · Project directory {project_root}[/dim][/default]")
    CONSOLE.print()
    CONSOLE.print(f"[dim]{ICON_START}[/dim] Start environment inspection")
    CONSOLE.print(f"[dim]{ICON_ARROW}[/dim]")

    findings = []

    major, raw = detect_jdk()
    if major is None:
        f = Finding(Status.FAIL, "No JDK was detected",
                    raw or "`java` not on PATH and JAVA_HOME unset")
        print_node(f.status, f.title, lead=False)
    else:
        java_home = os.environ.get("JAVA_HOME", "(unset)")
        detail = f"{raw}\nJAVA_HOME={java_home}"
        f = Finding(Status.PASS, f"JDK {major} is available", detail)
        print_node(f.status, f.title, lead=False)
    findings.append(f)

    if major is not None and major >= DETEKT_BAD_JDK_MAJOR:
        f = Finding(Status.WARN, f"detekt may fail on JDK {major}",
                    f"detekt is known to fail on JDK {DETEKT_BAD_JDK_MAJOR}+ (reports 25.0.4.1). "
                    "Build with `-x detekt` or switch to JDK 21.")
    elif major is None:
        f = Finding(Status.FAIL, "detekt compatibility cannot be assessed", "no JDK detected")
    else:
        f = Finding(Status.PASS, f"detekt is compatible with JDK {major}",
                    f"below the problematic threshold JDK {DETEKT_BAD_JDK_MAJOR}")
    print_node(f.status, f.title)
    findings.append(f)

    f = check_gradle_properties(project_root)
    print_node(f.status, f.title)
    findings.append(f)

    f = check_settings_json()
    print_node(f.status, f.title)
    findings.append(f)

    f = check_ollama()
    print_node(f.status, f.title)
    findings.append(f)

    f = probe_build(project_root)
    print_node(f.status, f.title)
    findings.append(f)

    recommendations = []
    if major is None:
        recommendations.append("Install a JDK (project targets JDK 21) and set JAVA_HOME")
    elif major >= DETEKT_BAD_JDK_MAJOR:
        recommendations.append(f"Switch JDK to 21 (current JDK {major} breaks detekt)")

    for fd in findings:
        if fd.status == Status.FAIL and "gradle.properties" in fd.title:
            recommendations.append("Fix gradle.properties: ensure daemon+incremental, drop configuration-cache")
        if fd.status == Status.FAIL and "settings.json" in fd.title:
            recommendations.append("Fix ~/.gradum/settings.json provider dotted keys")
        if fd.status == Status.FAIL and "probe build" in fd.title:
            recommendations.append("Probe build failed; check compiler output above")

    problem_count = sum(1 for fd in findings if fd.status != Status.PASS)
    noun = "problem" if problem_count == 1 else "problems"

    CONSOLE.print(f"[dim]{ICON_ARROW}[/dim]")
    CONSOLE.print(f"[dim]{ICON_ARROW}[/dim]")
    CONSOLE.print(f"[cyan]{ICON_DIAMOND}[/cyan] [default]Found {problem_count} {noun} in your machine[/default]")

    if recommendations:
        for rec in recommendations:
            CONSOLE.print(f"[dim]{ICON_ARROW}[/dim]      [dim black]{rec}[/]")
        CONSOLE.print(f"[dim]{ICON_ARROW}[/dim]")
        CONSOLE.print(f"[dim]{ICON_ARROW}[/dim]")

    log_name = write_report(findings, project_root)
    CONSOLE.print(f"[default][blue]{ICON_STEP}[/blue] Report written to {log_name}[/default]")
    CONSOLE.print(f"[dim]{ICON_ARROW}[/dim]")
    CONSOLE.print(f"[default][dim]{ICON_COMPLETE}[/dim] Complete[/default]")
    CONSOLE.print("")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        CONSOLE.print(f"\n[yellow]{ICON_WARNING}  Inspection interrupted[/yellow]")
