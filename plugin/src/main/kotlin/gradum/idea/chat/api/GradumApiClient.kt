package gradum.idea.chat.api

import com.intellij.openapi.diagnostic.Logger
import gradum.idea.PluginConfig
import gradum.idea.server.ServerTokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * HTTP client for the Gradum backend REST API (models and streaming NDJSON at `POST /events`).
 *
 * NDJSON parsing is centralized here: each line is JSON-decoded independently;
 * malformed lines are logged at WARN and skipped without aborting the stream.
 * This gives callers a typed `Flow<JsonObject>` contract with per-line resilience.
 *
 * @property baseUrl The base URL of the Gradum server (default: `http://localhost:8765`).
 */
class GradumApiClient(val baseUrl: String = "http://localhost:8765") {

  private val log: Logger = Logger.getInstance(GradumApiClient::class.java)
  private val client: HttpClient = HttpClient.newBuilder()
    .connectTimeout(PluginConfig.API_CONNECT_TIMEOUT)
    .build()

  /**
   * Builds a request for [url] with the embedded server's bearer token
   * attached when one is published. The token is read lazily per request
   * (cached by file mtime in [ServerTokenStore]) so a server restart that
   * rotates the token is picked up without restarting the IDE.
   */
  private fun newRequest(url: String): HttpRequest.Builder {
    val builder: HttpRequest.Builder = HttpRequest.newBuilder().uri(URI.create(url))
    ServerTokenStore.currentToken()?.let { token ->
      builder.header("Authorization", "Bearer $token")
    }
    return builder
  }

  /**
   * Sends the request built by [buildRequest]. On 401 the token cache is
   * invalidated and the request is rebuilt and sent once more, so a token
   * rotated after this client last read the file still succeeds.
   */
  private fun <T> sendWithAuthRetry(
    bodyHandler: HttpResponse.BodyHandler<T>,
    buildRequest: () -> HttpRequest
  ): HttpResponse<T> {
    val firstResponse: HttpResponse<T> = client.send(buildRequest(), bodyHandler)
    if (firstResponse.statusCode() != 401) return firstResponse
    ServerTokenStore.invalidate()
    return client.send(buildRequest(), bodyHandler)
  }

  /**
   * Wire-shaped image attachment for [sendMessage]. Mirrors the
   * server-side `AttachmentDto` exactly (the `type` field is
   * `image` for now; the discriminator exists so future
   * attachment kinds (e.g. PDF, audio) share the same envelope
   * without a breaking change).
   *
   * The base64 payload in [data] is the original file bytes
   * (no re-encoding) and [mime] is the IANA type derived from
   * the file extension (`image/png`, `image/jpeg`, `image/webp`,
   * …): the server gets exactly what the user picked.
   */
  data class ApiImageAttachment(
    val mime: String,
    val data: String,
    val filename: String
  )

  /**
   * Request payload for [sendMessage]. Groups the 10 per-call
   * parameters into a single data class so callers construct one
   * object instead of passing a long positional / named list.
   */
  data class SendMessageRequest(
    val message: String,
    val toolMode: String? = null,
    val modelName: String? = null,
    val sessionId: String? = null,
    val messageId: String? = null,
    val projectRoot: String? = null,
    val toolCallXml: String? = null,
    val loadContext: Boolean = true,
    val displayText: String? = null,
    val promptVariant: String? = null,
    val thinkingLevel: String? = null,
    val modelParams: Map<String, String>? = null,
    val imageAttachments: List<ApiImageAttachment> = emptyList()
  )

  private val ndjsonParser: Json = Json {
    ignoreUnknownKeys = true; isLenient = true
  }

  /**
   * Fetches the list of available LLM models discovered by the server.
   *
   * The server probes local LLM providers (Ollama, LM Studio, vLLM, LocalAI)
   * and returns a consolidated list of models. This is typically called on
   * plugin startup and when the user clicks the refresh button.
   *
   * @return A JSON string containing the model list.
   * @throws java.net.ConnectException If the server is not running.
   */
  suspend fun getModels(): String = withContext(Dispatchers.IO) {
    val response: HttpResponse<String> =
      sendWithAuthRetry(HttpResponse.BodyHandlers.ofString()) {
        newRequest("$baseUrl/models").GET().build()
      }

    if (response.statusCode() !in 200..299) {
      val bodyPreview: String = response.body().take(n = 200)
      throw IOException(
        "Server returned HTTP ${response.statusCode()} for GET $baseUrl/models" +
          (if (bodyPreview.isNotBlank()) ": $bodyPreview" else "")
      )
    }

    response.body()
  }

  /**
   * Aborts an in-flight `/events` run on the server.
   *
   * @param runId The run to stop (the envelope's `runId`).
   * @return A JSON string with the stop status.
   */
  suspend fun stopSession(runId: String): String = withContext(Dispatchers.IO) {
    val requestBody: JsonObject = buildJsonObject { put("runId", runId) }

    val response: HttpResponse<String> =
      sendWithAuthRetry(HttpResponse.BodyHandlers.ofString()) {
        newRequest("$baseUrl/stop")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
          .build()
      }

    response.body()
  }

  /**
   * Answers a pending agent-initiated question (`ask_interaction`). The server
   * parks the skill's coroutine until it gets one of `choice` / `text` /
   * `cancelled` for the matching [requestId]. Only one of [choice], [text] or
   * [cancelled] is sent per call.
   *
   * @return `true` when the server acknowledged the answer (HTTP 200), `false`
   *   when the request id is unknown/already answered (404) or the call failed.
   */
  suspend fun respondToAsk(
    sessionId: String,
    requestId: String,
    text: String? = null,
    choice: String? = null,
    cancelled: Boolean = false
  ): Boolean = withContext(Dispatchers.IO) {
    val requestBody: JsonObject = buildJsonObject {
      put("sessionId", sessionId)
      put("requestId", requestId)
      when {
        cancelled -> put("cancelled", true)
        choice != null -> put("choice", choice)
        text != null -> put("text", text)
      }
    }

    try {
      val response: HttpResponse<String> =
        sendWithAuthRetry(HttpResponse.BodyHandlers.ofString()) {
          newRequest("$baseUrl/events/respond")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        }
      response.statusCode() == 200
    } catch (respondException: Exception) {
      log.warn("Failed to respond to ask $requestId on server", respondException)
      false
    }
  }

  /**
   * Rewinds a session's server-side context to just before the message
   * identified by [messageId], dropping that message and everything after it.
   * Mirrors the plugin's local withdrawal so the persisted `context.json` and
   * the on-screen transcript stay aligned.
   *
   * @return `true` when the server truncated the context, `false` when the
   *   message id was not found or the request failed.
   */
  suspend fun rewindSession(projectRoot: String, sessionId: String, messageId: String): Boolean =
    withContext(Dispatchers.IO) {
      val requestBody: JsonObject = buildJsonObject {
        put("projectRoot", projectRoot)
        put("sessionId", sessionId)
        put("messageId", messageId)
      }

      try {
        val response: HttpResponse<String> =
          sendWithAuthRetry(HttpResponse.BodyHandlers.ofString()) {
            newRequest("$baseUrl/session/rewind")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
              .build()
          }
        response.statusCode() == 200
      } catch (rewindException: Exception) {
        log.warn("Failed to rewind session $sessionId on server", rewindException)
        false
      }
    }

  /**
   * Deletes a session on the server: the transcript directory under
   * `<projectRoot>/.gradum/sessions/` and the matching chat DB row.
   *
   * @param projectRoot The project the session belongs to.
   * @param sessionId The client-generated conversation session id.
   * @return `true` when the server confirmed deletion, `false` when the
   *   session was not found or the request failed.
   */
  suspend fun deleteSession(projectRoot: String, sessionId: String): Boolean =
    withContext(Dispatchers.IO) {
      val requestBody: JsonObject = buildJsonObject {
        put("projectRoot", projectRoot)
        put("sessionId", sessionId)
      }

      try {
        val response: HttpResponse<String> =
          sendWithAuthRetry(HttpResponse.BodyHandlers.ofString()) {
            newRequest("$baseUrl/session/delete")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
              .build()
          }
        response.statusCode() == 200
      } catch (deleteException: Exception) {
        log.warn("Failed to delete session $sessionId on server", deleteException)
        false
      }
    }

  /**
   * One session row from `GET /sessions`, mirroring the server's
   * transcript-header metadata (`id` / `title` / `model` / `createdAt` /
   * `updatedAt`).
   */
  data class ApiSessionSummary(
    val sessionId: String,
    val title: String,
    val modelName: String,
    val createdAt: Long,
    val updatedAt: Long
  )

  /**
   * The server's authoritative session projection from `GET /session/state`.
   *
   * [messages] are the raw agent-history maps (`session-state.json`); the
   * caller maps them to bubbles. [pendingAsks] / [subAgents] are live extras
   * computed per request, and [isRunning] reports whether a run is in flight
   * for this session.
   */
  data class ApiSessionState(
    val sessionId: String,
    val runId: String?,
    val isRunning: Boolean,
    val lastMessageIndex: Int,
    val title: String,
    val modelName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<JsonObject>,
    val pendingAsks: List<JsonObject>,
    val subAgents: List<JsonObject>
  )

  /**
   * Fetches one session's authoritative projection. [since] drops the messages
   * at or before that 0-based index; the default (-1) returns the whole history.
   *
   * @return The projection, or `null` when the session does not exist or the
   *   request failed.
   */
  suspend fun fetchSessionState(
    projectRoot: String,
    sessionId: String,
    since: Int = -1
  ): ApiSessionState? = withContext(Dispatchers.IO) {
    val encodedRoot: String = URLEncoder.encode(projectRoot, StandardCharsets.UTF_8)
    val encodedSession: String = URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
    try {
      val response: HttpResponse<String> =
        sendWithAuthRetry(HttpResponse.BodyHandlers.ofString()) {
          newRequest(
            "$baseUrl/session/state?projectRoot=$encodedRoot&sessionId=$encodedSession&since=$since"
          )
            .GET()
            .build()
        }
      if (response.statusCode() != 200) return@withContext null
      val parsed: JsonObject = ndjsonParser.parseToJsonElement(response.body()) as? JsonObject
        ?: return@withContext null

      ApiSessionState(
        runId = parsed.stringOrNull("runId"),
        messages = parsed.objectList("messages"),
        subAgents = parsed.objectList("subAgents"),
        pendingAsks = parsed.objectList("pendingAsks"),
        title = parsed.stringOrNull("title").orEmpty(),
        modelName = parsed.stringOrNull("modelName").orEmpty(),
        sessionId = parsed.stringOrNull("sessionId") ?: sessionId,
        createdAt = (parsed["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
        updatedAt = (parsed["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
        isRunning = (parsed["isRunning"] as? JsonPrimitive)?.booleanOrNull ?: false,
        lastMessageIndex = (parsed["lastMessageIndex"] as? JsonPrimitive)?.intOrNull ?: -1
      )
    } catch (fetchException: Exception) {
      if (fetchException is CancellationException) throw fetchException
      log.warn("Failed to load session state for session $sessionId on server", fetchException)
      null
    }
  }

  private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

  private fun JsonObject.objectList(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()

  /**
   * Lists every saved session for [projectRoot], most recently updated first.
   * The server reads only each transcript's leading header lines, so the
   * plugin gets the same cheap listing it used to build from local files.
   *
   * @throws java.net.ConnectException If the server is not running.
   * @throws IOException On any non-2xx response.
   */
  suspend fun listSessions(projectRoot: String): List<ApiSessionSummary> =
    withContext(Dispatchers.IO) {
      val encodedRoot: String = URLEncoder.encode(projectRoot, StandardCharsets.UTF_8)
      val response: HttpResponse<String> =
        sendWithAuthRetry(HttpResponse.BodyHandlers.ofString()) {
          newRequest("$baseUrl/sessions?projectRoot=$encodedRoot").GET().build()
        }

      if (response.statusCode() !in 200..299) {
        throw IOException(
          "Server returned HTTP ${response.statusCode()} for GET $baseUrl/sessions"
        )
      }

      val parsed: JsonElement = ndjsonParser.parseToJsonElement(response.body())
      (parsed as? JsonArray)?.mapNotNull { element: JsonElement ->
        val sessionRow: JsonObject = element as? JsonObject ?: return@mapNotNull null
        val rowId: String = sessionRow["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
        ApiSessionSummary(
          sessionId = rowId,
          title = sessionRow["title"]?.jsonPrimitive?.content ?: "",
          modelName = sessionRow["model"]?.jsonPrimitive?.content ?: "",
          createdAt = sessionRow["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
          updatedAt = sessionRow["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L
        )
      } ?: emptyList()
    }

  /**
   * Merges several sessions into a fresh server-side session.
   *
   * The server reads each source's sidecar, concatenates their messages in
   * timeline order, numbers [baseTitle] per the existing sessions, and writes
   * the merged session itself, so the plugin makes no merge decisions.
   *
   * @return The new session id, or `null` when a source is missing or the
   *   request failed.
   */
  suspend fun mergeSessions(projectRoot: String, sessionIds: List<String>, baseTitle: String): String? =
    withContext(Dispatchers.IO) {
      val requestBody: JsonObject = buildJsonObject {
        put("projectRoot", projectRoot)
        put("baseTitle", baseTitle)
        putJsonArray("sessionIds") { sessionIds.forEach { add(it) } }
      }

      try {
        val response: HttpResponse<String> =
          sendWithAuthRetry(HttpResponse.BodyHandlers.ofString()) {
            newRequest("$baseUrl/sessions/merge")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
              .build()
          }
        if (response.statusCode() != 200) {
          log.warn("Server rejected merge for sessions $sessionIds: HTTP ${response.statusCode()}")
          return@withContext null
        }
        val parsed: JsonObject = ndjsonParser.parseToJsonElement(response.body()) as? JsonObject
          ?: return@withContext null
        parsed.stringOrNull("sessionId")
      } catch (mergeException: Exception) {
        if (mergeException is CancellationException) throw mergeException
        log.warn("Failed to merge sessions on server", mergeException)
        null
      }
    }

  /**
   * Renames a session by rewriting the title in its transcript header.
   *
   * @return `true` when the server renamed the session, `false` when the
   *   session was not found or the request failed.
   */
  suspend fun renameSession(projectRoot: String, sessionId: String, title: String): Boolean =
    withContext(Dispatchers.IO) {
      val requestBody: JsonObject = buildJsonObject {
        put("projectRoot", projectRoot)
        put("sessionId", sessionId)
        put("title", title)
      }

      try {
        val response: HttpResponse<String> =
          sendWithAuthRetry(HttpResponse.BodyHandlers.ofString()) {
            newRequest("$baseUrl/session/rename")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
              .build()
          }
        response.statusCode() == 200
      } catch (renameException: Exception) {
        log.warn("Failed to rename session $sessionId on server", renameException)
        false
      }
    }

  /**
   * Sends a chat message and returns a streaming response of pre-parsed NDJSON events.
   *
   * Server processes through an agent loop (tool calls, file editing, search, etc.)
   * and streams back NDJSON events: `session_start`, `thinking`, `response`, `tool_call`,
   * `error`, `session_end`.
   *
   * **Per-line resilience:** a malformed line is logged at WARN and skipped; only
   * connection-level failures abort the flow (caught by caller's `.catch`).
   * Line payload truncated to [MAX_LOGGED_LINE] chars in logs.
   *
   * **Sequence check:** every event carries a monotonic `seq` (plus a `gap`
   * count); a discontinuity is logged at WARN and reported to [onStreamGap] so
   * the caller can reconcile against the authoritative projection instead of
   * trusting an incomplete local view.
   *
   * @param request The chat message and its associated parameters.
   * @param onStreamGap Invoked with `(expected, received, dropped)` when the
   *   stream skips an event. Local state may be incomplete from then on.
   * @return A [Flow] of [JsonObject] events. Failed lines are skipped without aborting the flow.
   */
  fun sendMessage(
    request: SendMessageRequest,
    onStreamGap: (expected: Long, received: Long, dropped: Int) -> Unit = { _, _, _ -> }
  ): Flow<JsonObject> = flow {
    val requestBody: JsonObject = buildJsonObject {
      put("message", request.message)

      if (request.modelName != null) put("model", request.modelName)

      if (request.modelParams != null) {
        put(
          key = "config",
          element = JsonObject(
            content = request.modelParams.mapValues { (_, value: String) ->
              JsonPrimitive(value)
            })
        )
      }

      put("loadContext", request.loadContext)

      if (request.toolMode != null) put("toolMode", request.toolMode)
      if (request.thinkingLevel != null) put("thinkingLevel", request.thinkingLevel)
      if (request.sessionId != null) put("sessionId", request.sessionId)
      if (request.messageId != null) put("messageId", request.messageId)
      if (request.toolCallXml != null) put("toolCallXml", request.toolCallXml)
      if (request.projectRoot != null) put("projectRoot", request.projectRoot)
      if (request.promptVariant != null) put("promptVariant", request.promptVariant)
      if (request.imageAttachments.isNotEmpty()) {
        val imageAttachmentsJson: JsonArray = buildJsonArray {
          for ((mime: String, data: String, filename: String) in request.imageAttachments) {
            add(buildJsonObject {
              put("type", "image")
              put("mime", mime)
              put("data", data)
              put("filename", filename)
            })
          }
        }
        put(key = "attachments", element = imageAttachmentsJson)
      }
    }

    val response: HttpResponse<InputStream> =
      sendWithAuthRetry(HttpResponse.BodyHandlers.ofInputStream()) {
        newRequest("$baseUrl/events")
          .header("Content-Type", "application/json")
          .timeout(PluginConfig.API_REQUEST_TIMEOUT)
          .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
          .build()
      }

    var previousSeq: Long = 0

    response.body().bufferedReader().use { reader: BufferedReader ->
      reader.useLines { lines ->
        lines
          .withIndex()
          .filter { it.value.isNotBlank() }
          .forEach { (index: Int, line: String) ->
            val event: JsonObject = parseLine(line, lineNumber = index + 1) ?: return@forEach
            previousSeq = warnOnStreamGap(
              event = event, previousSeq = previousSeq, onStreamGap = onStreamGap
            )
            emit(value = event)
          }
      }
    }
  }.flowOn(context = Dispatchers.IO)

  /**
   * Parses a single NDJSON line into a [JsonObject]. A malformed line is logged
   * with a truncated preview and skipped (`null`), so the stream keeps flowing.
   */
  private fun parseLine(line: String, lineNumber: Int): JsonObject? {
    val parsed: JsonElement =
      try {
        ndjsonParser.parseToJsonElement(string = line)
      } catch (exception: SerializationException) {
        val preview: String = line.take(n = MAX_LOGGED_LINE)
        log.warn("Skipping malformed NDJSON line #$lineNumber (len=${line.length}): $preview", exception)
        return null
      }
    return when (parsed) {
      is JsonObject -> parsed
      else -> {
        log.warn("Skipping NDJSON line #$lineNumber: expected object, got ${parsed::class.simpleName}")
        null
      }
    }
  }

  /**
   * Compares an event's `seq` with the previous one and logs when part of the
   * stream was lost. The server reserves a number even for events it had to
   * drop, so both `gap > 0` and a sequence jump mean the client is missing
   * events. Returns the sequence to remember for the next event.
   */
  private fun warnOnStreamGap(
    event: JsonObject,
    previousSeq: Long,
    onStreamGap: (expected: Long, received: Long, dropped: Int) -> Unit
  ): Long {
    val sequence: Long = (event["seq"] as? JsonPrimitive)?.longOrNull ?: return previousSeq
    if (previousSeq != 0L) {
      val expected: Long = previousSeq + 1
      val dropped: Int = (event["gap"] as? JsonPrimitive)?.intOrNull ?: 0
      if (dropped > 0 || sequence != expected) {
        log.warn(
          "Stream gap: expected event #$expected but received #$sequence " +
            "(server reported $dropped dropped event(s)) \u2014 local state may be incomplete"
        )
        onStreamGap(expected, sequence, dropped)
      }
    }
    return sequence
  }

  private companion object {
    const val MAX_LOGGED_LINE: Int = 200
  }
}
