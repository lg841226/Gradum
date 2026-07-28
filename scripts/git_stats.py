#  Copyright (c) 2026 Gradum team, some rights reserved.
#  For licensing terms and conditions, see the MIT LICENSE file.
#
#  git_stats.py  2026-07-28 03:54:57 Changed by gwy
#
#  git_stats.py  2026-07-28 03:54:33 Changed by gwy
#
#  git_stats.py  2026-07-28 03:40:24 Changed by gwy
#
#  git_stats.py  2026-07-28 03:29:40 Changed by gwy
#
#  git_stats.py  2026-07-28 03:23:29 Changed by gwy

import csv
import functools
import itertools
import json
import math
import matplotlib
import os
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
import warnings
import zipfile
from collections import defaultdict, deque
from datetime import datetime, timezone, timedelta

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
import numpy as npy
from matplotlib.colors import ListedColormap
from rich.console import Console
from rich.markup import escape
from rich.progress import Progress, SpinnerColumn, TextColumn
from rich.spinner import SPINNERS
from typing import Dict, List, Optional, Tuple

__version__ = "1.1.0"

console = Console()

ICON_START = "\u23A1"
ICON_COMPLETE = "\u23A3"
ICON_ARROW = "│"
ICON_STEP = "\u25CF"
ICON_POINT = "\u25CB"
ICON_WARNING = "\u25B2"

SPINNERS["blink"] = {"interval": 450, "frames": ["\u25CF", " "]}


def _git(repo: str, cmd: str) -> Optional[str]:
    try:
        result = subprocess.run(cmd, cwd=repo, shell=True, capture_output=True, text=True, check=True)
        return result.stdout.strip()
    except subprocess.CalledProcessError as exc:
        print(f"{ICON_WARNING} Git command failed (exit {exc.returncode}): {cmd}",
              file=sys.stderr)
        if exc.stderr and exc.stderr.strip():
            print(f"  stderr: {exc.stderr.strip()}", file=sys.stderr)
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


def all_commits(repo_path: str, on_commit=None) -> List[Dict]:
    total = 0
    try:
        raw = _git(repo_path, 'git rev-list --count HEAD')
        if raw:
            total = int(raw.strip())
    except (ValueError, AttributeError):
        pass

    proc = subprocess.Popen(
        f'git log -c --numstat --reverse --format="%H||%cI||%s||%an||%ae||%(trailers:key=Co-Authored-By,only=yes)"',
        cwd=repo_path, shell=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        text=True, bufsize=1,
    )

    entries = []
    current_hash = current_iso = current_subject = current_author = current_email = current_trailer = None
    numstat_lines = []
    index = 0

    for line in proc.stdout:
        line = line.rstrip("\n")
        if "||" in line and len(line) > 40:
            if current_hash:
                index += 1
                commit_date = datetime.fromisoformat(current_iso.replace("Z", "+00:00"))
                additions, deletions = _parse_numstat("\n".join(numstat_lines))
                is_claude = current_trailer and "Claude <noreply@anthropic.com>" in current_trailer
                file_paths = [numstat_line.split("\t")[2] for numstat_line in numstat_lines if numstat_line.count("\t") >= 2]
                entries.append(
                    {"hash": current_hash[:8], "date": commit_date, "additions": additions, "deletions": deletions,
                     "files_changed": len(numstat_lines), "files_changed_list": file_paths, "is_claude": is_claude,
                     "author_name": current_author, "author_email": current_email,
                     "subject": current_subject})
                if on_commit:
                    on_commit(current_hash[:8], current_subject, index, total)
                numstat_lines.clear()
            current_hash, current_iso, current_subject, current_author, current_email, current_trailer = line.split(
                "||",
                5)
        elif line and "\t" in line:
            numstat_lines.append(line)

    if current_hash:
        index += 1
        commit_date = datetime.fromisoformat(current_iso.replace("Z", "+00:00"))
        additions, deletions = _parse_numstat("\n".join(numstat_lines))
        is_claude = current_trailer and "Claude <noreply@anthropic.com>" in current_trailer
        file_paths = [numstat_line.split("\t")[2] for numstat_line in numstat_lines if numstat_line.count("\t") >= 2]
        entries.append({"hash": current_hash[:8], "date": commit_date, "additions": additions, "deletions": deletions,
                        "files_changed": len(numstat_lines), "files_changed_list": file_paths, "is_claude": is_claude,
                        "author_name": current_author, "author_email": current_email,
                        "subject": current_subject})
        if on_commit:
            on_commit(current_hash[:8], current_subject, index, total)

    proc.stdout.close()
    proc.wait()
    return entries


# Per-commit additions/deletions and dates — the atomic units that feed CSV
# export, custom formulas, and every quality sub-score. All downstream
# analyses are just aggregations and transformations of these three raw
# numbers per commit.
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


def list_builtin_formulas():
    for name, expr in BUILTIN_FORMULAS.items():
        console.print(f"  [green]{name}[/green]: {expr}")


def build_formula(formula_expr: str) -> Tuple[Optional[str], Optional[str]]:
    if not formula_expr:
        return None, None
    if formula_expr in BUILTIN_FORMULAS:
        return BUILTIN_FORMULAS[formula_expr], formula_expr
    return formula_expr, "Custom Formula"


# Custom formulas let users define new CSV columns at export time (net_ratio,
# efficiency, churn, or arbitrary expressions) without modifying the code.
# SAFE_BUILTINS restricts eval to pure math — no I/O or system access.
def write_csv(config: Dict, entries: List[Dict]) -> str:
    path = config.get("outputFilePath") or f'git_stats_{datetime.now().strftime("%Y%m%d_%H%M%S")}.csv'
    expr, label = build_formula(config.get("formulaExpression"))
    window_size = config.get("movingAverageWindow", 5)

    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        headers = [
            "Index", "Commit Hash", "Date", "Time",
            "Additions", "Deletions", "Net Change",
            "Cumulative Add", "Cumulative Del", "Cumulative Net",
            "Total Lines", "Growth Rate (%)",
        ]
        if window_size > 0:
            headers.append("Moving Avg")
        if expr is not None:
            headers.append(label)
        writer.writerow(headers)

        cum_add = cum_del = cum_net = 0
        first_commit = True
        first_cum_net = 0
        window = deque(maxlen=window_size) if window_size > 0 else None
        moving_avg = 0

        for idx, entry in enumerate(entries):
            index = idx + 1
            add = entry["additions"]
            delete = entry["deletions"]
            net = add - delete

            cum_add += add
            cum_del += delete
            cum_net += net
            cum_total = cum_add + cum_del

            if first_commit:
                first_commit = False
                first_cum_net = cum_net
                growth_rate = 0
            elif first_cum_net != 0:
                growth_rate = round(((cum_net - first_cum_net) * 100) / abs(first_cum_net), 2)
            else:
                growth_rate = 0

            date = entry["date"].strftime("%Y-%m-%d")
            time_str = entry["date"].strftime("%H:%M:%S")
            row = [index, entry["hash"], date, time_str, add, delete, net,
                   cum_add, cum_del, cum_net, cum_total, growth_rate]

            if window_size > 0:
                window.append(net)
                moving_avg = round(sum(window) / len(window), 2)
                row.append(moving_avg)

            if expr is not None:
                vars_ = {
                    "add": add, "deletions": delete, "net": net,
                    "cum_add": cum_add, "cum_del": cum_del, "cum_net": cum_net,
                    "cum_total": cum_total, "count": index, "growth_rate": growth_rate,
                    "moving_avg": moving_avg,
                }
                val = evaluate_formula(expr, vars_)
                row.append(val if val is not None else "")

            writer.writerow(row)

    return path


# The CSV is the primary output format — it layers cumulative totals, growth
# rate, moving average, and optional custom formulas over the raw per-commit
# diff counts. Every column is a derived view of the same add/delete pairs.
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

    Pipeline:
      evaluate()
        ├─ gather_metrics()    → extract raw values from commit entries (no scoring)
        ├─ score_*()            → one method per dimension (independent, testable)
        ├─ aggregate_suspicion()→ combine AI sub-dimensions via noisy-OR
        ├─ compute_raw_score()  → weighted sum of factor scores
        └─ adjust_composite()   → confidence shrinkage + personality jitter → clamp

    Why separate methods instead of one monolithic function:
    Each dimension can be unit-tested, tuned, or replaced in isolation
    without touching the rest of the pipeline.
    """

    def __init__(self, quality_params: dict):
        """All toggles and thresholds come from configs.json — no hardcoded magic numbers."""
        self._params = quality_params

    @staticmethod
    def gather_metrics(entries: List[Dict], now: datetime) -> dict:
        """
        Extract all raw metrics from a commit list.

        This is the only method that touches commit data directly.
        Scoring methods consume the returned dict, not raw entries,
        so they can be tested with synthetic data.

        Parameters
        ----------
        entries : List[Dict]
            Sorted list of commit dicts, each with "date", "additions",
            "deletions", "files_changed", and optionally "is_claude".
        now : datetime
            Reference timestamp for recency calculations.

        Returns
        -------
        dict
            Keys: total_commits, first/last_commit_date, active_days,
            days_since_last_commit, total_additions/deletions/lines,
            average_additions, commits_per_day, claude_commit_count,
            all_additions (list), average_files_changed.
        """
        total_commits = len(entries)
        first_commit_date = entries[0]["date"]
        last_commit_date = entries[-1]["date"]
        # Clamp to 0.1 days to avoid division by zero on single-commit repos
        active_days = max((last_commit_date - first_commit_date).total_seconds() / 86400.0, 0.1)
        days_since_last_commit = (now - last_commit_date).total_seconds() / 86400.0

        total_additions = sum(entry["additions"] for entry in entries)
        total_deletions = sum(entry["deletions"] for entry in entries)
        total_lines = total_additions + total_deletions

        commits_with_additions = sum(1 for entry in entries if entry["additions"] > 0)
        average_additions = total_additions / max(commits_with_additions, 1)
        commits_per_day = total_commits / active_days

        claude_commit_count = sum(1 for entry in entries if entry.get("is_claude"))
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
            all_additions=all_additions,
            average_files_changed=average_files_changed,
        )

    def score_recency(self, days_since_last_commit: float) -> float:
        """
        Recent activity score. The more recent the last commit, the higher the score.

        Uses Gaussian rather than linear decay because project activity
        doesn't die on a fixed date — it decays continuously.
        At half-life = 90 days: 90-day-old project scores ~0.61,
        180-day-old scores ~0.14.

        Parameters
        ----------
        days_since_last_commit : float
            Days elapsed since the most recent commit.

        Returns
        -------
        float
            0 (stale) to 1 (just committed).
        """
        return _gaussian(days_since_last_commit, 0.0, self._params["recencyHalfLifeDays"])

    def score_ai_volume(self, average_additions: float) -> float:
        """
        AI suspicion — average additions per commit.

        GPT-class AI often generates hundreds to thousands of lines per commit,
        while human commits typically range from tens to a few hundred.
        Uses sigmoid instead of a hard threshold to avoid edge discontinuities.

        Config params used: ai_additions_threshold (sigmoid midpoint),
        ai_additions_scale (sigmoid steepness).
        """
        deviation = (average_additions - self._params["aiAdditionsThreshold"])
        normalized = deviation / self._params["aiAdditionsScale"]
        return _sigmoid(normalized)

    def score_ai_initiative(self, entries: List[Dict]) -> float:
        """
        AI suspicion — initial project bootstrap signature.

        AI tends to generate large amounts of boilerplate in early commits:
        - Very high additions (creating the full file structure)
        - Very low deletions (generative, not modifying)
        Captured via the add/delete ratio of the first N commits.
        Higher ratio → more likely AI-initiated.

        Config params: ai_initiative_commits (N), ai_initiative_threshold,
        ai_initiative_scale.
        """
        early_count = min(self._params["aiInitiativeCommits"], len(entries))
        early_additions = sum(entry["additions"] for entry in entries[:early_count])
        early_deletions = sum(entry["deletions"] for entry in entries[:early_count])
        addition_to_deletion_ratio = early_additions / max(early_deletions, 1)
        deviation = addition_to_deletion_ratio - self._params["aiInitiativeThreshold"]
        normalized = deviation / self._params["aiInitiativeScale"]
        return _sigmoid(normalized)

    def score_ai_repetition(self, all_additions: List[int]) -> float:
        """
        AI suspicion — commit size repetition.

        Human commit sizes vary widely (2-line bugfix vs 500-line feature).
        AI-generated commits tend to be uniformly sized.
        Uses coefficient of variation CV = stddev / mean to quantify uniformity.
        Low CV → highly uniform → suspicious.
        Inverse sigmoid: low CV maps to high suspicion.

        Config params: ai_repetition_cv_threshold, ai_repetition_scale.
        """
        if len(all_additions) < 2:
            return 0.5  # Not enough data — neutral score
        coefficient_of_variation = statistics.stdev(all_additions) / max(
            statistics.mean(all_additions), 1
        )
        deviation = coefficient_of_variation - self._params["aiRepetitionCvThreshold"]
        normalized = deviation / self._params["aiRepetitionScale"]
        return 1.0 - _sigmoid(normalized)

    def score_ai_focus(self, average_files_changed: float) -> float:
        """
        AI suspicion — file focus per commit.

        Humans typically touch multiple related files per commit
        (interface + implementation + tests). AI tends to modify
        1-2 files at a time. Target is 3 files; deviation in either
        direction raises suspicion.

        Config params: ai_focus_target, ai_focus_scale.
        """
        deviation = self._params["aiFocusTarget"] - average_files_changed
        normalized = deviation / self._params["aiFocusScale"]
        return _sigmoid(normalized)

    def score_firework(self, commits_per_day: float, active_days: float) -> float:
        """
        AI suspicion — firework pattern: dense commits in a short span.

        AI can produce 10+ commits within minutes, while humans have
        natural rhythms (work hours, weekdays). Uses the product of
        two sigmoids so BOTH conditions must hold:

          1. Daily commit density exceeds threshold
          2. Active window is short (so new-project bursts aren't penalized)

        Product (not average): if either factor is 0, score is 0.
        Only "high density + short span" together trigger the pattern.

        Config params: firework_density_threshold, firework_density_slope,
        firework_active_days, firework_duration_slope.
        """
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

    def score_ai_claude(self, claude_commit_count: int, total_commits: int) -> float:
        """
        AI suspicion — Claude Co-Authored-By commit ratio.

        Uses ratio instead of binary check: one Claude commit doesn't
        make a project AI-driven. Ratio better reflects real AI dependency.

        Multiplied by ai_claude_factor to amplify sensitivity:
        factor=2.0 means 50% Claude commits saturates at 1.0,
        light usage (<10%) has negligible impact.

        Config params: ai_claude_factor.
        """
        claude_ratio = claude_commit_count / max(total_commits, 1)
        return min(claude_ratio * self._params["aiClaudeFactor"], 1.0)

    def score_deletion_health(self, total_additions: int, total_deletions: int) -> float:
        """
        Deletion health — how close the delete/add ratio is to ideal.

        Uses Gaussian instead of linear: neither "delete less = better"
        nor "delete more = better". Ideal ratio ~35% scores highest.
        Deviation in either direction lowers the score.

        Config params: deletion_ideal_ratio, deletion_sigma,
        deletion_default_score (fallback when additions = 0).
        """
        if total_additions == 0:
            return self._params.get("deletionDefaultScore", 0.5)
        deletion_ratio = total_deletions / total_additions
        return _gaussian(
            deletion_ratio,
            self._params["deletionIdealRatio"],
            self._params["deletionSigma"],
        )

    def score_scale(self, total_lines: int) -> float:
        """
        Project scale maturity — maps line count to score via a smooth log curve.

        Formula:
          score = min + (max - min) × log(1 + lines / ref) / log(1 + max_ref / ref)

        Why log returns diminish: going from 100 to 1,000 lines matters
        far more than going from 100,000 to 101,000 lines.

        Why not a step function: steps produce discontinuities.
        A 9,999-line project and a 10,001-line project should not
        differ by 0.3 just because of a threshold boundary.

        Config params: scale_ref_lines, scale_max_lines,
        scale_min_score, scale_max_score.
        """
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
        """
        Contribution concentration risk — share of commits by top N contributors.

        Doesn't look at absolute team size: a 10-person project where
        one person writes 90% is high-risk; a 3-person project at
        33% each is healthy. Higher return value = more distributed = healthier.

        Author names matching bot_patterns (from configs.json) are excluded
        before computing the concentration ratio.

        Returns: 1.0 - (top N commit total / all commit total).
        So 1.0 = perfectly distributed, 0.0 = fully concentrated.
        """
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
        """
        Tiny-project penalty — scores are unreliable with very few commits.

        Uses sigmoid for smooth transition:
        5 commits → ~0.22 penalty, 10 → ~0.12, 20 → ~0.01.
        No hard cliff at the threshold.

        Config params: tiny_commit_threshold, tiny_penalty_max, tiny_penalty_slope.
        """
        shortfall = self._params["tinyCommitThreshold"] - total_commits
        activation = _sigmoid(shortfall / self._params["tinyPenaltySlope"])
        return activation * self._params["tinyPenaltyMax"]

    @staticmethod
    def compute_confidence(total_commits: int) -> float:
        """
        Confidence — more commits = more trust in the score.

        confidence = 1 - 1 / (√n + 1)
        1 commit  → 0.50
        9 commits → 0.75
        99        → 0.91

        Square root makes confidence growth diminish:
        the first few commits add the most information.
        Never reaches 1.0 — always preserves some uncertainty.
        """
        return 1.0 - 1.0 / (math.sqrt(total_commits) + 1.0)

    def compute_personality(self, total_lines: int, confidence: float) -> float:
        """
        Scale-based jitter — small projects get a slight downward nudge,
        large projects a slight upward nudge.

        Uses project size (log) instead of a random hash for the offset:
        larger projects statistically have more reliable scores,
        which is a natural inductive bias.

        Formula:
          size_factor   = log10(max(lines, 1) + 1) / log10(max_lines + 1)
          personality   = (size_factor - 0.5) × amplitude × confidence

        A million-line project (size_factor ≈ 1.0) gets +0.5×4%×confidence ≈ +2%.
        A hundred-line project (size_factor ≈ 0.0) gets -0.5×4%×confidence ≈ -2%.

        Config params: personality_max_lines, personality_amplitude.
        """
        max_lines = self._params.get("personalityMaxLines", 1000000)
        amplitude = self._params.get("personalityAmplitude", 0.04)
        size_factor = math.log10(max(total_lines, 1) + 1) / math.log10(max_lines + 1)
        return (size_factor - 0.5) * amplitude * confidence

    @staticmethod
    def aggregate_suspicion(ai_sub_scores: List[float]) -> float:
        """Arithmetic mean of the 6 AI suspicion sub-dimensions."""
        if not ai_sub_scores:
            return 0.0
        return sum(ai_sub_scores) / len(ai_sub_scores)

    def compute_raw_score(self, factor_scores: dict) -> float:
        """
        Weighted sum of factor scores:
          raw = Σ(weight_i × score_i)

        Weights come from configs.json under keys matching
        weight_{factor_name}. The weight dict must sum to 1.0
        for the composite to stay in the 0-1 range.
        """
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
        """
        Shrink the raw score toward baseline by confidence, add personality jitter, then clamp.

        adjusted   = baseline + (raw - baseline) × confidence
        composite  = clamp(adjusted + personality)

        Why shrink toward baseline: with few commits the score is unreliable.
        Shrinking prevents extreme values from misleading interpretations.
        Baseline 0.5 represents "neutral judgment under complete uncertainty."

        Parameters
        ----------
        raw_score : float
            Weighted sum before adjustment (may include penalty subtraction).
        confidence : float
            0 (no confidence) to ~1 (high confidence).
        baseline : float
            Anchor value for shrinkage (0.5 by default).
        personality : float
            Scale-based jitter term (typically -0.02 to +0.02).
        """
        adjusted = baseline + (raw_score - baseline) * confidence
        composite = adjusted + personality
        return max(0.0, min(1.0, composite))

    def evaluate(self, entries: List[Dict], project_name: str, now: datetime,
                 repo: str = None) -> dict:
        """
        Run a complete quality evaluation on a project.

        Returns raw metrics, per-dimension scores, composite score,
        quality band, and metadata. The result dict matches the schema
        expected by write_report() and the CLI output.

        Parameters
        ----------
        entries : List[Dict]
            Sorted commit list with all required fields.
        project_name : str
            Repository name for display and band computation.
        now : datetime
            Reference timestamp for recency.
        repo : str, optional
            Repository path for AI artifact detection.

        Returns
        -------
        dict
            Keys: name, total_commits, active_days, days_since_last,
            first/last_commit, total_additions/deletions/lines,
            net_change, avg_additions_per_commit, commits_per_day,
            churn_ratio, scores (dict with all sub-scores + composite),
            band, band_color, avg_files_changed, claude_commits.
            Returns {} if entries is empty.
            Returns archived skeleton if zombie_days exceeded.
        """
        if not entries:
            return {}

        metrics = self.gather_metrics(entries, now)

        # Early exit for zombie projects — skip all scoring
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

        # Score every AI sub-dimension
        ai_volume_score = self.score_ai_volume(metrics["average_additions"])
        ai_initiative_score = self.score_ai_initiative(entries)
        ai_repetition_score = self.score_ai_repetition(metrics["all_additions"])
        ai_focus_score = self.score_ai_focus(metrics["average_files_changed"])
        firework_score = self.score_firework(metrics["commits_per_day"], metrics["active_days"])
        agent_artifact_score = self.score_agent_artifacts(repo)
        ai_claude_score = self.score_ai_claude(
            metrics["claude_commit_count"], metrics["total_commits"]
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

        # Compose the 5 top-level factors
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
            claude_commits=metrics["claude_commit_count"],
        )

    def evaluate_period_batch(
            self, entries: List[Dict], unit: str, now: datetime,
            overall_hero_score: float = None, repo: str = None,
    ) -> List[dict]:
        """
        Evaluate quality per time period (month/week/quarter/year).

        Uses the same scoring pipeline as evaluate() so period scores
        are directly comparable to the overall score — unlike the old
        model which used a simplified formula for periods.

        Hero score is passed from the overall evaluation (not recomputed
        per period) because contribution concentration is a project-level
        attribute, not a period-level one.

        Parameters
        ----------
        entries : List[Dict]
            Sorted commit list (same as evaluate).
        unit : str
            Bucket unit: "month", "week", "quarter", or "year".
        now : datetime
            Reference timestamp for recency.
        overall_hero_score : float, optional
            Hero score from the overall evaluation. If omitted, defaults
            to 1.0 (perfectly distributed — neutral assumption).
        repo : str, optional
            Repository path for AI artifact detection.

        Returns
        -------
        List[dict]
            Each dict has period, commits, additions, deletions, recency,
            ai_volume, deletion, composite, band.
            Returns empty list if entries is empty.
        """
        if not entries:
            return []

        # Bucket entries by time period
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

        # Walking index avoids O(n) list.index() per period;
        # cumulative_lines accumulates incrementally to avoid O(n) rescanning.
        entry_index = 0
        cumulative_lines = 0

        for period_name in sorted(buckets):
            group = buckets[period_name]
            metrics = self.gather_metrics(group, now)

            ai_volume_score = self.score_ai_volume(metrics["average_additions"])
            firework_score = self.score_firework(metrics["commits_per_day"], metrics["active_days"])

            # Full suspicion (same as evaluate)
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

            # Scale uses cumulative lines to this period's end,
            # reflecting "what the project looked like at that time"
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
    """Delegate to QualityModel.evaluate()."""
    model = QualityModel(params)
    return model.evaluate(entries, name, now, repo=repo)


def compute_quality_periods(entries: List[Dict], unit: str, now: datetime,
                            params: Dict, overall_hero_score: float = None,
                            repo: str = None) -> List[Dict]:
    """Delegate to QualityModel.evaluate_period_batch()."""
    model = QualityModel(params)
    return model.evaluate_period_batch(entries, unit, now, overall_hero_score, repo=repo)


# The 4-factor quality model scores repo health 0-1 across four independent
# dimensions and composites them into a single quality band. Understanding
# individual factor scores is more important than the composite — each one
# reveals a different failure mode: abandonment (recency), bot-like commits
# (anti-AI), copy-paste without cleanup (deletion health), or trivial scale
# (maturity). The batch variant shows how these factors trend over time.
def _load_config() -> dict:
    config_path = os.path.join(os.path.dirname(__file__), "configs.jsonc")
    try:
        with open(config_path) as f:
            text = f.read()
        text = re.sub(r'//.*', '', text)
        text = re.sub(r'/\*.*?\*/', '', text, flags=re.DOTALL)
        return json.loads(text)
    except (FileNotFoundError, json.JSONDecodeError):
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

_AUDIT_CODES = {
    # Critical (S1000-S1999)
    "S1001": ("High-Risk Refactoring", "critical",
              "single commit +{add}/-{dels} lines, threshold is {threshold}"),
    "S1002": ("Net Reduction", "critical",
              "codebase net loss {ratio:.0f}% ({dels} deletions vs {add} additions)"),
    "S1003": ("Single-Author Project", "critical",
              "only \"{author}\" across {total} commits, bus factor is 1"),
    "S1004": ("Mass Rewrite", "critical",
              "single commit rewrote {lines} lines, {pct:.0f}% of total codebase"),
    "S1005": ("Zero Activity", "critical",
              "repository has no commits"),
    # Alert (S2000-S2999)
    "S2001": ("Deletion Cluster", "alert",
              "{count} consecutive deletion-heavy commits, {lines} lines removed at positions {start_idx} to {end_idx}"),
    "S2002": ("Mature Churn", "alert",
              "{total} total commits, {count} heavy deletions within {days} days"),
    "S2003": ("Core Net Deletion", "alert",
              "commit removed {lines} lines from core source files"),
    "S2004": ("Accumulation-Only", "alert",
              "deletion ratio {ratio:.1f}% over {total} commits, almost no cleanup"),
    "S2005": ("AI Volume Spike", "alert",
              "avg {avg:.0f} lines per commit exceeds AI threshold {threshold}"),
    "S2006": ("AI Bootstrap", "alert",
              "first {n} commits have add/delete ratio of {ratio:.1f}x, matches AI boilerplate pattern"),
    "S2007": ("AI Uniformity", "alert",
              "CV {cv:.3f} is below threshold {threshold:.2f}, commit sizes are suspiciously uniform"),
    "S2008": ("AI Focus Deviation", "alert",
              "avg {avg:.1f} files per commit deviates from target {target}, AI tends to touch fewer files"),
    "S2009": ("Firework Burst", "alert",
              "{density:.1f} commits per day over {days:.0f} days, resembles AI rapid-fire pattern"),
    "S2010": ("Claude Flood", "alert",
              "{pct:.0f}% of commits ({count} out of {total}) are Claude Co-Authored-By"),
    "S2011": ("Hero Dependency", "alert",
              "top {n} contributors own {pct:.0f}% of {total} commits, bus factor risk"),
    "S2012": ("Abandoned", "alert",
              "last commit was {days:.0f} days ago, half-life is {half} days, project may be inactive"),
    # Watch (S3000-S3999)
    "S3001": ("Non-Core Deletion", "watch",
              "deleted {lines} lines, {core} out of {files} changed files are source code"),
    "S3002": ("Heavy Churn", "watch",
              "{dels} deletions vs {add} additions, deletion ratio is {ratio:.0f}%"),
    "S3003": ("Bot-like Author", "watch",
              "\"{author}\" matches bot pattern \"{pattern}\""),
    "S3004": ("Weekend Warrior", "watch",
              "{pct:.0f}% of commits ({count} out of {total}) are on weekends, possible personal project"),
    "S3005": ("Day Burst", "watch",
              "{count} commits on {date}, unusually high single day activity"),
    "S3006": ("No Merges", "watch",
              "0 merge commits in {total} total commits, linear history only"),
    "S3007": ("Tiny Commits", "watch",
              "{count} out of {total} commits ({pct:.0f}%) are under {limit} lines, possible WIP or generated"),
    "S3008": ("Vague Messages", "watch",
              "{count} out of {total} commits ({pct:.0f}%) have generic subjects of 5 characters or fewer"),
    # Normal (S4000-S4999)
    "S4001": ("Low Cleanup", "normal",
              "deletion ratio is {ratio:.1f}%, below ideal range"),
    "S4002": ("Small Project", "normal",
              "{lines} lines across {files} files, early stage or tiny utility"),
    "S4003": ("Compact History", "normal",
              "{days} days of active development"),
    # Clean (S5000-S5999)
    "S5001": ("Balanced Churn", "clean",
              "deletion ratio is {ratio:.1f}%, healthy cleanup rate"),
    "S5002": ("Multiple Contributors", "clean",
              "{count} distinct authors: {names}"),
    "S2013": ("AI Agent Artifacts", "alert",
              "tracked files {files}, {count} tool{plural} detected"),
    "S5003": ("Gradual Growth", "clean",
              "{lines} lines added over {days} days, steady organic growth"),
}

# Pre-built lookup: code to (type, level)
_AUDIT_CODE_META = {key: (value[0], value[1]) for key, value in _AUDIT_CODES.items()}

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
    """
    Count AI participation per author = Claude commits + commits touching AI config files.

    Claude commits are from the is_claude flag on entries.
    AI config file commits come from git log --format="%H" per artifact file.
    A single commit that is both Claude AND touches an AI file counts as 2.
    """
    result: Dict[str, int] = defaultdict(int)

    for e in entries:
        if e.get("is_claude"):
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


def _audit_msg(code: str, **kwargs) -> str:
    """Format the audit message for a given code with keyword args."""
    template = _AUDIT_CODES[code][2]
    return template.format(**kwargs)


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
                "note": _audit_msg("S3001",
                                   lines=-commit["net"], files=total_files, core=core_count),
            })
        elif both_large:
            suspicion.append({
                "code": "S1001", "hash": commit["hash"], "index": commit["index"],
                "date": str(commit["date"].date()),
                "time_since": int((now - commit["date"]).total_seconds() / 86400),
                "type": "High-Risk Refactoring", "level": "critical",
                "note": _audit_msg("S1001",
                                   add=commit["additions"], dels=commit["deletions"],
                                   threshold=thresholds["both_large"]),
            })
        else:
            suspicion.append({
                "code": "S2003", "hash": commit["hash"], "index": commit["index"],
                "date": str(commit["date"].date()),
                "time_since": int((now - commit["date"]).total_seconds() / 86400),
                "type": "Core Net Deletion", "level": "alert",
                "note": _audit_msg("S2003", lines=-commit["net"]),
            })

    for index, cluster in enumerate(clusters):
        cluster_commits = [entry for entry in enriched if entry["index"] in cluster]
        total_del = sum(-entry["net"] for entry in cluster_commits if entry["net"] < 0)
        suspicion.append({
            "code": "S2001", "hash": f"Cluster #{index + 1}", "index": cluster[0],
            "date": str(cluster_commits[0]["date"].date()) if cluster_commits else "",
            "time_since": "-",
            "type": "Deletion Cluster", "level": "alert",
            "note": _audit_msg("S2001",
                               count=len(cluster), lines=total_del,
                               start_idx=cluster[0], end_idx=cluster[-1]),
        })

    if total >= thresholds["mature_commits"]:
        recent_heavy = [commit for commit in heavy if commit["is_recent"]]
        if len(recent_heavy) >= thresholds["cluster_min"]:
            sum(-entry["net"] for entry in recent_heavy)
            suspicion.append({
                "code": "S2002", "hash": "Recent Spike", "index": recent_heavy[-1]["index"],
                "date": str(recent_heavy[-1]["date"].date()), "time_since": "-",
                "type": "Mature Project Churn", "level": "alert",
                "note": _audit_msg("S2002", total=total, count=len(recent_heavy),
                                   days=thresholds["recent_days"]),
            })

    total_additions = sum(entry["additions"] for entry in entries)
    total_deletions = sum(entry["deletions"] for entry in entries)
    total_lines_all = total_additions + total_deletions

    # S1002 / S2004 / S3002 / S4001 / S5001: churn ratios
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
            "note": _audit_msg("S2004", ratio=0.0, total=total),
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
                "note": _audit_msg("S2004", ratio=ratio * 100, total=total),
            })
        elif ratio < 0.25:
            ratio_label = "Low Cleanup"
            ratio_level = "normal"
            suspicion.append({
                "code": "S4001", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "Low Cleanup", "level": "normal",
                "note": _audit_msg("S4001", ratio=ratio * 100),
            })
        elif ratio < 0.50:
            ratio_label = "Balanced Churn"
            ratio_level = "clean"
            suspicion.append({
                "code": "S5001", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "Balanced Churn", "level": "clean",
                "note": _audit_msg("S5001", ratio=ratio * 100),
            })
        elif ratio < 1.0:
            ratio_label = "Heavy Churn"
            ratio_level = "watch"
            suspicion.append({
                "code": "S3002", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "Heavy Churn", "level": "watch",
                "note": _audit_msg("S3002", ratio=ratio * 100,
                                   dels=total_deletions, add=total_additions),
            })
        else:
            ratio_label = "Net Reduction"
            ratio_level = "critical"
            suspicion.append({
                "code": "S1002", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "Net Reduction", "level": "critical",
                "note": _audit_msg("S1002", dels=total_deletions, add=total_additions,
                                   ratio=total_deletions / max(total_additions, 1) * 100),
            })

    # S1003: Single-Author Project
    authors = set(e["author"] for e in enriched)
    non_bot_authors = [a for a in authors if not _is_bot_author(a)]
    if len(non_bot_authors) <= 1 and total >= 10:
        author_name = non_bot_authors[0] if non_bot_authors else "unknown"
        suspicion.append({
            "code": "S1003", "hash": "-", "index": -1,
            "date": str(now.date()), "time_since": "-",
            "type": "Single-Author Project", "level": "critical",
            "note": _audit_msg("S1003", author=author_name, total=total),
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
            "note": _audit_msg("S1004", lines=single_total, pct=pct_of_total),
        })

    if scores:
        # S2005: AI Volume
        if scores.get("ai_volume", 0) > 0.5:
            avg_add = total_additions / max(total, 1)
            suspicion.append({
                "code": "S2005", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "AI Volume Spike", "level": "alert",
                "note": _audit_msg("S2005", avg=avg_add,
                                   threshold=audit_params.get("aiAdditionsPerCommitThreshold", 500)),
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
                "note": _audit_msg("S2006", n=n, ratio=early_ratio),
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
                "note": _audit_msg("S2007", cv=cv,
                                   threshold=audit_params.get("aiRepetitionCvThreshold", 0.5)),
            })
        # S2008: AI Focus
        if scores.get("ai_focus", 0) > 0.5:
            avg_files = sum(e["files_changed"] for e in enriched) / max(total, 1)
            suspicion.append({
                "code": "S2008", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "AI Focus Deviation", "level": "alert",
                "note": _audit_msg("S2008", avg=avg_files, n=total,
                                   target=audit_params.get("aiFocusTargetFiles", 3.0)),
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
                "note": _audit_msg("S2009", density=density, days=days_active),
            })
        # S2010: Claude Flood
        if scores.get("ai_claude", 0) > 0.25:
            claude_count = sum(1 for e in enriched if e.get("is_claude", False))
            suspicion.append({
                "code": "S2010", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "Claude Flood", "level": "alert",
                "note": _audit_msg("S2010", pct=claude_count / max(total, 1) * 100,
                                   count=claude_count, total=total),
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
                "note": _audit_msg("S2011", n=hero_n, pct=hero_pct, total=total),
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
                "note": _audit_msg("S2012", days=days_since, half=half_life),
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
                "note": _audit_msg("S3003", author=e["author"],
                                   pattern=matched_pattern),
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
            "note": _audit_msg("S3004", pct=weekend_pct, count=weekend_count, total=total),
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
            "note": _audit_msg("S3005", date=str(max_day), count=max_day_count),
        })

    # S3006: No Merges
    merge_count = sum(1 for e in enriched if "merge" in e["subject"].lower())
    if merge_count == 0 and total >= 20:
        suspicion.append({
            "code": "S3006", "hash": "-", "index": -1,
            "date": str(now.date()), "time_since": "-",
            "type": "No Merges", "level": "watch",
            "note": _audit_msg("S3006", total=total),
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
            "note": _audit_msg("S3007", count=tiny_count, total=total, pct=tiny_pct, limit=tiny_threshold),
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
            "note": _audit_msg("S3008", count=vague_count, total=total, pct=vague_pct),
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
            "note": _audit_msg("S4002", lines=total_lines_all, files=len(all_files)),
        })

    # S4003: Compact History
    if enriched:
        active_span = (enriched[-1]["date"] - enriched[0]["date"]).total_seconds() / 86400
        if active_span < 30 and total >= 5:
            suspicion.append({
                "code": "S4003", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "Compact History", "level": "normal",
                "note": _audit_msg("S4003", days=round(active_span)),
            })

    # S2013: AI Agent Artifacts
    agent_traces = _detect_agent_artifacts(repo)
    if agent_traces:
        file_list = []
        for agent_name, files in sorted(agent_traces.items()):
            file_list.extend(files[:2])
        display = ", ".join(file_list[:5])
        suspicion.append({
            "code": "S2013", "hash": "-", "index": -1,
            "date": str(now.date()), "time_since": "-",
            "type": "AI Agent Artifacts", "level": "alert",
            "note": _audit_msg("S2013", files=display,
                               count=len(agent_traces),
                               plural="s" if len(agent_traces) > 1 else ""),
        })

    # S5002: Multiple Contributors
    if len(non_bot_authors) > 3:
        author_list = ", ".join(non_bot_authors[:5])
        if len(non_bot_authors) > 5:
            author_list += f" and {len(non_bot_authors) - 5} more"
        suspicion.append({
            "code": "S5002", "hash": "-", "index": -1,
            "date": str(now.date()), "time_since": "-",
            "type": "Multiple Contributors", "level": "clean",
            "note": _audit_msg("S5002", count=len(non_bot_authors), names=author_list),
        })

    # S5003: Gradual Growth
    if enriched:
        active_span = (enriched[-1]["date"] - enriched[0]["date"]).total_seconds() / 86400
        if active_span > 90 and total_lines_all > 5000 and total_deletions / max(total_additions, 1) < 0.5:
            suspicion.append({
                "code": "S5003", "hash": "-", "index": -1,
                "date": str(now.date()), "time_since": "-",
                "type": "Gradual Growth", "level": "clean",
                "note": _audit_msg("S5003", lines=total_lines_all, days=round(active_span)),
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


def _file_breakdown(root: str) -> Optional[Dict]:
    raw = _git(root, "git ls-files")
    if not raw:
        return None
    files = raw.strip().split("\n")
    exts: Dict[str, Dict] = {}
    for fp in files:
        if not fp.strip():
            continue
        ext = os.path.splitext(fp)[1].lower()
        full = os.path.join(root, fp)
        try:
            with open(full, "rb") as fh:
                chunk = fh.read(8192)
            if b"\x00" in chunk:
                continue
            with open(full, "r", encoding="utf-8", errors="replace") as fh:
                total = non_blank = 0
                for line in fh:
                    total += 1
                    if line.strip():
                        non_blank += 1
        except (IOError, OSError):
            continue
        entry = exts.setdefault(ext, {"files": 0, "total": 0, "non_blank": 0})
        entry["files"] += 1
        entry["total"] += total
        entry["non_blank"] += non_blank

    if not exts:
        return None

    sorted_exts = sorted(exts.items(), key=lambda item: -item[1]["total"])
    total_files = sum(e["files"] for _, e in sorted_exts)
    total_lines = sum(e["total"] for _, e in sorted_exts)
    total_non_blank = sum(e["non_blank"] for _, e in sorted_exts)
    code_lines = sum(e["non_blank"] for ext, e in sorted_exts if ext in _CORE_EXTS)

    return {
        "exts": sorted_exts,
        "total_files": total_files,
        "total_lines": total_lines,
        "total_non_blank": total_non_blank,
        "code_lines": code_lines,
    }


def _growth_trend_chart(entries: List[Dict], path: str, scale: float = 1.0, color: str = _PRIMARY_COLOR):
    daily = defaultdict(lambda: {"net": 0})
    for e in entries:
        day = e["date"].date()
        daily[day]["net"] += e["additions"] - e["deletions"]

    sorted_days = sorted(daily)
    cum = 0
    days_list, cum_list = [], []
    for day in sorted_days:
        cum += daily[day]["net"]
        days_list.append(day)
        cum_list.append(cum * scale)

    if len(days_list) < 2:
        fig, ax = plt.subplots(figsize=(10, 4))
        ax.text(0.5, 0.5, "Not enough data", ha="center", va="center", transform=ax.transAxes)
        fig.savefig(path, dpi=150, bbox_inches="tight")
        plt.close(fig)
        return

    day_series = mdates.date2num(days_list)
    values_array = npy.array(cum_list, dtype=float)
    data_length = len(day_series)
    window_size = max(3, data_length // 20)
    if window_size % 2 == 0:
        window_size += 1

    pad = window_size // 2
    y_pad = npy.pad(values_array, pad, mode="edge")
    kernel = npy.ones(window_size) / window_size
    smooth = npy.convolve(y_pad, kernel, mode="same")[pad:-pad or None]

    fig, ax = plt.subplots(figsize=(10, 4))
    ax.fill_between(days_list, 0, smooth, alpha=0.08, color=color)
    ax.plot(days_list, smooth, color=color, linewidth=1.8)
    ax.set_xlabel("Date")
    ax.set_ylabel("Effective Code Lines")
    ax.set_title("Code Growth Trend")
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%m-%d"))
    fig.tight_layout()
    fig.savefig(path, dpi=150, bbox_inches="tight")
    plt.close(fig)


def _commit_barchart(entries: List[Dict], path: str, color: str = _PRIMARY_COLOR):
    daily = defaultdict(int)
    for e in entries:
        daily[e["date"].date()] += 1

    if not daily:
        fig, ax = plt.subplots(figsize=(10, 4))
        ax.text(0.5, 0.5, "Not enough data", ha="center", va="center", transform=ax.transAxes)
        fig.savefig(path, dpi=150, bbox_inches="tight")
        plt.close(fig)
        return

    sorted_days = sorted(daily)
    values = [daily[day] for day in sorted_days]
    day_numbers = mdates.date2num(sorted_days)

    fig, ax = plt.subplots(figsize=(10, 4))
    ax.bar(day_numbers, values, width=0.4, color=color, alpha=0.8, linewidth=0)
    ax.set_xlabel("Date")
    ax.set_ylabel("Commits")
    ax.set_title("Daily Commit Activity")
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%m-%d"))
    fig.tight_layout()
    fig.savefig(path, dpi=150, bbox_inches="tight")
    plt.close(fig)


def write_contributor_csv(entries: List[Dict], path: str,
                          ai_participation: Dict[str, int] = None) -> str:
    c = defaultdict(lambda: {"commits": 0, "ai_commits": 0, "email": "", "first": None, "last": None, "days": set()})
    for e in entries:
        name = e.get("author_name", "Unknown")
        email = e.get("author_email", "")
        day = e["date"].date()
        c[name]["commits"] += 1
        c[name]["email"] = email
        c[name]["days"].add(day)
        if c[name]["first"] is None or e["date"] < c[name]["first"]:
            c[name]["first"] = e["date"]
        if c[name]["last"] is None or e["date"] > c[name]["last"]:
            c[name]["last"] = e["date"]

    if ai_participation:
        for name, count in ai_participation.items():
            if name in c:
                c[name]["ai_commits"] = count
    else:
        for e in entries:
            if e.get("is_claude"):
                c[e.get("author_name", "Unknown")]["ai_commits"] += 1

    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(
            ["Index", "Author", "Email", "Commits", "AI Participation", "Active Days", "First Commit", "Last Commit"])
        for index, (name, info) in enumerate(sorted(c.items(), key=lambda x: -x[1]["commits"]), 1):
            w.writerow([index, name, info["email"], info["commits"], info["ai_commits"], len(info["days"]),
                        info["first"].strftime("%Y-%m-%d") if info["first"] else "",
                        info["last"].strftime("%Y-%m-%d") if info["last"] else ""])
    return path


def write_suspicion_csv(audit: Dict, path: str) -> str:
    rows = audit.get("suspicion", [])
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["Code", "Severity", "Type", "Hash", "Date", "Days Since", "Message"])
        for r in rows:
            w.writerow([
                r.get("code", ""),
                r.get("level", ""),
                r.get("type", ""),
                r.get("hash", ""),
                r.get("date", ""),
                r.get("time_since", "-"),
                r.get("note", ""),
            ])
    return path


def _contributor_pareto(entries: List[Dict], path: str, color: str = _PRIMARY_COLOR, max_bars: int = 50,
                        line_color: str = _PARETO_LINE_COLOR):
    authors = defaultdict(int)
    for e in entries:
        name = e.get("author_name", "Unknown")
        if _is_bot_author(name, _BOT_PATTERNS):
            continue
        authors[name] += 1

    if not authors:
        fig, ax = plt.subplots(figsize=(10, 4))
        ax.text(0.5, 0.5, "Not enough data", ha="center", va="center", transform=ax.transAxes)
        fig.savefig(path, dpi=150, bbox_inches="tight")
        plt.close(fig)
        return

    sorted_authors = sorted(authors.items(), key=lambda item: -item[1])
    sorted_authors = sorted_authors[:max_bars]
    counts = [a[1] for a in sorted_authors]
    total = sum(counts)
    cumulative = [sum(counts[:end + 1]) / total * 100 for end in range(len(counts))]
    indices = list(range(1, len(counts) + 1))

    fig, ax1 = plt.subplots(figsize=(10, 4))
    ax1.bar(indices, counts, color=color, alpha=0.85, width=0.6)
    ax1.set_ylabel("Commits")
    ax1.set_title("Contributor Pareto")
    ax1.set_xticks(indices)
    ax1.set_xticklabels(indices, fontsize=7)

    ax2 = ax1.twinx()
    ax2.plot(indices, cumulative, color=line_color, linewidth=1.5)
    ax2.set_ylabel("Cumulative %")

    fig.tight_layout()
    fig.savefig(path, dpi=150, bbox_inches="tight")
    plt.close(fig)


def _quality_radar_chart(scores: Dict, path: str, color: str = _PRIMARY_COLOR):
    axes = ["Recency", "AI Safety", "Deletion\nHealth", "Scale\nMaturity", "Dev\nDistribution"]
    values = [
        scores.get("recency", 0),
        scores.get("anti_ai", 0) or (1 - scores.get("suspicion", 0)),
        scores.get("deletion_health", 0),
        scores.get("scale", 0),
        scores.get("hero", 0),
    ]
    n = len(axes)
    angles = npy.linspace(0, 2 * npy.pi, n, endpoint=False).tolist()
    angles += angles[:1]
    values += values[:1]

    fig, ax = plt.subplots(figsize=(5, 5), subplot_kw=dict(polar=True))
    ax.set_theta_offset(npy.pi / 2)
    ax.set_theta_direction(-1)
    ax.set_rlabel_position(30)

    for tick in [0.25, 0.5, 0.75, 1.0]:
        ax.plot(angles, [tick] * len(angles), color="gray", linewidth=0.5, alpha=0.3)
    ax.fill(angles, values, alpha=0.08, color=color)
    ax.plot(angles, values, color=color, linewidth=1.8)
    ax.scatter(angles[:-1], values[:-1], color=color, s=30, zorder=5)

    ax.set_xticks(angles[:-1])
    ax.set_xticklabels(axes, fontsize=9)
    ax.set_ylim(0, 1)
    ax.set_yticks([0.25, 0.5, 0.75])
    ax.set_yticklabels(["0.25", "0.50", "0.75"], fontsize=7, color="gray")
    ax.set_title("Quality Factor Radar", fontsize=11, pad=20)
    fig.tight_layout()
    fig.savefig(path, dpi=150, bbox_inches="tight")
    plt.close(fig)


def _hero_risk(entries: List[Dict], top_n: int = 5, bot_patterns: List[str] = None) -> Tuple[float, int, int]:
    if bot_patterns is None:
        bot_patterns = _BOT_PATTERNS
    author_commit_counts = defaultdict(int)
    for entry in entries:
        author = entry.get("author_name", "Unknown")
        if _is_bot_author(author, bot_patterns):
            continue
        author_commit_counts[author] += 1
    sorted_counts = sorted(author_commit_counts.values(), reverse=True)
    top_total = sum(sorted_counts[:top_n])
    grand_total = sum(sorted_counts) or 1
    return top_total / grand_total, top_total, grand_total


def _build_factor_scores(s: Dict) -> str:
    agent_extra = f", Agent: {s['agent_artifact']:.2f}" if s.get("agent_artifact", 0) > 0 else ""
    ai_breakdown = (
        f"(Volume: {s['ai_volume']:.2f}, Initiative: {s['ai_initiative']:.2f}, "
        f"Repetition: {s['ai_repetition']:.2f}, Focus: {s['ai_focus']:.2f}, "
        f"Burst: {s['firework']:.2f}{agent_extra})"
    )
    tiny = f"    Tiny Project Penalty        -{s['tiny_project']:.2f}\n" if s["tiny_project"] > 0 else ""
    return ai_breakdown, tiny


def _build_batch_section(periods: List[Dict]) -> str:
    if not periods:
        return ""
    lines = ["", "  Batch Analysis by Period:",
             f"  {'Period':<12} {'C':>4} {'+Ln':>7} {'-Ln':>7} {'Rec':>5} {'Vol':>5} {'DelH':>5} {'Q':>5}  Band",
             "  " + "-" * 62]
    for p in periods:
        lines.append(
            f"  {p['period']:<12} {p['commits']:>4} {p['additions']:>7} {p['deletions']:>7} "
            f"{p['recency']:>5.2f} {p['ai_volume']:>5.2f} {p['deletion']:>5.2f} {p['composite']:>5.2f}  {p['band']}")
    return "\n".join(lines)


def _build_breakdown_section(breakdown: Optional[Dict]) -> str:
    if not breakdown:
        return ""
    lines = ["",
             f"  File Breakdown{'':>56} "
             f"Total: {breakdown['total_lines']:,}  Non-blank: {breakdown['total_non_blank']:,}  "
             f"Code: {breakdown['code_lines']:,}",
             "",
             f"  {'Extension':<16} {'Files':>7} {'Lines':>10} {'Non-blank':>10}",
             f"  {'─' * 46}"]
    for ext, info in breakdown["exts"]:
        label = ext if ext else "(no ext)"
        lines.append(f"  {label:<16} {info['files']:>7} {info['total']:>10,} {info['non_blank']:>10,}")
    lines.extend([
        f"  {'─' * 46}",
        f"  {'Total':<16} {breakdown['total_files']:>7} {breakdown['total_lines']:>10,} {breakdown['total_non_blank']:>10,}",
    ])
    return "\n".join(lines)


def _build_hero_section(entries: List[Dict], hero_top_n: int, hero_threshold: float) -> str:
    if not entries:
        return ""
    hero_ratio, hero_top, hero_total = _hero_risk(entries, hero_top_n)
    lines = ["",
             f"  Hero Risk (top {hero_top_n}):  {hero_ratio:.0%}  ({hero_top:,} / {hero_total:,} commits)"]
    if hero_ratio >= hero_threshold:
        lines.append(f"  High risk — project relies on top {hero_top_n} contributors")
    return "\n".join(lines)


def _build_see_also() -> str:
    return ("\n"
            "  See also (in charts/ subdirectory):\n"
            "    charts/growth_trend.png     — Code growth trend\n"
            "    charts/commit_activity.png  — Daily commit activity\n"
            "    charts/contributors.png     — Developer Pareto (bots excluded)\n"
            "    charts/quality_radar.png    — Quality factor radar\n"
            "    contributors.csv            — Contributor activity summary (AI Participation)\n"
            "    suspicion.csv               — Detailed audit findings with error codes")


def write_report(q: Dict, periods: List[Dict], audit: Dict, repo_path: str, csv_path: str, path: str,
                 entries: List[Dict] = None, color: str = _PRIMARY_COLOR, hero_top_n: int = 5,
                 hero_threshold: float = 0.8, pareto_max_bars: int = 50) -> int:
    template_path = os.path.join(os.path.dirname(__file__), "report_template.txt")
    try:
        with open(template_path) as f:
            template = f.read()
    except FileNotFoundError:
        template = ""

    breakdown = _file_breakdown(repo_path) if repo_path else None
    now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    duration = _duration_str(q['days_since_last'])

    if q.get("band") == "Archived":
        marker = "{ARCHIVED_BLOCK}"
        body = template.split(marker)[1]
        ctx = dict(
            generated_at=now_str, project_name=q['name'], total_commits=q['total_commits'],
            active_days=q['active_days'], duration_since_last=duration,
            status=f"Archived — no activity in {duration}",
        )
    else:
        marker = "{NORMAL_BLOCK}"
        body = template.split(marker)[1]
        s = q["scores"]
        ai_breakdown, tiny = _build_factor_scores(s)
        claude_line = f"    Claude AI commits:         {q['claude_commits']}" if q.get("claude_commits", 0) > 0 else ""
        agent_tools = []
        for suspect in audit.get("suspicion", []):
            if suspect.get("code") == "S2013":
                agent_tools.append(suspect.get("note", ""))
        ai_tool_line = claude_line
        if agent_tools:
            tools_str = "; ".join(agent_tools[:3])
            if ai_tool_line:
                ai_tool_line += f"\n    AI config files:         {tools_str}"
            else:
                ai_tool_line = f"    AI config files:         {tools_str}"
        ctx = dict(
            generated_at=now_str, project_name=q['name'], total_commits=q['total_commits'],
            active_days=q['active_days'], duration_since_last=duration,
            band=q['band'], score=s['composite'],
            recency=s['recency'], anti_ai=1 - s['suspicion'], ai_breakdown=ai_breakdown,
            deletion_health=s['deletion_health'], scale=s['scale'], hero=s['hero'],
            tiny_penalty=tiny,
            total_lines=f"{q['total_lines']:,}", total_additions=f"{q['total_additions']:,}",
            total_deletions=f"{q['total_deletions']:,}", net_change=f"{q['net_change']:+,}",
            avg_additions=q['avg_additions_per_commit'], commit_density=q['commits_per_day'],
            churn_ratio=q['churn_ratio'], active_days_raw=f"{q['active_days']:.0f}",
            first_commit=q['first_commit'], last_commit=q['last_commit'],
            claude_ai_line=ai_tool_line,
            batch_analysis=_build_batch_section(periods),
            file_breakdown=_build_breakdown_section(breakdown),
            hero_risk=_build_hero_section(entries, hero_top_n, hero_threshold),
            see_also=_build_see_also() if entries else "",
        )

    text = body.format(**ctx)
    lines = text.split("\n")

    tmpdir = tempfile.mkdtemp()
    detail_count = 0
    try:
        if entries:
            chart_dir = os.path.join(tmpdir, "charts")
            os.makedirs(chart_dir, exist_ok=True)
            cum_net = sum(e["additions"] - e["deletions"] for e in entries) if entries else 0
            nb_goal = breakdown["total_non_blank"] if breakdown else 0
            nb_ratio = nb_goal / cum_net if cum_net > 0 else 1.0
            _growth_trend_chart(entries, os.path.join(chart_dir, "growth_trend.png"), nb_ratio, color)
            _commit_barchart(entries, os.path.join(chart_dir, "commit_activity.png"), color)
            _contributor_pareto(entries, os.path.join(chart_dir, "contributors.png"), color, pareto_max_bars)
            ai_participation = _ai_participation_per_author(repo_path, entries)
            write_contributor_csv(entries, os.path.join(tmpdir, "contributors.csv"), ai_participation)
            _quality_radar_chart(q.get("scores", {}), os.path.join(chart_dir, "quality_radar.png"), color)
            write_suspicion_csv(audit, os.path.join(tmpdir, "suspicion.csv"))

        with open(os.path.join(tmpdir, "report.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")

        if csv_path and os.path.exists(csv_path):
            shutil.copy2(csv_path, os.path.join(tmpdir, os.path.basename(csv_path)))

        detail_dir = os.path.join(tmpdir, "details")
        os.makedirs(detail_dir, exist_ok=True)

        seen_hashes = set()
        for suspect in audit.get("suspicion", []):
            commit_hash = suspect.get("hash", "")
            if not commit_hash or not all(c in "0123456789abcdef" for c in commit_hash):
                continue
            if commit_hash in seen_hashes:
                continue
            seen_hashes.add(commit_hash)
            safe_name = commit_hash.replace("/", "_")
            raw = _git(repo_path, f'git show {commit_hash}')
            if raw is None:
                raw = "[error: commit not found]"
            with open(os.path.join(detail_dir, f"{safe_name}_details.txt"), "w", encoding="utf-8") as f:
                f.write(raw + "\n")
            detail_count += 1

        csv_count = 0
        zip_path = path
        if not zip_path.endswith(".zip"):
            zip_path += ".zip"
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
            zf.write(os.path.join(tmpdir, "report.txt"), "report.txt")
            if csv_path and os.path.exists(os.path.join(tmpdir, os.path.basename(csv_path))):
                zf.write(os.path.join(tmpdir, os.path.basename(csv_path)), os.path.basename(csv_path))
                csv_count += 1
            if os.path.isdir(detail_dir):
                for fn in sorted(os.listdir(detail_dir)):
                    fpath = os.path.join(detail_dir, fn)
                    zf.write(fpath, f"details/{fn}")
            chart_dir_tmp = os.path.join(tmpdir, "charts")
            if os.path.isdir(chart_dir_tmp):
                for fn in sorted(os.listdir(chart_dir_tmp)):
                    zf.write(os.path.join(chart_dir_tmp, fn), f"charts/{fn}")
            if os.path.exists(os.path.join(tmpdir, "contributors.csv")):
                zf.write(os.path.join(tmpdir, "contributors.csv"), "contributors.csv")
                csv_count += 1
            if os.path.exists(os.path.join(tmpdir, "suspicion.csv")):
                zf.write(os.path.join(tmpdir, "suspicion.csv"), "suspicion.csv")
                csv_count += 1
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    return detail_count, csv_count


# The report is a human-readable snapshot of the quality analysis — used for
# sharing results outside the CLI or keeping a historical record.
def main():
    fd = _SETTINGS
    repo = os.getcwd()

    console.print()
    console.print("[default]Git Commit Statistics Analyzer by Gradum[/default]")
    console.print(f"[default][dim]Version: {__version__}[/dim][/default]")
    console.print()

    if not os.path.exists(repo):
        console.print(f"[yellow]{ICON_WARNING}  Directory does not exist: {repo}[/yellow]")
        sys.exit(1)
    if not os.path.exists(os.path.join(repo, ".git")):
        console.print(f"[yellow]{ICON_WARNING}  Not a valid Git repository: {repo}[/yellow]")
        sys.exit(1)

    console.print(f"[dim]{ICON_START}[/dim] Start investigating Git data")
    console.print(f"[dim]{ICON_ARROW}[/dim]")

    scan_start = time.time()

    with Progress(
            SpinnerColumn(spinner_name="blink", style="default"),
            TextColumn("{task.description}"),
            console=console, transient=True
    ) as progress:
        task = progress.add_task("", total=None)
        entries = all_commits(
            repo,
            on_commit=lambda hash_str, subject, current, total_count: progress.update(
                task, description=f"Scanning {current}/{total_count} {escape(hash_str)}: {escape(subject[:60])}"
            )
        )

    total = len(entries)
    if total == 0:
        console.print(f"[yellow]{ICON_WARNING}  No commits found[/yellow]")
        sys.exit(1)

    elapsed = time.time() - scan_start
    minutes = int(elapsed // 60)
    seconds = int(elapsed % 60)
    scan_time = f"{minutes}m {seconds}s" if minutes > 0 else f"{seconds}s"
    console.print(f"[default][blue]{ICON_STEP}[/blue] Found {total} commit records in {repo_name(repo)}[/default]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[blue]{ICON_STEP}[/blue] [default]Scanned for {scan_time}[/default]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")

    csv_path = write_csv(fd, entries)
    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[blue]{ICON_STEP}[/blue] [default]Statistics written to {csv_path}[/default]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")

    if fd.get("enableQualityAnalysis", False):
        quality_params = dict(_QUALITY_PARAMS)
        quality_params_path = fd.get("qualityParamsFilePath")
        if quality_params_path and os.path.exists(quality_params_path):
            with open(quality_params_path) as file:
                quality_params.update(json.load(file))

        now = datetime.now(timezone.utc)
        quality = compute_quality(entries, quality_params, now, repo_name(repo), repo)

        audit = {}
        periods = []
        if not quality or quality.get("band") == "Archived":
            audit = {"suspicion_count": 0, "deletion_percent": 0, "heavy_deletions": 0, "suspicion": []}
        else:
            overall_hero = quality.get("scores", {}).get("hero", 0.0)
            batch_unit = fd.get("batchPeriodUnit")
            periods = compute_quality_periods(entries, batch_unit or "month", now, quality_params,
                                              overall_hero, repo) if batch_unit else []

            with Progress(
                    SpinnerColumn(spinner_name="blink", style="default"),
                    TextColumn("[default]{task.description}[/default]"),
                    console=console, transient=True,
            ) as progress:
                console.print(f"[dim]{ICON_ARROW}[/dim]")
                progress.add_task("Scanning deletion patterns", total=None)
                audit = audit_deletions(repo, entries, now, quality.get("scores"))

        if not quality or quality.get("band") == "Archived":
            msg = f"Archived — no commits in {_duration_str(quality['days_since_last'])}" if quality else "Archived — no commits"
            console.print(f"[grey58]{ICON_STEP}[/grey58] [default]{msg}[/default]")
        else:
            console.print(f"[dim]{ICON_ARROW}[/dim]")
            console.print(
                f"[blue]{ICON_STEP}[/blue] [default]Analyzed {len(entries)} commits, {audit['suspicion_count']} suspicious patterns[/default]"
            )
            console.print(f"[dim]{ICON_ARROW}[/dim]")
            console.print(f"[dim]{ICON_ARROW}[/dim]")

            ts = datetime.now().strftime("%Y%m%d_%H%M%S")
            report_path = fd.get("reportOutputPath") or f"quality_report_{ts}.zip"
            chart_color = fd.get("chartPrimaryColor", _PRIMARY_COLOR)
            detail_count, csv_count = write_report(quality, periods, audit, repo, csv_path, report_path, entries, chart_color,
                                                   _HERO_TOP_N, _HERO_THRESHOLD, _PARETO_MAX_BARS)
            console.print(
                f"[blue]{ICON_POINT}[/blue] [default]Packaged {detail_count} detail files + {csv_count} CSV files[/default]")
            console.print(f"[dim]{ICON_ARROW}[/dim]")
            console.print(f"[dim]{ICON_ARROW}[/dim]")

            if os.path.exists(csv_path):
                os.remove(csv_path)

            console.print(f"[blue]{ICON_STEP}[/blue] [default]Deletion audit results[/default]")
            console.print(f"[dim]{ICON_ARROW}[/dim]")
            console.print(f"[dim]{ICON_ARROW}[/dim]")

            _SEVERITY = {"critical": 0, "alert": 1, "watch": 2, "normal": 3, "clean": 4}
            _ABBREV = {"critical": "C", "alert": "A", "watch": "W", "normal": "N", "clean": "L"}

            quality_band = quality["band"]
            quality_color = quality["band_color"]
            console.print(
                f"[{quality_color}]{ICON_STEP}[/{quality_color}] "
                f"[default]Quality: Add/Del rate {audit['deletion_percent']}% · "
                f"{len(audit['suspicion'])} problems[/default] · "
                f"[{quality_color}]{quality_band}[/{quality_color}]"
            )

            top = sorted(audit["suspicion"], key=lambda item: _SEVERITY.get(item["level"], 99))[:4]
            if top:
                parts = []
                for s in top:
                    _, lvl_color = _SUSPICION_LABELS.get(s["level"], ["", "default"])
                    parts.append(
                        f"[{lvl_color}]{s['hash']} [{_ABBREV.get(s['level'], '?')}][/{lvl_color}]"
                    )
                console.print(f"[dim]{ICON_ARROW}       ⎿  {'  '.join(parts)}[/dim]")
                remaining = max(0, audit["suspicion_count"] - 4)
                if remaining > 0:
                    console.print(f"[dim]{ICON_ARROW}          [/dim][default]+{remaining} more...[/default]")

            console.print(f"[dim]{ICON_ARROW}[/dim]")
            console.print(f"[dim]{ICON_ARROW}[/dim]")
            console.print(f"[default][blue]{ICON_STEP}[/blue] Report written to {report_path}[/default]")

    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[default][dim]{ICON_COMPLETE}[/dim] Complete[/default]")
    console.print("")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        console.print(f"\n[yellow]{ICON_WARNING}  Analysis interrupted[/yellow]")
        sys.exit(1)
    except Exception as error:
        import traceback

        console.print(f"[yellow]{ICON_WARNING}  {error}[/yellow]")
        traceback.print_exc()
        sys.exit(1)
