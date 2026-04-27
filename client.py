"""Ollama client module - Handles communication with Ollama API."""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Generator, Optional

import requests

DEFAULT_MODEL = "qwen3.5:397b-cloud"
PORT = 11434


@dataclass
class AgentConfig:
    """Configuration for Agent and OllamaClient."""
    base_url: str = f"http://localhost:{PORT}"
    model: str = DEFAULT_MODEL
    timeout: int = 80
    think: bool = False


class OllamaClient:
    """Client for interacting with Ollama API."""

    def __init__(self, config: AgentConfig):
        self.base_url = config.base_url
        self.model = config.model
        self.timeout = config.timeout
        self.think = config.think  # Thinking mode support
        self.last_token_stats = None  # Store last token usage stats

    def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict] | None = None,
        stream: bool = True,
        think: Optional[bool] = None  # Override default think setting
    ) -> Generator[tuple[str, Optional[list], Optional[str]], None, None]:
        """Send a chat request to Ollama.
        
        Args:
            messages: List of chat messages
            tools: List of tool schemas for function calling
            stream: Whether to stream the response
            think: Enable chain-of-thought reasoning (overrides default)
            
        Yields:
            Tuple of (content, tool_calls, thinking)
            - content: The response content
            - tool_calls: List of tool calls if any
            - thinking: Thinking process if think=True
        """
        url = f"{self.base_url}/api/chat"
        payload = {
            "model": self.model,
            "messages": messages,
            "stream": stream
        }
        if tools:
            payload["tools"] = tools
        
        # Enable thinking if specified
        think_mode = think if think is not None else self.think
        if think_mode:
            payload["think"] = True

        try:
            response = requests.post(
                url,
                json=payload,
                stream=stream,
                timeout=self.timeout
            )
            response.raise_for_status()

            for line in response.iter_lines():
                if line:
                    data = json.loads(line)
                    if "message" in data:
                        message = data["message"]
                        content = message.get("content", "")
                        tool_calls = message.get("tool_calls")
                        thinking = message.get("thinking")  # Extract thinking process
                        
                        # Extract and store token usage if available
                        if "prompt_eval_count" in data or "eval_count" in data:
                            self.last_token_stats = {
                                "prompt_tokens": data.get("prompt_eval_count", 0),
                                "completion_tokens": data.get("eval_count", 0),
                                "total_tokens": data.get("prompt_eval_count", 0) + data.get("eval_count", 0)
                            }
                        
                        yield content, tool_calls, thinking
                    elif "error" in data:
                        yield f"Error: {data['error']}", None, None
                    # Handle token stats in non-streaming final chunk
                    elif "done" in data and data["done"]:
                        if "prompt_eval_count" in data or "eval_count" in data:
                            self.last_token_stats = {
                                "prompt_tokens": data.get("prompt_eval_count", 0),
                                "completion_tokens": data.get("eval_count", 0),
                                "total_tokens": data.get("prompt_eval_count", 0) + data.get("eval_count", 0)
                            }

        except requests.exceptions.Timeout:
            yield f"Error: Request timed out after {self.timeout} seconds", None, None
        except requests.exceptions.ConnectionError:
            yield f"Error: Could not connect to Ollama server at {self.base_url}", None, None
        except requests.exceptions.HTTPError as e:
            if e.response.status_code == 403:
                yield f"Error: Access denied, {str(e)}", None, None
            else:
                yield f"Error: HTTP error - {str(e)}", None, None
        except Exception as e:
            yield f"Error: {str(e)}", None, None

    def reset_token_stats(self) -> None:
        """Reset token usage statistics."""
        self.last_token_stats = None
