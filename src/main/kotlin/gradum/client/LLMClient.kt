/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * LLMClient.kt  2026-09-24 23:12:43 Changed by gwy
 */

package gradum.client

import gradum.AgentConfiguration
import gradum.GradumConfig
import gradum.Provider
import gradum.utils.JsonUtil
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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

/** Shared across clients to reuse connections instead of building a client per turn. */
private val sharedHttpClient: HttpClient = HttpClient {
  install(HttpTimeout) {
    requestTimeoutMillis = GradumConfig.LLM_REQUEST_TIMEOUT_MS
  }
}

private fun JsonObject.optString(key: String): String =
  this[key]?.jsonPrimitive?.contentOrNull ?: ""

private fun JsonObject.optInt(key: String): Int =
  this[key]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

private fun JsonObject.optObject(key: String): JsonObject =
  this[key]?.jsonObject ?: JsonObject(emptyMap())

/**
 * Per-provider wire-format quirks for OpenAI-compatible chat APIs.
 *
 * Most providers we proxy to (Zhipu BigModel, OpenAI, OpenRouter, local
 * LM Studio / vLLM / LocalAI) follow the OpenAI spec to the letter, so
 * the [Default] row covers them. DeepSeek and MiniMax deviate in two
 * narrow spots — the thinking field shape and the token-cap field name
 * — and DeepSeek / MiniMax both surface the model's reasoning in a
 * separate `reasoning_content` delta alongside `content`. Without this
 * table we'd either drop native thinking (waste DeepSeek-R1) or send
 * `max_tokens` to MiniMax (silently ignored → unbounded completion).
 *
 * Match is by substring against [AgentConfiguration.baseUrl] rather
 * than a hard enum so a future model change on the same host keeps
 * working without touching this file.
 */
internal data class ProviderHints(
  val reasoningDeltaField: String? = null,
  val maxTokensFieldName: String = "max_tokens",
  val thinkingFieldValue: Map<String, Any?>? = null
) {
  companion object {
    val Default: ProviderHints = ProviderHints()

    fun forBaseUrl(baseUrl: String): ProviderHints = when {
      baseUrl.contains(other = "api.deepseek.com", ignoreCase = true) -> ProviderHints(
        reasoningDeltaField = "reasoning_content",
        thinkingFieldValue = mapOf("type" to "enabled")
      )

      baseUrl.contains(other = "api.minimaxi.com", ignoreCase = true) -> ProviderHints(
        reasoningDeltaField = "reasoning_content",
        maxTokensFieldName = "max_completion_tokens",
        thinkingFieldValue = mapOf("type" to "adaptive")
      )

      else -> Default
    }
  }
}

data class ToolCallEntry(
  val functionName: String,
  val callIdentifier: String,
  val functionArguments: Map<String, JsonElement>
)

sealed class LLMResponseChunk {
  data class TextContent(val text: String) : LLMResponseChunk()
  data class ReasoningContent(val text: String) : LLMResponseChunk()
  data class ErrorMessage(val description: String) : LLMResponseChunk()
  data class ToolCallBatch(val toolCalls: List<ToolCallEntry>) : LLMResponseChunk()
}

@Serializable
data class TokenUsageSnapshot(
  val totalTokens: Int = 0,
  val promptTokens: Int = 0,
  val completionTokens: Int = 0
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
    toolDefinitions: List<Map<String, Any>>? = null
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
  message: Map<String, Any>, target: Provider
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
  val stringBuilder: StringBuilder = StringBuilder()
  val imageDataList: MutableList<String> = mutableListOf()

  for (contentPart in parts) {
    when (contentPart["type"]) {
      "text" -> {
        (contentPart["text"] as? String)?.let {
          if (stringBuilder.isNotEmpty()) stringBuilder.append("\n\n")
          stringBuilder.append(it)
        }
      }

      "image" -> {
        (contentPart["data"] as? String)?.let { imageDataList.add(it) }
      }
    }
  }

  val messageMap: MutableMap<String, Any> = mutableMapOf("role" to "user")
  if (stringBuilder.isNotEmpty()) messageMap["content"] = stringBuilder.toString()
  if (imageDataList.isNotEmpty()) messageMap["images"] = imageDataList
  return messageMap
}

private fun projectToOpenAi(parts: List<Map<String, Any>>): Map<String, Any> {
  val projectedContentParts: MutableList<Map<String, Any>> = mutableListOf()

  for (partData in parts) {
    when (partData["type"]) {
      "text" -> {
        (partData["text"] as? String)?.let {
          projectedContentParts.add(mapOf("type" to "text", "text" to it))
        }
      }

      "image" -> {
        val base64Data = partData["data"] as? String ?: continue
        val mimeType = partData["mime"] as? String ?: "image/jpeg"
        projectedContentParts.add(
          mapOf(
            "type" to "image_url",
            "image_url" to mapOf("url" to "data:$mimeType;base64,$base64Data")
          )
        )
      }
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
class OllamaClient(
  private val configuration: AgentConfiguration,
  private val httpClient: HttpClient = sharedHttpClient,
) : LlmClient {

  override var tokenUsage: TokenUsageSnapshot = TokenUsageSnapshot()
    private set

  override fun sendChat(
    messageHistory: List<Map<String, Any>>, toolDefinitions: List<Map<String, Any>>?
  ): Flow<LLMResponseChunk> = flow {
    val requestUrl = "${configuration.baseUrl}/api/chat"
    val shouldThink: Boolean = configuration.enableThinking

    val projectedHistory: List<Map<String, Any>> =
      projectHistoryForBackend(messageHistory, target = Provider.OLLAMA)

    val requestPayload: MutableMap<String, Any> = mutableMapOf(
      "model" to configuration.modelName,
      "messages" to projectedHistory,
      "stream" to true,
      "options" to mapOf(
        "top_p" to configuration.topPValue,
        "num_ctx" to configuration.contextWindowSize,
        "temperature" to configuration.temperatureValue,
        "num_predict" to configuration.maxTokensToGenerate
      ),
    )
    // Local-first: keep the model resident so consecutive turns skip the
    // cold re-load. Minutes go to Ollama as whole seconds; -1 pins it
    // until the server stops, 0 leaves the field unset (Ollama default).
    if (configuration.keepAliveMinutes != 0)
      requestPayload["keep_alive"] =
        if (configuration.keepAliveMinutes < 0) -1 else configuration.keepAliveMinutes * 60

    toolDefinitions?.let { definitions -> requestPayload["tools"] = definitions }
    if (shouldThink) requestPayload["think"] = true
    var lastError: Exception? = null
    var emittedAnyChunk = false

    for (attemptIndex: Int in 0..2) {
      try {
        val flowCollector = this
        var succeeded = false
        httpClient.preparePost(urlString = requestUrl) {
          contentType(ContentType.Application.Json)
          setBody(JsonUtil.encodeMap(input = requestPayload))
        }.execute { httpResponse: HttpResponse ->
          if (httpResponse.status.value !in 200..299) {
            flowCollector.emit(
              value = LLMResponseChunk.ErrorMessage(description = extractApiError(httpResponse))
            )
            lastError = null
            succeeded = true
            return@execute
          }

          val responseChannel: ByteReadChannel = httpResponse.body()

          while (!responseChannel.isClosedForRead) {
            val rawLine: String = responseChannel.readUTF8Line() ?: break
            if (rawLine.isBlank()) continue

            val eventData: JsonObject = jsonParser.parseToJsonElement(string = rawLine).jsonObject

            eventData["message"]?.jsonObject?.let { messageObject ->
              val reasoningContent: String = messageObject.optString(key = "thinking")

              if (reasoningContent.isNotEmpty())
                flowCollector.emit(value = LLMResponseChunk.ReasoningContent(text = reasoningContent))

              messageObject["tool_calls"]?.jsonArray?.let { toolCallsArray ->
                val parsedCalls: List<ToolCallEntry> = toolCallsArray.map { element ->
                  val callObject: JsonObject = element.jsonObject
                  val functionObject: JsonObject = callObject.optObject(key = "function")
                  ToolCallEntry(
                    callIdentifier = callObject.optString(key = "id"),
                    functionName = functionObject.optString(key = "name"),
                    functionArguments = functionObject["arguments"]?.jsonObject?.toMap() ?: emptyMap(),
                  )
                }

                flowCollector.emit(value = LLMResponseChunk.ToolCallBatch(toolCalls = parsedCalls))
              }

              val messageContent: String = messageObject.optString(key = "content")
              if (messageContent.isNotEmpty()) {
                emittedAnyChunk = true
                flowCollector.emit(value = LLMResponseChunk.TextContent(messageContent))
              }
            }

            val serverErrorMessage: String = eventData.optString(key = "error")
            if (serverErrorMessage.isNotBlank()) {
              emittedAnyChunk = true
              flowCollector.emit(
                value = LLMResponseChunk.ErrorMessage(description = serverErrorMessage)
              )
            }

            tokenUsage = recordTokenUsage(
              usageStats = eventData,
              promptField = "prompt_eval_count",
              completionField = "eval_count",
              currentUsage = tokenUsage
            )
          }
          succeeded = true
          lastError = null
        }
        if (succeeded) break
      } catch (httpClientException: Exception) {
        if (httpClientException is CancellationException) throw httpClientException
        if (emittedAnyChunk) throw httpClientException
        lastError = httpClientException
        if (attemptIndex < 2 && isTransientError(httpClientException)) {
          val delayMs: Long = 5_000L * (1L shl attemptIndex)
          delay(duration = delayMs.milliseconds)
        } else break
      }
    }

    lastError?.let { error ->
      emit(
        value = LLMResponseChunk.ErrorMessage(
          description = formatLlmError(
            configuration.baseUrl,
            serverName = "Ollama server",
            runningHint = "Make sure Ollama is running.",
            configuration.timeoutSeconds,
            exception = error
          )
        )
      )
    }
  }
}

/**
 * Talks to any OpenAI-compatible /v1/chat/completions endpoint.
 *
 * Used for hosted providers (OpenAI, OpenRouter, Zhipu, DeepSeek,
 * MiniMax, …) when the local Ollama server is not the deployment
 * target.
 *
 * The "Thinking Mode" toggle is dual-channel: the system prompt's
 * "Think first, then act" instruction is sent for every model, and
 * — when the matched [ProviderHints] declares a native
 * `thinking` field — the request also carries that native shape
 * (DeepSeek: `{"type":"enabled"}`; MiniMax: `{"type":"adaptive"}`).
 * The native channel's reasoning tokens are surfaced as
 * [LLMResponseChunk.ReasoningContent] so the UI can render them.
 */
class OpenAICompatibleClient(
  private val configuration: AgentConfiguration,
  private val httpClient: HttpClient = sharedHttpClient,
) : LlmClient {

  override var tokenUsage: TokenUsageSnapshot = TokenUsageSnapshot()
    private set

  override fun sendChat(
    messageHistory: List<Map<String, Any>>, toolDefinitions: List<Map<String, Any>>?
  ): Flow<LLMResponseChunk> = flow {

    val base = configuration.baseUrl.trimEnd('/')
    val path = configuration.chatCompletionsPath.trimStart('/')
    val requestUrl =
      if (base.endsWith(suffix = "/v1") && path.startsWith(prefix = "v1/")) {
        base.removeSuffix("/v1") + "/" + path
      } else {
        "$base/$path"
      }
    val hints: ProviderHints = ProviderHints.forBaseUrl(configuration.baseUrl)

    val projectedHistory: List<Map<String, Any>> =
      projectHistoryForBackend(messageHistory, target = Provider.OPENAI)

    val requestPayload: MutableMap<String, Any> = mutableMapOf(
      "stream" to true,
      "messages" to projectedHistory,
      "top_p" to configuration.topPValue,
      "model" to configuration.modelName,
      "temperature" to configuration.temperatureValue,
      hints.maxTokensFieldName to configuration.maxTokensToGenerate
    )

    toolDefinitions?.let { definitions -> requestPayload["tools"] = definitions }


    if (configuration.enableThinking && hints.thinkingFieldValue != null) {
      requestPayload["thinking"] = hints.thinkingFieldValue
    }

    var lastError: Exception? = null
    var emittedAnyChunk = false

    for (attemptIndex in 0..2) {
      try {
        val httpResponse: HttpResponse = httpClient.post(urlString = requestUrl) {
          contentType(ContentType.Application.Json)
          setBody(JsonUtil.encodeMap(input = requestPayload))

          configuration.apiKey?.takeIf { it.isNotBlank() }?.let { key ->
            header("Authorization", "Bearer $key")
          }
        }

        if (httpResponse.status.value !in 200..299) {
          emit(value = LLMResponseChunk.ErrorMessage(description = extractApiError(httpResponse)))
          lastError = null
          break
        }

        val streamedChunks: Flow<LLMResponseChunk> = parseServerSentEvents(httpResponse, hints)
        streamedChunks.collect { chunk ->
          emittedAnyChunk = true
          emit(value = chunk)
        }

        lastError = null
        break

      } catch (httpClientException: Exception) {
        if (httpClientException is CancellationException) throw httpClientException
        if (emittedAnyChunk) throw httpClientException

        lastError = httpClientException
        if (isTransientError(httpClientException) && attemptIndex < 2)
          delay(duration = (5_000L * 2.0.pow(x = attemptIndex.toDouble())).toLong().milliseconds)
        else break
      }
    }

    lastError?.let { error ->
      emit(
        value = LLMResponseChunk.ErrorMessage(
          description = formatLlmError(
            baseUrl = "server",
            serverName = configuration.baseUrl,
            runningHint = "Make sure the server is running.",
            configuration.timeoutSeconds,
            exception = error
          )
        )
      )
    }
  }

  private fun parseServerSentEvents(
    httpResponse: HttpResponse, hints: ProviderHints
  ): Flow<LLMResponseChunk> = flow {
    val accumulatedCalls: MutableMap<Int, MutableMap<String, Any>> = mutableMapOf()
    var streamCompleted = false

    val responseChannel: ByteReadChannel = httpResponse.bodyAsChannel()

    while (!responseChannel.isClosedForRead) {
      val rawLine: String = responseChannel.readUTF8Line() ?: break
      if (rawLine.isBlank()) continue

      val eventBody: String =
        if (rawLine.startsWith(prefix = "data:")) {
          rawLine.removePrefix("data:").trimStart()
        } else continue

      if (eventBody.trim() == "[DONE]") {
        streamCompleted = true
        break
      }

      val parsedPayload: JsonObject =
        try {
          jsonParser.parseToJsonElement(string = eventBody).jsonObject
        } catch (jsonParseException: Exception) {
          logger.debug("Skipping malformed SSE event: ${jsonParseException.message}", jsonParseException)
          continue
        }

      val firstChoice: JsonObject = parsedPayload["choices"]
        ?.jsonArray?.firstOrNull()?.jsonObject ?: continue

      val deltaFields: JsonObject = firstChoice["delta"]?.jsonObject ?: continue
      val contentDelta: String = deltaFields.optString(key = "content")

      if (contentDelta.isNotEmpty())
        emit(value = LLMResponseChunk.TextContent(contentDelta))

      /**
       * Native reasoning surface: DeepSeek and MiniMax both emit
       * thinking in a separate `reasoning_content` delta on the same
       * choice. Surface it as ReasoningContent so the UI can render
       * it as a collapsible "thought" block, matching the Ollama
       * backend's existing `message.thinking` path.
       */
      hints.reasoningDeltaField?.let { fieldName ->
        val reasoningDelta: String = deltaFields.optString(key = fieldName)
        if (reasoningDelta.isNotEmpty())
          emit(value = LLMResponseChunk.ReasoningContent(text = reasoningDelta))
      }

      deltaFields["tool_calls"]?.jsonArray?.let { toolCallsArray ->
        accumulateCallDeltas(toolCallsArray, accumulator = accumulatedCalls)
      }

      parsedPayload["usage"]?.jsonObject?.let { usageStats ->
        tokenUsage = recordTokenUsage(
          usageStats,
          currentUsage = tokenUsage,
          promptField = "prompt_tokens",
          completionField = "completion_tokens"
        )
      }
    }

    if (accumulatedCalls.isNotEmpty()) {
      val finalCalls: List<ToolCallEntry> = buildCompletedCalls(accumulator = accumulatedCalls)
      emit(value = LLMResponseChunk.ToolCallBatch(toolCalls = finalCalls))
    }

    if (!streamCompleted) {
      emit(
        value = LLMResponseChunk.ErrorMessage(
          description = "Response interrupted, the model may have run out of memory. Try reducing the context length in your model settings."
        )
      )
    }
  }

  private fun accumulateCallDeltas(toolCallsArray: JsonArray, accumulator: MutableMap<Int, MutableMap<String, Any>>) {
    for (toolCallElement in toolCallsArray) {
      val toolCallObject: JsonObject = toolCallElement.jsonObject

      val callIndex: Int =
        if (toolCallObject.containsKey("index")) {
          toolCallObject.optInt(key = "index")
        } else {
          (accumulator.keys.maxOrNull() ?: -1) + 1
        }

      val storedEntry: MutableMap<String, Any> = accumulator.getOrPut(key = callIndex) {
        mutableMapOf("identifier" to "", "functionName" to "", "argumentsBuffer" to StringBuilder())
      }

      val newId: String = toolCallObject.optString(key = "id")
      if (newId.isNotBlank()) storedEntry["identifier"] = newId

      toolCallObject["function"]?.jsonObject?.let { functionDelta ->
        val nameDelta: String = functionDelta.optString(key = "name")
        if (nameDelta.isNotBlank()) storedEntry["functionName"] = nameDelta

        val argumentDelta: String = functionDelta.optString(key = "arguments")
        if (argumentDelta.isNotBlank()) {
          val argumentsBuffer: StringBuilder = storedEntry["argumentsBuffer"] as? StringBuilder
            ?: StringBuilder().also { storedEntry["argumentsBuffer"] = it }
          argumentsBuffer.append(argumentDelta)
        }
      }
    }
  }

  private fun buildCompletedCalls(accumulator: MutableMap<Int, MutableMap<String, Any>>): List<ToolCallEntry> {
    return accumulator.entries.sortedBy { entry -> entry.key }.map { entry ->
      val callData: MutableMap<String, Any> = entry.value
      val argumentsBuffer: StringBuilder = callData["argumentsBuffer"]
        as? StringBuilder ?: StringBuilder()
      val argumentsText: String = argumentsBuffer.toString()

      val parsedArguments: Map<String, JsonElement> =
        if (argumentsText.isBlank()) emptyMap()
        else try {
          jsonParser.parseToJsonElement(string = argumentsText).jsonObject.toMap()
        } catch (jsonParseException: Exception) {
          logger.debug("Failed to parse tool-call arguments: ${jsonParseException.message}", jsonParseException)
          emptyMap()
        }

      ToolCallEntry(
        functionName = callData["functionName"] as? String ?: "",
        callIdentifier = callData["identifier"] as? String ?: "",
        functionArguments = parsedArguments,
      )
    }
  }
}

private fun isTransientError(exception: Exception): Boolean = exception is IOException
  || exception is TimeoutCancellationException

/**
 * Pulls the human-readable message out of a non-2xx API response.
 *
 * OpenAI-compatible providers answer errors as
 * `{"error": {"message": "..."}}` (or occasionally `{"error": "..."}`),
 * Zhipu / DeepSeek / MiniMax all follow that shape. Falls back to the
 * raw HTTP status line so the user always sees *something* concrete
 * instead of the generic stream-interrupt message.
 */
private suspend fun extractApiError(httpResponse: HttpResponse): String {
  val statusLine = "HTTP ${httpResponse.status.value} ${httpResponse.status.description}"
  val bodyText: String =
    try {
      httpResponse.bodyAsText()
    } catch (_: Exception) {
      return statusLine
    }
  val errorText: String? = try {
    val parsed: JsonObject = jsonParser.parseToJsonElement(string = bodyText).jsonObject
    parsed["error"]?.let { errorElement ->
      errorElement.jsonObject.optString(key = "message")
        .takeIf { it.isNotBlank() }
        ?: errorElement.jsonPrimitive.contentOrNull
    }
  } catch (_: Exception) {
    null
  }
  return errorText?.takeIf { it.isNotBlank() } ?: statusLine
}

/**
 * Common error formatter shared by every [LlmClient] implementation.
 * The only per-provider differences are the server label and the
 * "is it running?" hint.
 */
private fun formatLlmError(
  baseUrl: String,
  serverName: String,
  runningHint: String,
  timeoutSeconds: Int,
  exception: Exception
): String =
  when (exception) {
    is TimeoutCancellationException ->
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
  usageStats: JsonObject, promptField: String, completionField: String, currentUsage: TokenUsageSnapshot
): TokenUsageSnapshot {
  val promptTokens: Int = usageStats.optInt(key = promptField)
  val completionTokens: Int = usageStats.optInt(key = completionField)

  if (promptTokens <= 0 && completionTokens <= 0) return currentUsage

  return currentUsage.copy(
    promptTokens = currentUsage.promptTokens + promptTokens,
    completionTokens = currentUsage.completionTokens + completionTokens,
    totalTokens = currentUsage.totalTokens + promptTokens + completionTokens
  )
}
