"""LLM client module - Handles communication with Ollama and OpenAI-compatible APIs."""

from __future__ import annotations

import json
import time
from collections.abc import Generator
from dataclasses import dataclass
from typing import Any, Optional

import requests

DEFAULT_MODEL = "minimax-m2.5:cloud"
PORT = 11434
TIMEOUT = 300
MAX_RETRIES = 2
RETRY_DELAY = 5.0


def _is_retryable_error(exc: Exception) -> bool:
    """Check if an exception is transient and worth retrying."""
    if isinstance(exc, (requests.exceptions.Timeout, requests.exceptions.ConnectionError)):
        return True
    if isinstance(exc, requests.exceptions.HTTPError) and exc.response is not None:
        return exc.response.status_code >= 500
    return False


@dataclass
class AgentConfig:
    """Configuration for Agent and LLM client."""

    base_url: str = f"http://localhost:{PORT}"
    model: str = DEFAULT_MODEL
    timeout: int = TIMEOUT
    think: bool = False
    temperature: float = 0.7
    top_p: float = 0.9
    num_ctx: int = 4096
    num_predict: int = 2048
    provider: str = "ollama"  # "ollama" or "openai"


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
            self.last_token_stats = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}

        self.last_token_stats["prompt_tokens"] += prompt_tokens
        self.last_token_stats["completion_tokens"] += completion_tokens
        self.last_token_stats["total_tokens"] += prompt_tokens + completion_tokens

    def chat(
        self,
        messages: list[dict[str, Any]],
        tools: Optional[list[dict]] = None,
        stream: bool = True,
        think: Optional[bool] = None,  # Override default think setting
    ) -> Generator[tuple[str, Optional[list], Optional[str], bool], None, None]:
        """Send a chat request to Ollama.

        Args:
            messages: List of chat messages
            tools: List of tool schemas for function calling
            stream: Whether to stream the response
            think: Enable chain-of-thought reasoning (overrides default)

        Yields:
            Tuple of (content, tool_calls, thinking, is_error)
            - content: The response content (or error message text when is_error=True)
            - tool_calls: List of tool calls if any
            - thinking: Thinking process if enabled by think=True
            - is_error: True if this chunk is a client-side error, False for normal content
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
                "num_predict": self.num_predict,
            },
        }
        if tools:
            payload["tools"] = tools

        # Enable thinking if specified
        think_mode = think if think is not None else self.think
        if think_mode:
            payload["think"] = True

        try:
            for attempt in range(MAX_RETRIES + 1):
                try:
                    response = requests.post(url, json=payload, stream=stream, timeout=self.timeout)
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

                                yield content, tool_calls, thinking, False
                            elif "error" in data:
                                yield data["error"], None, None, True
                            elif "done" in data and data["done"]:
                                if "prompt_eval_count" in data or "eval_count" in data:
                                    prompt_tokens = data.get("prompt_eval_count", 0)
                                    completion_tokens = data.get("eval_count", 0)
                                    self._accumulate_token_stats(prompt_tokens, completion_tokens)
                    break  # Success, exit retry loop

                except Exception as e:
                    if _is_retryable_error(e) and attempt < MAX_RETRIES:
                        time.sleep(RETRY_DELAY * (2 ** attempt))
                        continue
                    raise  # Re-raise for outer except handlers

        except requests.exceptions.Timeout:
            error_msg = f"Request timed out after {self.timeout} seconds. The server is taking too long to respond."
            yield error_msg, None, None, True
        except requests.exceptions.ConnectionError as e:
            error_msg = f"Could not connect to Ollama server at {self.base_url}. Make sure Ollama is running (try 'ollama serve'). Details: {str(e)}"
            yield error_msg, None, None, True
        except requests.exceptions.HTTPError as e:
            if e.response.status_code == 403:
                error_msg = (
                    f"Access denied (403 Forbidden). Check API permissions. Details: {str(e)}"
                )
            elif e.response.status_code == 404:
                error_msg = f"Model '{self.model}' not found (404). Check if the model is pulled. Details: {str(e)}"
            elif e.response.status_code == 500:
                error_msg = (
                    f"Server internal error (500). The model may have crashed. Details: {str(e)}"
                )
            else:
                error_msg = f"HTTP {e.response.status_code} - {str(e)}"
            yield error_msg, None, None, True
        except requests.exceptions.TooManyRedirects:
            error_msg = "Too many redirects. Check server configuration."
            yield error_msg, None, None, True
        except json.JSONDecodeError:
            error_msg = "Invalid JSON response"
            yield error_msg, None, None, True
        except (KeyError, TypeError):
            error_msg = "Invalid response format"
            yield error_msg, None, None, True
        except Exception as e:
            error_msg = f"Unexpected error - {str(e)}"
            yield error_msg, None, None, True


class OpenAICompatibleClient:
    """Client for OpenAI-compatible APIs (LM Studio, vLLM, etc.)."""

    def __init__(self, config: AgentConfig):
        self.base_url = config.base_url
        self.model = config.model
        self.timeout = config.timeout
        self.temperature = config.temperature
        self.top_p = config.top_p
        self.num_predict = config.num_predict
        self.last_token_stats = None

    def _accumulate_token_stats(self, prompt_tokens: int, completion_tokens: int) -> None:
        """Accumulate token usage across multiple API calls."""
        if self.last_token_stats is None:
            self.last_token_stats = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}

        self.last_token_stats["prompt_tokens"] += prompt_tokens
        self.last_token_stats["completion_tokens"] += completion_tokens
        self.last_token_stats["total_tokens"] += prompt_tokens + completion_tokens

    def _build_tools(self, tools: Optional[list[dict]]) -> Optional[list[dict]]:
        """Convert Gradum tool schemas to OpenAI function-calling format.

        OpenAI expects: {type: "function", function: {name, description, parameters}}
        Gradum schemas already follow this format, so pass through directly.
        """
        return tools

    def _build_payload(
        self,
        messages: list[dict[str, Any]],
        tools: Optional[list[dict]],
        stream: bool,
    ) -> dict[str, Any]:
        """Build OpenAI-compatible request payload."""
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": messages,
            "stream": stream,
            "temperature": self.temperature,
            "top_p": self.top_p,
            "max_tokens": self.num_predict,
        }
        if tools:
            payload["tools"] = self._build_tools(tools)
        return payload

    def _parse_tool_calls(self, raw_tool_calls: list[dict]) -> list[dict]:
        """Normalize OpenAI tool_calls to Gradum internal format.

        OpenAI format:
            {id, type: "function", function: {name, arguments: "<json_string>"}}

        Gradum format (matching Ollama convention):
            {function: {name, arguments: <dict>}}
        """
        normalized = []
        for tc in raw_tool_calls:
            func = tc.get("function", {})
            args = func.get("arguments", "{}")
            if isinstance(args, str):
                try:
                    args = json.loads(args)
                except (json.JSONDecodeError, TypeError):
                    args = {}
            normalized.append(
                {
                    "id": tc.get("id", ""),
                    "function": {
                        "name": func.get("name", ""),
                        "arguments": args,
                    },
                }
            )
        return normalized

    def chat(
        self,
        messages: list[dict[str, Any]],
        tools: Optional[list[dict]] = None,
        stream: bool = True,
        think: Optional[bool] = None,
    ) -> Generator[tuple[str, Optional[list], Optional[str], bool], None, None]:
        """Send a chat request to an OpenAI-compatible API.

        Args:
            messages: List of chat messages
            tools: List of tool schemas for function calling
            stream: Whether to stream the response
            think: Ignored (kept for interface compatibility with OllamaClient)

        Yields:
            Tuple of (content, tool_calls, thinking, is_error)
        """
        url = f"{self.base_url}/v1/chat/completions"
        payload = self._build_payload(messages, tools, stream)

        try:
            for attempt in range(MAX_RETRIES + 1):
                try:
                    response = requests.post(
                        url,
                        json=payload,
                        stream=stream,
                        timeout=self.timeout,
                    )
                    response.raise_for_status()

                    if stream:
                        yield from self._handle_stream(response)
                    else:
                        yield from self._handle_non_stream(response)
                    break  # Success, exit retry loop

                except Exception as e:
                    if _is_retryable_error(e) and attempt < MAX_RETRIES:
                        time.sleep(RETRY_DELAY * (2 ** attempt))
                        continue
                    raise  # Re-raise for outer except handlers

        except requests.exceptions.Timeout:
            error_msg = (
                f"Request timed out after {self.timeout} seconds. "
                "The server is taking too long to respond."
            )
            yield error_msg, None, None, True
        except requests.exceptions.ConnectionError as e:
            error_msg = (
                f"Could not connect to server at {self.base_url}. "
                f"Make sure the server is running. Details: {e}"
            )
            yield error_msg, None, None, True
        except requests.exceptions.HTTPError as e:
            if e.response is not None and e.response.status_code == 404:
                error_msg = (
                    f"Model '{self.model}' not found (404). "
                    f"Check if the model is loaded. Details: {e}"
                )
            elif e.response is not None:
                error_msg = f"HTTP {e.response.status_code} - {e}"
            else:
                error_msg = f"HTTP error - {e}"
            yield error_msg, None, None, True
        except requests.exceptions.TooManyRedirects:
            error_msg = "Too many redirects. Check server configuration."
            yield error_msg, None, None, True
        except json.JSONDecodeError:
            error_msg = "Invalid JSON response"
            yield error_msg, None, None, True
        except (KeyError, TypeError):
            error_msg = "Invalid response format"
            yield error_msg, None, None, True
        except Exception as e:
            error_msg = f"Unexpected error - {e}"
            yield error_msg, None, None, True

    def _handle_stream(
        self, response: requests.Response
    ) -> Generator[tuple[str, Optional[list], Optional[str], bool], None, None]:
        """Handle SSE streaming response from OpenAI-compatible API.

        OpenAI streams tool_calls incrementally by index:
            chunk 1: tool_calls[{index:0, id:"call_1", function:{name:"run_cmd"}}]
            chunk 2: tool_calls[{index:0, function:{arguments:"{\"co"}}]
            chunk 3: tool_calls[{index:0, function:{arguments:"mmand\":"}}]
            chunk 4: tool_calls[{index:0, function:{arguments:"\"ls\"}"}}]

        We accumulate deltas by index, then yield completed tool calls
        once all arguments are fully received.
        """
        accumulated_calls: dict[int, dict[str, Any]] = {}
        content_parts: list[str] = []

        for raw_line in response.iter_lines():
            if not raw_line:
                continue

            line = raw_line.decode("utf-8", errors="ignore")

            # SSE format: "data: {json}" or "data: [DONE]"
            if line.startswith("data: "):
                line = line[6:]
            else:
                continue

            if line.strip() == "[DONE]":
                break

            try:
                data = json.loads(line)
            except json.JSONDecodeError:
                continue

            choices = data.get("choices", [])
            if not choices:
                continue

            delta = choices[0].get("delta", {})

            content = delta.get("content", "") or ""
            if content:
                content_parts.append(content)

            # Accumulate tool call deltas by index
            raw_tool_calls = delta.get("tool_calls")
            if raw_tool_calls:
                for tc in raw_tool_calls:
                    idx = tc.get("index", 0)
                    if idx not in accumulated_calls:
                        accumulated_calls[idx] = {
                            "id": tc.get("id", ""),
                            "function": {"name": "", "arguments": ""},
                        }
                    entry = accumulated_calls[idx]

                    if tc.get("id"):
                        entry["id"] = tc["id"]

                    func_delta = tc.get("function", {})
                    if func_delta.get("name"):
                        entry["function"]["name"] = func_delta["name"]
                    if func_delta.get("arguments"):
                        entry["function"]["arguments"] += func_delta["arguments"]

            # Accumulate token usage if present in the final chunk
            usage = data.get("usage")
            if usage:
                prompt_tokens = usage.get("prompt_tokens", 0)
                completion_tokens = usage.get("completion_tokens", 0)
                self._accumulate_token_stats(prompt_tokens, completion_tokens)

        # Yield accumulated content
        if content_parts:
            yield "".join(content_parts), None, None, False

        # Yield completed tool calls (parse arguments JSON string -> dict)
        if accumulated_calls:
            tool_calls = []
            for idx in sorted(accumulated_calls.keys()):
                entry = accumulated_calls[idx]
                args_str = entry["function"].get("arguments", "{}")
                try:
                    args = json.loads(args_str) if args_str else {}
                except (json.JSONDecodeError, TypeError):
                    args = {}
                tool_calls.append(
                    {
                        "id": entry["id"],
                        "function": {
                            "name": entry["function"]["name"],
                            "arguments": args,
                        },
                    }
                )
            yield "", tool_calls, None, False

    def _handle_non_stream(
        self, response: requests.Response
    ) -> Generator[tuple[str, Optional[list], Optional[str], bool], None, None]:
        """Handle non-streaming response from OpenAI-compatible API."""
        data = response.json()
        choices = data.get("choices", [])
        if not choices:
            return

        message = choices[0].get("message", {})
        content = message.get("content", "") or ""
        raw_tool_calls = message.get("tool_calls")

        tool_calls = None
        if raw_tool_calls:
            tool_calls = self._parse_tool_calls(raw_tool_calls)

        usage = data.get("usage", {})
        if usage:
            prompt_tokens = usage.get("prompt_tokens", 0)
            completion_tokens = usage.get("completion_tokens", 0)
            self._accumulate_token_stats(prompt_tokens, completion_tokens)

        yield content, tool_calls, None, False
