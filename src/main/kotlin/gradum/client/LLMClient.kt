/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * LLMClient.kt  2026-07-14 21:27:12 Changed by gwy
 */

package gradum.client

import gradum.AgentConfiguration
import gradum.Provider
import gradum.utils.JsonUtil
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

private val logger: Logger = LoggerFactory.getLogger("LLMClient")

private val jsonParser: Json = Json { ignoreUnknownKeys = true }

private fun JsonObject.optString(key: String): String =
  this[key]?.jsonPrimitive?.contentOrNull ?: ""

private fun JsonObject.optInt(key: String): Int =
  this[key]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

private fun JsonObject.optObject(key: String): JsonObject =
  this[key]?.jsonObject ?: JsonObject(emptyMap())

data class ToolCallEntry(
  val callIdentifier: String,
  val functionName: String,
  val functionArguments: Map<String, JsonElement>,
)

sealed class LLMResponseChunk {
  data class TextContent(val text: String) : LLMResponseChunk()
  data class ToolCallBatch(val toolCalls: List<ToolCallEntry>) : LLMResponseChunk()
  data class ReasoningContent(val text: String) : LLMResponseChunk()
  data class ErrorMessage(val description: String) : LLMResponseChunk()
}

@Serializable
data class TokenUsageSnapshot(
  val promptTokens: Int = 0,
  val completionTokens: Int = 0,
  val totalTokens: Int = 0,
)

/**
 * Anything that can expose accumulated token usage (Ollama, OpenAI-compatible, etc.).
 *
 * Used by the agent to surface usage metrics in the final session event
 * without coupling to a specific provider.
 */
interface TokenUsageProvider {
  val tokenUsage: TokenUsageSnapshot
}

/**
 * Common contract for LLM backends. The agent holds a single [LlmClient]
 * chosen via [gradum.Provider] and calls [sendChat] uniformly, removing the
 * need to special-case string-based dispatch at every call site.
 */
interface LlmClient : TokenUsageProvider {
  fun sendChat(
    messageHistory: List<Map<String, Any>>,
    toolDefinitions: List<Map<String, Any>>? = null,
  ): Flow<LLMResponseChunk>
}

/**
 * Project a single `Map<String, Any>` message into the wire shape the
 * target LLM backend expects.
 *
 * The conversation history uses an OpenAI-style content-array shape for
 * user messages carrying image attachments. The two backends disagree on
 * the wire format: Ollama collapses text parts into a single `content`
 * string and lifts images into a top-level `images` array; OpenAI keeps
 * the array but re-shapes each image part into `image_url`. Text-only
 * messages pass through untouched.
 *
 * The target is expressed via the canonical [gradum.Provider] enum, not a
 * parallel multimodal vocabulary.
 */
private fun projectMessageForBackend(
  message: Map<String, Any>,
  target: Provider
): Map<String, Any> {
  if (message["role"] != "user") return message

  val contentParts = extractContentParts(message) ?: return message
  if (contentParts.isEmpty()) return message

  return when (target) {
    Provider.OLLAMA -> projectToOllama(contentParts)
    Provider.OPENAI -> projectToOpenAi(contentParts)
  }
}

private fun extractContentParts(message: Map<String, Any>): List<Map<String, Any>>? {
  val content: Any? = message["content"]
  return if (content is List<*>) {
    content.filterIsInstance<Map<String, Any>>()
  } else null
}

private fun projectToOllama(parts: List<Map<String, Any>>): Map<String, Any> {
  val text: StringBuilder = StringBuilder()
  val images: MutableList<String> = mutableListOf()

  for (part in parts) {
    when (part["type"]) {
      "text" -> {
        (part["text"] as? String)?.let {
          if (text.isNotEmpty()) text.append("\n\n")
          text.append(it)
        }
      }

      "image" -> {
        (part["data"] as? String)?.let { images.add(it) }
      }
      // Non-text/image parts dropped at wire boundary
    }
  }

  val result: MutableMap<String, Any> = mutableMapOf("role" to "user")
  if (text.isNotEmpty()) result["content"] = text.toString()
  if (images.isNotEmpty()) result["images"] = images
  return result
}

private fun projectToOpenAi(parts: List<Map<String, Any>>): Map<String, Any> {
  val projectedContentParts: MutableList<Map<String, Any>> = mutableListOf()

  for (part in parts) {
    when (part["type"]) {
      "text" -> {
        (part["text"] as? String)?.let {
          projectedContentParts.add(mapOf("type" to "text", "text" to it))
        }
      }

      "image" -> {
        val data = part["data"] as? String ?: continue
        val mime = part["mime"] as? String ?: "image/jpeg"
        projectedContentParts.add(
          mapOf(
            "type" to "image_url",
            "image_url" to mapOf("url" to "data:$mime;base64,$data")
          )
        )
      }
      // Unknown parts dropped silently
    }
  }

  return mapOf("role" to "user", "content" to projectedContentParts)
}

private fun projectHistoryForBackend(
  messageHistory: List<Map<String, Any>>, target: Provider
): List<Map<String, Any>> = messageHistory.map { message ->
  projectMessageForBackend(message, target)
}

/**
 * Talks to a local or remote Ollama server using its native /api/chat streaming
 * protocol. Supports thinking-mode content separation and tool-call parsing.
 */
class OllamaClient(private val configuration: AgentConfiguration) : LlmClient {

  override var tokenUsage: TokenUsageSnapshot = TokenUsageSnapshot()
    private set

  override fun sendChat(
    messageHistory: List<Map<String, Any>>, toolDefinitions: List<Map<String, Any>>?
  ): Flow<LLMResponseChunk> = flow {
    val requestUrl = "${configuration.baseUrl}/api/chat"
    val shouldThink: Boolean = configuration.enableThinking

    val projectedHistory: List<Map<String, Any>> =
      projectHistoryForBackend(messageHistory, Provider.OLLAMA)

    val requestPayload: MutableMap<String, Any> = mutableMapOf(
      "model" to configuration.modelName,
      "messages" to projectedHistory,
      "stream" to true,
      "options" to mapOf(
        "temperature" to configuration.temperatureValue,
        "top_p" to configuration.topPValue,
        "num_ctx" to configuration.contextWindowSize,
        "num_predict" to configuration.maxTokensToGenerate,
      ),
    )

    toolDefinitions?.let { definitions -> requestPayload["tools"] = definitions }
    if (shouldThink) requestPayload["think"] = true

    val httpClient: HttpClient = buildHttpClient()
    var lastError: Exception? = null

    for (attemptIndex in 0..2) {
      try {
        val httpResponse: HttpResponse = httpClient.post(requestUrl) {
          contentType(ContentType.Application.Json)
          setBody(JsonUtil.encodeMap(requestPayload))
        }

        val responseChannel: ByteReadChannel = httpResponse.bodyAsChannel()

        while (!responseChannel.isClosedForRead) {
          val rawLine: String = responseChannel.readUTF8Line() ?: break
          if (rawLine.isBlank()) continue

          val eventData: JsonObject = jsonParser.parseToJsonElement(rawLine).jsonObject

          eventData["message"]?.jsonObject?.let { messageObject ->
            val reasoningContent: String = messageObject.optString("thinking")

            if (reasoningContent.isNotBlank())
              emit(LLMResponseChunk.ReasoningContent(reasoningContent))

            messageObject["tool_calls"]?.jsonArray?.let { toolCallsArray ->
              val parsedCalls: List<ToolCallEntry> = toolCallsArray.map { element ->
                val callObject: JsonObject = element.jsonObject
                val functionObject: JsonObject = callObject.optObject("function")
                ToolCallEntry(
                  callIdentifier = callObject.optString("id"),
                  functionName = functionObject.optString("name"),
                  functionArguments = functionObject["arguments"]?.jsonObject?.toMap() ?: emptyMap(),
                )
              }
              emit(LLMResponseChunk.ToolCallBatch(parsedCalls))
            }

            val messageContent: String = messageObject.optString("content")
            if (messageContent.isNotBlank())
              emit(LLMResponseChunk.TextContent(messageContent))
          }

          val serverErrorMessage: String = eventData.optString("error")
          if (serverErrorMessage.isNotBlank())
            emit(LLMResponseChunk.ErrorMessage(serverErrorMessage))

          tokenUsage = recordTokenUsage(
            usageStats = eventData,
            promptField = "prompt_eval_count",
            completionField = "eval_count",
            currentUsage = tokenUsage
          )
        }
        lastError = null; break
      } catch (httpClientException: Exception) {
        lastError = httpClientException
        if (attemptIndex < 2 && isTransientError(httpClientException)) {
          val delayMs: Long = 5_000L * (1L shl attemptIndex)
          delay(delayMs.milliseconds)
        } else break
      }
    }

    httpClient.close()

    lastError?.let { error ->
      emit(
        LLMResponseChunk.ErrorMessage(
          formatLlmError(
            error,
            configuration.baseUrl,
            "Ollama server",
            "Make sure Ollama is running.",
            configuration.timeoutSeconds,
          )
        )
      )
    }
  }
}

/**
 * Talks to any OpenAI-compatible /v1/chat/completions endpoint.
 *
 * Used for hosted providers (OpenAI, OpenRouter, etc.) when the local Ollama
 * server is not the deployment target.
 */
class OpenAICompatibleClient(private val configuration: AgentConfiguration) : LlmClient {

  override var tokenUsage: TokenUsageSnapshot = TokenUsageSnapshot()
    private set

  override fun sendChat(
    messageHistory: List<Map<String, Any>>,
    toolDefinitions: List<Map<String, Any>>?,
  ): Flow<LLMResponseChunk> = flow {

    val requestUrl = "${configuration.baseUrl}/v1/chat/completions"

    val projectedHistory: List<Map<String, Any>> =
      projectHistoryForBackend(messageHistory, Provider.OPENAI)

    val requestPayload: MutableMap<String, Any> = mutableMapOf(
      "model" to configuration.modelName,
      "messages" to projectedHistory,
      "stream" to true,
      "temperature" to configuration.temperatureValue,
      "top_p" to configuration.topPValue,
      "max_tokens" to configuration.maxTokensToGenerate
    )

    toolDefinitions?.let { definitions -> requestPayload["tools"] = definitions }

    val httpClient: HttpClient = buildHttpClient()
    var lastError: Exception? = null

    for (attemptIndex in 0..2) {
      try {
        val httpResponse: HttpResponse = httpClient.post(requestUrl) {
          contentType(ContentType.Application.Json)
          setBody(JsonUtil.encodeMap(requestPayload))
        }

        val streamedChunks: Flow<LLMResponseChunk> = parseServerSentEvents(httpResponse)
        streamedChunks.collect { chunk -> emit(chunk) }

        lastError = null; break

      } catch (httpClientException: Exception) {
        lastError = httpClientException
        if (isTransientError(httpClientException) && attemptIndex < 2)
          delay((5_000L * 2.0.pow(attemptIndex.toDouble())).toLong().milliseconds)
        else
          break
      }
    }

    httpClient.close()

    lastError?.let { error ->
      emit(
        LLMResponseChunk.ErrorMessage(
          formatLlmError(
            error,
            configuration.baseUrl,
            "server",
            "Make sure the server is running.",
            configuration.timeoutSeconds,
          )
        )
      )
    }
  }

  private fun parseServerSentEvents(httpResponse: HttpResponse): Flow<LLMResponseChunk> = flow {
    val accumulatedCalls: MutableMap<Int, MutableMap<String, Any>> = mutableMapOf()
    var streamCompleted = false

    val responseChannel: ByteReadChannel = httpResponse.bodyAsChannel()

    while (!responseChannel.isClosedForRead) {
      val rawLine: String = responseChannel.readUTF8Line() ?: break
      if (rawLine.isBlank()) continue

      val eventBody: String = if (rawLine.startsWith("data: ")) rawLine.removePrefix("data: ") else continue

      // OpenAI SSE stream-end signal: all OpenAI-compatible servers send `data: [DONE]` at stream end
      if (eventBody.trim() == "[DONE]") {
        streamCompleted = true; break
      }

      val parsedPayload: JsonObject = try {
        jsonParser.parseToJsonElement(eventBody).jsonObject
      } catch (jsonParseException: Exception) {
        logger.debug("Skipping malformed SSE event: ${jsonParseException.message}", jsonParseException)
        continue
      }

      val firstChoice: JsonObject = parsedPayload["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue

      val deltaFields: JsonObject = firstChoice["delta"]?.jsonObject ?: continue

      val contentDelta: String = deltaFields.optString("content")

      if (contentDelta.isNotBlank())
        emit(LLMResponseChunk.TextContent(contentDelta))

      deltaFields["tool_calls"]?.jsonArray?.let { toolCallsArray ->
        accumulateCallDeltas(toolCallsArray, accumulatedCalls)
      }

      parsedPayload["usage"]?.jsonObject?.let { usageStats ->
        tokenUsage = recordTokenUsage(usageStats, "prompt_tokens", "completion_tokens", tokenUsage)
      }
    }

    if (accumulatedCalls.isNotEmpty()) {
      val finalCalls: List<ToolCallEntry> = buildCompletedCalls(accumulatedCalls)
      emit(LLMResponseChunk.ToolCallBatch(finalCalls))
    }

    if (!streamCompleted) {
      emit(LLMResponseChunk.ErrorMessage("Response interrupted — the model may have run out of memory. Try reducing the context length in your model settings."))
    }
  }

  private fun accumulateCallDeltas(toolCallsArray: JsonArray, accumulator: MutableMap<Int, MutableMap<String, Any>>) {
    for (toolCallElement in toolCallsArray) {
      val toolCallObject: JsonObject = toolCallElement.jsonObject
      val callIndex: Int = toolCallObject.optInt("index")

      val storedEntry: MutableMap<String, Any> = accumulator.getOrPut(callIndex) {
        mutableMapOf("identifier" to "", "functionName" to "", "argumentsBuffer" to "")
      }

      val newId: String = toolCallObject.optString("id")
      if (newId.isNotBlank()) storedEntry["identifier"] = newId

      toolCallObject["function"]?.jsonObject?.let { functionDelta ->
        val nameDelta: String = functionDelta.optString("name")
        if (nameDelta.isNotBlank()) storedEntry["functionName"] = nameDelta

        val argumentDelta: String = functionDelta.optString("arguments")
        if (argumentDelta.isNotBlank()) {
          val existingBuffer: String = storedEntry["argumentsBuffer"] as? String ?: ""
          storedEntry["argumentsBuffer"] = existingBuffer + argumentDelta
        }
      }
    }
  }

  private fun buildCompletedCalls(accumulator: MutableMap<Int, MutableMap<String, Any>>): List<ToolCallEntry> {
    return accumulator.entries.sortedBy { entry -> entry.key }.map { entry ->
      val callData: MutableMap<String, Any> = entry.value
      val argumentsText: String = callData["argumentsBuffer"] as? String ?: "{}"
      val parsedArguments: Map<String, JsonElement> = try {
        jsonParser.parseToJsonElement(argumentsText).jsonObject.toMap()
      } catch (jsonParseException: Exception) {
        logger.debug("Failed to parse tool-call arguments: ${jsonParseException.message}", jsonParseException)
        emptyMap()
      }

      ToolCallEntry(
        callIdentifier = callData["identifier"] as? String ?: "",
        functionName = callData["functionName"] as? String ?: "",
        functionArguments = parsedArguments,
      )
    }
  }
}

private fun isTransientError(exception: Exception): Boolean = exception is IOException
  || exception is kotlinx.coroutines.TimeoutCancellationException

private fun buildHttpClient(): HttpClient {
  return HttpClient {
    install(HttpTimeout) {
      requestTimeoutMillis = 600_000L
    }
  }
}

/**
 * Common error formatter shared by every [LlmClient] implementation.
 * The only per-provider differences are the server label and the
 * "is it running?" hint.
 */
private fun formatLlmError(
  exception: Exception,
  baseUrl: String,
  serverName: String,
  runningHint: String,
  timeoutSeconds: Int,
): String = when (exception) {
  is kotlinx.coroutines.TimeoutCancellationException ->
    "Request timed out after $timeoutSeconds seconds. The server is taking too long to respond."

  is IOException ->
    "Could not connect to $serverName at $baseUrl. $runningHint Details: ${exception.message}"

  else -> "Unexpected error - ${exception.message}"
}

/**
 * Common token-usage accumulator shared by every [LlmClient] implementation.
 *
 * Each provider reports usage under different field names (Ollama:
 * `prompt_eval_count`/`eval_count`; OpenAI: `prompt_tokens`/`completion_tokens`).
 * Returns [currentUsage] unchanged when neither field is positive.
 */
private fun recordTokenUsage(
  usageStats: JsonObject,
  promptField: String,
  completionField: String,
  currentUsage: TokenUsageSnapshot,
): TokenUsageSnapshot {
  val promptTokens: Int = usageStats.optInt(promptField)
  val completionTokens: Int = usageStats.optInt(completionField)

  if (promptTokens <= 0 && completionTokens <= 0) return currentUsage

  return currentUsage.copy(
    promptTokens = currentUsage.promptTokens + promptTokens,
    completionTokens = currentUsage.completionTokens + completionTokens,
    totalTokens = currentUsage.totalTokens + promptTokens + completionTokens,
  )
}
