"""AI Agent with Function Calling capability."""

import argparse
import json
import platform
import time
from datetime import datetime
from pathlib import Path

from client import OllamaClient, AgentConfig, DEFAULT_MODEL, TIMEOUT
from skills import Skills, __version__
from utils.io_utils import ContextManager


def _emit(event_type: str, data: dict) -> None:
    """Emit a JSON event in NDJSON format with unified structure."""
    event = {
        "type": event_type,
        "timestamp": datetime.now().strftime("%Y-%m-%dT%H:%M:%S"),
        "data": data
    }
    print(json.dumps(event, ensure_ascii=False))


class Agent:
    """AI Agent that coordinates skills and LLM communication."""

    def __init__(self, config=None):
        self.config = config or AgentConfig()
        self.client = OllamaClient(self.config)
        self.skills = Skills()
        self.messages = []  # Message list for LLM conversation
        self.full_read_files = set()  # Track fully read files (no line_range)

        output_dir = Path(__file__).parent / "output"
        self.context_manager = ContextManager(output_dir)

        self._setup_system_prompt()

    def _setup_system_prompt(self):
        """Load system prompt from file and add OS info."""
        prompt_path = Path(__file__).parent / "prompts" / "system_prompt.md"

        try:
            with open(prompt_path, "r", encoding="utf-8") as f:
                content = f.read()
        except (FileNotFoundError, IOError) as e:
            content = "You are a helpful AI assistant.\n"
            print(f"Warning: Could not load system prompt from {prompt_path}: {e}")

        system = platform.system()
        if system == "Windows":
            os_info = f"Your Current System- OS: Windows ({platform.release()})"
        elif system == "Darwin":
            os_info = f"Your Current System- OS: macOS ({platform.release()})"
        else:
            os_info = f"Your Current System- OS: {system} ({platform.release()})"

        self.messages.append({"role": "system", "content": content + os_info})

    def run(self, user_input, load_context=False):
        """Main entry point to process user input and generate response."""
        start_time = time.time()

        # Load previous conversation context if requested
        context_loaded = False
        context_messages = 0
        if load_context:
            loaded = self.context_manager.load()
            self.messages.extend(loaded)
            context_loaded = len(loaded) > 0
            context_messages = len(loaded)

        # Emit session start
        _emit("session_start", {
            "version": __version__,
            "model": self.client.model,
            "think": self.config.think,
            "context_loaded": context_loaded,
            "context_messages": context_messages
        })

        # Emit context loaded if applicable
        if context_loaded:
            _emit("context_loaded", {"message_count": context_messages})

        self.messages.append({"role": "user", "content": user_input})

        full_response = []
        cached_tool_schemas = None

        while True:
            # Get tool schemas once and cache them
            if cached_tool_schemas is None:
                cached_tool_schemas = self.skills.get_schemas()

            # Get response from LLM with streaming
            response_chunks = []
            thinking_chunks = []
            tool_calls = None

            for chunk, calls, thinking in self.client.chat(
                self.messages,
                tools=cached_tool_schemas,
                stream=True,
                think=self.config.think
            ):
                response_chunks.append(chunk)
                if thinking:
                    thinking_chunks.append(thinking)
                if calls and not tool_calls:
                    tool_calls = calls

            # Handle thinking output
            if thinking_chunks:
                thinking_text = "".join(thinking_chunks)
                if thinking_text.strip():
                    _emit("thinking", {"content": thinking_text})

            # Handle LLM response
            if response_chunks:
                full_response.extend(response_chunks)
                if tool_calls:
                    response_text = "".join(response_chunks)
                    if response_text.strip():
                        _emit("llm_response", {"content": response_text})
                else:
                    # Check if response is an error message from client
                    response_text = "".join(response_chunks)
                    if response_text.strip().startswith("Error:"):
                        _emit("error", {
                            "code": "CLIENT_ERROR",
                            "message": response_text.strip(),
                            "source": "ollama_client"
                        })
                    elif response_text.strip():
                        _emit("llm_response", {"content": response_text})

            # Exit loop if no tool calls needed
            if not tool_calls:
                if full_response:
                    final_content = "".join(full_response)
                    self.messages.append({"role": "assistant", "content": final_content})
                break

            # Execute tool calls
            for tool_call in tool_calls:
                self._execute_tool(tool_call, "".join(full_response))

            full_response = []

        # Finalize and save context
        self._finish(start_time)

    def _execute_tool(self, tool_call, full_response):
        """Execute a single tool call and update messages."""
        func = tool_call.get("function", {})
        tool_name = func.get("name", "")
        arguments = func.get("arguments", {})

        # Track files read without line_range (full file read)
        if tool_name == "read_file" and not arguments.get("line_range"):
            self.full_read_files.add(arguments.get("path", ""))

        # Execute the skill
        skill = self.skills.get(tool_name)
        if not skill:
            result = {
                "success": False,
                "error": {
                    "code": "SKILL_NOT_FOUND",
                    "message": f"Skill '{tool_name}' not found"
                }
            }
        else:
            result = skill.execute(**arguments)

        # Determine success from result dict
        success = result.get("success", False) if isinstance(result, dict) else False

        # Emit tool_call event
        _emit("tool_call", {
            "tool": tool_name,
            "arguments": arguments,
            "success": success,
            "result": result
        })

        # Emit error event if failed
        if not success and isinstance(result, dict):
            error_info = result.get("error", {})
            _emit("error", {
                "code": error_info.get("code", "EXECUTION_ERROR"),
                "message": error_info.get("message", "Unknown error"),
                "tool": tool_name
            })

        # Add to message history - convert result to string for LLM
        result_str = json.dumps(result, ensure_ascii=False) if isinstance(result, dict) else str(result)
        self.messages.append({"role": "assistant", "content": full_response})
        self.messages.append({"role": "tool", "content": result_str})

        # Add plan reminder if available
        from skills.todo import get_todo_manager
        reminder = get_todo_manager().get_reminder()
        if reminder:
            self.messages.append({"role": "tool", "content": reminder})

    def _finish(self, start_time):
        """Finalize the session and save context."""
        elapsed = int(time.time() - start_time)
        token_stats = self.client.last_token_stats

        # Emit session_end event
        _emit("session_end", {
            "version": __version__,
            "elapsed_seconds": elapsed,
            "model": self.client.model,
            "token_usage": token_stats or {}
        })

        self.context_manager.save(self.messages, self.client.model, self.full_read_files)
        self.client.last_token_stats = None


def main():
    """Command line entry point."""
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--model", "-m", default=DEFAULT_MODEL)
    parser.add_argument("--think", "-t", action="store_true")
    parser.add_argument("--context", "-c", action="store_true")
    parser.add_argument("--timeout", type=int, default=None)
    parser.add_argument("--temperature", type=float, default=0.7)
    parser.add_argument("--top-p", type=float, default=0.9)
    parser.add_argument("--num-ctx", type=int, default=4096)
    parser.add_argument("--num-predict", type=int, default=2048)
    parser.add_argument("prompt", nargs="+")

    args = parser.parse_args()

    timeout = args.timeout if args.timeout else TIMEOUT
    config = AgentConfig(
        model=args.model,
        timeout=timeout,
        think=args.think,
        temperature=args.temperature,
        top_p=args.top_p,
        num_ctx=args.num_ctx,
        num_predict=args.num_predict
    )
    agent = Agent(config)
    agent.run(" ".join(args.prompt), load_context=args.context)


if __name__ == "__main__":
    main()