# Copyright (c) 2026 Gradum Authors, Ge Wangyang. Licensed under MIT.
# See LICENSE for details.

"""IO utilities for context management."""

import json
import logging
import time
from pathlib import Path
from typing import Optional

from gradum.utils.encryption import decrypt_content, encrypt_content

_logger = logging.getLogger(__name__)


class ContextManager:
    """Manages conversation context persistence."""

    def __init__(self, output_dir: Path, max_messages: int = 30):
        self._output_dir = output_dir
        self._max_messages = max_messages

    def save(self, messages: list[dict], model: str, full_read_files: Optional[set[str]] = None) -> bool:
        """Save conversation context to JSON file.

        Args:
            messages: List of conversation messages
            model: Model name
            full_read_files: Set of file paths that were fully read (without line_range).
                             These file contents will not be saved since LLM already knows them.
        """
        context_file = self._output_dir / "context.json"
        self._output_dir.mkdir(parents=True, exist_ok=True)

        saveable = [m for m in messages if m.get("role") not in ("system", "tool")]
        cleaned = self._clean_messages(saveable, full_read_files or set())

        # Encrypt user and assistant messages (prevent context injection attacks)
        for msg in cleaned:
            if msg.get("role") in ("user", "assistant") and msg.get("content"):
                msg["content"] = encrypt_content(msg["content"])
                msg["_encrypted"] = True

        context_data = {
            "messages": cleaned,
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
            "model": model,
            "message_count": len(cleaned),
        }

        try:
            with open(context_file, "w", encoding="utf-8") as f:
                json.dump(context_data, f, ensure_ascii=False, indent=2)
            return True
        except (IOError, OSError, TypeError) as e:
            _logger.error("Failed to save context: %s", e)
            return False

    def load(self) -> list[dict]:
        """Load conversation context from JSON file."""
        context_file = self._output_dir / "context.json"

        if not context_file.exists() or not context_file.is_file():
            return []

        try:
            with open(context_file, "r", encoding="utf-8") as f:
                context_data = json.load(f)

            messages = context_data.get("messages", [])
            if not isinstance(messages, list):
                return []

            # Decrypt encrypted messages
            for msg in messages:
                if msg.get("_encrypted"):
                    try:
                        msg["content"] = decrypt_content(msg["content"])
                        msg.pop("_encrypted", None)
                    except (TypeError, ValueError, UnicodeDecodeError) as e:
                        _logger.warning("Failed to decrypt message: %s, using raw content", e)

            filtered = [m for m in messages if m.get("role") not in ("system", "tool")]
            return filtered[-self._max_messages:]

        except (json.JSONDecodeError, IOError, OSError) as e:
            _logger.error("Failed to load context: %s", e)
            return []

    def _clean_messages(self, messages: list[dict], full_read_files: Optional[set[str]] = None) -> list[dict]:
        """Clean messages for storage.

        Args:
            messages: List of messages to clean
            full_read_files: File paths that were fully read. Their content will be
                            replaced with a summary since LLM already knows them.
        """
        if full_read_files is None:
            full_read_files = set()

        cleaned = []
        pending_assistant = None

        for msg in messages:
            role = msg.get("role")
            content = msg.get("content", "")

            if role == "assistant":
                if content is None or (isinstance(content, str) and not content.strip()):
                    continue
                pending_assistant = msg
            elif role == "user":
                if pending_assistant:
                    cleaned.append(pending_assistant.copy())
                    pending_assistant = None
                cleaned.append(msg.copy())
            elif role == "tool":
                simplified = self._maybe_summarize_tool_content(msg, full_read_files)
                if simplified:
                    cleaned.append(simplified)

        if pending_assistant:
            cleaned.append(pending_assistant.copy())

        return cleaned

    @staticmethod
    def _maybe_summarize_tool_content(msg: dict, full_read_files: set[str]) -> Optional[dict]:
        """Discard full file read content - LLM already knows it from context.

        Args:
            msg: Tool message
            full_read_files: Set of file paths that were fully read

        Returns:
            None to skip this message (file was fully read), msg to keep it
        """
        content = msg.get("content", "")

        if not content.startswith("Success: Read "):
            return msg

        for file_path in full_read_files:
            if f'Success: Read {file_path}' in content:
                return None
        return msg