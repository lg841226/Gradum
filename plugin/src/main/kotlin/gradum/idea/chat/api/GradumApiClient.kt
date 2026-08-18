/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumApiClient.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.idea.chat.api

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * HTTP client for the Gradum backend REST API (models + streaming NDJSON at `POST /events`).
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
    .connectTimeout(Duration.ofSeconds(5))
    .build()

  /**
   * Wire-shaped image attachment for [sendMessage]. Mirrors the
   * server-side `AttachmentDto` exactly (the `type` field is
   * `image` for now; the discriminator exists so future
   * attachment kinds — e.g. PDF, audio — share the same envelope
   * without a breaking change).
   *
   * The base64 payload in [data] is the original file bytes
   * (no re-encoding) and [mime] is the IANA type derived from
   * the file extension (`image/png`, `image/jpeg`, `image/webp`,
   * …) — the server gets exactly what the user picked.
   */
  data class ApiImageAttachment(
    val mime: String,
    val data: String,
    val filename: String
  )

  /** Lenient parser used for NDJSON lines so a missing `type` field does not throw. */
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
    val request: HttpRequest = HttpRequest.newBuilder()
      .uri(URI.create("$baseUrl/models"))
      .GET()
      .build()

    val response: HttpResponse<String> =
      client.send(request, HttpResponse.BodyHandlers.ofString())

    // A non-2xx response body is not a model list — decoding it as one
    // fails with a SerializationException that callers misreport as
    // "cannot reach server". Surface the real status code instead.
    if (response.statusCode() !in 200..299) {
      val bodyPreview: String = response.body().take(200)
      throw IOException(
        "Server returned HTTP ${response.statusCode()} for GET $baseUrl/models" +
          (if (bodyPreview.isNotBlank()) ": $bodyPreview" else "")
      )
    }

    response.body()
  }

  /**
   * Stops an active session on the server.
   *
   * @param sessionId The ID of the session to stop.
   * @return A JSON string with the stop status.
   */
  suspend fun stopSession(sessionId: String): String = withContext(Dispatchers.IO) {
    val requestBody: JsonObject = buildJsonObject { put("sessionId", sessionId) }

    val request: HttpRequest = HttpRequest.newBuilder()
      .uri(URI.create("$baseUrl/stop"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
      .build()

    val response: HttpResponse<String> =
      client.send(request, HttpResponse.BodyHandlers.ofString())

    response.body()
  }

  /**
   * Deletes a session's entire directory on the server (transcript cascade
   * is handled locally by [gradum.idea.chat.history.ChatSessionStore]).
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

      val request: HttpRequest = HttpRequest.newBuilder()
        .uri(URI.create("$baseUrl/session/delete"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
        .build()

      try {
        val response: HttpResponse<String> =
          client.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() == 200
      } catch (deleteException: Exception) {
        log.warn("Failed to delete session $sessionId on server", deleteException)
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
   * @param sessionId The client-generated conversation session id that scopes
   *   this session's model context on the server. `null` keeps the legacy
   *   single `.gradum/context.json` behavior for old clients / servers.
   * @return A [Flow] of [JsonObject] events. Failed lines are skipped silently.
   */
  fun sendMessage(
    message: String, modelName: String? = null,
    modelParams: Map<String, String>? = null,
    loadContext: Boolean = true, toolMode: String? = null,
    promptVariant: String? = null, projectRoot: String? = null,
    imageAttachments: List<ApiImageAttachment> = emptyList(),
    toolCallXml: String? = null,
    sessionId: String? = null
  ): Flow<JsonObject> = flow {
    val requestBody: JsonObject = buildJsonObject {
      put("message", message)

      if (modelName != null) put("model", modelName)

      if (modelParams != null) {
        put("config", JsonObject(modelParams.mapValues { (_, value) ->
          JsonPrimitive(value)
        }))
      }

      put("loadContext", loadContext)

      if (toolMode != null) put("toolMode", toolMode)
      if (promptVariant != null) put("promptVariant", promptVariant)
      if (projectRoot != null) put("projectRoot", projectRoot)
      if (sessionId != null) put("sessionId", sessionId)

      if (toolCallXml != null) put("toolCallXml", toolCallXml)

      if (imageAttachments.isNotEmpty()) {
        // OpenAI-style request: `attachments` array with `type` discriminator.
        // Server projects this into the user message's content array.
        // Then re-shapes per provider: Ollama `images` or OpenAI `image_url`.
        val imageAttachmentsJson: JsonArray = buildJsonArray {
          for ((mime, data, filename) in imageAttachments) {
            add(buildJsonObject {
              put("type", "image")
              put("mime", mime)
              put("data", data)
              put("filename", filename)
            })
          }
        }
        put("attachments", imageAttachmentsJson)
      }
    }

    val request: HttpRequest = HttpRequest.newBuilder()
      .uri(URI.create("$baseUrl/events"))
      .header("Content-Type", "application/json")
      .timeout(Duration.ofSeconds(30))
      .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
      .build()

    val response: HttpResponse<InputStream> =
      client.send(request, HttpResponse.BodyHandlers.ofInputStream())

    // Parse errors are handled here, not by callers.
    // This avoids forgetting error handling in new consumers.
    // Callers get typed Flow<JsonObject>; .catch covers only connection failures.
    response.body().bufferedReader().use { reader ->
      reader.useLines { lines ->
        lines
          .withIndex()
          .filter { it.value.isNotBlank() }
          .forEach { (index, line) ->
            parseAndEmit(line, index + 1)
          }
      }
    }
  }.flowOn(Dispatchers.IO)

  /**
   * Parses a single NDJSON line into a [JsonObject] and emits it. Failures
   * are logged with a truncated preview and swallowed so the stream keeps
   * flowing. Returns nothing — the result (success or skipped) is expressed
   * through whether [emit][kotlinx.coroutines.flow.FlowCollector.emit] was
   * called.
   */
  private suspend fun FlowCollector<JsonObject>.parseAndEmit(line: String, lineNumber: Int) {
    val parsed: JsonElement = try {
      ndjsonParser.parseToJsonElement(line)
    } catch (exception: SerializationException) {
      val preview: String = line.take(MAX_LOGGED_LINE)

      log.warn("Skipping malformed NDJSON line #$lineNumber (len=${line.length}): $preview", exception)
      return
    }
    val asObject: JsonObject = when (parsed) {
      is JsonObject -> parsed
      else -> {
        log.warn("Skipping NDJSON line #$lineNumber: expected object, got ${parsed::class.simpleName}")
        return
      }
    }
    emit(asObject)
  }

  /** Cap on how much of a malformed line we copy into the IDE log. */
  private companion object {
    const val MAX_LOGGED_LINE: Int = 200
  }
}
