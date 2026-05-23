"""AI Agent with Function Calling capability."""

import argparse
import json
import platform
import time
from pathlib import Path

from client import OllamaClient, AgentConfig, DEFAULT_MODEL, TIMEOUT
from skills import Skills
from utils.io_utils import LogManager, ContextManager


class Agent:
    """AI Agent that coordinates skills and LLM communication."""

    def __init__(self, config=None):
        self.config = config or AgentConfig()
        self.client = OllamaClient(self.config)
        self.skills = Skills()
        self.messages = []  # Message list for LLM conversation
        self.full_read_files = set()  # Track fully read files (no line_range)

        output_dir = Path(__file__).parent / "output"
        self.log_manager = LogManager(output_dir)
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
        if load_context:
            loaded = self.context_manager.load()
            self.messages.extend(loaded)
            self.log_manager.append("context_loaded", {
                "message_count": len(loaded),
                "status": "success" if loaded else "no_context"
            })

        self.log_manager.init_log(user_input)
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
                    print(json.dumps({"type": "thinking", "content": thinking_text}, ensure_ascii=False))
                    self.log_manager.append("thinking", {"content": thinking_text})

            # Handle LLM response
            if response_chunks:
                full_response.extend(response_chunks)
                if tool_calls:
                    response_text = "".join(response_chunks)
                    if response_text.strip():
                        print(json.dumps({"type": "llm_response", "content": response_text}, ensure_ascii=False))
                        self.log_manager.append("llm_response", {"content": response_text})

            # Exit loop if no tool calls needed
            if not tool_calls:
                if full_response:
                    final_content = "".join(full_response)
                    if final_content.strip():
                        print(json.dumps({"type": "final_response", "content": final_content}, ensure_ascii=False))
                        self.log_manager.append("final_llm_response", {"content": final_content})
                    self.messages.append({"role": "assistant", "content": final_content})
                break

            # Execute tool calls
            for tool_call in tool_calls:
                self._execute_tool(tool_call, "".join(full_response))

            full_response = []

        # Finalize and save context
        self._finish(start_time, "".join(full_response))

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
            result = f"Error: Skill '{tool_name}' not found"
            success = False
        else:
            result = skill.execute(**arguments)
            success = not (result.startswith("Error:") or 
                          result.startswith("Warning:") or 
                          result.startswith("SECURITY ERROR:"))

        # Output and log the result
        output = {"tool": tool_name, "arguments": arguments, "success": success, "result": result}
        print(json.dumps(output, ensure_ascii=False))
        self.log_manager.append("tool_call", output)

        # Add to message history
        self.messages.append({"role": "assistant", "content": full_response})
        self.messages.append({"role": "tool", "content": result})

        # Add plan reminder if available
        from skills.todo import get_todo_manager
        reminder = get_todo_manager().get_reminder()
        if reminder:
            self.messages.append({"role": "tool", "content": reminder})

    def _finish(self, start_time, response):
        """Finalize the session and save context."""
        elapsed = int(time.time() - start_time)
        token_stats = self.client.last_token_stats

        self.log_manager.append("final_response", {
            "response": response,
            "elapsed_seconds": elapsed,
            "model": self.client.model,
            "token_usage": token_stats,
        })

        print(json.dumps({
            "response": response,
            "elapsed": elapsed,
            "model": self.client.model,
            "token_usage": token_stats,
        }, ensure_ascii=False))

        self.context_manager.save(self.messages, self.client.model, self.full_read_files)
        self.log_manager.close()
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