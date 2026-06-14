"""Model auto-discovery - probe local LLM servers and collect available models."""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Callable

import requests


def _extract_ollama_models(response_data: dict) -> list[str]:
    """Extract model names from Ollama /api/tags response."""
    return [model["name"] for model in response_data.get("models", [])]


def _extract_openai_models(response_data: dict) -> list[str]:
    """Extract model IDs from OpenAI /v1/models response."""
    return [model["id"] for model in response_data.get("data", [])]

@dataclass
class ModelEntry:
    """A model available on a discovered server."""

    name: str
    provider: str
    base_url: str
    server_name: str


# (server_name, provider, base_url, api_endpoint, model_list_extractor)
_KNOWN_SERVERS: list[tuple[str, str, str, str, Callable[[dict], list[str]]]] = [
    (
        "Ollama",
        "ollama",
        "http://localhost:11434",
        "/api/tags",
        _extract_ollama_models,
    ),
    (
        "LM Studio",
        "openai",
        "http://localhost:1234",
        "/v1/models",
        _extract_openai_models,
    ),
    (
        "vLLM",
        "openai",
        "http://localhost:8000",
        "/v1/models",
        _extract_openai_models,
    ),
    (
        "LocalAI",
        "openai",
        "http://localhost:8080",
        "/v1/models",
        _extract_openai_models,
    ),
]

_PROBE_TIMEOUT: float = 1.5
_MAX_RETRIES: int = 2
_RETRY_DELAY: float = 5.0


def discover_models(
    timeout: float = _PROBE_TIMEOUT,
    max_retries: int = _MAX_RETRIES,
) -> list[ModelEntry]:
    """Probe all known local servers and return available models.

    Args:
        timeout: HTTP request timeout in seconds per server.
        max_retries: Number of retries per server on transient errors.

    Returns:
        List of ModelEntry from all reachable servers.
    """
    models: list[ModelEntry] = []
    for server_name, provider, base_url, endpoint, extractor in _KNOWN_SERVERS:
        for attempt in range(max_retries + 1):
            try:
                response = requests.get(
                    f"{base_url}{endpoint}",
                    timeout=timeout,
                )
                if response.status_code == 200:
                    for name in extractor(response.json()):
                        models.append(
                            ModelEntry(
                                name=name,
                                provider=provider,
                                base_url=base_url,
                                server_name=server_name,
                            )
                        )
                    break
            except requests.exceptions.RequestException:
                if attempt < max_retries:
                    time.sleep(_RETRY_DELAY * (2 ** attempt))
                continue
    return models


def resolve_model(model_name: str, models: list[ModelEntry]) -> ModelEntry | None:
    """Find the server info for a given model name.

    Args:
        model_name: The model name to look up.
        models: List of discovered models.

    Returns:
        Matching ModelEntry, or None if not found.
    """
    for entry in models:
        if entry.name == model_name:
            return entry
    return None
