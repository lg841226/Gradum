#!/usr/bin/env python3
#  Copyright (c) 2026 Gradum Authors
#  For licensing terms and conditions, see the MIT LICENSE file.
#
#  git_stats.py  2026-09-20 13:32:23 Changed by gwy
#
#  git_stats.py  2026-08-31 19:21:55 Changed by gwy

import functools
import itertools
import json
import math
import os
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
import zipfile
from collections import defaultdict, deque
from datetime import datetime, timezone, timedelta
from typing import Dict, Iterable, List, Optional, Tuple

__version__ = "1.1.0"

# Matches git short hashes (7-40 hex chars). Used to filter finding records
# that carry a real per-commit hash vs placeholders like `-`, `Cluster #N`,
# `Recent Spike`.
_SHORT_HASH_RE = re.compile(r"^[0-9a-f]{7,40}$")

# Severity word -> log level for audit findings.
_SEVERITY_TO_LOG = {
    "clean": "INFO",
    "normal": "INFO",
    "watch": "WARN",
    "alert": "WARN",
    "critical": "ERROR"
}


def _sentence_case(text: str) -> str:
    """Capitalize only the first alphabetic character, keeping the rest as-is."""
    for i, ch in enumerate(text):
        if ch.isalpha():
            return text[:i] + ch.upper() + text[i + 1:]
    return text


# When `--jsonl` is passed (the IDE plugin always does), emit one compact
# JSON object per line so a streaming parser can read records line-by-line.
# Without the flag, records are pretty-printed (indent=2) for humans.
_JSONL = "--jsonl" in sys.argv


def log(log_level: str, **fields) -> None:
    """Emit a JSON record with a level and structured fields."""
    record = {"level": log_level, **fields}
    if "message" in record:
        record["message"] = _sentence_case(record["message"])
    if _JSONL:
        print(json.dumps(record, ensure_ascii=False))
    else:
        print(json.dumps(record, ensure_ascii=False, indent=2))
    sys.stdout.flush()


def _git(repo: str, cmd: str) -> Optional[str]:
    try:
        result = subprocess.run(cmd, cwd=repo, shell=True, capture_output=True, text=True, check=True)
        return result.stdout.strip()
    except subprocess.CalledProcessError as exc:
        log("ERROR", message="git command failed", cmd=cmd, exit=exc.returncode, error="E_GIT_FAILED")
        if exc.stderr and exc.stderr.strip():
            log("ERROR", message="git stderr", line=exc.stderr.strip(), error="E_GIT_FAILED")
        return None


def _parse_numstat(numstat: str) -> Tuple[int, int]:
    lines = [line.strip() for line in numstat.strip().split("\n") if line.strip()]
    additions = deletions = 0
    for line in lines:
        parts = line.split("\t")
        if len(parts) >= 2 and parts[0] != "-" and parts[1] != "-":
            try:
                additions += int(parts[0])
                deletions += int(parts[1])
            except ValueError:
                pass
    return additions, deletions


def repo_name(repo_path: str) -> str:
    return os.path.basename(repo_path) or "Unknown"


def _parse_commit_entry(current_hash, current_iso, current_subject, current_author, current_email, current_trailer,
                        numstat_lines):
    """Parse a single commit's raw data into a structured dict."""
    commit_date = datetime.fromisoformat(current_iso.replace("Z", "+00:00"))
    additions, deletions = _parse_numstat("\n".join(numstat_lines))
    is_claude = current_trailer and "Claude <noreply@anthropic.com>" in current_trailer
    is_opencode = current_trailer and "opencode <opencode@opencode.ai>" in current_trailer
    file_paths = [line.split("\t")[2] for line in numstat_lines if line.count("\t") >= 2]
    return {
        "hash": current_hash[:8], "date": commit_date,
        "additions": additions, "deletions": deletions,
        "files_changed": len(numstat_lines), "files_changed_list": file_paths,
        "is_claude": is_claude, "is_opencode": is_opencode,
        "is_ai": is_claude or is_opencode,
        "author_name": current_author, "author_email": current_email,
        "subject": current_subject,
    }


def _stream_git_log(repo_path: str, git_args: str, on_commit=None, progress_offset=0, progress_total=0):
    """Stream git log output and yield parsed commit dicts."""
    proc = subprocess.Popen(
        f'git log -c --numstat --reverse --format="%H||%cI||%s||%an||%ae||%(trailers:key=Co-Authored-By,only=yes)" {git_args}',
        cwd=repo_path, shell=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        text=True, bufsize=1,
    )

    current_hash = current_iso = current_subject = current_author = current_email = current_trailer = None
    numstat_lines = []
    index = 0

    for line in proc.stdout:
        line = line.rstrip("\n")
        if "||" in line and len(line) > 40:
            if current_hash:
                index += 1
                yield _parse_commit_entry(current_hash, current_iso, current_subject,
                                          current_author, current_email, current_trailer, numstat_lines)
                if on_commit:
                    on_commit(current_hash[:8], progress_offset + index, progress_total)
                numstat_lines.clear()
            current_hash, current_iso, current_subject, current_author, current_email, current_trailer = line.split(
                "||", 5)
        elif line and "\t" in line:
            numstat_lines.append(line)

    if current_hash:
        index += 1
        yield _parse_commit_entry(current_hash, current_iso, current_subject,
                                  current_author, current_email, current_trailer, numstat_lines)
        if on_commit:
            on_commit(current_hash[:8], progress_offset + index, progress_total)

    proc.stdout.close()
    proc.wait()


def _fetch_commit_bodies(repo_path: str, hashes: Iterable[str]) -> Dict[str, str]:
    """Batch-fetch the full original commit message for each given hash.

    Returns a map of short hash -> full message (subject + body). Commits that
    cannot be resolved are omitted. Used to enrich per-commit audit findings
    with their complete original message text.
    """
    unique = sorted({h for h in hashes if _SHORT_HASH_RE.match(h or "")})
    if not unique:
        return {}
    try:
        proc = subprocess.run(
            f'git log --no-walk --format="%H%x00%B%x00" {" ".join(unique)}',
            cwd=repo_path, shell=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, bufsize=1, timeout=30,
        )
    except subprocess.SubprocessError:
        return {}
    parts = proc.stdout.split("\x00")
    messages: Dict[str, str] = {}
    for i in range(0, len(parts) - 1, 2):
        full_hash = parts[i].strip()
        message = parts[i + 1]
        if full_hash:
            messages[full_hash[:8]] = message.rstrip("\n")
    return messages


def all_commits(repo_path: str, all_branches: bool = False, since_date: str = None, on_commit=None) -> List[Dict]:
    """Fetch all commits from the repository. Same behavior as the original."""
    ref = "--all" if all_branches else "HEAD"
    since_flag = f"--since={since_date}" if since_date else ""

    total = 0
    try:
        raw = _git(repo_path, f'git rev-list --count {since_flag} {ref}')
        if raw:
            total = int(raw.strip())
    except (ValueError, AttributeError):
        pass

    git_args = f"{since_flag} {ref}".strip()
    entries = list(_stream_git_log(repo_path, git_args, on_commit, progress_total=total))

    entries.sort(key=lambda e: e["date"])
    return entries


# Per-commit additions/deletions and dates — the atomic units that feed
# every quality sub-score. All downstream analyses are just aggregations
# and transformations of these three raw numbers per commit.
SAFE_BUILTINS = {
    "abs": abs, "max": max, "min": min, "round": round,
    "sum": sum, "pow": pow, "int": int, "float": float,
}

BUILTIN_FORMULAS = {
    "net_ratio": "(net / (add + deletions) * 100) if (add + deletions) > 0 else 0",
    "efficiency": "(net / max(add, deletions) * 100) if max(add, deletions) > 0 else 0",
    "churn": "((add + deletions) / cum_total * 100) if cum_total > 0 else 0",
}


def evaluate_formula(expr: str, vars_: dict):
    try:
        res = eval(expr, {"__builtins__": SAFE_BUILTINS}, vars_)
        return round(res, 2) if isinstance(res, float) else res
    except Exception:
        return None


def _sigmoid(value: float) -> float:
    if value < -700:
        return 0.0
    if value > 700:
        return 1.0
    return 1.0 / (1.0 + math.exp(-value))


def _gaussian(value: float, mu: float, sigma: float) -> float:
    return math.exp(-((value - mu) ** 2) / (2 * sigma ** 2))


def quality_band(score: float) -> Tuple[str, str]:
    for band in _QUALITY_BANDS:
        if score >= band["min"]:
            return band["name"], band["color"]
    return "Caution", "red"


class QualityModel:
    """
    Quality scoring engine. Every scoring method is independently testable:
    takes raw data + config, returns a 0-1 score with no side effects.
    Identical to the original git_stats.py — only the output layer changed.
    """

    def __init__(self, quality_params: dict):
        self._params = quality_params

    @staticmethod
    def gather_metrics(entries: List[Dict], now: datetime) -> dict:
        total_commits = len(entries)
        first_commit_date = entries[0]["date"]
        last_commit_date = entries[-1]["date"]
        active_days = max((last_commit_date - first_commit_date).total_seconds() / 86400.0, 0.1)
        days_since_last_commit = (now - last_commit_date).total_seconds() / 86400.0

        total_additions = sum(entry["additions"] for entry in entries)
        total_deletions = sum(entry["deletions"] for entry in entries)
        total_lines = total_additions + total_deletions

        commits_with_additions = sum(1 for entry in entries if entry["additions"] > 0)
        average_additions = total_additions / max(commits_with_additions, 1)
        commits_per_day = total_commits / active_days

        claude_commit_count = sum(1 for entry in entries if entry.get("is_claude"))
        opencode_commit_count = sum(1 for entry in entries if entry.get("is_opencode"))
        ai_commit_count = sum(1 for entry in entries if entry.get("is_ai"))
        all_additions = [entry["additions"] for entry in entries]
        average_files_changed = (
            statistics.mean([entry["files_changed"] for entry in entries])
            if total_commits > 0 else 0.0
        )

        return dict(
            total_commits=total_commits,
            first_commit_date=first_commit_date,
            last_commit_date=last_commit_date,
            active_days=active_days,
            days_since_last_commit=days_since_last_commit,
            total_additions=total_additions,
            total_deletions=total_deletions,
            total_lines=total_lines,
            average_additions=average_additions,
            commits_per_day=commits_per_day,
            claude_commit_count=claude_commit_count,
            opencode_commit_count=opencode_commit_count,
            ai_commit_count=ai_commit_count,
            all_additions=all_additions,
            average_files_changed=average_files_changed,
        )

    def score_recency(self, days_since_last_commit: float) -> float:
        return _gaussian(days_since_last_commit, 0.0, self._params["recencyHalfLifeDays"])

    @staticmethod
    def _score_sigmoid(value: float, threshold: float, scale: float, invert: bool = False) -> float:
        normalized = (value - threshold) / scale
        score = _sigmoid(normalized)
        return 1.0 - score if invert else score

    def score_ai_volume(self, average_additions: float) -> float:
        return self._score_sigmoid(
            average_additions,
            self._params["aiAdditionsThreshold"],
            self._params["aiAdditionsScale"],
        )

    def score_ai_initiative(self, entries: List[Dict]) -> float:
        early_count = min(self._params["aiInitiativeCommits"], len(entries))
        early_additions = sum(entry["additions"] for entry in entries[:early_count])
        early_deletions = sum(entry["deletions"] for entry in entries[:early_count])
        ratio = early_additions / max(early_deletions, 1)
        return self._score_sigmoid(
            ratio,
            self._params["aiInitiativeThreshold"],
            self._params["aiInitiativeScale"],
        )

    def score_ai_repetition(self, all_additions: List[int]) -> float:
        if len(all_additions) < 2:
            return 0.5
        cv = statistics.stdev(all_additions) / max(statistics.mean(all_additions), 1)
        return self._score_sigmoid(
            cv,
            self._params["aiRepetitionCvThreshold"],
            self._params["aiRepetitionScale"],
            invert=True,
        )

    def score_ai_focus(self, average_files_changed: float) -> float:
        return self._score_sigmoid(
            average_files_changed,
            self._params["aiFocusTarget"],
            self._params["aiFocusScale"],
            invert=True,
        )

    def score_firework(self, commits_per_day: float, active_days: float) -> float:
        density_normalized = (
                                     commits_per_day - self._params["fireworkDensityThreshold"]
                             ) / self._params["fireworkDensitySlope"]
        density_activation = _sigmoid(density_normalized)

        duration_normalized = (
                                      self._params["fireworkActiveDays"] - active_days
                              ) / self._params["fireworkDurationSlope"]
        duration_penalty = _sigmoid(duration_normalized)

        return density_activation * duration_penalty

    @staticmethod
    def score_agent_artifacts(repo: str) -> float:
        if not repo:
            return 0.0
        detected = _detect_agent_artifacts(repo)
        if not detected:
            return 0.0
        return min(len(detected) / 3.0, 1.0)

    def score_ai_claude(self, ai_commit_count: int, total_commits: int) -> float:
        ai_ratio = ai_commit_count / max(total_commits, 1)
        return min(ai_ratio * self._params["aiClaudeFactor"], 1.0)

    def score_deletion_health(self, total_additions: int, total_deletions: int) -> float:
        if total_additions == 0:
            return self._params.get("deletionDefaultScore", 0.5)
        deletion_ratio = total_deletions / total_additions
        return _gaussian(
            deletion_ratio,
            self._params["deletionIdealRatio"],
            self._params["deletionSigma"],
        )

    def score_scale(self, total_lines: int) -> float:
        reference_lines = self._params["scaleRefLines"]
        maximum_lines = self._params["scaleMaxLines"]
        minimum_score = self._params["scaleMinScore"]
        maximum_score = self._params["scaleMaxScore"]

        if total_lines <= 0:
            return minimum_score

        log_ratio = math.log(1 + total_lines / reference_lines)
        log_range = math.log(1 + maximum_lines / reference_lines)
        normalized_position = min(log_ratio / log_range, 1.0)

        return minimum_score + (maximum_score - minimum_score) * normalized_position

    @staticmethod
    def score_hero(entries: List[Dict], top_n: int) -> float:
        author_commit_counts = defaultdict(int)
        for entry in entries:
            author_name = entry.get("author_name", "Unknown")
            if _is_bot_author(author_name, _BOT_PATTERNS):
                continue
            author_commit_counts[author_name] += 1

        if not author_commit_counts:
            return 1.0

        sorted_counts = sorted(author_commit_counts.values(), reverse=True)
        top_total = sum(sorted_counts[:top_n])
        grand_total = sum(sorted_counts)
        concentration_ratio = top_total / max(grand_total, 1)
        return 1.0 - concentration_ratio

    def compute_tiny_penalty(self, total_commits: int) -> float:
        shortfall = self._params["tinyCommitThreshold"] - total_commits
        activation = _sigmoid(shortfall / self._params["tinyPenaltySlope"])
        return activation * self._params["tinyPenaltyMax"]

    @staticmethod
    def compute_confidence(total_commits: int) -> float:
        return 1.0 - 1.0 / (math.sqrt(total_commits) + 1.0)

    def compute_personality(self, total_lines: int, confidence: float) -> float:
        max_lines = self._params.get("personalityMaxLines", 1000000)
        amplitude = self._params.get("personalityAmplitude", 0.04)
        size_factor = math.log10(max(total_lines, 1) + 1) / math.log10(max_lines + 1)
        return (size_factor - 0.5) * amplitude * confidence

    @staticmethod
    def aggregate_suspicion(ai_sub_scores: List[float]) -> float:
        if not ai_sub_scores:
            return 0.0
        return sum(ai_sub_scores) / len(ai_sub_scores)

    def compute_raw_score(self, factor_scores: dict) -> float:
        raw = 0.0
        for factor_name, score in factor_scores.items():
            weight_key = "weight" + factor_name.replace("_", " ").title().replace(" ", "")
            if weight_key in self._params:
                raw += self._params[weight_key] * score
        return raw

    @staticmethod
    def adjust_composite(
            raw_score: float, confidence: float,
            baseline: float, personality: float
    ) -> float:
        adjusted = baseline + (raw_score - baseline) * confidence
        composite = adjusted + personality
        return max(0.0, min(1.0, composite))

    def evaluate(self, entries: List[Dict], project_name: str, now: datetime,
                 repo: str = None) -> dict:
        if not entries:
            return {}

        metrics = self.gather_metrics(entries, now)

        if metrics["days_since_last_commit"] > self._params["zombieDays"]:
            return dict(
                name=project_name,
                total_commits=metrics["total_commits"],
                active_days=round(metrics["active_days"], 1),
                days_since_last=round(metrics["days_since_last_commit"], 1),
                band="Archived",
                band_color="grey58",
                scores={"composite": 0.0},
            )

        ai_volume_score = self.score_ai_volume(metrics["average_additions"])
        ai_initiative_score = self.score_ai_initiative(entries)
        ai_repetition_score = self.score_ai_repetition(metrics["all_additions"])
        ai_focus_score = self.score_ai_focus(metrics["average_files_changed"])
        firework_score = self.score_firework(metrics["commits_per_day"], metrics["active_days"])
        agent_artifact_score = self.score_agent_artifacts(repo)
        ai_claude_score = self.score_ai_claude(
            metrics["ai_commit_count"], metrics["total_commits"]
        )

        suspicion = self.aggregate_suspicion([
            ai_volume_score,
            ai_initiative_score,
            ai_repetition_score,
            ai_focus_score,
            firework_score,
            agent_artifact_score,
        ])

        deletion_health_score = self.score_deletion_health(
            metrics["total_additions"], metrics["total_deletions"]
        )
        scale_score = self.score_scale(metrics["total_lines"])
        hero_score = self.score_hero(entries, _HERO_TOP_N)
        tiny_penalty = self.compute_tiny_penalty(metrics["total_commits"])

        factor_scores = dict(
            recency=self.score_recency(metrics["days_since_last_commit"]),
            anti_ai=1.0 - suspicion,
            deletion_health=deletion_health_score,
            scale=scale_score,
            hero=hero_score,
        )
        raw = self.compute_raw_score(factor_scores) - tiny_penalty
        confidence = self.compute_confidence(metrics["total_commits"])
        baseline = self._params.get("baseline", 0.5)
        personality = self.compute_personality(metrics["total_lines"], confidence)
        composite = self.adjust_composite(raw, confidence, baseline, personality)

        band_name, band_color = quality_band(composite)

        return dict(
            name=project_name,
            total_commits=metrics["total_commits"],
            active_days=round(metrics["active_days"], 1),
            days_since_last=round(metrics["days_since_last_commit"], 1),
            first_commit=metrics["first_commit_date"].isoformat(),
            last_commit=metrics["last_commit_date"].isoformat(),
            total_additions=metrics["total_additions"],
            total_deletions=metrics["total_deletions"],
            total_lines=metrics["total_lines"],
            net_change=metrics["total_additions"] - metrics["total_deletions"],
            avg_additions_per_commit=round(metrics["average_additions"], 1),
            commits_per_day=round(metrics["commits_per_day"], 2),
            churn_ratio=round(
                metrics["total_deletions"] / max(metrics["total_additions"], 1), 3
            ),
            scores=dict(
                recency=round(factor_scores["recency"], 4),
                ai_volume=round(ai_volume_score, 4),
                ai_initiative=round(ai_initiative_score, 4),
                ai_repetition=round(ai_repetition_score, 4),
                ai_focus=round(ai_focus_score, 4),
                firework=round(firework_score, 4),
                agent_artifact=round(agent_artifact_score, 4),
                ai_claude=round(ai_claude_score, 4),
                tiny_project=round(tiny_penalty, 4),
                suspicion=round(suspicion, 4),
                deletion_health=round(deletion_health_score, 4),
                scale=round(scale_score, 4),
                hero=round(hero_score, 4),
                composite=round(composite, 4),
            ),
            band=band_name,
            band_color=band_color,
            avg_files_changed=round(metrics["average_files_changed"], 2),
            claude_commits=metrics["ai_commit_count"],
        )

    def evaluate_period_batch(
            self, entries: List[Dict], unit: str, now: datetime,
            overall_hero_score: float = None, repo: str = None,
    ) -> List[dict]:
        if not entries:
            return []

        buckets = defaultdict(list)
        for entry in entries:
            commit_date = entry["date"]
            if unit == "month":
                period_key = commit_date.strftime("%Y-%m")
            elif unit == "week":
                period_key = commit_date.strftime("%Y-W%V")
            elif unit == "quarter":
                period_key = f"{commit_date.year}-Q{(commit_date.month - 1) // 3 + 1}"
            elif unit == "year":
                period_key = commit_date.strftime("%Y")
            else:
                period_key = commit_date.strftime("%Y-%m")
            buckets[period_key].append(entry)

        periods = []
        overall_hero = overall_hero_score if overall_hero_score is not None else 1.0

        entry_index = 0
        cumulative_lines = 0

        for period_name in sorted(buckets):
            group = buckets[period_name]
            metrics = self.gather_metrics(group, now)

            ai_volume_score = self.score_ai_volume(metrics["average_additions"])
            firework_score = self.score_firework(metrics["commits_per_day"], metrics["active_days"])

            ai_initiative_score = self.score_ai_initiative(group)
            ai_repetition_score = self.score_ai_repetition(metrics["all_additions"])
            ai_focus_score = self.score_ai_focus(metrics["average_files_changed"])
            agent_artifact_score = self.score_agent_artifacts(repo)

            suspicion = self.aggregate_suspicion([
                ai_volume_score,
                ai_initiative_score,
                ai_repetition_score,
                ai_focus_score,
                firework_score,
                agent_artifact_score,
            ])

            deletion_health_score = self.score_deletion_health(
                metrics["total_additions"], metrics["total_deletions"]
            )

            cumulative_lines += metrics["total_additions"] + metrics["total_deletions"]
            entry_index += len(group)
            scale_score = self.score_scale(cumulative_lines)

            tiny_penalty = self.compute_tiny_penalty(metrics["total_commits"])

            factor_scores = dict(
                recency=self.score_recency(metrics["days_since_last_commit"]),
                anti_ai=1.0 - suspicion,
                deletion_health=deletion_health_score,
                scale=scale_score,
                hero=overall_hero,
            )
            raw = self.compute_raw_score(factor_scores) - tiny_penalty
            confidence = self.compute_confidence(metrics["total_commits"])
            baseline = self._params.get("baseline", 0.5)
            personality = self.compute_personality(cumulative_lines, confidence)
            composite = self.adjust_composite(raw, confidence, baseline, personality)

            band_name, _ = quality_band(composite)

            periods.append(dict(
                period=period_name,
                commits=metrics["total_commits"],
                additions=metrics["total_additions"],
                deletions=metrics["total_deletions"],
                recency=round(factor_scores["recency"], 3),
                ai_volume=round(ai_volume_score, 3),
                deletion=round(deletion_health_score, 3),
                composite=round(composite, 3),
                band=band_name,
            ))

        return periods


# Compatibility wrappers — delegate to QualityModel.
def compute_quality(entries: List[Dict], params: Dict, now: datetime,
                    name: str = "", repo: str = None) -> Dict:
    model = QualityModel(params)
    return model.evaluate(entries, name, now, repo=repo)


def compute_quality_periods(entries: List[Dict], unit: str, now: datetime,
                            params: Dict, overall_hero_score: float = None,
                            repo: str = None) -> List[Dict]:
    model = QualityModel(params)
    return model.evaluate_period_batch(entries, unit, now, overall_hero_score, repo=repo)


def _load_config() -> dict:
    config_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "configs.jsonc")
    try:
        with open(config_path) as f:
            text = f.read()
        text = re.sub(r'//.*', '', text)
        text = re.sub(r'/\*.*?\*/', '', text, flags=re.DOTALL)
        return json.loads(text)
    except (FileNotFoundError, json.JSONDecodeError):
        log("WARN", message="failed to loadProperties configs.jsonc", path=config_path)
        return {}


_CONFIG = _load_config()
all_exts = set(_CONFIG.get("codeExtensions", []))
_non_prog_exts = set(_CONFIG.get("nonProgrammingExtensions", []))
_CORE_EXTS = all_exts - _non_prog_exts
_EXCLUDED_DIRS = set(_CONFIG.get("excludedDirectories", []))
_QUALITY_PARAMS = _CONFIG.get("qualityParams", {})
_SUSPICION_LABELS = _CONFIG.get("suspicionLabels", {})
_QUALITY_BANDS = _CONFIG.get("qualityBands", [])
_PRIMARY_COLOR = "#4a9eff"
_PARETO_LINE_COLOR = "#1a5276"

_SETTINGS = _CONFIG.get("settings", {})
_AUDIT_PARAMS = _CONFIG.get("audit", {})
_HERO_TOP_N = _AUDIT_PARAMS.get("heroTopContributorCount", 5)
_HERO_THRESHOLD = _AUDIT_PARAMS.get("heroRiskThreshold", 0.8)
_PARETO_MAX_BARS = _AUDIT_PARAMS.get("paretoMaxBarCount", 50)
_BOT_PATTERNS = _AUDIT_PARAMS.get("botAuthorPatterns", ["bot", "agent"])

_AUDIT_PARAM_KEYS = {
    "del_net": ("netDeletionThreshold", -200),
    "del_ratio": ("deletionRatioBoundary", 0.05),
    "both_large": ("refactoringBothLargeThreshold", 500),
    "cluster_gap": ("clusterPositionGap", 3),
    "cluster_min": ("clusterMinimumSize", 3),
    "mature_commits": ("matureProjectCommitCount", 50),
    "recent_days": ("recentActivityWindowDays", 90),
}
_SUSPICION_THRESHOLDS = {}
for internal, (config_key, default) in _AUDIT_PARAM_KEYS.items():
    _SUSPICION_THRESHOLDS[internal] = _AUDIT_PARAMS.get(config_key, default)

# Audit finding codes -> (type, level). The human-readable message is
# produced on the plugin side from the code + params; the script only
# carries the raw values needed to render it.
_AUDIT_CODES = {
    # Critical (S1000-S1999)
    "S1001": ("High-Risk Refactoring", "critical"),
    "S1002": ("Net Reduction", "critical"),
    "S1003": ("Single-Author Project", "critical"),
    "S1004": ("Mass Rewrite", "critical"),
    "S1005": ("Zero Activity", "critical"),
    # Alert (S2000-S2999)
    "S2001": ("Deletion Cluster", "alert"),
    "S2002": ("Mature Churn", "alert"),
    "S2003": ("Core Net Deletion", "alert"),
    "S2004": ("Accumulation-Only", "alert"),
    "S2005": ("AI Volume Spike", "alert"),
    "S2006": ("AI Bootstrap", "alert"),
    "S2007": ("AI Uniformity", "alert"),
    "S2008": ("AI Focus Deviation", "alert"),
    "S2009": ("Firework Burst", "alert"),
    "S2010": ("Claude Flood", "alert"),
    "S2011": ("Bus Factor", "alert"),
    "S2012": ("Abandoned", "alert"),
    # Watch (S3000-S3999)
    "S3001": ("Non-Core Deletion", "watch"),
    "S3002": ("Heavy Churn", "watch"),
    "S3003": ("Bot-like Author", "watch"),
    "S3004": ("Weekend Warrior", "watch"),
    "S3005": ("Day Burst", "watch"),
    "S3006": ("No Merges", "watch"),
    "S3007": ("Tiny Commits", "watch"),
    "S3008": ("Vague Messages", "watch"),
    # Normal (S4000-S4999)
    "S4001": ("Low Cleanup", "normal"),
    "S4002": ("Small Project", "normal"),
    # Clean (S5000-S5999)
    "S2013": ("AI Agent Artifacts", "alert"),
}

# Agent artifact file/directory patterns for AI tool detection (S2013)
_AGENT_ARTIFACT_PATTERNS = {
    "Claude Code": ["CLAUDE.md", ".mcp.json", ".claude/*"],
    "Cursor": [".cursor/*", ".cursorrules", ".cursorignore"],
    "GitHub Copilot": [".github/copilot-instructions.md", ".github/instructions/*"],
    "Windsurf": [".windsurfrules", ".windsurf/*", ".codeiumignore"],
    "Cline": [".clinerules", ".cline/*", ".clineignore"],
    "Aider": [".aider.conf.yml", ".aiderignore"],
    "Gemini CLI": ["GEMINI.md", ".gemini/*"],
    "OpenCode": ["opencode.json", "opencode.jsonc", ".opencode/*"],
    "Codex CLI": ["AGENTS.md"],
    "Amazon Q": [".amazonq/*"],
}


@functools.lru_cache(maxsize=None)
def _detect_agent_artifacts(repo: str) -> Dict[str, List[str]]:
    detected = {}
    for agent_name, patterns in _AGENT_ARTIFACT_PATTERNS.items():
        for pattern in patterns:
            out = _git(repo, f'git ls-files "{pattern}"')
            if out:
                files = [f for f in out.strip().split("\n") if f]
                if files:
                    detected.setdefault(agent_name, []).extend(files)
    return detected


def _ai_participation_per_author(repo: str, entries: List[Dict]) -> Dict[str, int]:
    result: Dict[str, int] = defaultdict(int)

    for e in entries:
        if e.get("is_ai"):
            result[e.get("author_name", "Unknown")] += 1

    artifacts = _detect_agent_artifacts(repo)
    for files in artifacts.values():
        for file_path in files:
            out = _git(repo, f'git log --format="%an||%H" -- "{file_path}"')
            if out:
                seen = set()
                for line in out.strip().split("\n"):
                    if "||" not in line:
                        continue
                    author = line.split("||")[0].strip()
                    commit_hash = line.split("||")[1].strip()
                    key = (author, commit_hash)
                    if author and key not in seen:
                        seen.add(key)
                        result[author] += 1

    return dict(result)


def _is_bot_author(name: str, patterns: List[str] = None) -> bool:
    if patterns is None:
        patterns = _BOT_PATTERNS
    return any(pattern.lower() in name.lower() for pattern in patterns)


@functools.lru_cache(maxsize=None)
def _changed_files(repo: str, hash_: str) -> List[str]:
    out = _git(repo, f'git show --name-only --format="" {hash_}')
    return out.strip().split("\n") if out else []


def _is_core_commit(repo: str, hash_: str) -> Tuple[bool, int, int]:
    files = _changed_files(repo, hash_)
    if not files:
        return False, 0, 0
    core_count = sum(1 for f in files if os.path.splitext(f)[1] in _CORE_EXTS)
    return core_count > 0, core_count, len(files)


def audit_deletions(repo: str, entries: List[Dict], now: datetime,
                    scores: Dict = None, params: Optional[Dict] = None) -> Dict:
    thresholds = {**_SUSPICION_THRESHOLDS, **(params or {})}
    total = len(entries)
    if total == 0:
        return {}

    audit_params = _AUDIT_PARAMS

    enriched = []
    for index, entry in enumerate(entries):
        net = entry["additions"] - entry["deletions"]
        enriched.append({
            "index": index,
            "hash": entry["hash"],
            "date": entry["date"],
            "additions": entry["additions"],
            "deletions": entry["deletions"],
            "net": net,
            "author": entry.get("author_name", entry.get("author", "Unknown")),
            "email": entry.get("author_email", ""),
            "subject": entry.get("subject", ""),
            "files_changed": entry.get("files_changed", 0),
            "is_claude": entry.get("is_claude", False),
            "is_opencode": entry.get("is_opencode", False),
            "is_ai": entry.get("is_ai", False),
            "is_recent": (now - entry["date"]).total_seconds() / 86400.0 < thresholds["recent_days"],
        })

    deleters = sorted([entry for entry in enriched if entry["net"] < 0], key=lambda x: x["net"])
    heavy = [entry for entry in deleters if entry["net"] <= thresholds["del_net"]]

    positions = [entry["index"] for entry in heavy]
    clusters = []
    if positions:
        cluster = [positions[0]]
        for pos in positions[1:]:
            if pos - cluster[-1] <= thresholds["cluster_gap"]:
                cluster.append(pos)
            else:
                if len(cluster) >= thresholds["cluster_min"]:
                    clusters.append(cluster)
                cluster = [pos]
        if len(cluster) >= thresholds["cluster_min"]:
            clusters.append(cluster)

    suspicion = []

    for commit in heavy:
        is_core, core_count, total_files = _is_core_commit(repo, commit["hash"])
        both_large = commit["additions"] >= thresholds["both_large"] and commit["deletions"] >= thresholds["both_large"]

        if not is_core:
            suspicion.append({
                "code": "S3001", "hash": commit["hash"], "index": commit["index"],
                "date": str(commit["date"].date()),
                "time_since": int((now - commit["date"]).total_seconds() / 86400),
                "type": "Non-Core Deletion", "level": "watch",
                "subject": commit.get("subject", ""), "author": commit.get("author", ""),
                "params": {
                    "lines": -commit["net"], "files": total_files, "core": core_count,
                },
            })
        elif both_large:
            suspicion.append({
                "code": "S1001", "hash": commit["hash"], "index": commit["index"],
                "date": str(commit["date"].date()),
                "time_since": int((now - commit["date"]).total_seconds() / 86400),
                "type": "High-Risk Refactoring", "level": "critical",
                "subject": commit.get("subject", ""), "author": commit.get("author", ""),
                "params": {
                    "add": commit["additions"], "dels": commit["deletions"],
                    "threshold": thresholds["both_large"],
                },
            })
        else:
            suspicion.append({
                "code": "S2003", "hash": commit["hash"], "index": commit["index"],
                "date": str(commit["date"].date()),
                "time_since": int((now - commit["date"]).total_seconds() / 86400),
                "type": "Core Net Deletion", "level": "alert",
                "subject": commit.get("subject", ""), "author": commit.get("author", ""),
                "params": {"lines": -commit["net"]},
            })

    for index, cluster in enumerate(clusters):
        cluster_commits = [entry for entry in enriched if entry["index"] in cluster]
        total_del = sum(-entry["net"] for entry in cluster_commits if entry["net"] < 0)
        suspicion.append({
            "code": "S2001", "hash": f"Cluster #{index + 1}", "index": cluster[0],
            "date": str(cluster_commits[0]["date"].date()) if cluster_commits else "",
            "time_since": "-",
            "type": "Deletion Cluster", "level": "alert",
            "params": {
                "count": len(cluster), "lines": total_del,
                "start_idx": cluster[0], "end_idx": cluster[-1],
            },
        })

    if total >= thresholds["mature_commits"]:
        recent_heavy = [commit for commit in heavy if commit["is_recent"]]
        if len(recent_heavy) >= thresholds["cluster_min"]:
            sum(-entry["net"] for entry in recent_heavy)
            suspicion.append({
                "code": "S2002", "hash": "Recent Spike", "index": recent_heavy[-1]["index"],
                "date": str(recent_heavy[-1]["date"].date()), "time_since": "-",
                "type": "Mature Project Churn", "level": "alert",
                "params": {
                    "total": total, "count": len(recent_heavy),
                    "days": thresholds["recent_days"],
                },
            })

    total_additions = sum(entry["additions"] for entry in entries)
    total_deletions = sum(entry["deletions"] for entry in entries)
    total_lines_all = total_additions + total_deletions

    # S1002 / S2004 / S3002 / S4001: churn ratios
    if total_additions == 0 and total_deletions == 0:
        ratio_label = "No Changes"
        ratio_level = "normal"
    elif total_deletions == 0:
        ratio_label = "Accumulation-Only"
        ratio_level = "alert"
        suspicion.append({
            "code": "S2004", "hash": "-", "index": -1,
            "date": str(now.date()), "time_since": "-",
            "type": "Accumulation-Only", "level": "alert",
            "params": {"ratio": 0.0, "total": total},
        })
    else:
        ratio = total_deletions / total_additions
        if ratio < 0.05:
            ratio_label = "Accumulation-Only"
            ratio_level = "alert"
            suspicion.append({
                "code": "S2004", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "Accumulation-Only", "level": "alert",
                "params": {"ratio": ratio * 100, "total": total},
            })
        elif ratio < 0.25:
            ratio_label = "Low Cleanup"
            ratio_level = "normal"
            suspicion.append({
                "code": "S4001", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "Low Cleanup", "level": "normal",
                "params": {"ratio": ratio * 100},
            })
        elif ratio < 0.50:
            ratio_label = "Balanced Churn"
            ratio_level = "clean"
        elif ratio < 1.0:
            ratio_label = "Heavy Churn"
            ratio_level = "watch"
            suspicion.append({
                "code": "S3002", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "Heavy Churn", "level": "watch",
                "params": {
                    "ratio": ratio * 100,
                    "dels": total_deletions, "add": total_additions,
                },
            })
        else:
            ratio_label = "Net Reduction"
            ratio_level = "critical"
            suspicion.append({
                "code": "S1002", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "Net Reduction", "level": "critical",
                "params": {
                    "dels": total_deletions, "add": total_additions,
                    "ratio": total_deletions / max(total_additions, 1) * 100,
                },
            })

    # S1003: Single-Author Project
    # Identify authors by email (stable identity) so the same person using
    # multiple display names counts as one contributor; fall back to the
    # display name when no email is available.
    def _author_identity(entry: Dict) -> str:
        email = entry.get("email", "").strip()
        return email.lower() if email else entry.get("author", "Unknown").lower()

    author_ids = {_author_identity(e) for e in enriched}
    non_bot_author_ids = [aid for aid in author_ids if not _is_bot_author(aid)]
    if len(non_bot_author_ids) <= 1 and total >= 10:
        author_name = "unknown"
        if non_bot_author_ids:
            match = next((e["author"] for e in enriched if _author_identity(e) == non_bot_author_ids[0]), "")
            author_name = match or non_bot_author_ids[0]
        suspicion.append({
            "code": "S1003", "hash": "-", "index": -1,
            "date": str(now.date()), "time_since": "-",
            "type": "Single-Author Project", "level": "critical",
            "params": {"author": author_name, "total": total},
        })

    # S1004: Mass Rewrite
    max_single = max(enriched, key=lambda e: e["additions"] + e["deletions"])
    single_total = max_single["additions"] + max_single["deletions"]
    pct_of_total = single_total / max(total_lines_all, 1) * 100
    if pct_of_total > 50 and total_lines_all > 1000:
        suspicion.append({
            "code": "S1004", "hash": max_single["hash"], "index": max_single["index"],
            "date": str(max_single["date"].date()),
            "time_since": int((now - max_single["date"]).total_seconds() / 86400),
            "type": "Mass Rewrite", "level": "critical",
            "subject": max_single.get("subject", ""), "author": max_single.get("author", ""),
            "params": {"lines": single_total, "pct": pct_of_total},
        })

    if scores:
        # S2005: AI Volume
        if scores.get("ai_volume", 0) > 0.5:
            avg_add = total_additions / max(total, 1)
            suspicion.append({
                "code": "S2005", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "AI Volume Spike", "level": "alert",
                "params": {
                    "avg": avg_add,
                    "threshold": audit_params.get("aiAdditionsPerCommitThreshold", 500),
                },
            })
        # S2006: AI Initiative
        if scores.get("ai_initiative", 0) > 0.5:
            n = audit_params.get("aiInitiativeEarlyCommitCount", 3)
            early = [e for e in enriched[:n] if e["deletions"] > 0]
            early_ratio = (sum(e["additions"] for e in early) /
                           max(sum(e["deletions"] for e in early), 1)) if early else 0
            suspicion.append({
                "code": "S2006", "hash": enriched[0]["hash"] if enriched else "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "AI Bootstrap", "level": "alert",
                "subject": enriched[0].get("subject", "") if enriched else "",
                "author": enriched[0].get("author", "") if enriched else "",
                "params": {"n": n, "ratio": early_ratio},
            })
        # S2007: AI Repetition
        if scores.get("ai_repetition", 0) > 0.5:
            all_adds = [e["additions"] for e in enriched]
            cv = (statistics.stdev(all_adds) / statistics.mean(all_adds)) if len(all_adds) > 1 and statistics.mean(
                all_adds) > 0 else 0
            suspicion.append({
                "code": "S2007", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "AI Uniformity", "level": "alert",
                "params": {
                    "cv": cv,
                    "threshold": audit_params.get("aiRepetitionCvThreshold", 0.5),
                },
            })
        # S2008: AI Focus
        if scores.get("ai_focus", 0) > 0.5:
            avg_files = sum(e["files_changed"] for e in enriched) / max(total, 1)
            suspicion.append({
                "code": "S2008", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "AI Focus Deviation", "level": "alert",
                "params": {
                    "avg": avg_files, "n": total,
                    "target": audit_params.get("aiFocusTargetFiles", 3.0),
                },
            })
        # S2009: Firework Burst
        if scores.get("firework", 0) > 0.5:
            days_active = max((enriched[-1]["date"] - enriched[0]["date"]).total_seconds() / 86400,
                              0.1) if enriched else 1
            density = total / days_active
            suspicion.append({
                "code": "S2009", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "Firework Burst", "level": "alert",
                "params": {"density": density, "days": days_active},
            })
        # S2010: Claude Flood / AI Co-author Flood
        if scores.get("ai_claude", 0) > 0.25:
            ai_coauthor_count = sum(1 for e in enriched if e.get("is_ai", False))
            claude_count = sum(1 for e in enriched if e.get("is_claude", False))
            opencode_count = sum(1 for e in enriched if e.get("is_opencode", False))
            coauthors = []
            if claude_count > 0:
                coauthors.append("Claude")
            if opencode_count > 0:
                coauthors.append("OpenCode")
            coauthors_str = " / ".join(coauthors) if coauthors else "AI"
            suspicion.append({
                "code": "S2010", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "Claude Flood", "level": "alert",
                "params": {
                    "pct": ai_coauthor_count / max(total, 1) * 100,
                    "count": ai_coauthor_count, "total": total,
                    "coauthors": coauthors_str,
                },
            })
        # S2011: Hero Dependency
        if scores.get("hero", 1.0) < 0.5:
            hero_n = _HERO_TOP_N
            author_counts = defaultdict(int)
            for e in enriched:
                a = e["author"]
                if not _is_bot_author(a):
                    author_counts[a] += 1
            sorted_counts = sorted(author_counts.values(), reverse=True)
            top_total = sum(sorted_counts[:hero_n])
            hero_pct = top_total / max(sum(sorted_counts), 1) * 100
            suspicion.append({
                "code": "S2011", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "Hero Dependency", "level": "alert",
                "params": {"n": hero_n, "pct": hero_pct, "total": total},
            })
        # S2012: Abandoned
        days_since = (now - enriched[-1]["date"]).total_seconds() / 86400 if enriched else 9999
        half_life = audit_params.get("recencyHalfLifeDays", 90)
        if days_since > half_life:
            suspicion.append({
                "code": "S2012", "hash": enriched[-1]["hash"] if enriched else "-", "index": -1,
                "date": str(enriched[-1]["date"].date()) if enriched else str(now.date()),
                "time_since": int(days_since),
                "type": "Abandoned", "level": "alert",
                "subject": enriched[-1].get("subject", "") if enriched else "",
                "author": enriched[-1].get("author", "") if enriched else "",
                "params": {"days": days_since, "half": half_life},
            })

    # S3003: Bot-like Author
    for e in enriched:
        if _is_bot_author(e["author"]):
            matched_pattern = next((p for p in _BOT_PATTERNS if p.lower() in e["author"].lower()), _BOT_PATTERNS[0])
            suspicion.append({
                "code": "S3003", "hash": e["hash"], "index": e["index"],
                "date": str(e["date"].date()),
                "time_since": int((now - e["date"]).total_seconds() / 86400),
                "type": "Bot-like Author", "level": "watch",
                "subject": e.get("subject", ""), "author": e.get("author", ""),
                "params": {
                    "author": e["author"],
                    "pattern": matched_pattern,
                },
            })
            break

    # S3004: Weekend Warrior
    weekend_count = sum(1 for e in enriched if e["date"].weekday() >= 5)
    weekend_pct = weekend_count / max(total, 1) * 100
    if weekend_pct > 50 and total >= 10:
        suspicion.append({
            "code": "S3004", "hash": "-", "index": -1,
            "date": str(now.date()), "time_since": "-",
            "type": "Weekend Warrior", "level": "watch",
            "params": {"pct": weekend_pct, "count": weekend_count, "total": total},
        })

    # S3005: Day Burst
    day_counts = defaultdict(int)
    for e in enriched:
        day_counts[e["date"].date()] += 1
    max_day = max(day_counts, key=day_counts.get)
    max_day_count = day_counts[max_day]
    if max_day_count > 10:
        suspicion.append({
            "code": "S3005",
            "hash": enriched[next(index for index, entry in enumerate(enriched) if entry["date"].date() == max_day)][
                "hash"],
            "index": -1, "date": str(max_day), "time_since": "-",
            "type": "Day Burst", "level": "watch",
            "params": {"count": max_day_count},
        })

    # S3006: No Merges
    merge_count = sum(1 for e in enriched if "merge" in e["subject"].lower())
    if merge_count == 0 and total >= 20:
        suspicion.append({
            "code": "S3006", "hash": "-", "index": -1,
            "date": str(now.date()), "time_since": "-",
            "type": "No Merges", "level": "watch",
            "params": {"total": total},
        })

    # S3007: Tiny Commits
    tiny_threshold = audit_params.get("tinyCommitLineThreshold", 10)
    tiny_count = sum(1 for e in enriched if e["additions"] + e["deletions"] < tiny_threshold)
    tiny_pct = tiny_count / max(total, 1) * 100
    if tiny_pct > 30 and total >= 10:
        suspicion.append({
            "code": "S3007", "hash": "-", "index": -1,
            "date": str(now.date()), "time_since": "-",
            "type": "Tiny Commits", "level": "watch",
            "params": {
                "count": tiny_count, "total": total, "pct": tiny_pct, "limit": tiny_threshold,
            },
        })

    # S3008: Vague Messages (≤5 chars + regex)
    _VAGUE_PATTERN = re.compile(
        r'^[\s.]*$'  # blank or dots
        r'|^\d+$'  # just numbers
        r'|^[a-z]{1}$'  # single letter
        r'|^(update|fix|bugfix|minor|tweak|cleanup|wip|temp|test|asdf|qwerty|xxx|foo|bar)$'
    )
    vague_count = 0
    for e in enriched:
        subject = e["subject"].strip().lower()
        if "initial commit" in subject:
            continue
        if len(subject) <= 5 and _VAGUE_PATTERN.match(subject):
            vague_count += 1
    vague_pct = vague_count / max(total, 1) * 100
    if vague_pct > 30 and total >= 10:
        suspicion.append({
            "code": "S3008", "hash": "-", "index": -1,
            "date": str(now.date()), "time_since": "-",
            "type": "Vague Messages", "level": "watch",
            "params": {"count": vague_count, "total": total, "pct": vague_pct},
        })

    # S4002: Small Project
    if total_lines_all < 1000 and total >= 5:
        all_files = set()
        for e in entries:
            all_files.update(e.get("files_changed_list", []))
        suspicion.append({
            "code": "S4002", "hash": "-", "index": -1,
            "date": str(now.date()), "time_since": "-",
            "type": "Small Project", "level": "normal",
            "params": {"lines": total_lines_all, "files": len(all_files)},
        })

    # S2013: AI Agent Artifacts
    agent_traces = _detect_agent_artifacts(repo)
    if agent_traces:
        agents = sorted(agent_traces.keys())
        display = ", ".join(agents[:5])
        suspicion.append({
            "code": "S2013", "hash": "-", "index": -1,
            "date": str(now.date()), "time_since": "-",
            "type": "AI Agent Artifacts", "level": "alert",
            "params": {
                "agents": display,
                "count": len(agent_traces),
            },
        })

    highest_level = "clean"
    for suspect in suspicion:
        level = suspect["level"]
        if level == "critical":
            highest_level = "critical"
        elif level == "alert" and highest_level not in ("critical",):
            highest_level = "alert"
        elif level == "watch" and highest_level not in ("critical", "alert"):
            highest_level = "watch"
        elif level == "normal" and highest_level not in ("critical", "alert", "watch"):
            highest_level = "normal"

    bodies = _fetch_commit_bodies(
        repo, (suspect.get("hash", "") for suspect in suspicion)
    )
    for suspect in suspicion:
        suspect_hash = suspect.get("hash", "")
        message = bodies.get(suspect_hash, "")
        if message:
            subject = suspect.get("subject", "")
            body = message
            if subject and message.startswith(subject):
                body = message[len(subject):].lstrip("\n")
            suspect["body"] = body

    return {
        "total_commits": total,
        "total_additions": total_additions,
        "total_deletions": total_deletions,
        "deletion_percent": round(total_deletions / max(total_additions, 1) * 100, 1),
        "ratio_label": ratio_label,
        "ratio_level": ratio_level,
        "heavy_deletions": len(heavy),
        "heavy_list": [commit["hash"] for commit in heavy[:10]],
        "clusters": len(clusters),
        "suspicion_count": len(suspicion),
        "suspicion": suspicion,
        "overall_level": highest_level,
    }


def _duration_str(days: float) -> str:
    if days < 0:
        return "0 minutes"
    total_minutes = int(days * 24 * 60 + 0.5)
    years = total_minutes // (365 * 24 * 60)
    total_minutes %= (365 * 24 * 60)
    days_part = total_minutes // (24 * 60)
    total_minutes %= (24 * 60)
    hours = total_minutes // 60
    minutes = total_minutes % 60
    parts = []
    if years > 0:
        parts.append(f"{years} year{'s' if years > 1 else ''}")
    if days_part > 0:
        parts.append(f"{days_part} day{'s' if days_part > 1 else ''}")
    if hours > 0:
        parts.append(f"{hours} hour{'s' if hours > 1 else ''}")
    if minutes > 0 or not parts:
        parts.append(f"{minutes} minute{'s' if minutes != 1 else ''}")
    return ", ".join(parts)


def main():
    fd = _SETTINGS
    repo = os.getcwd()

    log("INFO", message="Git Commit Statistics Analyzer by Gradum", version=__version__)

    if not os.path.exists(repo):
        log("ERROR", message="directory does not exist", repo=repo, error="E_DIR_NOT_EXISTS")
        sys.exit(2)
    if not os.path.exists(os.path.join(repo, ".git")):
        log("ERROR", message="not a valid git repository", repo=repo, error="E_NOT_GIT_REPO")
        sys.exit(3)

    all_branches = fd.get("branches", False)
    since_date = fd.get("sinceDate")

    log("INFO", message="start", repo=repo_name(repo), branches=all_branches, since=since_date)

    scan_start = time.time()

    def on_commit(hash_str, current, total_count):
        log("INFO", message="scanning commit", current=current, total=total_count,
            hash=hash_str)

    entries = all_commits(
        repo,
        all_branches=all_branches,
        since_date=since_date,
        on_commit=on_commit,
    )

    total = len(entries)
    if total == 0:
        log("WARN", message="No Commits Found", error="E_NO_COMMITS")
        sys.exit(1)

    elapsed = time.time() - scan_start
    elapsed_ms = int(elapsed * 1000)
    log("INFO", message="scanned", commits=total, repo=repo_name(repo), elapsed_ms=elapsed_ms)

    if fd.get("enableQualityAnalysis", False):
        quality_params = dict(_QUALITY_PARAMS)
        quality_params_path = fd.get("qualityParamsFilePath")
        if quality_params_path and os.path.exists(quality_params_path):
            with open(quality_params_path) as file:
                quality_params.update(json.load(file))

        now = datetime.now(timezone.utc)
        log("INFO", message="Analyze Quality", commits=total)
        quality = compute_quality(entries, quality_params, now, repo_name(repo), repo)
        periods = []
        if not quality or quality.get("band") == "Archived":
            audit = {"suspicion_count": 0, "deletion_percent": 0, "heavy_deletions": 0, "suspicion": []}
        else:
            overall_hero = quality.get("scores", {}).get("hero", 0.0)
            batch_unit = fd.get("batchPeriodUnit")
            periods = compute_quality_periods(entries, batch_unit or "month", now, quality_params,
                                              overall_hero, repo) if batch_unit else []

            log("INFO", message="Audit Deletions", commits=total)
            audit = audit_deletions(repo, entries, now, quality.get("scores"))

        if not quality or quality.get("band") == "Archived":
            msg = f"Archived — no commits in {_duration_str(quality['days_since_last'])}" if quality else "Archived — no commits"
            log("WARN", message=msg, band="Archived")
        else:
            log("INFO", message="analyzed",
                commits=total,
                problems=audit['suspicion_count'],
                deletion_percent=audit['deletion_percent'],
                overall_level=audit['overall_level'])

            s = quality["scores"]
            log("INFO", message="quality",
                band=quality["band"],
                score=s["composite"],
                factor_recency=s["recency"],
                factor_ai=1 - s["suspicion"],
                factor_deletion=s["deletion_health"],
                factor_scale=s["scale"],
                factor_hero=s["hero"])

            for suspect in audit.get("suspicion", []):
                fields = dict(
                    params=suspect["params"],
                    code=suspect["code"],
                    level=suspect["level"],
                    type=suspect["type"],
                    hash=suspect["hash"],
                    index=suspect["index"],
                    date=suspect["date"],
                    days=suspect["time_since"],
                    subject=suspect.get("subject", ""),
                    author=suspect.get("author", ""),
                    body=suspect.get("body", ""),
                )
                log(_SEVERITY_TO_LOG.get(suspect["level"], "INFO"), **fields)

            for p in periods:
                log("INFO", message="period",
                    period=p["period"],
                    commits=p["commits"],
                    add=p["additions"],
                    del_=p["deletions"],
                    score=p["composite"],
                    band=p["band"])

    log("INFO", message="complete", elapsed_ms=elapsed_ms)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        log("WARN", message="analysis interrupted", error="E_INTERRUPTED")
        sys.exit(130)
    except Exception as error:
        import traceback

        log("ERROR", message=str(error), trace=traceback.format_exc(), error="E_UNEXPECTED")
        sys.exit(1)
