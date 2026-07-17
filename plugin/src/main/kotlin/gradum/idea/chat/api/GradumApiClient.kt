/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumApiClient.kt  2026-07-17 09:35:23 Changed by gwy
 */

package gradum.idea.chat.api

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * HTTP client for communicating with the Gradum backend server.
 *
 * Provides methods to fetch available LLM models and send chat messages
 * via the server's REST API. The server exposes a streaming NDJSON endpoint
 * at `POST /events` for real-time agent responses.
 *
 * The NDJSON stream is consumed and **parsed per line inside this client**:
 * each line is JSON-decoded independently, and a line that fails to parse
 * is logged at WARN level and skipped — the stream itself does not abort.
 * This used to live in the caller ([gradum.idea.chat.state.GradumChatSession]),
 * but the previous per-line try-catch was easy to miss when adding new
 * consumers and meant the same JSON object was being parsed twice (once to
 * validate, once to dispatch). Centralizing it here gives us a typed
 * `Flow<JsonObject>` contract and a single place to add observability
 * (e.g. per-line counters, schema validation).
 *
 * @property baseUrl The base URL of the Gradum server (default: `http://localhost:8765`).
 */
class GradumApiClient(val baseUrl: String = "http://localhost:8765") {

  private val log: Logger = Logger.getInstance(GradumApiClient::class.java)
  private val client: HttpClient = HttpClient.newHttpClient()

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
   * Sends a chat message to the Gradum server and returns a streaming response
   * of pre-parsed JSON event objects.
   *
   * The server processes the message through an agent loop that may invoke
   * tools (file editing, search, command execution) and streams back NDJSON
   * events as the agent works. Each event line contains a JSON object with
   * `type`, `timestamp`, and `data` fields.
   *
   * **Per-line resilience:** Each NDJSON line is parsed in isolation. A
   * malformed line is logged at WARN and the stream continues with the
   * next line. Only connection-level failures (e.g. the underlying
   * [HttpClient.send] throws) abort the flow — those are caught by the
   * caller's `Flow.catch` block. The line payload is truncated to
   * [MAX_LOGGED_LINE] characters in the log to keep IDE logs readable.
   *
   * Event types include:
   * - `session_start` — Agent session initialized
   * - `thinking` — LLM reasoning content (when thinking mode is enabled)
   * - `response` — Text content from the LLM
   * - `tool_call` — A skill/tool was invoked
   * - `error` — An error occurred
   * - `session_end` — Agent finished processing
   *
   * @param message The user's message text.
   * @param modelName Optional model name override. When `null`, the server uses its configured default.
   * @param modelParams Optional configuration map with keys like `baseUrl`, `provider`, `think`, etc.
   * @param loadContext Whether to load previous conversation context from disk.
   * @return A [Flow] of [JsonObject] events from the server. Lines that fail
   *   to parse are logged and skipped — they do not appear in the flow.
   */
  fun sendMessage(
    message: String, modelName: String? = null,
    modelParams: Map<String, String>? = null,
    loadContext: Boolean = true, toolMode: String? = null,
    promptVariant: String? = null, projectRoot: String? = null,
    imageAttachments: List<ApiImageAttachment> = emptyList()
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

      if (imageAttachments.isNotEmpty()) {
        // OpenAI-style request: `attachments` array with `type` discriminator.
        // Server projects this into the user message's content array.
        // Then re-shapes per provider: Ollama `images` or OpenAI `image_url`.
        val imageAttachmentsJson: JsonArray = buildJsonArray {
          for (image in imageAttachments) {
            add(buildJsonObject {
              put("type", "image")
              put("mime", image.mime)
              put("data", image.data)
              put("filename", image.filename)
            })
          }
        }
        put("attachments", imageAttachmentsJson)
      }
    }

    val request: HttpRequest = HttpRequest.newBuilder()
      .uri(URI.create("$baseUrl/events"))
      .header("Content-Type", "application/json")
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
    } catch (exception: Exception) {
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
