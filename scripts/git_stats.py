#  Copyright (c) 2026 Gradum team, some rights reserved.
#  For licensing terms and conditions, see the MIT LICENSE file.

import argparse
import concurrent.futures
import csv
import json
import math
import os
import subprocess
import sys
import time
from collections import defaultdict, deque
from datetime import datetime, timezone
from typing import Dict, List, Optional, Tuple

from rich.console import Console
from rich.progress import (
    Progress, SpinnerColumn, TextColumn,
    TaskProgressColumn, TimeElapsedColumn, TimeRemainingColumn
)
from rich.spinner import SPINNERS, Spinner
from rich.table import Table
from rich.panel import Panel

console = Console()

ICON_START = "\u23A1"
ICON_COMPLETE = "\u23A3"
ICON_ARROW = "\u2502"
ICON_STEP = "\u25CF"
ICON_POINT = "\u25CB"
ICON_WARNING = "\u25B2"

SPINNERS["blink"] = {"interval": 450, "frames": ["\u25CF", " "]}

# ---------------------------------------------------------------------------
# Shared git helpers
# ---------------------------------------------------------------------------

def _run(repo: str, cmd: str) -> Optional[str]:
    try:
        r = subprocess.run(cmd, cwd=repo, shell=True, capture_output=True, text=True, check=True)
        return r.stdout.strip()
    except subprocess.CalledProcessError:
        return None

def get_repo_name(repo_path):
    return os.path.basename(repo_path) or "Unknown"

def get_commit_stats(repo_path, commit_hash):
    stats = _run(repo_path, f'git show --numstat --format="" {commit_hash}')
    if not stats:
        return 0, 0
    add = del_ = 0
    for line in stats.split("\n"):
        line = line.strip()
        if line:
            parts = line.split("\t")
            if len(parts) >= 2 and parts[0] != "-" and parts[1] != "-":
                try:
                    add += int(parts[0])
                    del_ += int(parts[1])
                except ValueError:
                    pass
    return add, del_

def get_commit_dates(repo_path):
    out = _run(repo_path, 'git log --reverse --format="%H||%ai"')
    if not out:
        return {}
    result = {}
    for line in out.split("\n"):
        if "||" in line:
            h, rest = line.split("||", 1)
            parts = rest.split()
            d = parts[0] if parts else ""
            t = parts[1].split("+")[0] if len(parts) > 1 else ""
            result[h] = (d, t)
    return result

def get_commits_full(repo_path):
    """Return list of {hash, date, add, del} for every commit."""
    raw = _run(repo_path, 'git log --reverse --format="%H||%cI"')
    if not raw:
        return []
    commits = []
    for line in raw.strip().split("\n"):
        if "||" not in line:
            continue
        h, iso = line.split("||", 1)
        dt = datetime.fromisoformat(iso)
        stats = _run(repo_path, f'git show --numstat --format="" {h}')
        add = del_ = 0
        if stats:
            for ln in stats.strip().split("\n"):
                if ln.strip():
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
# Stats analysis (per-commit CSV)
# ---------------------------------------------------------------------------

SAFE_BUILTINS = {
    "abs": abs, "max": max, "min": min, "round": round,
    "sum": sum, "pow": pow, "int": int, "float": float,
}

BUILTIN_FORMULAS = {
    "net_ratio": "(net / (add + deletions) * 100) if (add + deletions) > 0 else 0",
    "efficiency": "(net / max(add, deletions) * 100) if max(add, deletions) > 0 else 0",
    "churn": "((add + deletions) / cum_total * 100) if cum_total > 0 else 0",
}

def evaluate_formula(expr, vars_dict):
    try:
        res = eval(expr, {"__builtins__": SAFE_BUILTINS}, vars_dict)
        return round(res, 2) if isinstance(res, float) else res
    except Exception:
        return None

def list_builtin_formulas():
    for name, expr in BUILTIN_FORMULAS.items():
        console.print(f"  [green]{name}[/green]: {expr}")

def analyze_commits(args, commits_list, commit_dates, stats_results):
    """Write per-commit CSV."""
    output_file = args.output or f'git_stats_{datetime.now().strftime("%Y%m%d_%H%M%S")}.csv'

    formula_expr = formula_display = None
    if args.formula:
        if args.formula in BUILTIN_FORMULAS:
            formula_expr = BUILTIN_FORMULAS[args.formula]
            formula_display = args.formula
        else:
            formula_expr = args.formula
            formula_display = "Custom Formula"

    with open(output_file, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        headers = [
            "Index", "Commit Hash", "Date", "Time",
            "Additions", "Deletions", "Net Change",
            "Cumulative Add", "Cumulative Del", "Cumulative Net",
            "Total Lines", "Growth Rate (%)",
        ]
        if args.window > 0:
            headers.append("Moving Avg")
        if formula_expr is not None:
            headers.append(formula_display)
        writer.writerow(headers)

        cum_add = cum_del = cum_net = 0
        first = True
        first_cum_net = 0
        moving_window = deque(maxlen=args.window) if args.window > 0 else None
        moving_avg = 0

        for i, ch in enumerate(commits_list):
            count = i + 1
            add, delete = stats_results.get(ch, (0, 0))
            net = add - delete
            cum_add += add
            cum_del += delete
            cum_net += net
            cum_total = cum_add + cum_del

            if first:
                first = False
                first_cum_net = cum_net
                growth_rate = 0
            elif first_cum_net != 0:
                growth_rate = round(((cum_net - first_cum_net) * 100) / abs(first_cum_net), 2)
            else:
                growth_rate = 0

            date, time_str = commit_dates.get(ch, ("", ""))
            row = [count, ch[:8], date, time_str, add, delete, net,
                   cum_add, cum_del, cum_net, cum_total, growth_rate]

            if args.window > 0:
                moving_window.append(net)
                moving_avg = round(sum(moving_window) / len(moving_window), 2)
                row.append(moving_avg)

            if formula_expr is not None:
                vars_dict = {
                    "add": add, "deletions": delete, "net": net,
                    "cum_add": cum_add, "cum_del": cum_del, "cum_net": cum_net,
                    "cum_total": cum_total, "count": count, "growth_rate": growth_rate,
                    "moving_avg": moving_avg,
                }
                val = evaluate_formula(formula_expr, vars_dict)
                row.append(val if val is not None else "")

            writer.writerow(row)

    return output_file

# ---------------------------------------------------------------------------
# Quality analysis formulas
# ---------------------------------------------------------------------------

QUALITY_PARAMS = {
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

def _sigmoid(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-x))

def _gaussian(x: float, mu: float, sigma: float) -> float:
    return math.exp(-((x - mu) ** 2) / (2 * sigma ** 2))

def _quality_band(score: float) -> Tuple[str, str]:
    if score >= 0.8:
        return "Excellent", "green"
    if score >= 0.6:
        return "Good", "cyan"
    if score >= 0.4:
        return "Fair", "yellow"
    if score >= 0.2:
        return "Poor", "orange1"
    return "Critical", "red"

def compute_quality(commits: List[Dict], params: Dict, now: datetime, repo_name: str = "") -> Dict:
    total = len(commits)
    if total == 0:
        return {}

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

    r = _gaussian(days_since_last, 0.0, params["recency_half_life_days"])
    ai = _sigmoid((avg_add - params["ai_additions_threshold"]) / params["ai_additions_scale"])
    fw = _sigmoid((cpd - params["firework_density_threshold"]) / 2.0) * \
         _sigmoid((params["firework_active_days"] - active_days) / 20.0)
    dh = _gaussian(total_del / max(total_add, 1), params["deletion_ideal_ratio"], params["deletion_sigma"]) \
        if total_add > 0 else 0.5
    sc = 0.1
    if total_lines >= params["scale_tiny"]:
        sc = 0.3
    if total_lines >= params["scale_small"]:
        sc = 0.7
    if total_lines >= params["scale_medium"]:
        sc = 1.0
    if total_lines >= params["scale_large"]:
        sc = 0.8
    suspicion = max(ai, fw)
    q = (params["weight_recency"] * r +
         params["weight_anti_ai"] * (1.0 - suspicion) +
         params["weight_deletion_health"] * dh +
         params["weight_scale"] * sc)
    band, color = _quality_band(q)

    return {
        "repo": repo_name,
        "total_commits": total,
        "active_days": round(active_days, 1),
        "days_since_last": round(days_since_last, 1),
        "first_commit": first.isoformat(),
        "last_commit": last.isoformat(),
        "total_add": total_add,
        "total_del": total_del,
        "total_lines": total_lines,
        "net_change": total_add - total_del,
        "avg_add_per_commit": round(avg_add, 1),
        "commits_per_day": round(cpd, 2),
        "churn_ratio": round(total_del / max(total_add, 1), 3),
        "scores": {
            "recency": round(r, 4),
            "ai_suspicion": round(ai, 4),
            "firework": round(fw, 4),
            "suspicion": round(suspicion, 4),
            "deletion_health": round(dh, 4),
            "scale": round(sc, 4),
            "composite": round(q, 4),
        },
        "band": band,
        "band_color": color,
    }

def compute_quality_batch(commits: List[Dict], unit: str, now: datetime, params: Dict) -> List[Dict]:
    buckets = defaultdict(list)
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

    periods = []
    for label in sorted(buckets):
        cs = buckets[label]
        add = sum(c["add"] for c in cs)
        dell = sum(c["del"] for c in cs)
        cnt = len(cs)
        span = max((cs[-1]["date"] - cs[0]["date"]).total_seconds() / 86400.0, 0.1)
        avg_a = add / max(sum(1 for c in cs if c["add"] > 0), 1)
        cpd = cnt / span
        r = _gaussian((now - cs[-1]["date"]).total_seconds() / 86400.0, 0.0, params["recency_half_life_days"])
        ai = _sigmoid((avg_a - params["ai_additions_threshold"]) / params["ai_additions_scale"])
        fw = _sigmoid((cpd - params["firework_density_threshold"]) / 2.0) * \
             _sigmoid((params["firework_active_days"] - span) / 20.0)
        dh = _gaussian(dell / max(add, 1), params["deletion_ideal_ratio"], params["deletion_sigma"]) \
            if add > 0 else 0.5
        s_val = 0.1
        cum_lines = sum(c["add"] + c["del"] for c in commits[:commits.index(cs[-1]) + 1])
        if cum_lines >= params["scale_tiny"]:
            s_val = 0.3
        if cum_lines >= params["scale_small"]:
            s_val = 0.7
        if cum_lines >= params["scale_medium"]:
            s_val = 1.0
        if cum_lines >= params["scale_large"]:
            s_val = 0.8
        suspicion = max(ai, fw)
        q = (params["weight_recency"] * r +
             params["weight_anti_ai"] * (1.0 - suspicion) +
             params["weight_deletion_health"] * dh +
             params["weight_scale"] * s_val)
        band, _ = _quality_band(q)
        periods.append({
            "period": label, "commits": cnt, "add": add, "del": dell,
            "recency": round(r, 3), "ai": round(ai, 3),
            "deletion": round(dh, 3), "composite": round(q, 3), "band": band,
        })
    return periods

# ---------------------------------------------------------------------------
# Text report writer
# ---------------------------------------------------------------------------

def write_report(q: Dict, periods: List[Dict], output_path: str):
    s = q["scores"]
    lines = []
    sep = "=" * 66

    lines.append(sep)
    lines.append(f"  Project Quality Analysis Report")
    lines.append(f"  Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(sep)
    lines.append(f"  Project:         {q['repo']}")
    lines.append(f"  Commits:         {q['total_commits']}")
    lines.append(f"  Period:          {q['active_days']} days")
    lines.append(f"  Last commit:     {q['days_since_last']} days ago")
    lines.append(f"  Overall Quality: {q['band']} ({s['composite']:.2f})")
    lines.append(sep)
    lines.append("")
    lines.append("  Factor Scores:")
    lines.append(f"    Recency (no zombie)      {s['recency']:.2f}")
    lines.append(f"    Anti-AI (human pattern)  {1 - s['suspicion']:.2f}  (AI: {s['ai_suspicion']:.2f}, Firework: {s['firework']:.2f})")
    lines.append(f"    Deletion Health          {s['deletion_health']:.2f}")
    lines.append(f"    Scale Maturity           {s['scale']:.2f}")
    lines.append("")
    lines.append(sep)
    lines.append("  Raw Metrics:")
    lines.append(f"    Total lines (add+del):  {q['total_lines']:,}")
    lines.append(f"    Additions:              {q['total_add']:,}")
    lines.append(f"    Deletions:              {q['total_del']:,}")
    lines.append(f"    Net change:             {q['net_change']:+,}")
    lines.append(f"    Avg additions/commit:   {q['avg_add_per_commit']}")
    lines.append(f"    Commit density:         {q['commits_per_day']} / day")
    lines.append(f"    Churn ratio (del/add):  {q['churn_ratio']}")
    lines.append(f"    Active days:            {q['active_days']:.0f}")
    lines.append(f"    First commit:           {q['first_commit']}")
    lines.append(f"    Last commit:            {q['last_commit']}")
    lines.append(sep)

    if periods:
        lines.append("")
        lines.append("  Batch Analysis by Period:")
        lines.append(f"  {'Period':<12} {'C':>4} {'+Ln':>7} {'-Ln':>7} {'Rec':>5} {'AI':>5} {'DelH':>5} {'Q':>5}  Band")
        lines.append("  " + "-" * 62)
        for p in periods:
            lines.append(
                f"  {p['period']:<12} {p['commits']:>4} {p['add']:>7} {p['del']:>7} "
                f"{p['recency']:>5.2f} {p['ai']:>5.2f} {p['deletion']:>5.2f} {p['composite']:>5.2f}  {p['band']}"
            )
        lines.append("")

    with open(output_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return output_path

# ---------------------------------------------------------------------------
# Quality CSV writer
# ---------------------------------------------------------------------------

def write_quality_csv(q: Dict, periods: List[Dict], output_path: str):
    with open(output_path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)

        # summary section
        w.writerow(["Metric", "Value"])
        for k, v in q.items():
            if k == "scores":
                for sk, sv in v.items():
                    w.writerow([f"score.{sk}", sv])
            elif k == "band_color":
                continue
            else:
                w.writerow([k, v])

        if periods:
            w.writerow([])
            w.writerow(["Batch Analysis"])
            headers = ["Period", "Commits", "Added", "Deleted", "Recency", "AI", "Deletion", "Composite", "Band"]
            w.writerow(headers)
            for p in periods:
                w.writerow([p["period"], p["commits"], p["add"], p["del"],
                           p["recency"], p["ai"], p["deletion"], p["composite"], p["band"]])
    return output_path

# ---------------------------------------------------------------------------
# Main orchestrator
# ---------------------------------------------------------------------------

def parse_args():
    p = argparse.ArgumentParser(description="Git Stats & Quality Analyzer")
    p.add_argument("repo_path", nargs="?", default=os.getcwd(),
                   help="Path to git repository (default: current directory)")
    p.add_argument("--threads", "-t", type=int, default=0,
                   help="Parallel worker threads (0=auto, default: 0)")
    p.add_argument("--batch-size", type=int, default=50,
                   help="Commits per batch for thread scheduling (default: 50)")
    p.add_argument("--window", "-w", type=int, default=5,
                   help="Moving average window (default: 5, 0 to disable)")
    p.add_argument("--formula", "-f", type=str, default=None,
                   help='Custom formula (net_ratio|efficiency|churn|expr)')
    p.add_argument("--output", "-o", type=str, default=None,
                   help="Output CSV path (default: auto-generated)")
    p.add_argument("--no-threads", action="store_true",
                   help="Disable multi-threading even for 100+ commits")
    p.add_argument("--quality", "-q", action="store_true",
                   help="Enable 4-factor quality analysis")
    p.add_argument("--batch", choices=["month", "week", "quarter", "year"],
                   default=None, help="Enable per-period quality batch analysis")
    p.add_argument("--report", "-r", type=str, default=None,
                   help="Text report output path (default: auto-name)")
    p.add_argument("--quality-params", type=str, default=None,
                   help="JSON file with custom quality formula parameters")
    return p.parse_args()


def main():
    if len(sys.argv) > 2 and sys.argv[1] == "--formula" and sys.argv[2] in ("list", "--help", "-h"):
        list_builtin_formulas()
        sys.exit(0)

    args = parse_args()
    repo = args.repo_path

    console.print()
    console.print("[default]Git Commit Statistics Analyzer by Gradum[/default]")
    console.print("[default][dim]Version: 1.0.0[/dim][/default]")
    console.print()

    if not os.path.exists(repo):
        console.print(f"[yellow]{ICON_WARNING}  Directory does not exist: {repo}[/yellow]")
        sys.exit(1)
    if not os.path.exists(os.path.join(repo, ".git")):
        console.print(f"[yellow]{ICON_WARNING}  Not a valid Git repository: {repo}[/yellow]")
        sys.exit(1)

    # ---------- fetch commit list ----------
    with console.status("Fetching commit history...", spinner="dots"):
        commits = _run(repo, 'git log --reverse --format="%H"')

    if not commits:
        console.print(f"[yellow]{ICON_WARNING}  No commits found[/yellow]")
        sys.exit(1)

    commits_list = commits.strip().split("\n")
    total_commits = len(commits_list)

    console.print(f"[dim]{ICON_START}[/dim] Start investigating Git data")
    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[default][blue]{ICON_STEP}[/blue] Found {total_commits} commit records in {get_repo_name(repo)}[/default]")

    use_threads = not args.no_threads
    num_threads = args.threads if args.threads > 0 else min(4, os.cpu_count() or 2)
    should_parallel = total_commits >= 100 and use_threads

    if should_parallel:
        console.print(f"[dim]{ICON_ARROW}[/dim]")
        console.print(f"[default][blue]{ICON_POINT}[/blue] Using {num_threads} threads for parallel processing[/default]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")

    with console.status("Fetching commit dates...", spinner="dots"):
        commit_dates = get_commit_dates(repo)

    # ---------- gather per-commit stats ----------
    stats_results = {}

    if should_parallel:
        with Progress(
                SpinnerColumn(spinner_name="blink", style="default"),
                TextColumn("{task.description}"),
                console=console, transient=True,
        ) as progress:
            task = progress.add_task("", total=total_commits)
            completed = 0
            with concurrent.futures.ThreadPoolExecutor(max_workers=num_threads) as executor:
                f2h = {executor.submit(get_commit_stats, repo, h): h for h in commits_list}
                for future in concurrent.futures.as_completed(f2h):
                    h = f2h[future]
                    a, d = future.result()
                    stats_results[h] = (a, d)
                    completed += 1
                    pct = int(completed * 100 / total_commits)
                    progress.update(task, advance=1,
                                    description=f"Read {pct}%, remaining {total_commits - completed} records")
    else:
        with Progress(
                SpinnerColumn(spinner_name="line", style="blue"),
                TextColumn("{task.description}"),
                console=console, transient=True,
        ) as progress:
            task = progress.add_task("", total=total_commits)
            for i, h in enumerate(commits_list):
                a, d = get_commit_stats(repo, h)
                stats_results[h] = (a, d)
                pct = int((i + 1) * 100 / total_commits)
                progress.update(task, advance=1,
                                description=f"Read {pct}%, remaining {total_commits - (i + 1)} records")

    # ---------- write per-commit CSV ----------
    csv_path = analyze_commits(args, commits_list, commit_dates, stats_results)
    console.print(f"[blue]{ICON_STEP}[/blue] [default]Statistics written to {csv_path}[/default]")

    # ---------- quality analysis ----------
    if args.quality:
        console.print(f"[dim]{ICON_ARROW}[/dim]")
        console.print(f"[default][blue]{ICON_POINT}[/blue] Running quality analysis...[/default]")

        params = dict(QUALITY_PARAMS)
        if args.quality_params and os.path.exists(args.quality_params):
            with open(args.quality_params) as f:
                params.update(json.load(f))

        # get full commit data for quality
        with console.status("Collecting quality data...", spinner="dots"):
            commits_full = get_commits_full(repo)

        now = datetime.now(timezone.utc)
        q = compute_quality(commits_full, params, now, get_repo_name(repo))
        periods = compute_quality_batch(commits_full, args.batch or "month", now, params) if args.batch else []

        # write quality CSV
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        q_csv = f"quality_{ts}.csv"
        write_quality_csv(q, periods, q_csv)
        console.print(f"[default][blue]{ICON_STEP}[/blue] Quality data written to {q_csv}[/default]")

        # write text report
        report_path = args.report or f"quality_report_{ts}.txt"
        write_report(q, periods, report_path)
        console.print(f"[default][blue]{ICON_STEP}[/blue] Report written to {report_path}[/default]")

        # show summary
        band, color = q["band"], q["band_color"]
        console.print()
        console.print(Panel(
            f"[bold]Overall Quality:[/bold] [{color}]{band}[/{color}] ({q['scores']['composite']:.2f})\n"
            f"[bold]Recency:[/bold] {q['scores']['recency']:.2f}  "
            f"[bold]Anti-AI:[/bold] {1 - q['scores']['suspicion']:.2f}  "
            f"[bold]Deletion Health:[/bold] {q['scores']['deletion_health']:.2f}  "
            f"[bold]Scale:[/bold] {q['scores']['scale']:.2f}",
            title="Quality Analysis Summary", border_style="blue",
        ))

    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[default][dim]{ICON_COMPLETE}[/dim] Complete[/default]")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        console.print(f"\n[yellow]{ICON_WARNING}  Analysis interrupted[/yellow]")
        sys.exit(1)
    except Exception as e:
        console.print(f"[yellow]{ICON_WARNING}  {e}[/yellow]")
        sys.exit(1)
