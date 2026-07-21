#!/usr/bin/env python3
"""
Project Quality Analysis Engine — 4-factor git health assessment.
Analyzes recency, addition patterns, deletion patterns, and project scale
with configurable formulas and batch (per-period) analysis.
"""

import argparse
import csv
import math
import os
import subprocess
import sys
from collections import defaultdict
from datetime import datetime, timezone
from typing import Dict, List, Optional, Tuple

from rich.console import Console
from rich.table import Table
from rich.panel import Panel
from rich.text import Text

console = Console()

ICON_BRACKET_L = "\u2502"
ICON_DOT = "\u25CF"
ICON_TICK = "\u2713"
ICON_WARN = "\u25B2"
ICON_CROSS = "\u2717"

# ---------------------------------------------------------------------------
# Formula parameter defaults
# ---------------------------------------------------------------------------

DEFAULT_PARAMS = {
    "recency_half_life_days": 90.0,
    "ai_additions_threshold": 500.0,
    "ai_additions_scale": 200.0,
    "firework_density_threshold": 5.0,
    "firework_active_days": 90.0,
    "deletion_ideal_ratio": 0.35,
    "deletion_sigma": 0.15,
    "scale_tiny": 100,
    "scale_small": 1000,
    "scale_medium": 10000,
    "scale_large": 100000,
    "weight_recency": 0.35,
    "weight_anti_ai": 0.25,
    "weight_deletion_health": 0.25,
    "weight_scale": 0.15,
}

# ---------------------------------------------------------------------------
# Core formulas
# ---------------------------------------------------------------------------

def _sigmoid(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-x))

def _gaussian(x: float, mu: float, sigma: float) -> float:
    return math.exp(-((x - mu) ** 2) / (2 * sigma ** 2))

def score_recency(days_since_last_commit: float, half_life: float = 90.0) -> float:
    """Gaussian decay: 0 days → 1.0, 90 days → 0.37, 180 days → 0.02."""
    return _gaussian(days_since_last_commit, 0.0, half_life)

def score_ai_suspicion(avg_additions: float, threshold: float = 500.0, scale: float = 200.0) -> float:
    """Sigmoid: low avg → ~0, >500 → rapidly climbs toward 1.0."""
    return _sigmoid((avg_additions - threshold) / scale)

def score_firework(commits_per_day: float, active_days: float,
                   density_threshold: float = 5.0, active_threshold: float = 90.0) -> float:
    """High commit density in short active period → 1.0."""
    return _sigmoid((commits_per_day - density_threshold) / 2.0) * \
           _sigmoid((active_threshold - active_days) / 20.0)

def score_deletion_health(deletions: int, additions: int,
                          ideal_ratio: float = 0.35, sigma: float = 0.15) -> float:
    """Gaussian centered at ideal churn ratio. 0.35 → 1.0, extremes → near 0."""
    if additions == 0:
        return 0.5
    return _gaussian(deletions / additions, ideal_ratio, sigma)

def score_scale(total_lines: int, params: Dict) -> float:
    if total_lines < params["scale_tiny"]:
        return 0.1
    if total_lines < params["scale_small"]:
        return 0.3
    if total_lines < params["scale_medium"]:
        return 0.7
    if total_lines < params["scale_large"]:
        return 1.0
    return 0.8

def composite_score(r: float, ai: float, d: float, s: float, weights: Dict[str, float]) -> float:
    return (weights["weight_recency"] * r +
            weights["weight_anti_ai"] * (1.0 - ai) +
            weights["weight_deletion_health"] * d +
            weights["weight_scale"] * s)

def quality_band(score: float) -> Tuple[str, str]:
    if score >= 0.8:
        return "Excellent", "green"
    if score >= 0.6:
        return "Good", "cyan"
    if score >= 0.4:
        return "Fair", "yellow"
    if score >= 0.2:
        return "Poor", "orange1"
    return "Critical", "red"

# ---------------------------------------------------------------------------
# Git data helpers
# ---------------------------------------------------------------------------

def _run(repo: str, cmd: str) -> Optional[str]:
    try:
        r = subprocess.run(cmd, cwd=repo, shell=True, capture_output=True, text=True, check=True)
        return r.stdout.strip()
    except subprocess.CalledProcessError:
        return None

def collect_commits(repo: str) -> List[Dict]:
    """Return list of {hash, date, add, del} for every commit."""
    raw = _run(repo, 'git log --reverse --format="%H||%cI"')
    if not raw:
        return []
    commits = []
    for line in raw.strip().split("\n"):
        if "||" not in line:
            continue
        h, iso = line.split("||", 1)
        dt = datetime.fromisoformat(iso)
        stats = _run(repo, f'git show --numstat --format="" {h}')
        add = del_ = 0
        if stats:
            for ln in stats.split("\n"):
                ln = ln.strip()
                if ln:
                    seg = ln.split("\t")
                    if len(seg) >= 2 and seg[0] != "-" and seg[1] != "-":
                        try:
                            add += int(seg[0])
                            del_ += int(seg[1])
                        except ValueError:
                            pass
        commits.append({"hash": h[:8], "date": dt, "add": add, "del": del_})
    return commits

# ---------------------------------------------------------------------------
# Analysis
# ---------------------------------------------------------------------------

def analyze(repo: str, params: Dict, batch: Optional[str]) -> Dict:
    commits = collect_commits(repo)
    if not commits:
        console.print(f"[red]{ICON_CROSS}  No commits found[/red]")
        sys.exit(1)

    now = datetime.now(timezone.utc)
    total = len(commits)

    # --- whole-repo aggregates ---
    first = commits[0]["date"]
    last = commits[-1]["date"]
    active_days = max((last - first).total_seconds() / 86400.0, 0.1)
    days_since_last = (now - last).total_seconds() / 86400.0
    total_add = sum(c["add"] for c in commits)
    total_del = sum(c["del"] for c in commits)
    total_lines = total_add + total_del
    add_commits = sum(1 for c in commits if c["add"] > 0)
    avg_add = total_add / max(add_commits, 1)
    cpd = total / active_days

    # --- per-period batches ---
    periods: List[Dict] = []
    if batch:
        periods = _batch_analysis(commits, batch, now, params)

    # --- whole-repo scores ---
    r = score_recency(days_since_last, params["recency_half_life_days"])
    ai = score_ai_suspicion(avg_add, params["ai_additions_threshold"], params["ai_additions_scale"])
    fw = score_firework(cpd, active_days, params["firework_density_threshold"], params["firework_active_days"])
    d = score_deletion_health(total_del, total_add, params["deletion_ideal_ratio"], params["deletion_sigma"])
    s = score_scale(total_lines, params)
    q = composite_score(r, ai, d, s, params)

    suspicion = max(ai, fw)
    band, color = quality_band(q)

    return {
        "repo": os.path.basename(repo),
        "total_commits": total,
        "active_days": round(active_days, 1),
        "days_since_last": round(days_since_last, 1),
        "total_add": total_add,
        "total_del": total_del,
        "total_lines": total_lines,
        "avg_add_per_commit": round(avg_add, 1),
        "commits_per_day": round(cpd, 2),
        "scores": {
            "recency": round(r, 4),
            "ai_suspicion": round(ai, 4),
            "firework": round(fw, 4),
            "suspicion": round(suspicion, 4),
            "deletion_health": round(d, 4),
            "scale": round(s, 4),
            "composite": round(q, 4),
        },
        "band": band,
        "band_color": color,
        "periods": periods,
    }

def _batch_analysis(commits: List[Dict], unit: str, now: datetime, params: Dict) -> List[Dict]:
    """Slice commits by time unit and score each slice."""
    periods: List[Dict] = []
    buckets: Dict[str, List[Dict]] = defaultdict(list)

    for c in commits:
        d = c["date"]
        if unit == "month":
            key = d.strftime("%Y-%m")
        elif unit == "week":
            key = d.strftime("%Y-W%V")
        elif unit == "quarter":
            key = f"{d.year}-Q{(d.month - 1) // 3 + 1}"
        elif unit == "year":
            key = d.strftime("%Y")
        else:
            key = d.strftime("%Y-%m")
        buckets[key].append(c)

    for label in sorted(buckets):
        cs = buckets[label]
        add = sum(c["add"] for c in cs)
        dell = sum(c["del"] for c in cs)
        cnt = len(cs)
        span = max((cs[-1]["date"] - cs[0]["date"]).total_seconds() / 86400.0, 1.0)
        avg_a = add / max(sum(1 for c in cs if c["add"] > 0), 1)
        cpd = cnt / span
        r = score_recency((now - cs[-1]["date"]).total_seconds() / 86400.0, params["recency_half_life_days"])
        ai = score_ai_suspicion(avg_a, params["ai_additions_threshold"], params["ai_additions_scale"])
        fw = score_firework(cpd, span, params["firework_density_threshold"], params["firework_active_days"])
        d = score_deletion_health(dell, add, params["deletion_ideal_ratio"], params["deletion_sigma"])
        s_val = score_scale(sum(c["add"] + c["del"] for c in commits[:commits.index(cs[-1]) + 1]), params)
        q = composite_score(r, ai, d, s_val, params)
        band, _ = quality_band(q)
        periods.append({
            "period": label,
            "commits": cnt,
            "add": add,
            "del": dell,
            "recency": round(r, 3),
            "ai": round(ai, 3),
            "deletion": round(d, 3),
            "composite": round(q, 3),
            "band": band,
        })
    return periods

# ---------------------------------------------------------------------------
# Display
# ---------------------------------------------------------------------------

def render(result: Dict):
    s = result["scores"]
    band, color = result["band"], result["band_color"]

    # header
    console.print()
    console.print(Panel(
        f"[bold]Project:[/bold] {result['repo']}   "
        f"[bold]Commits:[/bold] {result['total_commits']}   "
        f"[bold]Period:[/bold] {result['active_days']} days   "
        f"[bold]Last commit:[/bold] {result['days_since_last']} days ago\n\n"
        f"[bold {color}]{ICON_DOT} Overall Quality: {band} ({result['scores']['composite']:.2f})[/bold {color}]",
        title="Project Quality Analysis",
        border_style="blue",
    ))

    # factor breakdown
    tbl = Table(title="Factor Breakdown", box=None, padding=(0, 2))
    tbl.add_column("Factor", style="bold")
    tbl.add_column("Score")
    tbl.add_column("Interpretation")

    def row(name: str, val: float, good: str, mid: str, bad: str):
        if val >= 0.7:
            note = good
        elif val >= 0.4:
            note = mid
        else:
            note = bad
        tbl.add_row(name, f"{val:.2f}", note)

    row("Recency (no zombie)", s["recency"],
        "Active — recent commits",
        "Stale — consider reviving",
        "Zombie — project likely dead")
    row("Anti-AI (human pattern)", 1 - s["suspicion"],
        "Natural — varied commit sizes",
        "Mixed — some batch commits",
        "Synthetic — AI/firework pattern detected")
    row("Deletion Health", s["deletion_health"],
        "Healthy churn — balanced refactoring",
        "Moderate — some rewrite activity",
        "Unstable — extreme churn or stagnation")
    row("Scale Maturity", s["scale"],
        "Mature — substantial codebase",
        "Developing — growing project",
        "Nascent — early-stage project")
    console.print(tbl)

    # detail metrics
    detail = Table(title="Raw Metrics", box=None, padding=(0, 2))
    detail.add_column("Metric")
    detail.add_column("Value")
    detail.add_row("Total lines (add + del)", f"{result['total_lines']:,}")
    detail.add_row("Additions", f"{result['total_add']:,}")
    detail.add_row("Deletions", f"{result['total_del']:,}")
    detail.add_row("Net change", f"{result['total_add'] - result['total_del']:+,}")
    detail.add_row("Avg additions/commit", f"{result['avg_add_per_commit']:.1f}")
    detail.add_row("Commit density", f"{result['commits_per_day']:.2f} / day")
    detail.add_row("Active days", f"{result['active_days']:.0f}")
    detail.add_row("Days since last commit", f"{result['days_since_last']:.0f}")
    console.print(detail)

    # batch table
    if result["periods"]:
        bt = Table(title=f"Batch Analysis ({len(result['periods'])} periods)", box=None, padding=(0, 1))
        bt.add_column("Period", style="bold")
        bt.add_column("C")
        bt.add_column("+Ln")
        bt.add_column("-Ln")
        bt.add_column("Rec")
        bt.add_column("AI")
        bt.add_column("DelH")
        bt.add_column("Q")
        bt.add_column("Band")
        for p in result["periods"]:
            bd, bc = quality_band(p["composite"])
            bt.add_row(
                p["period"], str(p["commits"]), str(p["add"]), str(p["del"]),
                f"{p['recency']:.2f}", f"{p['ai']:.2f}", f"{p['deletion']:.2f}",
                f"{p['composite']:.2f}", f"[{bc}]{bd}[/{bc}]",
            )
        console.print(bt)

    console.print()

# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args():
    p = argparse.ArgumentParser(description="Project Quality Analysis Engine")
    p.add_argument("repo_path", nargs="?", default=os.getcwd(), help="Git repo path")
    p.add_argument("--batch", choices=["month", "week", "quarter", "year"], default=None,
                   help="Enable per-period batch analysis")
    p.add_argument("--params", type=str, default=None,
                   help="JSON file with custom formula parameters")
    return p.parse_args()


def load_params(path: Optional[str]) -> Dict:
    import json
    p = dict(DEFAULT_PARAMS)
    if path and os.path.exists(path):
        with open(path) as f:
            overrides = json.load(f)
        p.update(overrides)
    return p


def main():
    args = parse_args()
    params = load_params(args.params)

    console.print(f"[dim]Quality Analysis Engine | Copyright (c) 2026 Gradum team | MIT License[/dim]")

    if not os.path.exists(os.path.join(args.repo_path, ".git")):
        console.print(f"[red]{ICON_CROSS}  Not a git repo: {args.repo_path}[/red]")
        sys.exit(1)

    result = analyze(args.repo_path, params, args.batch)
    render(result)


if __name__ == "__main__":
    main()
