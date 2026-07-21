#!/usr/bin/env python3
"""
Git Commit Statistics Analyzer with Rich Progress Display
Usage: ./git_stats.py [repository_path] [options]
"""

import argparse
import concurrent.futures
import csv
import os
import subprocess
import sys
import time
from collections import deque
from datetime import datetime

from rich.console import Console
from rich.progress import (
    Progress, SpinnerColumn, TextColumn, BarColumn,
    TaskProgressColumn, TimeElapsedColumn, TimeRemainingColumn
)

console = Console()

ICON_POINT = "⎡"
ICON_STEP = "●"
ICON_COMPLETE = "⎣"
ICON_WARNING = "▲"
ICON_ARROW = "│"

BUILTIN_FORMULAS = {
    "net_ratio": "(net / (add + deletions) * 100) if (add + deletions) > 0 else 0",
    "efficiency": "(net / max(add, deletions) * 100) if max(add, deletions) > 0 else 0",
    "churn": "((add + deletions) / cum_total * 100) if cum_total > 0 else 0",
}


def parse_args():
    parser = argparse.ArgumentParser(
        description="Git Commit Statistics Analyzer"
    )
    parser.add_argument(
        "repo_path", nargs="?", default=os.getcwd(),
        help="Path to git repository (default: current directory)"
    )
    parser.add_argument(
        "--threads", "-t", type=int, default=0,
        help="Number of parallel worker threads (0=auto based on CPU count, default: 0)"
    )
    parser.add_argument(
        "--batch-size", type=int, default=50,
        help="Commits per batch for thread scheduling (default: 50)"
    )
    parser.add_argument(
        "--window", "-w", type=int, default=5,
        help="Moving average window size (default: 5, 0 to disable)"
    )
    parser.add_argument(
        "--formula", "-f", type=str, default=None,
        help='Custom formula. Use a built-in name (net_ratio, efficiency, churn) '
             'or a Python expression. '
             'Available vars: add, deletions, net, cum_add, cum_del, cum_net, '
             'cum_total, count, growth_rate, moving_avg'
    )
    parser.add_argument(
        "--output", "-o", type=str, default=None,
        help="Output CSV file name (default: auto-generated)"
    )
    parser.add_argument(
        "--no-threads", action="store_true",
        help="Disable multi-threading even for 100+ commits"
    )
    return parser.parse_args()


def get_repo_name(repo_path):
    return os.path.basename(repo_path) or "Unknown"


def run_git_command(repo_path, command):
    try:
        result = subprocess.run(
            command, cwd=repo_path, shell=True,
            capture_output=True, text=True, check=True
        )
        return result.stdout.strip()
    except subprocess.CalledProcessError:
        return None


def get_commit_stats(repo_path, commit_hash):
    stats = run_git_command(repo_path, f'git show --numstat --format="" {commit_hash}')
    if not stats:
        return 0, 0
    additions = 0
    deletions = 0
    for line in stats.split('\n'):
        if line.strip():
            parts = line.split('\t')
            if len(parts) >= 2 and parts[0] != '-' and parts[1] != '-':
                try:
                    additions += int(parts[0])
                    deletions += int(parts[1])
                except ValueError:
                    pass
    return additions, deletions


def get_commit_dates(repo_path):
    output = run_git_command(repo_path, 'git log --reverse --format="%H||%ai"')
    if not output:
        return {}
    result = {}
    for line in output.split('\n'):
        if '||' in line:
            hash_val, rest = line.split('||', 1)
            parts = rest.split()
            date = parts[0] if parts else ''
            time_str = parts[1].split('+')[0] if len(parts) > 1 else ''
            result[hash_val] = (date, time_str)
    return result


SAFE_BUILTINS = {
    "abs": abs, "max": max, "min": min, "round": round,
    "sum": sum, "pow": pow, "int": int, "float": float,
}

def evaluate_formula(expr, vars_dict):
    try:
        result = eval(expr, {"__builtins__": SAFE_BUILTINS}, vars_dict)
        if isinstance(result, float):
            return round(result, 2)
        return result
    except Exception:
        return None


def list_builtin_formulas():
    console.print("[bold]Built-in formulas:[/bold]")
    for name, expr in BUILTIN_FORMULAS.items():
        console.print(f"  [green]{name}[/green]: {expr}")


def analyze_commits(args):
    repo_path = args.repo_path
    repo_name = get_repo_name(repo_path)
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    output_file = args.output or f'git_stats_{timestamp}.csv'

    if not os.path.exists(repo_path):
        console.print(f"[yellow]{ICON_WARNING}  Directory does not exist: {repo_path}[/yellow]")
        sys.exit(1)
    if not os.path.exists(os.path.join(repo_path, '.git')):
        console.print(f"[yellow]{ICON_WARNING}  Not a valid Git repository: {repo_path}[/yellow]")
        sys.exit(1)

    use_threads = not args.no_threads
    num_threads = args.threads if args.threads > 0 else min(8, os.cpu_count() or 4)

    with console.status("Fetching commit history...", spinner="dots"):
        commits = run_git_command(repo_path, 'git log --reverse --format="%H"')

    if not commits:
        console.print(f"[yellow]{ICON_WARNING}  No commits found[/yellow]")
        sys.exit(1)

    commits_list = commits.split('\n')
    total_commits = len(commits_list)

    console.print(f"[dim]{ICON_POINT}[/dim] Start investigating Git data")
    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[default][blue]{ICON_STEP}[/blue] Found {total_commits} commit records in {repo_name}[/default]")
    if total_commits >= 100 and use_threads:
        console.print(f"[dim]{ICON_ARROW}[/dim]")
        console.print(f"[default][blue]{ICON_STEP}[/blue] Using {num_threads} threads for parallel processing[/default]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")

    with console.status("Fetching commit dates...", spinner="dots"):
        commit_dates = get_commit_dates(repo_path)

    formula_expr = None
    formula_display = None
    if args.formula:
        if args.formula in BUILTIN_FORMULAS:
            formula_expr = BUILTIN_FORMULAS[args.formula]
            formula_display = args.formula
        else:
            formula_expr = args.formula
            formula_display = "Custom Formula"

    stats_results = {}
    should_use_parallel = total_commits >= 100 and use_threads

    if should_use_parallel:
        with Progress(
            SpinnerColumn(spinner_name="line", style="blue"),
            TextColumn("{task.description}"),
            BarColumn(),
            TaskProgressColumn(),
            TimeElapsedColumn(),
            TimeRemainingColumn(),
            console=console,
            transient=True,
        ) as progress:
            task = progress.add_task("", total=total_commits)
            completed = 0

            with concurrent.futures.ThreadPoolExecutor(max_workers=num_threads) as executor:
                future_to_hash = {
                    executor.submit(get_commit_stats, repo_path, h): h
                    for h in commits_list
                }

                for future in concurrent.futures.as_completed(future_to_hash):
                    hash_val = future_to_hash[future]
                    add, delete = future.result()
                    stats_results[hash_val] = (add, delete)
                    completed += 1
                    pct = int(completed * 100 / total_commits)
                    remaining = total_commits - completed
                    progress.update(
                        task, advance=1,
                        description=f"Read {pct}%, remaining {remaining} records"
                    )
    else:
        with Progress(
            SpinnerColumn(spinner_name="line", style="blue"),
            TextColumn("{task.description}"),
            console=console,
            transient=True,
        ) as progress:
            task = progress.add_task("", total=total_commits)
            for i, commit_hash in enumerate(commits_list):
                add, delete = get_commit_stats(repo_path, commit_hash)
                stats_results[commit_hash] = (add, delete)
                pct = int((i + 1) * 100 / total_commits)
                remaining = total_commits - (i + 1)
                progress.update(
                    task, advance=1,
                    description=f"Read {pct}%, remaining {remaining} records"
                )

    with open(output_file, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        headers = [
            'Index', 'Commit Hash', 'Date', 'Time',
            'Additions', 'Deletions', 'Net Change',
            'Cumulative Add', 'Cumulative Del', 'Cumulative Net',
            'Total Lines', 'Growth Rate (%)',
        ]
        if args.window > 0:
            headers.append('Moving Avg')
        if formula_expr is not None:
            headers.append(formula_display)
        writer.writerow(headers)

        cum_add = 0
        cum_del = 0
        cum_net = 0
        first_commit = True
        first_cum_net = 0
        moving_window = deque(maxlen=args.window) if args.window > 0 else None
        moving_avg = 0

        for i, commit_hash in enumerate(commits_list):
            count = i + 1
            add, delete = stats_results.get(commit_hash, (0, 0))
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

            date, time_str = commit_dates.get(commit_hash, ('', ''))

            row = [
                count, commit_hash[:8], date, time_str,
                add, delete, net,
                cum_add, cum_del, cum_net,
                cum_total, growth_rate,
            ]

            if args.window > 0:
                moving_window.append(net)
                moving_avg = round(sum(moving_window) / len(moving_window), 2)
                row.append(moving_avg)

            if formula_expr is not None:
                vars_dict = {
                    'add': add, 'deletions': delete, 'net': net,
                    'cum_add': cum_add, 'cum_del': cum_del, 'cum_net': cum_net,
                    'cum_total': cum_total, 'count': count, 'growth_rate': growth_rate,
                    'moving_avg': moving_avg,
                }
                result = evaluate_formula(formula_expr, vars_dict)
                row.append(result if result is not None else "")

            writer.writerow(row)

    console.print(f"[blue]{ICON_STEP}[/blue] [default]Read 100%, remaining 0 records[/default]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[default][blue]{ICON_STEP}[/blue] Validating data integrity, {total_commits} / {total_commits} completed[/default]")
    time.sleep(0.3)
    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[dim]{ICON_ARROW}[/dim]")
    console.print(f"[default][dim]{ICON_COMPLETE}[/dim] Complete, {output_file} is in {os.getcwd()}[/default]")


def main():
    if len(sys.argv) > 1 and sys.argv[1] == '--formula' and len(sys.argv) > 2 and sys.argv[2] in ('list', '--help', '-h'):
        list_builtin_formulas()
        sys.exit(0)

    args = parse_args()
    try:
        analyze_commits(args)
    except KeyboardInterrupt:
        console.print(f"\n[yellow]{ICON_WARNING}  Analysis interrupted by user[/yellow]")
        sys.exit(1)
    except Exception as error:
        console.print(f"[yellow]{ICON_WARNING}  {str(error)}[/yellow]")
        sys.exit(1)


if __name__ == "__main__":
    main()
