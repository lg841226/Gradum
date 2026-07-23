#  Copyright (c) 2026 Gradum team, some rights reserved.
#  For licensing terms and conditions, see the MIT LICENSE file.
#
#  git_stats.py  2026-07-22 23:48:13 Changed by gwy
#
#  git_stats.py  2026-07-22 23:48:03 Changed by gwy
#
#  git_stats.py  2026-07-22 23:08:15 Changed by gwy
#
#  git_stats.py  2026-07-22 16:59:58 Changed by gwy
#
#  git_stats.py  2026-07-22 16:59:40 Changed by gwy
#
#  git_stats.py  2026-07-22 14:50:19 Changed by gwy
#
#  git_stats.py  2026-07-22 14:02:10 Changed by gwy

import argparse
import concurrent.futures
import csv
import hashlib
import json
import math
import os
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
import zipfile
from collections import defaultdict, deque
from datetime import datetime, timezone
from rich.console import Console
from rich.markup import escape
from rich.progress import Progress, SpinnerColumn, TextColumn
from rich.spinner import SPINNERS
from typing import Dict, List, Optional, Tuple

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
        r = subprocess.run(cmd, cwd=repo, shell=True, capture_output=True, text=True, check=True)
        return r.stdout.strip()
    except subprocess.CalledProcessError:
        return None


def _parse_numstat(numstat: str) -> Tuple[int, int]:
    lines = [ln.strip() for ln in numstat.strip().split("\n") if ln.strip()]
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


def commit_stats(repo_path: str, hash_: str) -> Tuple[int, int]:
    out = _git(repo_path, f'git show --numstat --format="" {hash_}')
    return _parse_numstat(out) if out else (0, 0)


def commit_dates(repo_path: str) -> Dict[str, Tuple[str, str]]:
    out = _git(repo_path, 'git log --reverse --format="%H||%ai"')
    if not out:
        return {}
    result = {}
    for line in out.split("\n"):
        if "||" not in line:
            continue
        h, rest = line.split("||", 1)
        parts = rest.split()
        date = parts[0] if parts else ""
        time_str = parts[1].split("+")[0] if len(parts) > 1 else ""
        result[h] = (date, time_str)
    return result


def all_commits(repo_path: str, on_commit=None) -> List[Dict]:
    total = 0
    try:
        raw = _git(repo_path, 'git rev-list --count HEAD')
        if raw:
            total = int(raw.strip())
    except (ValueError, AttributeError):
        pass

    proc = subprocess.Popen(
        'git log -c --numstat --reverse --format="%H||%cI||%s"',
        cwd=repo_path, shell=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        text=True, bufsize=1,
    )

    entries = []
    current_hash = current_iso = current_subject = None
    numstat_lines = []
    idx = 0

    for ln in proc.stdout:
        ln = ln.rstrip("\n")
        if "||" in ln and len(ln) > 40:
            if current_hash:
                idx += 1
                dt = datetime.fromisoformat(current_iso.replace("Z", "+00:00"))
                additions, deletions = _parse_numstat("\n".join(numstat_lines))
                entries.append({"hash": current_hash[:8], "date": dt, "additions": additions, "deletions": deletions,
                                "files_changed": len(numstat_lines)})
                if on_commit:
                    on_commit(current_hash[:8], current_subject, idx, total)
                numstat_lines.clear()
            current_hash, current_iso, current_subject = ln.split("||", 2)
        elif ln and "\t" in ln:
            numstat_lines.append(ln)

    if current_hash:
        idx += 1
        dt = datetime.fromisoformat(current_iso.replace("Z", "+00:00"))
        additions, deletions = _parse_numstat("\n".join(numstat_lines))
        entries.append({"hash": current_hash[:8], "date": dt, "additions": additions, "deletions": deletions,
                        "files_changed": len(numstat_lines)})
        if on_commit:
            on_commit(current_hash[:8], current_subject, idx, total)

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


def build_formula(args) -> Tuple[Optional[str], Optional[str]]:
    if not args.formula:
        return None, None
    if args.formula in BUILTIN_FORMULAS:
        return BUILTIN_FORMULAS[args.formula], args.formula
    return args.formula, "Custom Formula"


# Custom formulas let users define new CSV columns at export time (net_ratio,
# efficiency, churn, or arbitrary expressions) without modifying the code.
# SAFE_BUILTINS restricts eval to pure math — no I/O or system access.

def write_csv(args, hashes: List[str], dates: Dict, stats: Dict) -> str:
    path = args.output or f'git_stats_{datetime.now().strftime("%Y%m%d_%H%M%S")}.csv'
    expr, label = build_formula(args)

    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        headers = [
            "Index", "Commit Hash", "Date", "Time",
            "Additions", "Deletions", "Net Change",
            "Cumulative Add", "Cumulative Del", "Cumulative Net",
            "Total Lines", "Growth Rate (%)",
        ]
        if args.window > 0:
            headers.append("Moving Avg")
        if expr is not None:
            headers.append(label)
        w.writerow(headers)

        cum_add = cum_del = cum_net = 0
        first_commit = True
        first_cum_net = 0
        window = deque(maxlen=args.window) if args.window > 0 else None
        moving_avg = 0

        for i, hash_ in enumerate(hashes):
            idx = i + 1
            add, delete = stats.get(hash_, (0, 0))
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

            date, time_str = dates.get(hash_, ("", ""))
            row = [idx, hash_[:8], date, time_str, add, delete, net,
                   cum_add, cum_del, cum_net, cum_total, growth_rate]

            if args.window > 0:
                window.append(net)
                moving_avg = round(sum(window) / len(window), 2)
                row.append(moving_avg)

            if expr is not None:
                vars_ = {
                    "add": add, "deletions": delete, "net": net,
                    "cum_add": cum_add, "cum_del": cum_del, "cum_net": cum_net,
                    "cum_total": cum_total, "count": idx, "growth_rate": growth_rate,
                    "moving_avg": moving_avg,
                }
                val = evaluate_formula(expr, vars_)
                row.append(val if val is not None else "")

            w.writerow(row)

    return path


# The CSV is the primary output format — it layers cumulative totals, growth
# rate, moving average, and optional custom formulas over the raw per-commit
# diff counts. Every column is a derived view of the same add/delete pairs.

QUALITY_PARAMS = {
    "recency_half_life_days": 90.0,
    "ai_additions_threshold": 500.0,
    "ai_additions_scale": 200.0,
    "ai_initiative_commits": 3,
    "ai_initiative_threshold": 20.0,
    "ai_initiative_scale": 10.0,
    "ai_repetition_cv_threshold": 0.5,
    "ai_repetition_scale": 0.15,
    "ai_focus_target": 3.0,
    "ai_focus_scale": 1.5,
    "firework_density_threshold": 5.0,
    "firework_active_days": 90.0,
    "deletion_ideal_ratio": 0.35,
    "deletion_sigma": 0.15,
    "scale_tiny": 100,
    "scale_small": 1000,
    "scale_medium": 10000,
    "scale_large": 100000,
    "tiny_commit_threshold": 10,
    "tiny_penalty_max": 0.25,
    "zombie_days": 730,
    "weight_recency": 0.34,
    "weight_anti_ai": 0.24,
    "weight_deletion_health": 0.26,
    "weight_scale": 0.16,
}


def _sigmoid(x: float) -> float:
    if x < -700:
        return 0.0
    if x > 700:
        return 1.0
    return 1.0 / (1.0 + math.exp(-x))


def _gaussian(x: float, mu: float, sigma: float) -> float:
    return math.exp(-((x - mu) ** 2) / (2 * sigma ** 2))


def quality_band(score: float) -> Tuple[str, str]:
    if score >= 0.8:
        return "Excellent", "purple"
    if score >= 0.6:
        return "Good", "cyan"
    if score >= 0.4:
        return "Fair", "green"
    if score >= 0.2:
        return "Needs Attention", "orange1"
    return "Caution", "red"


def _scale_score(total_lines: int, p: Dict) -> float:
    if total_lines < p["scale_tiny"]:
        return 0.1
    if total_lines < p["scale_small"]:
        return 0.3
    if total_lines < p["scale_medium"]:
        return 0.7
    if total_lines < p["scale_large"]:
        return 1.0
    return 0.8


def _confidence(total_commits: int) -> float:
    return 1.0 - 1.0 / (math.sqrt(total_commits) + 1.0)


def _project_seed(name: str) -> int:
    return int(hashlib.sha256(name.encode()).hexdigest()[:8], 16)


def _seeded_rand(seed: int) -> float:
    h = hashlib.sha256(str(seed).encode()).hexdigest()
    return int(h[:8], 16) / 0xFFFFFFFF


def compute_quality(entries: List[Dict], p: Dict, now: datetime, name: str = "") -> Dict:
    total = len(entries)
    if total == 0:
        return {}

    first = entries[0]["date"]
    last = entries[-1]["date"]
    active_days = max((last - first).total_seconds() / 86400.0, 0.1)
    days_since_last = (now - last).total_seconds() / 86400.0

    if days_since_last > p["zombie_days"]:
        return {
            "name": name,
            "total_commits": total,
            "active_days": round(active_days, 1),
            "days_since_last": round(days_since_last, 1),
            "band": "Archived",
            "band_color": "grey58",
            "scores": {"composite": 0.0},
        }

    total_additions = sum(e["additions"] for e in entries)
    total_deletions = sum(e["deletions"] for e in entries)
    total_lines = total_additions + total_deletions
    add_commits = sum(1 for e in entries if e["additions"] > 0)
    avg_additions = total_additions / max(add_commits, 1)
    total_deletions / max(add_commits, 1)
    commits_per_day = total / active_days

    recency = _gaussian(days_since_last, 0.0, p["recency_half_life_days"])

    ai_volume = _sigmoid((avg_additions - p["ai_additions_threshold"]) / p["ai_additions_scale"])

    n_init = min(p["ai_initiative_commits"], total)
    initial_adds = sum(e["additions"] for e in entries[:n_init])
    initial_dels = sum(e["deletions"] for e in entries[:n_init])
    initial_ratio = initial_adds / max(initial_dels, 1)
    ai_initiative = _sigmoid((initial_ratio - p["ai_initiative_threshold"]) / p["ai_initiative_scale"])

    if total >= 2:
        additions_list = [e["additions"] for e in entries]
        cv = statistics.stdev(additions_list) / max(statistics.mean(additions_list), 1)
    else:
        cv = 1.0
    ai_repetition = 1.0 - _sigmoid((cv - p["ai_repetition_cv_threshold"]) / p["ai_repetition_scale"])

    avg_files = statistics.mean([e["files_changed"] for e in entries]) if total > 0 else 0.0
    ai_focus = _sigmoid((p["ai_focus_target"] - avg_files) / p["ai_focus_scale"])

    firework = _sigmoid((commits_per_day - p["firework_density_threshold"]) / 2.0) * \
               _sigmoid((p["firework_active_days"] - active_days) / 20.0)
    deletion_health = _gaussian(total_deletions / max(total_additions, 1),
                                p["deletion_ideal_ratio"], p["deletion_sigma"]) \
        if total_additions > 0 else 0.5
    scale = _scale_score(total_lines, p)

    suspicion = (ai_volume + ai_initiative + ai_repetition + ai_focus + firework) / 5.0

    tiny_project = _sigmoid((p["tiny_commit_threshold"] - total) / 2.0) * p["tiny_penalty_max"]
    raw = (p["weight_recency"] * recency +
           p["weight_anti_ai"] * (1.0 - suspicion) +
           p["weight_deletion_health"] * deletion_health +
           p["weight_scale"] * scale) - tiny_project
    confidence = _confidence(total)
    baseline = 0.5
    adjusted = baseline + (raw - baseline) * confidence
    seed = _project_seed(name)
    personality = (_seeded_rand(seed) - 0.5) * 0.02 * confidence
    composite = max(0.0, min(1.0, adjusted + personality))
    band, band_color = quality_band(composite)

    return {
        "name": name,
        "total_commits": total,
        "active_days": round(active_days, 1),
        "days_since_last": round(days_since_last, 1),
        "first_commit": first.isoformat(),
        "last_commit": last.isoformat(),
        "total_additions": total_additions,
        "total_deletions": total_deletions,
        "total_lines": total_lines,
        "net_change": total_additions - total_deletions,
        "avg_additions_per_commit": round(avg_additions, 1),
        "commits_per_day": round(commits_per_day, 2),
        "churn_ratio": round(total_deletions / max(total_additions, 1), 3),
        "scores": {
            "recency": round(recency, 4),
            "ai_volume": round(ai_volume, 4),
            "ai_initiative": round(ai_initiative, 4),
            "ai_repetition": round(ai_repetition, 4),
            "ai_focus": round(ai_focus, 4),
            "firework": round(firework, 4),
            "tiny_project": round(tiny_project, 4),
            "suspicion": round(suspicion, 4),
            "deletion_health": round(deletion_health, 4),
            "scale": round(scale, 4),
            "composite": round(composite, 4),
        },
        "band": band,
        "band_color": band_color,
        "avg_files_changed": round(avg_files, 2),
    }


def compute_quality_periods(entries: List[Dict], unit: str, now: datetime, p: Dict) -> List[Dict]:
    buckets = defaultdict(list)
    for e in entries:
        commit_date = e["date"]
        if unit == "month":
            key = commit_date.strftime("%Y-%m")
        elif unit == "week":
            key = commit_date.strftime("%Y-W%V")
        elif unit == "quarter":
            key = f"{commit_date.year}-Q{(commit_date.month - 1) // 3 + 1}"
        elif unit == "year":
            key = commit_date.strftime("%Y")
        else:
            key = commit_date.strftime("%Y-%m")
        buckets[key].append(e)

    periods = []
    for label in sorted(buckets):
        group = buckets[label]
        additions = sum(e["additions"] for e in group)
        deletions = sum(e["deletions"] for e in group)
        count = len(group)
        span_days = max((group[-1]["date"] - group[0]["date"]).total_seconds() / 86400.0, 0.1)
        avg_additions = additions / max(sum(1 for e in group if e["additions"] > 0), 1)
        commits_per_day = count / span_days
        recency = _gaussian((now - group[-1]["date"]).total_seconds() / 86400.0, 0.0, p["recency_half_life_days"])
        ai_volume = _sigmoid((avg_additions - p["ai_additions_threshold"]) / p["ai_additions_scale"])
        firework = _sigmoid((commits_per_day - p["firework_density_threshold"]) / 2.0) * \
                   _sigmoid((p["firework_active_days"] - span_days) / 20.0)
        deletion_health = _gaussian(deletions / max(additions, 1), p["deletion_ideal_ratio"], p["deletion_sigma"]) \
            if additions > 0 else 0.5
        cum_lines = sum(e["additions"] + e["deletions"] for e in entries[:entries.index(group[-1]) + 1])
        scale = _scale_score(cum_lines, p)
        suspicion = max(ai_volume, firework)
        raw = (p["weight_recency"] * recency +
               p["weight_anti_ai"] * (1.0 - suspicion) +
               p["weight_deletion_health"] * deletion_health +
               p["weight_scale"] * scale)
        confidence = _confidence(count)
        baseline = 0.5
        adjusted = baseline + (raw - baseline) * confidence
        composite = max(0.0, min(1.0, adjusted))
        band, _ = quality_band(composite)
        periods.append({
            "period": label, "commits": count, "additions": additions, "deletions": deletions,
            "recency": round(recency, 3), "ai_volume": round(ai_volume, 3),
            "deletion": round(deletion_health, 3), "composite": round(composite, 3), "band": band,
        })
    return periods


# The 4-factor quality model scores repo health 0-1 across four independent
# dimensions and composites them into a single quality band. Understanding
# individual factor scores is more important than the composite — each one
# reveals a different failure mode: abandonment (recency), bot-like commits
# (anti-AI), copy-paste without cleanup (deletion health), or trivial scale
# (maturity). The batch variant shows how these factors trend over time.

SUSPICION_LABELS = {
    "clean": ("Clean", "green"),
    "normal": ("Normal", "cyan"),
    "watch": ("Watch", "yellow"),
    "alert": ("Alert", "orange1"),
    "critical": ("Critical", "red"),
}


def _load_core_configs() -> Tuple[set, set]:
    config_path = os.path.join(os.path.dirname(__file__), "configs.json")
    try:
        with open(config_path) as f:
            cfg = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        cfg = {}
    all_exts = set(cfg.get("code_extensions", []))
    non_prog = set(cfg.get("non_programming_languages", []))
    non_prog_exts = set()
    for ext, lang in cfg.get("language_mapping", {}).items():
        if lang in non_prog:
            non_prog_exts.add(ext)
    source_dirs = set(cfg.get("source_dirs", []))
    return all_exts - non_prog_exts, source_dirs


_CORE_EXTS, _SOURCE_DIRS = _load_core_configs()


def _changed_files(repo: str, hash_: str) -> List[str]:
    out = _git(repo, f'git show --name-only --format="" {hash_}')
    return out.strip().split("\n") if out else []


def _is_core_commit(repo: str, hash_: str) -> Tuple[bool, int, int]:
    files = _changed_files(repo, hash_)
    if not files:
        return False, 0, 0
    core_count = 0
    for f in files:
        ext = os.path.splitext(f)[1]
        if ext in _CORE_EXTS:
            parts = f.replace("\\", "/").split("/")
            in_source_dir = any(d in parts for d in _SOURCE_DIRS)
            if in_source_dir:
                core_count += 1
    return core_count > 0, core_count, len(files)


SUSPICION_THRESHOLDS = {
    "del_net": -200,
    "del_ratio": 0.05,
    "both_large": 500,
    "cluster_gap": 3,
    "cluster_min": 3,
    "mature_commits": 50,
    "recent_days": 90,
}


def audit_deletions(repo: str, entries: List[Dict], now: datetime, params: Optional[Dict] = None) -> Dict:
    thresholds = {**SUSPICION_THRESHOLDS, **(params or {})}
    total = len(entries)
    if total == 0:
        return {}

    enriched = []
    for i, entry in enumerate(entries):
        net = entry["additions"] - entry["deletions"]
        enriched.append({
            "index": i,
            "hash": entry["hash"],
            "date": entry["date"],
            "additions": entry["additions"],
            "deletions": entry["deletions"],
            "net": net,
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
                "hash": commit["hash"],
                "index": commit["index"],
                "net": commit["net"],
                "type": "Non-Core Deletion",
                "level": "watch",
                "note": f"Deleted {-commit['net']} lines in {total_files} files, only {core_count} are source — downgraded alert",
            })

        elif both_large:
            suspicion.append({
                "hash": commit["hash"],
                "index": commit["index"],
                "net": commit["net"],
                "type": "High-Risk Refactoring",
                "level": "critical",
                "note": f"+{commit['additions']} / -{commit['deletions']}, both exceed {thresholds['both_large']} — potential regression window",
            })
        else:
            suspicion.append({
                "hash": commit["hash"],
                "index": commit["index"],
                "net": commit["net"],
                "type": "Core Net Deletion",
                "level": "alert",
                "note": f"Core code net deletion of {-commit['net']} lines",
            })

    for i, cluster in enumerate(clusters):
        cluster_commits = [entry for entry in enriched if entry["index"] in cluster]
        total_del = sum(-entry["net"] for entry in cluster_commits if entry["net"] < 0)
        suspicion.append({
            "hash": f"Cluster #{i + 1}",
            "index": cluster[0],
            "net": -total_del,
            "type": "Deletion Cluster",
            "level": "alert",
            "note": f"{len(cluster)} adjacent commits deleting {total_del} net lines (positions {cluster[0]}-{cluster[-1]}) — possible repeated churn",
        })

    if total >= thresholds["mature_commits"]:
        recent_heavy = [commit for commit in heavy if commit["is_recent"]]
        if len(recent_heavy) >= thresholds["cluster_min"]:
            suspicion.append({
                "hash": "Recent Spike",
                "index": recent_heavy[-1]["index"],
                "net": sum(-entry["net"] for entry in recent_heavy),
                "type": "Mature Project Churn",
                "level": "alert",
                "note": f"Mature project ({total} commits) with {len(recent_heavy)} heavy net-deletions recently — feature instability?",
            })

    total_additions = sum(entry["additions"] for entry in entries)
    total_deletions = sum(entry["deletions"] for entry in entries)

    if total_additions == 0 and total_deletions == 0:
        ratio_label = "No Changes"
        ratio_level = "normal"
    elif total_deletions == 0:
        ratio_label = "Accumulation-Only"
        ratio_level = "alert"
    else:
        ratio = total_deletions / total_additions
        if ratio < 0.05:
            ratio_label = "Accumulation-Only"
            ratio_level = "alert"
        elif ratio < 0.25:
            ratio_label = "Low Cleanup"
            ratio_level = "normal"
        elif ratio < 0.50:
            ratio_label = "Balanced Churn"
            ratio_level = "clean"
        elif ratio < 1.0:
            ratio_label = "Heavy Churn"
            ratio_level = "watch"
        else:
            ratio_label = "Net Reduction"
            ratio_level = "critical"

    highest_level = "clean"
    for suspect in suspicion:
        level = suspect["level"]
        if level == "critical":
            highest_level = "critical"
        elif level == "alert" and highest_level not in ("critical",):
            highest_level = "alert"
        elif level == "watch" and highest_level not in ("critical", "alert"):
            highest_level = "watch"

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
        "suspicion": suspicion[:10],
        "overall_level": highest_level,
    }


def write_report(q: Dict, periods: List[Dict], audit: Dict, repo_path: str, csv_path: str, path: str) -> int:
    if q.get("band") == "Archived":
        lines = [
            "",
            f"  Project Quality Analysis Report",
            f"  Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
            "",
            f"  Project:         {q['name']}",
            f"  Commits:         {q['total_commits']}",
            f"  Period:          {q['active_days']} days",
            f"  Last commit:     {q['days_since_last']:.0f} days ago",
            "",
            f"  Status:  Archived — no activity in {q['days_since_last']:.0f} days",
            "",
        ]
    else:
        s = q["scores"]
        lines = []

        lines.append("")
        lines.append(f"  Project Quality Analysis Report")
        lines.append(f"  Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
        lines.append("")
        lines.append(f"  Project:         {q['name']}")
        lines.append(f"  Commits:         {q['total_commits']}")
        lines.append(f"  Period:          {q['active_days']} days")
        lines.append(f"  Last commit:     {q['days_since_last']} days ago")
        lines.append(f"  Quality:         {q['band']} ({s['composite']:.2f})")
        lines.append("")
        lines.append("")
        lines.append("  Factor Scores:")
        lines.append(f"    Recency                     {s['recency']:.2f}")
        lines.append(
            f"    Anti-AI Detection           {1 - s['suspicion']:.2f}  "
            f"(Volume: {s['ai_volume']:.2f}, "
            f"Initiative: {s['ai_initiative']:.2f}, "
            f"Repetition: {s['ai_repetition']:.2f}, "
            f"Focus: {s['ai_focus']:.2f}, "
            f"Burst: {s['firework']:.2f})")
        lines.append(f"    Deletion Health             {s['deletion_health']:.2f}")
        lines.append(f"    Scale Maturity              {s['scale']:.2f}")
        if s["tiny_project"] > 0:
            lines.append(f"    Tiny Project Penalty        -{s['tiny_project']:.2f}")
        lines.append("")
        lines.append("")
        lines.append("  Raw Metrics:")
        lines.append(f"    Total lines (add+del):     {q['total_lines']:,}")
        lines.append(f"    Additions:                 {q['total_additions']:,}")
        lines.append(f"    Deletions:                 {q['total_deletions']:,}")
        lines.append(f"    Net change:                {q['net_change']:+,}")
        lines.append(f"    Avg additions/commit:      {q['avg_additions_per_commit']}")
        lines.append(f"    Commit density:            {q['commits_per_day']} / day")
        lines.append(f"    Churn ratio (del/add):     {q['churn_ratio']}")
        lines.append(f"    Active days:               {q['active_days']:.0f}")
        lines.append(f"    First commit:              {q['first_commit']}")
        lines.append(f"    Last commit:               {q['last_commit']}")
        lines.append("")

        if periods:
            lines.append("")
            lines.append("  Batch Analysis by Period:")
            lines.append(
                f"  {'Period':<12} {'C':>4} {'+Ln':>7} {'-Ln':>7} {'Rec':>5} {'Vol':>5} {'DelH':>5} {'Q':>5}  Band")
            lines.append("  " + "-" * 62)
            for p in periods:
                lines.append(
                    f"  {p['period']:<12} {p['commits']:>4} {p['additions']:>7} {p['deletions']:>7} "
                    f"{p['recency']:>5.2f} {p['ai_volume']:>5.2f} {p['deletion']:>5.2f} {p['composite']:>5.2f}  {p['band']}"
                )
            lines.append("")

    tmpdir = tempfile.mkdtemp()
    detail_count = 0
    try:
        with open(os.path.join(tmpdir, "report.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")

        if csv_path and os.path.exists(csv_path):
            shutil.copy2(csv_path, os.path.join(tmpdir, os.path.basename(csv_path)))

        detail_dir = os.path.join(tmpdir, "details")
        os.makedirs(detail_dir, exist_ok=True)

        for suspect in audit.get("suspicion", []):
            if not suspect["hash"] or "/" in suspect["hash"]:
                continue
            commit_hash = suspect["hash"]
            safe_name = commit_hash.replace("/", "_")
            raw = _git(repo_path, f'git show {commit_hash}')
            if raw is None:
                raw = "[error: commit not found]"
            with open(os.path.join(detail_dir, f"{safe_name}_details.txt"), "w", encoding="utf-8") as f:
                f.write(raw + "\n")
            detail_count += 1

        zip_path = path
        if not zip_path.endswith(".zip"):
            zip_path += ".zip"
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
            zf.write(os.path.join(tmpdir, "report.txt"), "report.txt")
            if csv_path and os.path.exists(os.path.join(tmpdir, os.path.basename(csv_path))):
                zf.write(os.path.join(tmpdir, os.path.basename(csv_path)), os.path.basename(csv_path))
            if os.path.isdir(detail_dir):
                for fn in sorted(os.listdir(detail_dir)):
                    fpath = os.path.join(detail_dir, fn)
                    zf.write(fpath, f"details/{fn}")
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    return detail_count


# The report is a human-readable snapshot of the quality analysis — used for
# sharing results outside the CLI or keeping a historical record.

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

    with console.status("Fetching commit history...", spinner="dots"):
        raw = _git(repo, 'git log --reverse --format="%H"')

    if not raw:
        console.print(f"[yellow]{ICON_WARNING}  No commits found[/yellow]")
        sys.exit(1)

    hashes = raw.strip().split("\n")
    total = len(hashes)

    console.print(f"[dim]{ICON_START}[/dim] Start investigating Git data")
    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[default][blue]{ICON_STEP}[/blue] Found {total} commit records in {repo_name(repo)}[/default]")

    use_threads = not args.no_threads
    num_threads = args.threads if args.threads > 0 else min(8, os.cpu_count() or 4)
    parallel = total >= 100 and use_threads

    if parallel:
        console.print(f"[dim]{ICON_ARROW}[/dim]")
        console.print(
            f"[default][blue]{ICON_POINT}[/blue] Using {num_threads} threads for parallel processing[/default]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")

    with console.status("Fetching commit dates...", spinner="dots"):
        dates = commit_dates(repo)

    scan_start = time.time()
    stats = {}

    with Progress(
            SpinnerColumn(spinner_name="blink", style="default"),
            TextColumn("{task.description}"),
            console=console, transient=True,
    ) as progress:
        task = progress.add_task("", total=total)

        if parallel:
            done = 0
            with concurrent.futures.ThreadPoolExecutor(max_workers=num_threads) as executor:
                f2h = {executor.submit(commit_stats, repo, h): h for h in hashes}
                for future in concurrent.futures.as_completed(f2h):
                    h = f2h[future]
                    a, d = future.result()
                    stats[h] = (a, d)
                    done += 1
                    pct = int(done * 100 / total)
                    progress.update(task, advance=1,
                                    description=f"Read {pct}%, remaining {total - done} records")
        else:
            for i, h in enumerate(hashes):
                a, d = commit_stats(repo, h)
                stats[h] = (a, d)
                pct = int((i + 1) * 100 / total)
                progress.update(task, advance=1,
                                description=f"Read {pct}%, remaining {total - (i + 1)} records")

    elapsed = time.time() - scan_start
    minutes = int(elapsed // 60)
    seconds = int(elapsed % 60)
    if minutes > 0:
        scan_time = f"{minutes}m {seconds}s"
    else:
        scan_time = f"{seconds}s"
    console.print(f"[blue]{ICON_STEP}[/blue] [default]Scanned for {scan_time}[/default]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")

    csv_path = write_csv(args, hashes, dates, stats)
    console.print(f"[blue]{ICON_STEP}[/blue] [default]Statistics written to {csv_path}[/default]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")

    if args.quality:
        p = dict(QUALITY_PARAMS)
        if args.quality_params and os.path.exists(args.quality_params):
            with open(args.quality_params) as f:
                p.update(json.load(f))

        with Progress(
                SpinnerColumn(spinner_name="blink", style="default"),
                TextColumn("[default]{task.description}[/default]"),
                console=console, transient=True,
        ) as progress:
            task = progress.add_task("Analyzing commits...", total=None)
            # noinspection bad-argument-type
            entries = all_commits(
                repo,
                on_commit=lambda h, s, i, t: progress.update(
                    task, description=f"Analyzing {i}/{t} {escape(h)}: {escape(s[:60])}"
                )
            )

            now = datetime.now(timezone.utc)
            q = compute_quality(entries, p, now, repo_name(repo))

            if q.get("band") == "Archived":
                progress.update(task, description=f"Archived — no commits in {q['days_since_last']:.0f} days")
                audit = {"suspicion_count": 0, "deletion_percent": 0, "heavy_deletions": 0, "suspicion": []}
                periods = []
            else:
                progress.update(task, description="Computing quality metrics...")
                periods = compute_quality_periods(entries, args.batch or "month", now, p) if args.batch else []

                progress.update(task, description="Scanning deletion patterns...")
                audit = audit_deletions(repo, entries, now)

        if q.get("band") == "Archived":
            console.print(
                f"[grey58]{ICON_STEP}[/grey58] [default]Archived — no commits in {q['days_since_last']:.0f} days[/default]")
        else:
            console.print(
                f"[blue]{ICON_STEP}[/blue] [default]Analyzed {len(entries)} commits, {audit['suspicion_count']} suspicious patterns[/default]"
            )
            console.print(f"[dim]{ICON_ARROW}[/dim]")
            console.print(f"[dim]{ICON_ARROW}[/dim]")

            ts = datetime.now().strftime("%Y%m%d_%H%M%S")
            report_path = args.report or f"quality_report_{ts}.zip"
            detail_count = write_report(q, periods, audit, repo, csv_path, report_path)
            console.print(f"[blue]{ICON_POINT}[/blue] [default]Packaged {detail_count} detail files + 1 CSV[/default]")
            console.print(f"[dim]{ICON_ARROW}[/dim]")
            console.print(f"[dim]{ICON_ARROW}[/dim]")

            if os.path.exists(csv_path):
                os.remove(csv_path)

            console.print(f"[blue]{ICON_STEP}[/blue] [default]Deletion audit results[/default]")
            console.print(f"[dim]{ICON_ARROW}[/dim]")
            console.print(f"[dim]{ICON_ARROW}[/dim]")

            _SEVERITY = {"critical": 0, "alert": 1, "watch": 2, "normal": 3, "clean": 4}
            _ABBREV = {"critical": "C", "alert": "A", "watch": "W", "normal": "N", "clean": "L"}

            q_band = q["band"]
            q_color = q["band_color"]
            console.print(
                f"[{q_color}]{ICON_STEP}[/{q_color}] "
                f"[default]Quality: Add/Del rate {audit['deletion_percent']}% · "
                f"{audit['heavy_deletions']} problems[/default] · "
                f"[{q_color}]{q_band}[/{q_color}]"
            )

            top = sorted(audit["suspicion"], key=lambda s: _SEVERITY.get(s["level"], 99))[:4]
            if top:
                parts = []
                for s in top:
                    _, lvl_color = SUSPICION_LABELS.get(s["level"], ("", "default"))
                    parts.append(
                        f"[{lvl_color}]{s['hash']} [{_ABBREV.get(s['level'], '?')}][/{lvl_color}]"
                    )
                console.print(f"[dim]{ICON_ARROW}       ⎿  {'  '.join(parts)}[/dim]")
                remaining = max(0, audit["suspicion_count"] - 4)
                if remaining > 0:
                    console.print(f"[dim]{ICON_ARROW}          [/dim][default]+{remaining} more...[/default]")

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
    except Exception as e:
        console.print(f"[yellow]{ICON_WARNING}  {e}[/yellow]")
        sys.exit(1)
