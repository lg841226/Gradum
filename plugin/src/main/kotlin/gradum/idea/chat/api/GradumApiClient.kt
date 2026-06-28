/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumApiClient.kt  2026-06-28 11:09:27 Changed by gwy
 */

package gradum.idea.chat.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
 * @property baseUrl The base URL of the Gradum server (default: `http://localhost:8765`).
 */
class GradumApiClient(val baseUrl: String = "http://localhost:8765") {

    private val client: HttpClient = HttpClient.newHttpClient()
    private val jsonEncoder: Json = Json { ignoreUnknownKeys = true }

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

        val response: HttpResponse<String> = client.send(request, HttpResponse.BodyHandlers.ofString())
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

        val response: HttpResponse<String> = client.send(request, HttpResponse.BodyHandlers.ofString())
        response.body()
    }

    /**
     * Sends a chat message to the Gradum server and returns a streaming response.
     *
     * The server processes the message through an agent loop that may invoke
     * tools (file editing, search, command execution) and streams back NDJSON
     * events as the agent works. Each event line contains a JSON object with
     * `type`, `timestamp`, and `data` fields.
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
     * @param model Optional model name override. When `null`, the server uses its configured default.
     * @param config Optional configuration map with keys like `baseUrl`, `provider`, `think`, etc.
     * @param loadContext Whether to load previous conversation context from disk.
     * @return A [Flow] of NDJSON event lines from the server.
     */
    fun sendMessage(
        message: String,
        model: String? = null,
        config: Map<String, String>? = null,
        loadContext: Boolean = true,
        projectDir: String? = null
    ): Flow<String> = flow {
        val requestBody: JsonObject = buildJsonObject {
            put("message", message)
            if (model != null) put("model", model)
            if (config != null) {
                val configObject = JsonObject(config.mapValues { (_, value) -> JsonPrimitive(value) })
                put("config", configObject)
            }
            put("loadContext", loadContext)
            if (projectDir != null) put("projectDir", projectDir)
        }

        val request: HttpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/events"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()

        val response: HttpResponse<InputStream> = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

        response.body().bufferedReader().use { reader: java.io.BufferedReader ->
            var ndjsonLine: String? = reader.readLine()
            while (ndjsonLine != null) {
                if (ndjsonLine.isNotBlank()) emit(ndjsonLine)
                ndjsonLine = reader.readLine()
            }
        }
    }.flowOn(Dispatchers.IO)
}
