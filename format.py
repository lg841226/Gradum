"""Format log files to readable text output - Used as a module by agent.py"""

import json
from typing import Optional


def _get_tool_alias(tool_name: str) -> str:
    """Get human-readable alias for a tool name.
    
    Returns skill.alias if defined, otherwise returns tool_name.
    """
    try:
        from skills import Skills
        skills_manager = Skills()
        skill = skills_manager.get(tool_name)
        if skill and skill.alias:
            return skill.alias
    except Exception:
        pass
    
    return tool_name


def format_log_entry(entry: dict) -> Optional[str]:
    """Format log entry by type"""
    entry_type = entry.get("type", "unknown")
    timestamp = entry.get("timestamp", "")
    data = entry.get("data", {})

    # Handle _metadata type from agent.py
    if entry_type == "_metadata":
        return format_metadata(entry, timestamp)

    if entry_type == "thinking":
        return format_thinking(timestamp, data)

    if entry_type == "llm_response":
        return format_llm_response(timestamp, data)

    if entry_type == "tool_call_start":
        return format_tool_call_start(timestamp, data)

    if entry_type == "tool_call":
        return format_tool_call(timestamp, data)

    if entry_type == "final_response":
        return format_final_response(timestamp, data)

    return f"[{entry_type}] {timestamp}\n  (Unknown type)"


def format_metadata(entry: dict, timestamp: str = "") -> str:
    """Format metadata entry.
    
    Handles both jsonl format (with _metadata key) and agent.py format (with data dict).
    """
    # Handle jsonl format
    if "_metadata" in entry:
        meta = entry.get("_metadata", {})
        user_input = meta.get("user_input", "")
        start_time = meta.get("start_time", "")
        status = meta.get("status", "")
    # Handle agent.py format
    else:
        data = entry.get("data", {})
        user_input = data.get("user_input", "")
        start_time = data.get("start_time", timestamp)
        status = data.get("status", "")
    
    return f"[meta] {start_time}\n  user_input: {user_input}\n  status: {status}"


def format_thinking(timestamp: str, data: dict) -> str:
    """Format thinking entry"""
    content = data.get("content", "")
    return f"[think] {timestamp}\n  {content}"


def format_llm_response(timestamp: str, data: dict) -> str:
    """Format LLM response entry"""
    content = data.get("content", "")
    return f"[llm_response] {timestamp}\n  {content}"


def format_tool_call_start(timestamp: str, data: dict) -> str:
    """Format tool call start entry"""
    tool = data.get("tool", "")
    arguments = data.get("arguments", {})
    alias = _get_tool_alias(tool)
    
    lines = [f"[{alias} → running] {timestamp}"]
    _append_tool_arguments(lines, tool, arguments)
    return "\n".join(lines)


def format_tool_call(timestamp: str, data: dict) -> str:
    """Format tool call entry"""
    tool = data.get("tool", "")
    arguments = data.get("arguments", {})
    success = data.get("success", False)
    result = data.get("result", "")

    result_str = "Success" if success else "Error"
    alias = _get_tool_alias(tool)

    lines = [f"[{alias}] {timestamp}"]
    _append_tool_arguments(lines, tool, arguments)
    lines.append(f"  result: {result_str}")
    
    if result and tool != "read_file":
        if '\n' in result:
            lines.append(f"  output:\n{result}")
        else:
            lines.append(f"  output: {result}")

    return "\n".join(lines)


def _append_tool_arguments(lines: list, tool: str, arguments: dict) -> None:
    """Helper to append tool arguments to lines list."""
    if tool == "run_command":
        cmd = arguments.get("command", "")
        reason = arguments.get("reason", "")
        lines.append(f"  cmd: {cmd}")
        if reason:
            lines.append(f"  reason: {reason}")

    elif tool == "search":
        keyword = arguments.get("keyword", "")
        keywords = arguments.get("keywords", [])
        if keyword:
            lines.append(f"  keyword: {keyword}")
        if keywords:
            lines.append(f"  keywords: {', '.join(keywords)}")

    elif tool == "read_file":
        path = arguments.get("path", "")
        line_range = arguments.get("line_range", "")
        lines.append(f"  path: {path}")
        if line_range:
            lines.append(f"  line_range: {line_range}")

    elif tool == "edit_file":
        path = arguments.get("path", "")
        edits = arguments.get("edits", [])
        search = arguments.get("search", "")
        replace = arguments.get("replace", "")
        lines.append(f"  path: {path}")
        if edits:
            lines.append(f"  edits: {len(edits)} changes")
        elif search:
            lines.append(f"  search: {search}")
            lines.append(f"  replace: {replace}")

    elif tool == "save_file":
        path = arguments.get("path", "")
        lines.append(f"  path: {path}")

    elif tool == "search_codebase":
        query = arguments.get("query", arguments.get("information_request", ""))
        lines.append(f"  query: {query}")

    else:
        for key, value in arguments.items():
            if value and key not in ("reason", "command"):
                lines.append(f"  {key}: {value}")


def format_final_response(timestamp: str, data: dict) -> str:
    """Format final response entry"""
    elapsed = data.get("elapsed_seconds", 0)
    token_usage = data.get("token_usage", {})

    result = f"[final] {timestamp}\n  elapsed: {elapsed}s"

    if token_usage:
        prompt = token_usage.get("prompt_tokens", 0)
        completion = token_usage.get("completion_tokens", 0)
        total = token_usage.get("total_tokens", 0)
        result += f"\n  tokens: prompt={prompt}, completion={completion}, total={total}"

    return result
