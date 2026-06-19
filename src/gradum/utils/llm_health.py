# Copyright (c) 2026 Gradum Authors, Ge Wangyang. Licensed under MIT.
# See LICENSE for details.

"""LLM health check helper.

Provides a generic health check for LLM backends (Ollama, OpenAI-compatible).
Does NOT auto-start any service - only checks and reports status.
"""

import requests

_CHECK_TIMEOUT = 2.0


def check_llm_health(
    base_url: str,
    provider: str = "ollama",
) -> dict:
    """Check if LLM server is reachable.

    Args:
        base_url: LLM server base URL (e.g. http://localhost:11434)
        provider: "ollama" or "openai_compatible"

    Returns:
        dict with keys:
        - ok: bool - whether server is reachable
        - code: str - error code (OK, CONNECTION_ERROR, TIMEOUT, etc.)
        - message: str - human-readable message
        - provider: str - provider type
        - base_url: str - the checked URL
    """
    # Determine endpoint based on provider
    if provider == "ollama":
        endpoint = f"{base_url}/api/tags"
    else:
        endpoint = f"{base_url}/v1/models"

    try:
        r = requests.get(endpoint, timeout=_CHECK_TIMEOUT)
        if r.status_code == 200:
            return {
                "ok": True,
                "code": "OK",
                "message": "LLM server is reachable",
                "provider": provider,
                "base_url": base_url,
            }
        elif r.status_code == 401:
            return {
                "ok": False,
                "code": "AUTH_ERROR",
                "message": "API key is missing or invalid",
                "provider": provider,
                "base_url": base_url,
            }
        elif r.status_code == 404:
            return {
                "ok": False,
                "code": "HTTP_404",
                "message": f"Endpoint not found. Server may not be a valid {provider} backend.",
                "provider": provider,
                "base_url": base_url,
            }
        elif r.status_code >= 500:
            return {
                "ok": False,
                "code": f"HTTP_{r.status_code}",
                "message": f"Server error: HTTP {r.status_code}",
                "provider": provider,
                "base_url": base_url,
            }
        else:
            return {
                "ok": False,
                "code": f"HTTP_{r.status_code}",
                "message": f"Unexpected HTTP status: {r.status_code}",
                "provider": provider,
                "base_url": base_url,
            }
    except requests.exceptions.Timeout:
        return {
            "ok": False,
            "code": "TIMEOUT",
            "message": "Server timed out. Check if server is running or increase timeout.",
            "provider": provider,
            "base_url": base_url,
        }
    except requests.exceptions.ConnectionError:
        return {
            "ok": False,
            "code": "CONNECTION_ERROR",
            "message": "Cannot connect to server. Check if server is running and URL is correct.",
            "provider": provider,
            "base_url": base_url,
        }
    except requests.exceptions.RequestException as e:
        return {
            "ok": False,
            "code": "REQUEST_ERROR",
            "message": f"Request failed: {str(e)}",
            "provider": provider,
            "base_url": base_url,
        }