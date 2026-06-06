"""Ollama client module - Handles communication with Ollama API."""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Generator, Optional, List

import requests

DEFAULT_MODEL = "minimax-m2.5:cloud"
PORT = 11434
TIMEOUT = 300


@dataclass
class AgentConfig:
    """Configuration for Agent and OllamaClient."""
    base_url: str = f"http://localhost:{PORT}"
    model: str = DEFAULT_MODEL
    timeout: int = TIMEOUT
    think: bool = False
    temperature: float = 0.7
    top_p: float = 0.9
    num_ctx: int = 4096
    num_predict: int = 2048


class OllamaClient:
    """Client for interacting with Ollama API."""

    def __init__(self, config: AgentConfig):
        self.base_url = config.base_url
        self.model = config.model
        self.timeout = config.timeout
        self.think = config.think
        self.temperature = config.temperature
        self.top_p = config.top_p
        self.num_ctx = config.num_ctx
        self.num_predict = config.num_predict
        self.last_token_stats = None

    def _accumulate_token_stats(self, prompt_tokens: int, completion_tokens: int) -> None:
        """Accumulate token usage across multiple API calls."""
        if self.last_token_stats is None:
            self.last_token_stats = {
                "prompt_tokens": 0,
                "completion_tokens": 0,
                "total_tokens": 0
            }

        self.last_token_stats["prompt_tokens"] += prompt_tokens
        self.last_token_stats["completion_tokens"] += completion_tokens
        self.last_token_stats["total_tokens"] += prompt_tokens + completion_tokens

    def chat(
        self,
        messages: list[dict[str, Any]],
        tools: Optional[List[dict]] = None,
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
            - thinking: Thinking process if enabled by think=True
        """
        url = f"{self.base_url}/api/chat"
        payload = {
            "model": self.model,
            "messages": messages,
            "stream": stream,
            "options": {
                "temperature": self.temperature,
                "top_p": self.top_p,
                "num_ctx": self.num_ctx,
                "num_predict": self.num_predict
            }
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

                        # Accumulate token usage across multiple calls
                        if "prompt_eval_count" in data or "eval_count" in data:
                            prompt_tokens = data.get("prompt_eval_count", 0)
                            completion_tokens = data.get("eval_count", 0)
                            self._accumulate_token_stats(prompt_tokens, completion_tokens)

                        yield content, tool_calls, thinking
                    elif "error" in data:
                        yield f"Error: {data['error']}", None, None
                    elif "done" in data and data["done"]:
                        if "prompt_eval_count" in data or "eval_count" in data:
                            prompt_tokens = data.get("prompt_eval_count", 0)
                            completion_tokens = data.get("eval_count", 0)
                            self._accumulate_token_stats(prompt_tokens, completion_tokens)

        except requests.exceptions.Timeout as e:
            error_msg = f"Error: Request timed out after {self.timeout} seconds. The server is taking too long to respond."
            print(f"\n[CLIENT ERROR] Timeout: {e}")
            yield error_msg, None, None
        except requests.exceptions.ConnectionError as e:
            error_msg = f"Error: Could not connect to Ollama server at {self.base_url}. Make sure Ollama is running (try 'ollama serve'). Details: {str(e)}"
            print(f"\n[CLIENT ERROR] Connection: {e}")
            yield error_msg, None, None
        except requests.exceptions.HTTPError as e:
            if e.response.status_code == 403:
                error_msg = f"Error: Access denied (403 Forbidden). Check API permissions. Details: {str(e)}"
            elif e.response.status_code == 404:
                error_msg = f"Error: Model '{self.model}' not found (404). Check if the model is pulled. Details: {str(e)}"
            elif e.response.status_code == 500:
                error_msg = f"Error: Server internal error (500). The model may have crashed. Details: {str(e)}"
            else:
                error_msg = f"Error: HTTP {e.response.status_code} - {str(e)}"
            print(f"\n[CLIENT ERROR] HTTP {e.response.status_code}: {e}")
            yield error_msg, None, None
        except requests.exceptions.TooManyRedirects as e:
            error_msg = f"Error: Too many redirects. Check server configuration."
            print(f"\n[CLIENT ERROR] Redirects: {e}")
            yield error_msg, None, None
        except json.JSONDecodeError as e:
            error_msg = f"Error: Invalid JSON response"
            print(f"\n[CLIENT ERROR] JSON: {e}")
            yield error_msg, None, None
        except (KeyError, TypeError) as e:
            error_msg = f"Error: Invalid response format"
            print(f"\n[CLIENT ERROR] Format: {e}")
            yield error_msg, None, None
        except Exception as e:
            error_msg = f"Error: Unexpected error - {str(e)}"
            print(f"\n[CLIENT ERROR] Unexpected: {type(e).__name__}: {e}")
            yield error_msg, None, None
