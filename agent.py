"""AI Agent with Function Calling capability."""

from __future__ import annotations

import argparse
import copy
import json
import platform
import time
from pathlib import Path
from typing import Any, Optional

from client import OllamaClient, AgentConfig, DEFAULT_MODEL, TIMEOUT
from format import format_log_entry
from skills import Skills
from skills.plan_task import get_plan_manager


class Agent:
    """AI Agent that coordinates skills and LLM communication."""

    MAX_CONTEXT_MESSAGES = 30

    def __init__(
        self,
        config: Optional[AgentConfig] = None,
        log_batch_size: int = 5,
        enable_realtime_log: bool = True,
    ):
        self.config = config or AgentConfig()
        self.client = OllamaClient(self.config)
        self.skills = Skills()
        self.messages: list[dict[str, Any]] = []
        self.output_file: Optional[Path] = None
        self._log_entries: list[dict] = []
        self._log_batch_size = log_batch_size
        self._log_write_counter = 0
        self._cached_tool_schemas: Optional[list[dict]] = None
        self._enable_realtime_log = enable_realtime_log
        self._setup_system_prompt()

    def _setup_system_prompt(self) -> None:
        prompt_path = Path(__file__).parent / "prompts" / "system_prompt.md"
        try:
            with open(prompt_path, "r", encoding="utf-8") as prompt_file:
                content = prompt_file.read()
                
        except FileNotFoundError:
            raise FileNotFoundError(
                f"System prompt file not found: {prompt_path}"
            )

        system = platform.system()
        if system == "Windows":
            os_info = (
                f"Current System- OS: Windows ({platform.release()})"
                f"- Use Windows commands"
            )
        elif system == "Darwin":
            os_info = (
                f"Current System- OS: macOS ({platform.release()})"
                f"- Use macOS/Linux commands"
            )
        else:
            os_info = f"Current System- OS: {system} ({platform.release()})"

        content += os_info
        self.messages.append({"role": "system", "content": content})

    def run(self, user_input: str, load_saved_context: bool = False) -> None:
        """Process user input and generate response with support for multistep tool calling."""
        start_time = time.time()

        self._init_run(user_input, load_saved_context)
        self.messages.append({"role": "user", "content": user_input})

        full_response = []

        while True:
            response_chunks, thinking_chunks, tool_calls = self._get_llm_response()

            if thinking_chunks:
                self._process_thinking(thinking_chunks)

            if response_chunks:
                full_response.extend(response_chunks)
                if tool_calls:
                    self._output_response(response_chunks)

            if not tool_calls:
                if full_response:
                    final_content = "".join(full_response)
                    self._output_final(final_content)
                    # Save assistant response to messages for context persistence
                    self.messages.append({"role": "assistant", "content": final_content})
                break

            for tool_call in tool_calls:
                self._execute_tool_call(tool_call, "".join(full_response))

            full_response = []

        self._finish_run(start_time, "".join(full_response))
        
        # Save context and log it
        self.save_context()
        
        self._flush_log_buffer()

        if self.output_file:
            with open(self.output_file, "a", encoding="utf-8", newline="\n") as file:
                file.flush()

    def _output_final(self, content: str) -> None:
        """Output final LLM response."""
        print(json.dumps({"type": "final_response", "content": content}, ensure_ascii=False))
        self._append_to_log("final_llm_response", {"content": content})

    def _init_run(self, user_input: str, load_saved_context: bool = False) -> None:
        """Initialize execution and output file."""
        output_dir = Path(__file__).parent / "output"

        try:
            output_dir.mkdir(parents=True, exist_ok=True)
        except (IOError, OSError) as e:
            print(json.dumps({
                "type": "error",
                "content": f"Could not create output directory: {output_dir} - {e}"
            }, ensure_ascii=False))
            raise RuntimeError(f"Could not create output directory: {output_dir}") from e

        self.output_file = output_dir / "log.txt"
        self._log_entries = []
        self._log_write_counter = 0
        self._cached_tool_schemas = None
        self._log_metadata = {
            "user_input": user_input,
            "start_time": time.strftime("%Y-%m-%d %H:%M:%S"),
            "status": "running",
        }

        try:
            with open(str(self.output_file), "w", encoding="utf-8"):
                pass
        except (IOError, OSError) as e:
            print(json.dumps({
                "type": "error",
                "content": f"Could not create log file: {self.output_file} - {e}"
            }, ensure_ascii=False))
            raise RuntimeError(f"Could not create log file: {self.output_file}") from e

        # Load context BEFORE writing metadata to ensure proper log order
        if load_saved_context:
            self.load_context()

        # Write metadata AFTER loading context so [info] appears before [meta]
        self._append_to_log("_metadata", self._log_metadata, force_write=True)

    def _get_llm_response(self) -> tuple[list[str], list[str], Optional[list]]:
        """Get response from LLM with streaming.

        Returns:
            Tuple of (response_chunks, thinking_chunks, tool_calls)
        """
        response_chunks = []
        thinking_chunks = []
        tool_calls = None

        if self._cached_tool_schemas is None:
            self._cached_tool_schemas = self.skills.get_schemas()

        for chunk, calls, thinking in self.client.chat(
            self.messages,
            tools=self._cached_tool_schemas,
            stream=True,
        ):
            response_chunks.append(chunk)

            if thinking:
                thinking_chunks.append(thinking)

            if calls and not tool_calls:
                tool_calls = calls

        return response_chunks, thinking_chunks, tool_calls

    def _process_thinking(self, thinking_chunks: list[str]) -> None:
        """Process and output thinking process."""
        content = "".join(thinking_chunks)
        print(json.dumps({"type": "thinking", "content": content}, ensure_ascii=False))
        self._append_to_log("thinking", {"content": content})

    def _output_response(self, response_chunks: list[str]) -> None:
        """Output real-time LLM response."""
        content = "".join(response_chunks)
        print(json.dumps({"type": "llm_response", "content": content}, ensure_ascii=False))
        self._append_to_log("llm_response", {"content": content})

    def _execute_tool_call(self, tool_call: dict, full_response: str) -> None:
        """Execute a single tool call and update messages."""
        func = tool_call.get("function", {})
        tool_name: str = func.get("name", "")
        arguments = func.get("arguments", {})

        self._append_to_log("tool_call_start", {
            "tool": tool_name,
            "arguments": arguments,
            "status": "running",
        })

        skill = self.skills.get(tool_name)

        if not skill:
            result = f"Error: Skill '{tool_name}' not found"
            tool_output = {
                "tool": tool_name,
                "arguments": arguments,
                "success": False,
                "result": result,
            }
        else:
            raw_result = skill.execute(**arguments)
            success = not (
                raw_result.startswith("Error:")
                or raw_result.startswith("Warning:")
                or raw_result.startswith("SECURITY ERROR:")
            )
            tool_output = {
                "tool": tool_name,
                "arguments": arguments,
                "success": success,
                "result": raw_result,
            }
            result = raw_result

        print(json.dumps(tool_output, ensure_ascii=False))
        self._append_to_log("tool_call", tool_output)

        self.messages.append({"role": "assistant", "content": full_response})
        self.messages.append({"role": "tool", "content": result})

        if skill and skill.name not in ("finish_to_do_item", "to_do"):
            reminder = get_plan_manager().get_reminder()
            if reminder:
                self.messages.append({"role": "tool", "content": reminder})

    def _finish_run(self, start_time: float, full_response: str) -> None:
        """Finalize execution."""
        elapsed = int(time.time() - start_time)
        token_stats = self.client.last_token_stats

        self._append_to_log("final_response", {
            "response": full_response,
            "elapsed_seconds": elapsed,
            "model": self.client.model,
            "token_usage": token_stats,
        })

        print(json.dumps({
            "response": full_response,
            "elapsed": elapsed,
            "model": self.client.model,
            "token_usage": token_stats,
        }, ensure_ascii=False))

        self.client.last_token_stats = None

    def _append_to_log(self, event_type: str, data: Any, force_write: bool = False) -> None:
        """Append event to log with real-time writing for critical events."""
        if self._enable_realtime_log and event_type in ("tool_call_start", "tool_call"):
            self._write_log_entry_immediately({
                "type": event_type,
                "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
                "data": data,
            })
        else:
            self._log_entries.append({
                "type": event_type,
                "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
                "data": data,
            })
            self._log_write_counter += 1

            if force_write or self._log_write_counter >= self._log_batch_size:
                self._flush_log_buffer()

    def _write_log_entry_immediately(self, event: dict) -> None:
        """Write a single log entry immediately for real-time feedback."""
        if not self.output_file:
            return

        formatted = format_log_entry(event)
        if formatted:
            with open(self.output_file, "a", encoding="utf-8", newline="\n") as file:
                file.write(formatted + "\n\n")
                file.flush()

    def _flush_log_buffer(self) -> None:
        """Flush buffered log entries to file."""
        if not self.output_file or not self._log_entries:
            return

        formatted_lines = []
        for event in self._log_entries:
            formatted = format_log_entry(event)
            if formatted:
                formatted_lines.append(formatted + "\n\n")

        if formatted_lines:
            with open(self.output_file, "a", encoding="utf-8", newline="\n") as file:
                file.writelines(formatted_lines)
                file.flush()

        self._log_entries.clear()
        self._log_write_counter = 0

    def save_context(self, context_file: Optional[Path] = None) -> None:
        """Save conversation context to JSON file."""
        if context_file is None:
            context_file = Path(__file__).parent / "output" / "context.json"

        try:
            context_file.parent.mkdir(parents=True, exist_ok=True)
        except (IOError, OSError) as e:
            msg = f"Failed to create directory {context_file.parent}: {type(e).__name__} - {e}"
            print(json.dumps(
                {"type": "error", "content": msg},
                ensure_ascii=False,
            ))
            self._append_to_log("error", {"content": msg})
            return

        # Filter out system messages before saving (they are reloaded from prompts file)
        saveable_messages = [m for m in self.messages if m.get("role") != "system"]

        # Create copies to avoid modifying original messages
        messages_to_save = []
        for message in saveable_messages:
            msg_copy = copy.copy(message)
            if "content" in msg_copy:
                msg_copy["content"] = msg_copy["content"].replace("\n", "")
            messages_to_save.append(msg_copy)

        context_data = {
            "messages": messages_to_save,
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
            "model": self.client.model,
            "message_count": len(messages_to_save),
        }

        try:
            with open(context_file, "w", encoding="utf-8") as f:
                json.dump(context_data, f, ensure_ascii=False, indent=2)

            msg = f"Context saved to {context_file.name} ({len(messages_to_save)} messages)"
            print(json.dumps(
                {"type": "info", "content": msg},
                ensure_ascii=False,
            ))
            self._append_to_log("info", {"content": msg})

        except (IOError, OSError) as e:
            msg = f"Failed to save context to {context_file}: {type(e).__name__} - {e}"
            print(json.dumps(
                {"type": "error", "content": msg},
                ensure_ascii=False,
            ))
            self._append_to_log("error", {"content": msg})

        except TypeError as e:
            msg = f"Failed to serialize context: {type(e).__name__} - {e}"
            print(json.dumps(
                {"type": "error", "content": msg},
                ensure_ascii=False,
            ))
            self._append_to_log("error", {"content": msg})

    def load_context(self, context_file: Optional[Path] = None) -> bool:
        """Load conversation context from JSON file.

        Returns:
            True if context was loaded successfully, False otherwise.
        """
        if context_file is None:
            context_file = Path(__file__).parent / "output" / "context.json"

        if not context_file.exists():
            msg = f"Context file not found: {context_file}"
            print(json.dumps(
                {"type": "warning", "content": msg},
                ensure_ascii=False,
            ))
            self._append_to_log("warning", {"content": msg})
            return False

        if not context_file.is_file():
            msg = f"Path is not a file: {context_file}"
            print(json.dumps(
                {"type": "error", "content": msg},
                ensure_ascii=False,
            ))
            self._append_to_log("error", {"content": msg})
            return False

        try:
            file_size = context_file.stat().st_size
            if file_size == 0:
                msg = f"Context file is empty: {context_file}"
                print(json.dumps(
                    {"type": "warning", "content": msg},
                    ensure_ascii=False,
                ))
                self._append_to_log("warning", {"content": msg})
                return False

            with open(context_file, "r", encoding="utf-8") as f:
                context_data = json.load(f)

            messages = context_data.get("messages", [])
            if not messages:
                msg = f"No messages found in context file: {context_file}"
                print(json.dumps(
                    {"type": "warning", "content": msg},
                    ensure_ascii=False,
                ))
                self._append_to_log("warning", {"content": msg})
                return False

            if not isinstance(messages, list):
                msg = f"Invalid context format: 'messages' should be a list, got {type(messages).__name__}"
                print(json.dumps(
                    {"type": "error", "content": msg},
                    ensure_ascii=False,
                ))
                self._append_to_log("error", {"content": msg})
                return False

            timestamp = context_data.get("timestamp", "unknown")
            model = context_data.get("model", "unknown")

            loaded_messages = [
                m for m in messages if m.get("role") != "system"
            ]
            if len(loaded_messages) > self.MAX_CONTEXT_MESSAGES:
                loaded_messages = loaded_messages[-self.MAX_CONTEXT_MESSAGES:]

            self.messages.extend(loaded_messages)

            msg = (
                f"Loaded context from {context_file.name}: "
                f"{len(loaded_messages)} messages "
                f"(saved: {timestamp}, model: {model})"
            )
            print(json.dumps(
                {"type": "info", "content": msg},
                ensure_ascii=False,
            ))
            self._append_to_log("info", {"content": msg})
            return True

        except json.JSONDecodeError as e:
            msg = (
                f"Failed to parse context file {context_file}: "
                f"{type(e).__name__} - {e} "
                f"(line {e.lineno}, col {e.colno})"
            )
            print(json.dumps(
                {"type": "error", "content": msg},
                ensure_ascii=False,
            ))
            self._append_to_log("error", {"content": msg})
            return False

        except (IOError, OSError) as e:
            msg = f"Failed to read context file {context_file}: {type(e).__name__} - {e}"
            print(json.dumps(
                {"type": "error", "content": msg},
                ensure_ascii=False,
            ))
            self._append_to_log("error", {"content": msg})
            return False

        except Exception as e:
            msg = f"Unexpected error loading context: {type(e).__name__} - {e}"
            print(json.dumps(
                {"type": "error", "content": msg},
                ensure_ascii=False,
            ))
            self._append_to_log("error", {"content": msg})
            return False

    @staticmethod
    def read_log(log_file: Path) -> dict[str, Any]:
        """Read a JSONL log file and return structured data.

        Args:
            log_file: Path to the JSONL log file

        Returns:
            Dictionary with metadata, events, and completion info
        """
        if not log_file.exists():
            return {}

        metadata: dict[str, Any] = {}
        events: list[dict[str, Any]] = []
        completion: dict[str, Any] = {}

        with open(log_file, "r", encoding="utf-8") as file:
            for line in file:
                line = line.strip()

                if not line:
                    continue

                data = json.loads(line)

                if "_metadata" in data:
                    metadata = data["_metadata"]
                elif "_completion" in data:
                    completion = data["_completion"]
                else:
                    events.append(data)

        return {"metadata": metadata, "events": events, "completion": completion}


def main():
    parser = argparse.ArgumentParser(add_help=False)

    parser.add_argument("--model", "-m", default=DEFAULT_MODEL)
    parser.add_argument("--think", "-t", action="store_true")
    parser.add_argument("--context", "-c", action="store_true")
    parser.add_argument("prompt", nargs="+")

    args = parser.parse_args()

    config = AgentConfig(
        model=args.model,
        timeout=TIMEOUT,
        think=args.think,
    )

    agent = Agent(config)

    agent.run(" ".join(args.prompt), load_saved_context=args.context)


if __name__ == "__main__":
    main()
