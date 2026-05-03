"""AI Agent with Function Calling capability."""

from __future__ import annotations

import json
import platform
import time
import argparse
from pathlib import Path
from typing import Any, Optional

from client import OllamaClient, DEFAULT_MODEL, PORT, AgentConfig
from skills import Skills
from format import format_log_entry

class Agent:
    """AI Agent that coordinates skills and LLM communication."""
    def __init__(
        self,
        config: Optional[AgentConfig] = None,
        log_batch_size: int = 5,
        enable_realtime_log: bool = True
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
            with open(prompt_path, 'r', encoding='utf-8') as prompt_file:
                content = prompt_file.read()
        except FileNotFoundError:
            raise FileNotFoundError(f"System prompt file not found: {prompt_path}")

        system = platform.system()
        if system == "Windows":
            os_info = (f"Current System- OS: Windows ({platform.release()})"
                       f"- Use Windows commands")
        elif system == "Darwin":
            os_info = (f"Current System- OS: macOS ({platform.release()})"    
                       f"- Use macOS/Linux commands")
        else:
            os_info = f"Current System- OS: {system} ({platform.release()})"

        content += os_info
        self.messages.append({"role": "system", "content": content})

    def run(self, user_input: str) -> None:
        """Process user input and generate response with support for multistep tool calling."""
        start_time = time.time()
        
        self._initialize_execution(user_input, start_time)
        self.messages.append({"role": "user", "content": user_input})
        
        full_response = []
        while True:
            response_chunks, thinking_chunks, tool_calls = self._get_llm_response()
            
            if thinking_chunks:
                self._process_thinking(thinking_chunks)
            
            if response_chunks and tool_calls:
                self._output_realtime_response(response_chunks)
            
            if not tool_calls:
                break
            
            for tool_call in tool_calls:
                self._execute_tool_call(tool_call, "".join(full_response))
            
            full_response = []
        
        self._finalize_execution(start_time, "".join(full_response))
        self._flush_log_buffer(force=True)
        if self.output_file:
            with open(self.output_file, 'a', encoding='utf-8', newline='\n') as file:
                file.flush()

    def _initialize_execution(self, user_input: str, start_time: float) -> None:
        """Initialize execution and output file."""
        script_dir = Path(__file__).parent
        output_dir = script_dir / "output"
        
        try:
            output_dir.mkdir(parents=True, exist_ok=True)
        except (IOError, OSError) as e:
            error_msg = f"无法创建输出目录：{output_dir} - {e}"
            print(json.dumps({"type": "error", "content": error_msg}, ensure_ascii=False))
            raise RuntimeError(error_msg) from e
        
        self.output_file = output_dir / "log.txt"
        self._log_entries = []
        self._log_write_counter = 0
        self._cached_tool_schemas = None
        self._log_metadata = {
            "user_input": user_input,
            "start_time": time.strftime('%Y-%m-%d %H:%M:%S'),
            "status": "running"
        }
        
        try:
            with open(self.output_file, 'w', encoding='utf-8') as f:
                pass
        except (IOError, OSError) as e:
            error_msg = f"无法创建日志文件：{self.output_file} - {e}"
            print(json.dumps({"type": "error", "content": error_msg}, ensure_ascii=False))
            raise RuntimeError(error_msg) from e
        
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
            stream=True
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

    def _output_realtime_response(self, response_chunks: list[str]) -> None:
        """Output real-time LLM response."""
        content = "".join(response_chunks)
        print(json.dumps({"type": "llm_response", "content": content}, ensure_ascii=False))
        self._append_to_log("llm_response", {"content": content})

    def _execute_tool_call(self, tool_call: dict, full_response: str) -> None:
        """Execute a single tool call and update messages."""
        func = tool_call.get("function", {})
        tool_name = func.get("name")
        arguments = func.get("arguments", {})

        self._append_to_log("tool_call_start", {
            "tool": tool_name,
            "arguments": arguments,
            "status": "running"
        })

        skill = self.skills.get(tool_name)

        if not skill:
            result = f"Error: Skill '{tool_name}' not found"
            tool_output = {"tool": tool_name, "arguments": arguments, "success": False, "result": result}
        else:
            raw_result = skill.execute(**arguments)
            success = not (
                raw_result.startswith("Error:") or 
                raw_result.startswith("Warning:") or
                raw_result.startswith("SECURITY ERROR:")
            )
            tool_output = {"tool": tool_name, "arguments": arguments, "success": success, "result": raw_result}
            result = raw_result
        
        print(json.dumps(tool_output, ensure_ascii=False))
        self._append_to_log("tool_call", tool_output)

        self.messages.append({"role": "assistant", "content": full_response})
        self.messages.append({"role": "tool", "content": result})
        
        if skill and skill.name not in ("finish_to_do_item", "to_do"):
            reminder = "SYSTEM REMINDER: If you just completed a task from your to-do list, call finish_to_do_item() IMMEDIATELY before starting the next task."
            self.messages.append({"role": "system", "content": reminder})

    def _finalize_execution(self, start_time: float, full_response: str) -> None:
        """Finalize execution."""
        elapsed = int(time.time() - start_time)
        token_stats = self.client.last_token_stats

        self._append_to_log("final_response", {
            "response": full_response,
            "elapsed_seconds": elapsed,
            "model": self.client.model,
            "token_usage": token_stats
        })

        print(json.dumps({
            "response": full_response,
            "elapsed": elapsed,
            "model": self.client.model,
            "token_usage": token_stats
        }, ensure_ascii=False))
        
        self.client.last_token_stats = None

    def _append_to_log(self, event_type: str, data: Any, force_write: bool = False) -> None:
        """Append event to log with real-time writing for critical events."""
        if self._enable_realtime_log and event_type in ("tool_call_start", "tool_call"):
            self._write_log_entry_immediately({
                "type": event_type,
                "timestamp": time.strftime('%Y-%m-%d %H:%M:%S'),
                "data": data
            })
        else:
            self._log_entries.append({
                "type": event_type,
                "timestamp": time.strftime('%Y-%m-%d %H:%M:%S'),
                "data": data
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
            with open(self.output_file, 'a', encoding='utf-8', newline='\n') as file:
                file.write(formatted + "\n\n")
                file.flush()

    def _flush_log_buffer(self, force: bool = False) -> None:
        """Flush buffered log entries to file."""
        if not self.output_file or not self._log_entries:
            return

        formatted_lines = []
        for event in self._log_entries:
            formatted = format_log_entry(event)
            if formatted:
                formatted_lines.append(formatted + "\n\n")

        if formatted_lines:
            with open(self.output_file, 'a', encoding='utf-8', newline='\n') as file:
                file.writelines(formatted_lines)
                file.flush()

        self._log_entries.clear()
        self._log_write_counter = 0

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
        
        result = {"metadata": {}, "events": [], "completion": {}}
        
        with open(log_file, 'r', encoding='utf-8') as file:
            for line in file:
                line = line.strip()

                if not line:
                    continue
                
                data = json.loads(line)
                
                # Check for special keys
                if "_metadata" in data:
                    result["metadata"] = data["_metadata"]
                elif "_completion" in data:
                    result["completion"] = data["_completion"]
                else:
                    result["events"].append(data)
        
        return result


def main():
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--model", "-m", default=DEFAULT_MODEL)
    parser.add_argument("--url", "-u", default=f"http://localhost:{PORT}")
    parser.add_argument("--think", "-t", action="store_true")
    parser.add_argument("prompt", nargs="+")
    args = parser.parse_args()

    config = AgentConfig(base_url=args.url, model=args.model, timeout=80, think=args.think)
    agent = Agent(config)
    agent.run(" ".join(args.prompt))

if __name__ == "__main__":
    main()
