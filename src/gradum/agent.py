"""AI Agent with Function Calling capability."""

import argparse
import json
import platform
import time
from datetime import datetime

from gradum import __version__
from gradum.client import DEFAULT_MODEL, TIMEOUT, AgentConfig, OllamaClient
from gradum.paths import OUTPUT_DIR, PROMPTS_DIR
from gradum.skills import Skills
from gradum.skills.todo import get_todo_manager
from gradum.utils.io_utils import ContextManager
from gradum.utils.ollama_health import ensure_ollama_running


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

        ok, msg = ensure_ollama_running(self.client.base_url)
        _emit("ollama_status", {"reachable": ok, "message": msg})

        self.skills = Skills()
        self.messages = []  # Message list for LLM conversation
        self.full_read_files = set()  # Track fully read files (no line_range)
        self._tool_call_counter = 0  # Monotonic counter for tool_call_id generation

        self.context_manager = ContextManager(OUTPUT_DIR)

        self._setup_system_prompt()

    def _setup_system_prompt(self):
        """Load system prompt from file and substitute OS placeholder."""
        prompt_path = PROMPTS_DIR / "system_prompt.md"

        try:
            with open(prompt_path, "r", encoding="utf-8") as f:
                content = f.read()
        except (FileNotFoundError, IOError) as e:
            content = "You are a helpful AI assistant.\n"
            print(f"Warning: Could not load system prompt from {prompt_path}: {e}")

        content = content.replace("{{OS}}", f"{platform.system()} {platform.release()}")
        self.messages.append({"role": "system", "content": content})

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

        cached_tool_schemas = None

        while True:
            # Get tool schemas once and cache them
            if cached_tool_schemas is None:
                cached_tool_schemas = self.skills.get_schemas()

            # Get response from LLM with streaming
            response_chunks = []
            thinking_chunks = []
            tool_calls = None
            error_message = None

            for chunk, calls, thinking, is_error in self.client.chat(
                self.messages,
                tools=cached_tool_schemas,
                stream=True,
                think=self.config.think
            ):
                if is_error:
                    error_message = chunk
                else:
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

            # Build turn text from streamed chunks
            turn_text = "".join(response_chunks)

            # Handle LLM response
            if error_message:
                _emit("error", {
                    "code": "CLIENT_ERROR",
                    "message": error_message.strip(),
                    "source": "ollama_client"
                })
            elif turn_text.strip():
                _emit("llm_response", {"content": turn_text})

            # Exit loop if no tool calls needed
            if not tool_calls:
                if turn_text:
                    self._append_assistant_message(turn_text, None)
                break

            # Pre-process tool calls: assign unique IDs
            processed_calls = self._prepare_tool_calls(tool_calls)

            # Append assistant message with structured tool_calls field
            self._append_assistant_message(turn_text, processed_calls)

            # Execute each tool and append tool result with tool_call_id
            for pc in processed_calls:
                self._execute_tool(pc["call"], pc["id"])

        # Finalize and save context
        self._finish(start_time)

    def _prepare_tool_calls(self, raw_calls):
        """Assign unique IDs to each tool call.

        Uses 'id' from Ollama's response if present, otherwise generates a
        local monotonic ID (call_1, call_2, ...). Ensures every tool call
        has a stable identifier for log traceability and message history.
        """
        processed = []
        for call in raw_calls:
            existing_id = call.get("id")
            if existing_id:
                tc_id = existing_id
            else:
                self._tool_call_counter += 1
                tc_id = f"call_{self._tool_call_counter}"
            processed.append({"id": tc_id, "call": call})
        return processed

    def _append_assistant_message(self, content, processed_calls):
        """Append an assistant message with optional structured tool_calls field.

        Ollama-compatible format:
            {role: assistant, content: ..., tool_calls: [{function: {name, arguments}}]}

        The 'function.arguments' is a dict (not a JSON string) per Ollama spec.
        No 'id' or 'type' fields are sent (Ollama matches tool calls positionally).
        """
        msg = {"role": "assistant", "content": content}
        if processed_calls:
            msg["tool_calls"] = [
                {
                    "function": {
                        "name": pc["call"].get("function", {}).get("name", ""),
                        "arguments": pc["call"].get("function", {}).get("arguments", {}),
                    }
                }
                for pc in processed_calls
            ]
        self.messages.append(msg)

    def _execute_tool(self, tool_call, tool_call_id):
        """Execute a single tool call and update messages.

        Args:
            tool_call: Raw tool call dict with 'function' containing name+arguments
            tool_call_id: Unique ID linking this call to its result in history
        """
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

        # Emit tool_call event with tool_call_id for log traceability
        _emit("tool_call", {
            "tool": tool_name,
            "arguments": arguments,
            "tool_call_id": tool_call_id,
            "success": success,
            "result": result
        })

        # Emit error event if failed
        if not success and isinstance(result, dict):
            error_info = result.get("error", {})
            _emit("error", {
                "code": error_info.get("code", "EXECUTION_ERROR"),
                "message": error_info.get("message", "Unknown error"),
                "tool": tool_name,
                "tool_call_id": tool_call_id
            })

        # Add tool result to history (Ollama format: role=tool, content as string)
        result_str = json.dumps(result, ensure_ascii=False) if isinstance(result, dict) else str(result)

        # Embed plan reminder into the tool result so it is delivered as a single
        # well-formed tool message (avoiding a second tool message lacking tool_call_id)
        reminder = get_todo_manager().get_reminder()
        if reminder:
            result_str += "\n\n" + reminder

        self.messages.append({
            "role": "tool",
            "content": result_str
        })

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
    parser.add_argument("--num-predict", type=int, default=16384)
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
