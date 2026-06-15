"""Server configuration."""

from dataclasses import dataclass
from typing import Optional


@dataclass
class ServerConfig:
    """Configuration for Gradum HTTP Server."""

    host: str = "localhost"
    port: int = 8765
    debug: bool = False

    # Session settings
    max_sessions: int = 10
    session_timeout: int = 3600  # seconds

    # Agent settings (passed to AgentConfig)
    model: Optional[str] = None
    think: bool = False
    temperature: float = 0.7
    top_p: float = 0.9
    num_ctx: int = 4096
    num_predict: int = 16384
    timeout: int = 300

    # Provider settings
    provider: str = "ollama"
    base_url: Optional[str] = None