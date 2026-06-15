"""HTTP routes for Gradum Server.

NOTE: The HTTP server and /action endpoint are EXPERIMENTAL.
Agent execution via HTTP API is not yet fully implemented.
Use CLI mode (python -m gradum) for full functionality.
"""

import json
import time
from collections.abc import AsyncGenerator
from typing import Any, Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from gradum.client import AgentConfig
from gradum.discovery import discover_models
from gradum.server.session import SessionManager, SessionStatus
from gradum.skills import Skills


# Request models
class ActionRequest(BaseModel):
    """Request for /action endpoint."""

    message: Optional[str] = None
    session_id: Optional[str] = None
    input: Optional[dict[str, Any]] = Field(None, description="User input response")
    cancel: bool = False
    model: Optional[str] = None
    config: Optional[dict[str, Any]] = None


# Response helpers
def event_to_ndjson(event: dict[str, Any]) -> str:
    """Convert event to NDJSON line."""
    return json.dumps(event, ensure_ascii=False) + "\n"


def create_event(event_type: str, data: dict[str, Any]) -> dict[str, Any]:
    """Create an event dict."""
    return {
        "type": event_type,
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "data": data,
    }


def register_routes(app: FastAPI, server_config: Any) -> None:
    """Register all routes on the FastAPI app."""

    @app.post("/action")
    async def action(request: ActionRequest):
        """
        Handle action requests.

        EXPERIMENTAL: Agent execution is not fully implemented.
        Currently returns placeholder responses.

        - message: Start a new conversation
        - session_id + input: Inject user input
        - session_id + cancel: Cancel session
        """
        # Cancel session
        if request.session_id and request.cancel:
            session = SessionManager.get_or_raise(request.session_id)
            SessionManager.cancel(request.session_id)
            return {"success": True, "status": "cancelled", "session_id": request.session_id}

        # Inject user input
        if request.session_id and request.input:
            session = SessionManager.get_or_raise(request.session_id)

            if session.status != SessionStatus.WAITING_INPUT:
                raise HTTPException(400, "Session is not waiting for input")

            request_id = request.input.get("request_id")
            answer = request.input.get("answer")

            if not request_id or not answer:
                raise HTTPException(400, "Missing request_id or answer")

            # Mark input received, session will continue processing
            session.pending_input = None
            session.status = SessionStatus.PROCESSING
            session.add_event("input_received", {"request_id": request_id, "answer": answer})

            # Return streaming response for continued events
            async def stream_events() -> AsyncGenerator[str, None]:
                # Send input_received event
                yield event_to_ndjson(create_event("input_received", {"request_id": request_id, "answer": answer}))

                # Continue agent execution (placeholder - will be implemented)
                # For now, just return session status
                yield event_to_ndjson(create_event("session_status", {"status": "processing"}))

            return StreamingResponse(stream_events(), media_type="application/x-ndjson")

        # Start new conversation
        if request.message:
            # Build agent config
            config_dict = request.config or {}
            agent_config = AgentConfig(
                model=request.model or server_config.model or "minimax-m2.5:cloud",
                provider=server_config.provider,
                base_url=server_config.base_url,
                think=config_dict.get("think", server_config.think),
                temperature=config_dict.get("temperature", server_config.temperature),
                top_p=config_dict.get("top_p", server_config.top_p),
                num_ctx=config_dict.get("num_ctx", server_config.num_ctx),
                num_predict=config_dict.get("num_predict", server_config.num_predict),
                timeout=config_dict.get("timeout", server_config.timeout),
            )

            # Create session
            session = SessionManager.create(agent_config)
            session.add_event("session_start", {"session_id": session.session_id, "model": agent_config.model})

            # Return streaming response
            async def stream_new_session() -> AsyncGenerator[str, None]:
                # Send session_start event
                yield event_to_ndjson(create_event("session_start", {"session_id": session.session_id, "model": agent_config.model}))

                # Placeholder: Agent execution will be integrated here
                # For now, return a simple response
                yield event_to_ndjson(create_event("thinking", {"content": "Processing your request..."}))
                yield event_to_ndjson(create_event("llm_response", {"content": f"Received: {request.message}"}))
                yield event_to_ndjson(create_event("session_end", {"elapsed_seconds": 1}))

                # Mark session completed
                session.status = SessionStatus.COMPLETED

            return StreamingResponse(stream_new_session(), media_type="application/x-ndjson")

        # Invalid request
        raise HTTPException(400, "Invalid request: need message, input, or cancel")

    @app.get("/sessions")
    async def list_sessions():
        """List all sessions."""
        sessions = SessionManager.list_all()
        return {"sessions": [s.to_dict() for s in sessions]}

    @app.get("/sessions/{session_id}")
    async def get_session(session_id: str):
        """Get a specific session."""
        session = SessionManager.get_or_raise(session_id)
        return session.to_dict()

    @app.get("/sessions/{session_id}/events")
    async def get_events(session_id: str):
        """Get events for a session (NDJSON stream)."""
        session = SessionManager.get_or_raise(session_id)

        async def stream_session_events() -> AsyncGenerator[str, None]:
            for event in session.events:
                yield event_to_ndjson(event)

        return StreamingResponse(stream_session_events(), media_type="application/x-ndjson")

    @app.get("/health")
    async def health():
        """Health check."""
        return {
            "status": "healthy",
            "active_sessions": SessionManager.count(),
        }

    @app.get("/models")
    async def list_models():
        """List available models."""
        models = discover_models()
        return {
            "models": [{"name": m.name, "provider": m.provider, "server": m.base_url} for m in models]
        }

    @app.get("/skills")
    async def list_skills():
        """List available skills."""
        skills = Skills()
        return {
            "skills": [
                {"name": s.name, "description": s.description, "alias": s.alias}
                for s in skills._skills.values()
            ]
        }