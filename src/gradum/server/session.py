"""Session management for Gradum Server."""

import time
import uuid
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, List, Optional

from gradum.client import AgentConfig


class SessionStatus(Enum):
    """Session status."""

    PROCESSING = "processing"
    WAITING_INPUT = "waiting_input"
    COMPLETED = "completed"
    CANCELLED = "cancelled"
    ERROR = "error"


@dataclass
class PendingInput:
    """Pending user input request."""

    request_id: str
    question: str
    options: Optional[List[str]] = None
    timestamp: float = field(default_factory=time.time)

    def to_dict(self) -> dict[str, Any]:
        """Convert to dict for API response."""
        return {
            "request_id": self.request_id,
            "question": self.question,
            "options": self.options,
            "timestamp": self.timestamp,
        }


@dataclass
class Session:
    """A session represents one conversation with the Agent."""

    session_id: str
    status: SessionStatus
    created_at: float
    events: List[dict[str, Any]] = field(default_factory=list)
    pending_input: Optional[PendingInput] = None
    agent_config: Optional[AgentConfig] = None
    last_event_index: int = 0  # For streaming events

    def add_event(self, event_type: str, data: dict[str, Any]) -> None:
        """Add an event to the session."""
        event = {
            "type": event_type,
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
            "data": data,
        }
        self.events.append(event)
        self.last_event_index = len(self.events)

    def to_dict(self) -> dict[str, Any]:
        """Convert session to dict for API response."""
        return {
            "session_id": self.session_id,
            "status": self.status.value,
            "created_at": time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(self.created_at)),
            "pending_input": self.pending_input.to_dict() if self.pending_input else None,
            "event_count": len(self.events),
        }


class SessionManager:
    """Manages all sessions."""

    _sessions: dict[str, Session] = {}
    _max_sessions: int = 10
    _session_timeout: int = 3600

    @classmethod
    def configure(cls, max_sessions: int = 10, session_timeout: int = 3600) -> None:
        """Configure session manager."""
        cls._max_sessions = max_sessions
        cls._session_timeout = session_timeout

    @classmethod
    def create(cls, agent_config: Optional[AgentConfig] = None) -> Session:
        """Create a new session."""
        # Clean up expired sessions
        cls._cleanup_expired()

        # Check max sessions
        if len(cls._sessions) >= cls._max_sessions:
            raise RuntimeError("Maximum sessions reached")

        session_id = f"sess_{uuid.uuid4().hex[:8]}"
        session = Session(
            session_id=session_id,
            status=SessionStatus.PROCESSING,
            created_at=time.time(),
            agent_config=agent_config,
        )
        cls._sessions[session_id] = session
        return session

    @classmethod
    def get(cls, session_id: str) -> Optional[Session]:
        """Get a session by ID."""
        return cls._sessions.get(session_id)

    @classmethod
    def get_or_raise(cls, session_id: str) -> Session:
        """Get a session by ID, raise if not found."""
        session = cls._sessions.get(session_id)
        if not session:
            raise ValueError(f"Session not found: {session_id}")
        return session

    @classmethod
    def list_all(cls) -> List[Session]:
        """List all sessions."""
        cls._cleanup_expired()
        return list(cls._sessions.values())

    @classmethod
    def cancel(cls, session_id: str) -> bool:
        """Cancel a session."""
        session = cls._sessions.get(session_id)
        if session and session.status in (SessionStatus.PROCESSING, SessionStatus.WAITING_INPUT):
            session.status = SessionStatus.CANCELLED
            session.add_event("session_cancelled", {"session_id": session_id})
            return True
        return False

    @classmethod
    def delete(cls, session_id: str) -> bool:
        """Delete a session."""
        if session_id in cls._sessions:
            del cls._sessions[session_id]
            return True
        return False

    @classmethod
    def _cleanup_expired(cls) -> None:
        """Remove expired sessions."""
        now = time.time()
        expired = [
            sid
            for sid, sess in cls._sessions.items()
            if now - sess.created_at > cls._session_timeout
            or sess.status in (SessionStatus.COMPLETED, SessionStatus.CANCELLED, SessionStatus.ERROR)
        ]
        for sid in expired:
            del cls._sessions[sid]

    @classmethod
    def count(cls) -> int:
        """Count active sessions."""
        cls._cleanup_expired()
        return len(cls._sessions)