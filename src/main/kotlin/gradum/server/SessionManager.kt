/*
 * Copyright (c) 2026 Gradum team, Some Rights Reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SessionManager.kt  2026-06-20 20:22:43 Created by gwy
 */

package gradum.server

import gradum.AgentConfiguration
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

private val logger: org.slf4j.Logger = LoggerFactory.getLogger("SessionManager")

enum class SessionStatus {
    Processing,
    WaitingForInput,
    Completed,
    Cancelled,
    Error,
}

/**
 * Holds the per-session state for an in-flight or completed agent run:
 * the recorded NDJSON events, current status, and creation timestamp.
 */
class Session(
    val sessionIdentifier: String,
    var currentStatus: SessionStatus = SessionStatus.Processing,
    val creationTime: Instant = Instant.now(),
    val recordedEvents: MutableList<Map<String, Any>> = mutableListOf(),
    val agentConfiguration: AgentConfiguration? = null,
) {
    fun recordEvent(eventType: String, eventData: Map<String, Any>): Unit {
        val event: Map<String, Any> = mapOf(
            "type" to eventType,
            "timestamp" to Instant.now().toString(),
            "data" to eventData,
        )
        recordedEvents.add(event)
    }

    fun toClientMap(): Map<String, Any> {
        return mapOf(
            "sessionId" to sessionIdentifier,
            "status" to currentStatus.name,
            "createdAt" to creationTime.toString(),
            "eventCount" to recordedEvents.size,
        )
    }
}

/**
 * Process-wide registry of active [Session]s with LRU-style expiration.
 *
 * Bounded by [configure]'s `maxSessions` and `sessionTimeout` parameters so
 * long-running servers don't leak memory or accumulate stale runs.
 */
object SessionManager {

    private val activeSessions: MutableMap<String, Session> = mutableMapOf()
    private var maxSessionLimit: Int = 10
    private var sessionTimeoutValue: Int = 3600

    fun configure(maxSessions: Int = 10, sessionTimeout: Int = 3600): Unit {
        maxSessionLimit = maxSessions
        sessionTimeoutValue = sessionTimeout
    }

    fun createSession(agentConfiguration: AgentConfiguration? = null): Session {
        removeExpiredSessions()

        if (activeSessions.size >= maxSessionLimit) {
            throw RuntimeException("Maximum sessions reached")
        }

        val sessionId: String = "sess_${UUID.randomUUID().toString().take(8)}"
        val newSession: Session = Session(
            sessionIdentifier = sessionId,
            agentConfiguration = agentConfiguration,
        )
        activeSessions[sessionId] = newSession
        return newSession
    }

    fun getSession(sessionId: String): Session? {
        return activeSessions[sessionId]
    }

    fun getSessionOrThrow(sessionId: String): Session {
        return activeSessions[sessionId]
            ?: throw IllegalArgumentException("Session not found: $sessionId")
    }

    fun listAllSessions(): List<Session> {
        removeExpiredSessions()
        return activeSessions.values.toList()
    }

    fun cancelSession(sessionId: String): Boolean {
        val session: Session = activeSessions[sessionId] ?: return false
        if (session.currentStatus == SessionStatus.Processing || session.currentStatus == SessionStatus.WaitingForInput) {
            session.currentStatus = SessionStatus.Cancelled
            session.recordEvent("session_cancelled", mapOf("sessionId" to sessionId))
            return true
        }
        return false
    }

    fun deleteSession(sessionId: String): Boolean {
        return activeSessions.remove(sessionId) != null
    }

    fun countActiveSessions(): Int {
        removeExpiredSessions()
        return activeSessions.size
    }

    private fun removeExpiredSessions(): Unit {
        val now: Instant = Instant.now()
        val expiredIds: List<String> = activeSessions.filter { (_, session: Session) ->
            val ageSeconds: Long = java.time.Duration.between(session.creationTime, now).seconds
            ageSeconds > sessionTimeoutValue ||
                session.currentStatus in listOf(SessionStatus.Completed, SessionStatus.Cancelled, SessionStatus.Error)
        }.keys.toList()

        for (sessionId in expiredIds) {
            activeSessions.remove(sessionId)
        }
    }
}
