package gradum.client

import gradum.AgentConfiguration
import gradum.GradumConfig
import gradum.Provider
import gradum.agent.*
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
 * Resolve the wire thinking level for a request: the legacy master switch
 * still gates thinking off entirely, [AgentConfiguration.thinkingLevel]
 * only matters while [AgentConfiguration.enableThinking] is true.
 */
private fun effectiveThinkingLevel(configuration: AgentConfiguration): ThinkingLevel =
  if (configuration.enableThinking) configuration.thinkingLevel else ThinkingLevel.OFF

data class ToolCallEntry(
  val functionName: String,
  val callIdentifier: String,
  val functionArguments: Map<String, JsonElement>
)

sealed class LLMResponseChunk {
  data class TextContent(val text: String) : LLMResponseChunk()
  data class ReasoningContent(val text: String) : LLMResponseChunk()
  data class ErrorMessage(val description: String, val apiStatusCode: Int? = null) : LLMResponseChunk()
  data class ToolCallBatch(val toolCalls: List<ToolCallEntry>) : LLMResponseChunk()
}

@Serializable
data class TokenUsageSnapshot(
  val totalTokens: Int = 0,
  val promptTokens: Int = 0,
  val completionTokens: Int = 0,
  val lastPromptTokens: Int = 0,
  val lastCompletionTokens: Int = 0
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
    messageHistory: List<MessageRecord>,
    toolDefinitions: List<Map<String, Any>>? = null
  ): Flow<LLMResponseChunk>
}

/**
 * Projects one typed history record into the wire shape the target LLM
 * backend expects.
 *
 * The record layer is provider-neutral; the two backends disagree on
 * multimodal user content: Ollama collapses text parts into a single
 * `content` string and lifts images into a top-level `images` array;
 * OpenAI-compatible endpoints keep a content array with `image_url` parts.
 * Tool-call arguments stay a JSON string on the record, rendered as a JSON
 * object for Ollama and verbatim for OpenAI.
 */
private fun projectMessageForBackend(
  message: MessageRecord, target: Provider
): Map<String, Any> = when (message) {
  is SystemMessageRecord -> mapOf(
    "role" to message.role, "content" to message.content
  )

  is UserMessageRecord -> when (val content: MessageContent = message.content) {
    is PlainContent -> mapOf(
      "role" to message.role, "content" to content.text
    )

    is PartsContent -> when (target) {
      Provider.OLLAMA -> projectPartsToOllama(content.parts)
      else -> projectPartsToOpenAi(content.parts)
    }
  }

  is AssistantMessageRecord -> buildMap {
    put("role", message.role)
    put("content", message.content)
    if (message.toolCalls.isNotEmpty()) {
      put(
        "tool_calls", message.toolCalls.map { call ->
          if (target == Provider.OLLAMA) {
            mapOf(
              "function" to mapOf(
                "name" to call.name,
                "arguments" to parseArgumentsObject(call.argumentsJson)
              )
            )
          } else {
            mapOf(
              "id" to call.callId.orEmpty(),
              "type" to "function",
              "function" to mapOf("name" to call.name, "arguments" to call.argumentsJson)
            )
          }
        }
      )
    }
  }

  is ToolMessageRecord -> buildMap {
    put("role", message.role)
    put("content", message.content)
    if (target != Provider.OLLAMA) put("tool_call_id", message.callId.orEmpty())
  }

  is SealedMessageRecord -> emptyMap()
}

private fun projectPartsToOllama(parts: List<ContentPart>): Map<String, Any> {
  val stringBuilder: StringBuilder = StringBuilder()
  val imageDataList: MutableList<String> = mutableListOf()

  for (contentPart in parts) {
    when (contentPart) {
      is TextPart -> {
        if (stringBuilder.isNotEmpty()) stringBuilder.append("\n\n")
        stringBuilder.append(contentPart.text)
      }

      is ImagePart -> imageDataList.add(contentPart.data)
    }
  }

  val messageMap: MutableMap<String, Any> = mutableMapOf("role" to "user")
  if (stringBuilder.isNotEmpty()) messageMap["content"] = stringBuilder.toString()
  if (imageDataList.isNotEmpty()) messageMap["images"] = imageDataList
  return messageMap
}

private fun projectPartsToOpenAi(parts: List<ContentPart>): Map<String, Any> {
  val projectedContentParts: MutableList<Map<String, Any>> = mutableListOf()

  for (part in parts) {
    when (part) {
      is TextPart -> projectedContentParts.add(mapOf("type" to "text", "text" to part.text))

      is ImagePart -> projectedContentParts.add(
        mapOf(
          "type" to "image_url",
          "image_url" to mapOf("url" to "data:${part.mime};base64,${part.data}")
        )
      )
    }
  }

  return mapOf("role" to "user", "content" to projectedContentParts)
}

private fun parseArgumentsObject(rawArguments: String): Map<String, JsonElement> =
  try {
    jsonParser.parseToJsonElement(rawArguments).jsonObject.toMap()
  } catch (_: Exception) {
    emptyMap()
  }

private fun projectHistoryForBackend(
  messageHistory: List<MessageRecord>, target: Provider
): List<Map<String, Any>> = messageHistory.mapNotNull { message ->
  val projected: Map<String, Any> = projectMessageForBackend(message, target)
  projected.ifEmpty { null }
}

// Projects the internal [MessageRecord] history into the OpenAI Responses
// API `input` array used by the LM Studio `/v1/responses` path.
//
// Mapping rules:
// - system/user/assistant messages -> {type: "message", role, content}
// - assistant.toolCalls -> {type: "function_call", call_id, name, arguments}
// - tool messages -> {type: "function_call_output", call_id, output}
//
// Multimodal user content maps to Responses-API part shapes (`input_image`
// instead of the chat/completions `image_url`).
private fun projectHistoryToResponsesInput(
  messageHistory: List<MessageRecord>
): List<Map<String, Any>> {
  val inputItems: MutableList<Map<String, Any>> = mutableListOf()

  for (message in messageHistory) {
    when (message) {
      is SealedMessageRecord -> continue

      is SystemMessageRecord -> inputItems.add(
        mapOf(
          "type" to "message",
          "role" to "system",
          "content" to listOf(mapOf("type" to "text", "text" to message.content))
        )
      )

      is UserMessageRecord -> {
        val contentList: List<Map<String, Any>> = when (val content = message.content) {
          is PlainContent -> listOf(mapOf("type" to "text", "text" to content.text))
          is PartsContent -> content.parts.map { part ->
            when (part) {
              is TextPart -> mapOf("type" to "text", "text" to part.text)
              is ImagePart ->
                mapOf("type" to "input_image", "image_url" to "data:${part.mime};base64,${part.data}")
            }
          }
        }
        if (contentList.isNotEmpty()) {
          inputItems.add(
            mapOf("type" to "message", "role" to "user", "content" to contentList)
          )
        }
      }

      is AssistantMessageRecord -> {
        if (message.content.isNotEmpty()) {
          inputItems.add(
            mapOf(
              "type" to "message",
              "role" to "assistant",
              "content" to listOf(mapOf("type" to "text", "text" to message.content))
            )
          )
        }

        message.toolCalls.forEach { toolCall ->
          val callId: String = toolCall.callId ?: return@forEach
          inputItems.add(
            mapOf(
              "type" to "function_call",
              "call_id" to callId,
              "name" to toolCall.name,
              "arguments" to toolCall.argumentsJson.ifBlank { "{}" }
            )
          )
        }
      }

      is ToolMessageRecord -> {
        val callId: String = message.callId ?: continue
        inputItems.add(
          mapOf(
            "type" to "function_call_output",
            "call_id" to callId,
            "output" to message.content
          )
        )
      }
    }
  }
  return inputItems
}

/**
 * Talks to a local or remote Ollama server using its native /api/chat streaming
 * protocol. Supports thinking-mode content separation and tool-call parsing.
 */
class OllamaClient(
  private val configuration: AgentConfiguration,
  private val httpClient: HttpClient = sharedHttpClient
) : LlmClient {

  override var tokenUsage: TokenUsageSnapshot = TokenUsageSnapshot()
    private set

  override fun sendChat(
    messageHistory: List<MessageRecord>, toolDefinitions: List<Map<String, Any>>?
  ): Flow<LLMResponseChunk> = flow {
    val requestUrl = "${configuration.baseUrl}/api/chat"
    val wireProfile: ProviderProfile = profileFor(Provider.OLLAMA)
    val thinkingLevel: ThinkingLevel = effectiveThinkingLevel(configuration)
    val generationParams = GenerationParams(
      topP = configuration.topPValue,
      temperature = configuration.temperatureValue,
      maxTokens = configuration.maxTokensToGenerate,
      contextWindow = configuration.contextWindowSize,
      keepAliveMinutes = configuration.keepAliveMinutes
    )

    val projectedHistory: List<Map<String, Any>> =
      projectHistoryForBackend(messageHistory, target = Provider.OLLAMA)

    val requestPayload: MutableMap<String, Any> = mutableMapOf(
      "model" to configuration.modelName,
      "messages" to projectedHistory,
      "stream" to true,
    )

    requestPayload.putAll(wireProfile.requestFields(params = generationParams))
    val capabilities: OllamaModelCapabilities? =
      if (thinkingLevel != ThinkingLevel.OFF)
        OllamaModelCatalog(baseUrl = configuration.baseUrl).capabilitiesFor(modelName = configuration.modelName)
      else null
    requestPayload.putAll(wireProfile.thinkingFields(level = thinkingLevel, capabilities = capabilities))

    toolDefinitions?.let { definitions -> requestPayload["tools"] = definitions }
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
            val apiError: LlmApiError = extractApiError(httpResponse)
            flowCollector.emit(
              value = LLMResponseChunk.ErrorMessage(
                description = apiError.message,
                apiStatusCode = apiError.statusCode
              )
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
 * [OpenAiProfile] adds the native `reasoning_effort` field whenever the
 * resolved [ThinkingLevel] is above off. Reasoning tokens streamed in
 * the profile's `reasoning_content` delta surface as
 * [LLMResponseChunk.ReasoningContent] so the UI can render them.
 */
class OpenAICompatibleClient(
  private val configuration: AgentConfiguration,
  private val httpClient: HttpClient = sharedHttpClient,
  private val responsesEndpointProbe: suspend (String) -> Boolean = ::probeResponsesAvailability
) : LlmClient {

  override var tokenUsage: TokenUsageSnapshot = TokenUsageSnapshot()
    private set

  private suspend fun resolveWireFormat(wireProfile: ProviderProfile): WireFormat {
    if (wireProfile.wireFormat != WireFormat.RESPONSES) return wireProfile.wireFormat
    if (configuration.provider != Provider.LMSTUDIO) return wireProfile.wireFormat
    if (responsesEndpointProbe(configuration.baseUrl)) return WireFormat.RESPONSES
    logger.info(
      "LM Studio /v1/responses unavailable for {} — falling back to chat/completions",
      configuration.baseUrl
    )
    return WireFormat.CHAT_COMPLETIONS
  }

  override fun sendChat(
    messageHistory: List<MessageRecord>, toolDefinitions: List<Map<String, Any>>?
  ): Flow<LLMResponseChunk> = flow {

    val wireProfile: ProviderProfile = profileFor(configuration.provider)

    when (resolveWireFormat(wireProfile)) {
      WireFormat.CHAT_COMPLETIONS -> {
        sendChatCompletions(messageHistory, toolDefinitions, wireProfile).collect { emit(it) }
      }

      WireFormat.RESPONSES -> {
        sendResponses(messageHistory, toolDefinitions, wireProfile).collect { emit(it) }
      }
    }
  }

  private fun sendChatCompletions(
    messageHistory: List<MessageRecord>, toolDefinitions: List<Map<String, Any>>?,
    wireProfile: ProviderProfile
  ): Flow<LLMResponseChunk> = flow {

    val base = configuration.baseUrl.trimEnd('/')
    val path = configuration.chatCompletionsPath.trimStart('/')
    val requestUrl =
      if (base.endsWith(suffix = "/v1") && path.startsWith(prefix = "v1/")) {
        base.removeSuffix("/v1") + "/" + path
      } else {
        "$base/$path"
      }
    val thinkingLevel: ThinkingLevel = effectiveThinkingLevel(configuration)
    val generationParams = GenerationParams(
      topP = configuration.topPValue,
      temperature = configuration.temperatureValue,
      maxTokens = configuration.maxTokensToGenerate,
      contextWindow = configuration.contextWindowSize
    )

    val projectedHistory: List<Map<String, Any>> =
      projectHistoryForBackend(messageHistory, target = Provider.OPENAI)

    val requestPayload: MutableMap<String, Any> = mutableMapOf(
      "stream" to true,
      "messages" to projectedHistory,
      "model" to configuration.modelName,
      "stream_options" to mapOf("include_usage" to true),
    )
    requestPayload.putAll(wireProfile.requestFields(params = generationParams))
    if (wireProfile.wireFormat == WireFormat.CHAT_COMPLETIONS) {
      requestPayload.putAll(wireProfile.thinkingFields(level = thinkingLevel))
    }

    toolDefinitions?.let { definitions -> requestPayload["tools"] = definitions }

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
          val apiError: LlmApiError = extractApiError(httpResponse)
          emit(
            value = LLMResponseChunk.ErrorMessage(
              description = apiError.message,
              apiStatusCode = apiError.statusCode
            )
          )
          lastError = null
          break
        }

        val streamedChunks: Flow<LLMResponseChunk> =
          parseServerSentEvents(httpResponse, wireProfile)
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

  private fun sendResponses(
    messageHistory: List<MessageRecord>,
    toolDefinitions: List<Map<String, Any>>?,
    wireProfile: ProviderProfile
  ): Flow<LLMResponseChunk> = flow {

    val base = configuration.baseUrl.trimEnd('/')
    val requestUrl = if (base.endsWith(suffix = "/v1")) {
      "$base/responses"
    } else {
      "$base/v1/responses"
    }
    val thinkingLevel: ThinkingLevel = effectiveThinkingLevel(configuration)
    val generationParams = GenerationParams(
      topP = configuration.topPValue,
      temperature = configuration.temperatureValue,
      maxTokens = configuration.maxTokensToGenerate,
      contextWindow = configuration.contextWindowSize
    )

    val inputItems: List<Map<String, Any>> = projectHistoryToResponsesInput(messageHistory)

    val requestPayload: MutableMap<String, Any> = mutableMapOf(
      "model" to configuration.modelName,
      "input" to inputItems,
      "store" to false,
      "stream" to true,
    )
    requestPayload.putAll(wireProfile.requestFields(params = generationParams))
    requestPayload.putAll(wireProfile.thinkingFields(level = thinkingLevel))

    toolDefinitions?.let { definitions -> requestPayload["tools"] = definitions }

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
          val apiError: LlmApiError = extractApiError(httpResponse)
          emit(
            value = LLMResponseChunk.ErrorMessage(
              description = apiError.message,
              apiStatusCode = apiError.statusCode
            )
          )
          lastError = null
          break
        }

        val streamedChunks: Flow<LLMResponseChunk> =
          parseResponsesServerSentEvents(httpResponse)
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

  private fun parseResponsesServerSentEvents(
    httpResponse: HttpResponse
  ): Flow<LLMResponseChunk> = flow {
    val accumulatedCalls: MutableMap<String, MutableMap<String, Any>> = mutableMapOf()
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

      // Usage appears in response.completed event
      (parsedPayload["usage"] as? JsonObject)?.let { usageStats ->
        tokenUsage = recordTokenUsage(
          usageStats,
          currentUsage = tokenUsage,
          promptField = "input_tokens",
          completionField = "output_tokens"
        )
      }

      val eventType: String = parsedPayload["type"]?.jsonPrimitive?.contentOrNull ?: continue

      when (eventType) {
        "response.output_text.delta" -> {
          val delta: String = parsedPayload["delta"]?.jsonPrimitive?.contentOrNull ?: ""
          if (delta.isNotEmpty())
            emit(value = LLMResponseChunk.TextContent(delta))
        }

        "response.reasoning.delta" -> {
          val delta: String = parsedPayload["delta"]?.jsonPrimitive?.contentOrNull ?: ""
          if (delta.isNotEmpty())
            emit(value = LLMResponseChunk.ReasoningContent(text = delta))
        }

        "response.function_call_arguments.delta" -> {
          val callId: String = parsedPayload["call_id"]?.jsonPrimitive?.contentOrNull ?: continue
          val delta: String = parsedPayload["delta"]?.jsonPrimitive?.contentOrNull ?: ""
          val name: String? = parsedPayload["name"]?.jsonPrimitive?.contentOrNull

          val storedEntry: MutableMap<String, Any> = accumulatedCalls.getOrPut(key = callId) {
            mutableMapOf(
              "callId" to callId,
              "functionName" to name.orEmpty(),
              "argumentsBuffer" to StringBuilder()
            )
          }
          if (name != null && name.isNotBlank()) storedEntry["functionName"] = name
          if (delta.isNotBlank()) {
            val argumentsBuffer: StringBuilder = storedEntry["argumentsBuffer"] as? StringBuilder
              ?: StringBuilder().also { storedEntry["argumentsBuffer"] = it }
            argumentsBuffer.append(delta)
          }
        }

        "response.failed" -> {
          val errorObj: JsonObject = parsedPayload["error"]?.jsonObject ?: JsonObject(emptyMap())
          val errorMessage: String = errorObj.optString(key = "message")
          emit(value = LLMResponseChunk.ErrorMessage(description = errorMessage.takeIf { it.isNotBlank() } ?: "Response failed"))
        }
      }
    }

    if (accumulatedCalls.isNotEmpty()) {
      val finalCalls: List<ToolCallEntry> = accumulatedCalls.values.map { callData ->
        val argumentsBuffer: StringBuilder = callData["argumentsBuffer"] as? StringBuilder ?: StringBuilder()
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
          callIdentifier = callData["callId"] as? String ?: "",
          functionArguments = parsedArguments,
        )
      }.toList()
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

  private fun parseServerSentEvents(
    httpResponse: HttpResponse, wireProfile: ProviderProfile
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

      /**
       * Usage is read before the choices guard: per the OpenAI spec the final
       * usage trailer has an empty choices array and would otherwise be
       * skipped. Intermediate chunks may carry "usage": null, so the cast
       * must be nullable-safe.
       */
      (parsedPayload["usage"] as? JsonObject)?.let { usageStats ->
        tokenUsage = recordTokenUsage(
          usageStats,
          currentUsage = tokenUsage,
          promptField = "prompt_tokens",
          completionField = "completion_tokens"
        )
      }

      val firstChoice: JsonObject = parsedPayload["choices"]
        ?.jsonArray?.firstOrNull()?.jsonObject ?: continue

      val deltaFields: JsonObject = firstChoice["delta"]?.jsonObject ?: continue
      val contentDelta: String = deltaFields.optString(key = "content")

      if (contentDelta.isNotEmpty())
        emit(value = LLMResponseChunk.TextContent(contentDelta))

      val reasoningFieldNames: List<String> =
        (listOf("reasoning", "reasoning_content") + listOfNotNull(wireProfile.reasoningDeltaField)).distinct()
      for (fieldName in reasoningFieldNames) {
        val reasoningDelta: String = deltaFields.optString(key = fieldName)
        if (reasoningDelta.isNotEmpty()) {
          emit(value = LLMResponseChunk.ReasoningContent(text = reasoningDelta))
          break
        }
      }

      deltaFields["tool_calls"]?.jsonArray?.let { toolCallsArray ->
        accumulateCallDeltas(toolCallsArray, accumulator = accumulatedCalls)
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

/** A non-2xx answer from the upstream LLM API, with its HTTP status intact. */
private data class LlmApiError(val statusCode: Int, val message: String)

/**
 * Pulls the human-readable message out of a non-2xx API response.
 *
 * OpenAI-compatible providers answer errors as
 * `{"error": {"message": "..."}}` (or occasionally `{"error": "..."}`),
 * Zhipu / DeepSeek / MiniMax all follow that shape. Falls back to the
 * raw HTTP status line so the user always sees *something* concrete
 * instead of the generic stream-interrupt message.
 */
private suspend fun extractApiError(httpResponse: HttpResponse): LlmApiError {
  val statusCode: Int = httpResponse.status.value
  val statusLine = "HTTP $statusCode ${httpResponse.status.description}"
  val bodyText: String =
    try {
      httpResponse.bodyAsText()
    } catch (_: Exception) {
      return LlmApiError(statusCode = statusCode, message = statusLine)
    }
  logger.warn("LLM API error {}: {}", statusLine, bodyText)
  val errorText: String? =
    try {
      val parsed: JsonObject = jsonParser.parseToJsonElement(string = bodyText).jsonObject
      when (val errorElement: JsonElement? = parsed["error"]) {
        // OpenAI shape: {"error": {"message": "..."}}
        is JsonObject ->
          errorElement.optString(key = "message").takeIf { it.isNotBlank() }
            ?: (errorElement["message"] as? JsonPrimitive)?.contentOrNull
        // Ollama shape: {"error": "..."}
        is JsonPrimitive -> errorElement.contentOrNull
        else -> null
      }
    } catch (_: Exception) {
      null
    }
  return LlmApiError(
    statusCode = statusCode,
    message = errorText?.takeIf { it.isNotBlank() } ?: statusLine
  )
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
 * The `last*` fields keep the raw values of the latest request only, so callers
 * can read the current context window size instead of the cumulative sum.
 * Returns [currentUsage] unchanged when neither field is positive.
 */
private fun recordTokenUsage(
  usageStats: JsonObject, promptField: String, completionField: String, currentUsage: TokenUsageSnapshot
): TokenUsageSnapshot {
  val promptTokens: Int = usageStats.optInt(key = promptField)
  val completionTokens: Int = usageStats.optInt(key = completionField)

  if (promptTokens <= 0 && completionTokens <= 0) return currentUsage

  logger.info(
    "LLM call usage: prompt={} completion={} (running context watermark={})",
    promptTokens, completionTokens, promptTokens
  )

  return currentUsage.copy(
    lastPromptTokens = promptTokens,
    lastCompletionTokens = completionTokens,
    promptTokens = currentUsage.promptTokens + promptTokens,
    completionTokens = currentUsage.completionTokens + completionTokens,
    totalTokens = currentUsage.totalTokens + promptTokens + completionTokens
  )
}
