"""FastAPI application for Gradum Server.

NOTE: HTTP server is EXPERIMENTAL. Agent execution via HTTP API
is not fully implemented. Use CLI mode for full functionality.
"""

from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI

from gradum.server.config import ServerConfig
from gradum.server.routes import register_routes
from gradum.server.session import SessionManager


def create_app(config: Optional[ServerConfig] = None) -> FastAPI:
    """Create FastAPI application."""

    config = config or ServerConfig()

    # Configure session manager
    SessionManager.configure(max_sessions=config.max_sessions, session_timeout=config.session_timeout)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        """Application lifespan.

        Args:
            app: FastAPI application instance
        """
        # Startup
        print(f"Gradum Server starting on {config.host}:{config.port}")
        yield
        # Shutdown
        print("Gradum Server shutting down")

    app = FastAPI(
        title="Gradum Server",
        description="HTTP API for Gradum AI Coding Agent",
        version="0.6.0",
        lifespan=lifespan,
    )

    # Register routes
    register_routes(app, config)

    return app