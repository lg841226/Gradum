"""Format log files to readable text output - Used as a module by agent.py"""

from typing import Optional

from skills import Skills


def _get_tool_alias(tool_name: str) -> str:
    """Get human-readable alias for a tool name."""
    try:
        skills_manager = Skills()
        skill = skills_manager.get(tool_name)
        if skill and skill.alias:
            return skill.alias
    except (AttributeError, KeyError):
        pass

    return tool_name


def _format_entry_with_content(prefix: str, timestamp: str, entry_data: dict) -> str:
    """Helper to format simple entries with a content field."""
    content = entry_data.get("content", "")
    return f"[{prefix}] {timestamp}\n  {content}"


def format_log_entry(entry: dict) -> Optional[str]:
    """Format log entry by type"""
    entry_type = entry.get("type", "unknown")
    timestamp = entry.get("timestamp", "")
    entry_data = entry.get("data", {})

    if entry_type == "_metadata":
        return format_metadata(entry, timestamp)

    if entry_type == "thinking":
        return _format_entry_with_content("think", timestamp, entry_data)

    if entry_type == "llm_response":
        return _format_entry_with_content("llm_response", timestamp, entry_data)

    if entry_type == "tool_call":
        return format_tool_call(timestamp, entry_data)

    if entry_type == "final_response":
        return format_final_response(timestamp, entry_data)

    if entry_type == "final_llm_response":
        return _format_entry_with_content("llm_response", timestamp, entry_data)

    if entry_type == "info":
        return _format_entry_with_content("info", timestamp, entry_data)

    if entry_type == "warning":
        return _format_entry_with_content("warning", timestamp, entry_data)

    if entry_type == "error":
        return _format_entry_with_content("error", timestamp, entry_data)

    if entry_type == "context_loaded":
        message_count = entry_data.get("message_count", 0)
        status = entry_data.get("status", "unknown")
        status_text = "loaded" if status == "success" else "no context found"
        return f"[Context] {timestamp}\n  status: {status_text}\n  messages: {message_count}"

    return f"[{entry_type}] {timestamp}\n  (Unknown type)"


def format_metadata(entry: dict, timestamp: str = "") -> str:
    """Format metadata entry.
    
    Handles both JSONL format (with _metadata key) and agent.py format (with data dict).
    """
    if "_metadata" in entry:
        metadata_dict = entry.get("_metadata", {})
        user_input = metadata_dict.get("user_input", "")
        start_time = metadata_dict.get("start_time", "")
        status = metadata_dict.get("status", "")
    else:
        entry_data = entry.get("data", {})
        user_input = entry_data.get("user_input", "")
        start_time = entry_data.get("start_time", timestamp)
        status = entry_data.get("status", "")

    return f"[meta] {start_time}\n  user_input: {user_input}\n  status: {status}"


def format_tool_call_start(timestamp: str, entry_data: dict) -> str:
    """Format tool call start entry"""
    tool_name = entry_data.get("tool", "")
    arguments = entry_data.get("arguments", {})
    alias = _get_tool_alias(tool_name)

    output_lines = [f"[{alias}] {timestamp}"]
    _append_tool_arguments(output_lines, tool_name, arguments)
    return "\n".join(output_lines)


def format_tool_call(timestamp: str, entry_data: dict) -> str:
    """Format tool call entry"""
    tool_name = entry_data.get("tool", "")
    arguments = entry_data.get("arguments", {})
    success = entry_data.get("success", False)
    result = entry_data.get("result", "")

    result_str = "Success" if success else "Error"
    alias = _get_tool_alias(tool_name)

    output_lines = [f"[{alias}] {timestamp}"]
    _append_tool_arguments(output_lines, tool_name, arguments)
    output_lines.append(f"  result: {result_str}")
    
    if result and tool_name != "read_file":
        # Show full result without truncation
        if '\n' in result:
            output_lines.append(f"  output:\n{result}")
        else:
            output_lines.append(f"  output: {result}")

    return "\n".join(output_lines)


def _append_tool_arguments(output_lines: list, tool_name: str, arguments: dict) -> None:
    """Helper to append tool arguments to output lines list."""
    if tool_name == "run_cmd":
        _append_run_cmd_arguments(output_lines, arguments)

    elif tool_name == "search":
        _append_search_arguments(output_lines, arguments)

    elif tool_name == "read_file":
        _append_read_file_arguments(output_lines, arguments)

    elif tool_name == "edit_file":
        _append_edit_file_arguments(output_lines, arguments)

    elif tool_name == "save_file":
        _append_save_file_arguments(output_lines, arguments)

    elif tool_name == "search_codebase":
        _append_search_codebase_arguments(output_lines, arguments)

    else:
        _append_generic_arguments(output_lines, arguments)


def _append_run_cmd_arguments(output_lines: list, arguments: dict) -> None:
    """Append arguments for run_cmd tool."""
    command = arguments.get("command", "")
    reason = arguments.get("reason", "")
    output_lines.append(f"  cmd: {command}")
    if reason:
        output_lines.append(f"  reason: {reason}")


def _append_search_arguments(output_lines: list, arguments: dict) -> None:
    """Append arguments for search tool."""
    keyword = arguments.get("keyword", "")
    keywords = arguments.get("keywords", [])
    filename = arguments.get("filename", "")
    dirname = arguments.get("dirname", "")
    recursive = arguments.get("recursive", False)
    file_pattern = arguments.get("file_pattern", "")

    if keyword:
        output_lines.append(f"  keyword: {keyword}")
    if keywords:
        output_lines.append(f"  keywords: {', '.join(keywords)}")
    if filename:
        output_lines.append(f"  filename: {filename}")
    if dirname:
        output_lines.append(f"  dirname: {dirname}")
    if recursive:
        output_lines.append(f"  recursive: {recursive}")
    if file_pattern:
        output_lines.append(f"  file_pattern: {file_pattern}")


def _append_read_file_arguments(output_lines: list, arguments: dict) -> None:
    """Append arguments for read_file tool."""
    file_path = arguments.get("path", "")
    line_range = arguments.get("line_range", "")
    output_lines.append(f"  path: {file_path}")
    if line_range:
        output_lines.append(f"  line_range: {line_range}")


def _append_edit_file_arguments(output_lines: list, arguments: dict) -> None:
    """Append arguments for edit_file tool."""
    file_path = arguments.get("path", "")
    edits = arguments.get("edits", [])
    search_text = arguments.get("search", "")
    replace_text = arguments.get("replace", "")
    output_lines.append(f"  path: {file_path}")
    if edits:
        output_lines.append(f"  edits: {len(edits)} changes")
    elif search_text:
        output_lines.append(f"  search: {search_text}")
        output_lines.append(f"  replace: {replace_text}")


def _append_save_file_arguments(output_lines: list, arguments: dict) -> None:
    """Append arguments for save_file tool."""
    file_path = arguments.get("path", "")
    output_lines.append(f"  path: {file_path}")


def _append_search_codebase_arguments(output_lines: list, arguments: dict) -> None:
    """Append arguments for search_codebase tool."""
    query = arguments.get("query", arguments.get("information_request", ""))
    output_lines.append(f"  query: {query}")


def _append_generic_arguments(output_lines: list, arguments: dict) -> None:
    """Append arguments for generic tools."""
    for key, value in arguments.items():
        if value and key not in ("reason", "command"):
            output_lines.append(f"  {key}: {value}")


def format_final_response(timestamp: str, entry_data: dict) -> str:
    """Format final response entry"""
    elapsed = entry_data.get("elapsed_seconds", 0)
    token_usage = entry_data.get("token_usage", {})

    result = f"[end] {timestamp}\n  elapsed: {elapsed}s"

    if token_usage:
        prompt = token_usage.get("prompt_tokens", 0)
        completion = token_usage.get("completion_tokens", 0)
        total = token_usage.get("total_tokens", 0)
        result += f"\n  tokens: prompt={prompt}, completion={completion}, total={total}"

    return result
