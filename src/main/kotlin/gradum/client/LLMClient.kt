/*
 * Copyright (c) 2026 Gradum team, Some Rights Reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * LLMClient.kt  2026-06-20 Created by gwy
 */

package gradum.client

import gradum.AgentConfiguration
import gradum.util.JsonUtil
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
import java.io.IOException
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

private val jsonParser: Json = Json { ignoreUnknownKeys = true }

/**
 * Read [key] from this object as a string, returning "" when the key is missing
 * or the value is not a primitive. Centralizes the pattern that is repeated
 * across the streaming parsers below.
 */
private fun JsonObject.optString(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull ?: ""

/**
 * Read [key] from this object as an int, returning 0 when the key is missing
 * or the value is not a numeric primitive. Mirrors [optString] for integers.
 */
private fun JsonObject.optInt(key: String): Int =
    this[key]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

/**
 * Read [key] from this object as a nested [JsonObject], returning an empty
 * object when the key is missing or the value is not an object. Avoids the
 * noisy `?: JsonObject(emptyMap())` at every call site.
 */
private fun JsonObject.optObject(key: String): JsonObject =
    this[key]?.jsonObject ?: JsonObject(emptyMap())

data class ToolCallEntry(
    val callIdentifier: String,
    val functionTitle: String,
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
 * Talks to a local or remote Ollama server using its native /api/chat streaming
 * protocol. Supports thinking-mode content separation and tool-call parsing.
 */
class OllamaClient(private val configuration: AgentConfiguration) : LlmClient {

    override var tokenUsage: TokenUsageSnapshot = TokenUsageSnapshot()
        private set

    override fun sendChat(
        messageHistory: List<Map<String, Any>>,
        toolDefinitions: List<Map<String, Any>>?,
    ): Flow<LLMResponseChunk> = flow {

        val requestUrl: String = "${configuration.baseUrl}/api/chat"
        val shouldThink: Boolean = configuration.enableThinking

        val requestPayload: MutableMap<String, Any> = mutableMapOf(
            "model" to configuration.modelName,
            "messages" to messageHistory,
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
                        if (reasoningContent.isNotBlank()) {
                            emit(LLMResponseChunk.ReasoningContent(reasoningContent))
                        }

                        messageObject["tool_calls"]?.jsonArray?.let { toolCallsArray ->
                            val parsedCalls: List<ToolCallEntry> = toolCallsArray.map { element ->
                                val callObject: JsonObject = element.jsonObject
                                val functionObject: JsonObject = callObject.optObject("function")
                                ToolCallEntry(
                                    callIdentifier = callObject.optString("id"),
                                    functionTitle = functionObject.optString("name"),
                                    functionArguments = functionObject["arguments"]?.jsonObject?.toMap() ?: emptyMap(),
                                )
                            }
                            emit(LLMResponseChunk.ToolCallBatch(parsedCalls))
                        }

                        val messageContent: String = messageObject.optString("content")
                        if (messageContent.isNotBlank()) {
                            emit(LLMResponseChunk.TextContent(messageContent))
                        }
                    }

                    val serverErrorMessage: String = eventData.optString("error")
                    if (serverErrorMessage.isNotBlank()) {
                        emit(LLMResponseChunk.ErrorMessage(serverErrorMessage))
                    }

                    recordTokenUsage(eventData)
                }

                lastError = null
                break

            } catch (exception: Exception) {
                lastError = exception

                if (isTransientError(exception) && attemptIndex < 2) delay((5_000L * 2.0.pow(attemptIndex.toDouble())).toLong().milliseconds) else break
            }
        }

        httpClient.close()

        lastError?.let { error ->
            emit(LLMResponseChunk.ErrorMessage(formatOllamaError(error)))
        }
    }

    private fun recordTokenUsage(eventData: JsonObject): Unit {
        val promptTokens: Int = eventData.optInt("prompt_eval_count")
        val completionTokens: Int = eventData.optInt("eval_count")

        if (promptTokens > 0 || completionTokens > 0) {
            tokenUsage = tokenUsage.copy(
                promptTokens = tokenUsage.promptTokens + promptTokens,
                completionTokens = tokenUsage.completionTokens + completionTokens,
                totalTokens = tokenUsage.totalTokens + promptTokens + completionTokens,
            )
        }
    }

    private fun formatOllamaError(exception: Exception): String {
        return when (exception) {
            is kotlinx.coroutines.TimeoutCancellationException ->
                "Request timed out after ${configuration.timeoutSeconds} seconds. The server is taking too long to respond."
            is IOException ->
                """
                Could not connect to Ollama server at ${configuration.baseUrl}.
                Make sure Ollama is running. Details: ${exception.message}
                """.trimIndent()
            else ->
                "Unexpected error - ${exception.message}"
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

        val requestPayload: MutableMap<String, Any> = mutableMapOf(
            "model" to configuration.modelName,
            "messages" to messageHistory,
            "stream" to true,
            "temperature" to configuration.temperatureValue,
            "top_p" to configuration.topPValue,
            "max_tokens" to configuration.maxTokensToGenerate,
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

                lastError = null
                break

            } catch (exception: Exception) {
                lastError = exception
                if (isTransientError(exception) && attemptIndex < 2) delay((5_000L * 2.0.pow(attemptIndex.toDouble())).toLong().milliseconds) else break
            }
        }

        httpClient.close()

        lastError?.let { error ->
            emit(LLMResponseChunk.ErrorMessage(formatOpenAIError(error)))
        }
    }

    private fun parseServerSentEvents(httpResponse: HttpResponse): Flow<LLMResponseChunk> = flow {
        val accumulatedCalls: MutableMap<Int, MutableMap<String, Any>> = mutableMapOf()
        val contentFragments: MutableList<String> = mutableListOf()

        val responseChannel: ByteReadChannel = httpResponse.bodyAsChannel()

        while (!responseChannel.isClosedForRead) {
            val rawLine: String = responseChannel.readUTF8Line() ?: break
            if (rawLine.isBlank()) continue

            val eventBody: String = if (rawLine.startsWith("data: ")) rawLine.removePrefix("data: ") else continue

            if (eventBody.trim() == "[DONE]") break

            val parsedPayload: JsonObject = try {
                jsonParser.parseToJsonElement(eventBody).jsonObject
            } catch (_: Exception) {
                continue
            }

            val firstChoice: JsonObject = parsedPayload["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue

            val deltaFields: JsonObject = firstChoice["delta"]?.jsonObject ?: continue

            val contentDelta: String = deltaFields.optString("content")
            if (contentDelta.isNotBlank()) {
                contentFragments.add(contentDelta)
            }

            deltaFields["tool_calls"]?.jsonArray?.let { toolCallsArray ->
                accumulateCallDeltas(toolCallsArray, accumulatedCalls)
            }

            parsedPayload["usage"]?.jsonObject?.let { usageStats ->
                recordTokenUsage(usageStats)
            }
        }

        if (contentFragments.isNotEmpty()) {
            emit(LLMResponseChunk.TextContent(contentFragments.joinToString("")))
        }

        if (accumulatedCalls.isNotEmpty()) {
            val finalCalls: List<ToolCallEntry> = buildCompletedCalls(accumulatedCalls)
            emit(LLMResponseChunk.ToolCallBatch(finalCalls))
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
                } catch (_: Exception) {
                    emptyMap()
                }

                ToolCallEntry(
                    callIdentifier = callData["identifier"] as? String ?: "",
                    functionTitle = callData["functionName"] as? String ?: "",
                    functionArguments = parsedArguments,
                )
            }
    }

    private fun recordTokenUsage(usageStats: JsonObject): Unit {
        val promptTokens: Int = usageStats.optInt("prompt_tokens")
        val completionTokens: Int = usageStats.optInt("completion_tokens")

        if (promptTokens > 0 || completionTokens > 0) {
            tokenUsage = tokenUsage.copy(
                promptTokens = tokenUsage.promptTokens + promptTokens,
                completionTokens = tokenUsage.completionTokens + completionTokens,
                totalTokens = tokenUsage.totalTokens + promptTokens + completionTokens,
            )
        }
    }

    private fun formatOpenAIError(exception: Exception): String {
        return when (exception) {
            is kotlinx.coroutines.TimeoutCancellationException ->
                "Request timed out after ${configuration.timeoutSeconds} seconds. The server is taking too long to respond."
            is IOException ->
                "Could not connect to server at ${configuration.baseUrl}. Make sure the server is running. Details: ${exception.message}"
            else ->
                "Unexpected error - ${exception.message}"
        }
    }
}

private fun isTransientError(exception: Exception): Boolean = exception is IOException || exception is kotlinx.coroutines.TimeoutCancellationException

private fun buildHttpClient(): HttpClient {
    return HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000L
        }
    }
}
