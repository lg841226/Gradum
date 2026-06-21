/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Agent.kt  2026-06-21 07:53:44 Changed by gwy
 */

package gradum.agent

import gradum.*
import gradum.client.*
import gradum.skill.Skill
import gradum.skill.SkillRegistry
import gradum.skill.getTodoManagerInstance
import gradum.util.ContextManager
import gradum.util.JsonUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import org.slf4j.Logger

private val logger: Logger = LoggerFactory.getLogger("Agent")

/**
 * Drives a single user request through one or more LLM turns, dispatching
 * tool calls to [SkillRegistry] between turns until the model stops
 * requesting tools. Streams progress through [emitEvent] as NDJSON.
 */
class Agent(
    private val configuration: AgentConfiguration,
    private val emitEvent: (eventType: String, eventData: Map<String, Any>) -> Unit,
) {

    private val ollamaClient: OllamaClient = OllamaClient(configuration)
    private val openAiClient: OpenAICompatibleClient = OpenAICompatibleClient(configuration)
    private val skillRegistry: SkillRegistry = SkillRegistry()
    private val contextManager: ContextManager = ContextManager(OUTPUT_DIRECTORY)

    private val conversationHistory: MutableList<Map<String, Any>> = mutableListOf()
    private var toolCallCounter: Int = 0

    private val activeClient: LlmClient = when (configuration.provider) {
        Provider.OPENAI -> openAiClient
        Provider.OLLAMA -> ollamaClient
    }

    fun executeTask(userInput: String, loadPreviousContext: Boolean = false): Unit {
        val startTimeMillis: Long = System.currentTimeMillis()

        val contextLoaded: Boolean = if (loadPreviousContext) {
            val loadedMessages: List<Map<String, Any>> = contextManager.loadContext()
            conversationHistory.addAll(loadedMessages)
            loadedMessages.isNotEmpty()
        } else {
            false
        }

        loadSystemPrompt()

        emitEvent(
            "session_start", mapOf(
                "version" to GRADUM_VERSION,
                "model" to configuration.modelName,
                "think" to configuration.enableThinking,
                "contextLoaded" to contextLoaded,
                "contextMessages" to conversationHistory.size
            )
        )

        conversationHistory.add(mapOf("role" to "user", "content" to userInput))

        val toolSchemas: List<Map<String, Any>> = skillRegistry.getSchemas()

        while (true) {
            val result: AgentTurnResult = processLlmTurn(toolSchemas)

            result.errorMessage?.let { error ->
                emitEvent(
                    "error", mapOf(
                        "code" to "CLIENT_ERROR",
                        "message" to error.trim(),
                        "source" to "${configuration.provider.name.lowercase()}_client"
                    )
                )
            }

            result.responseText?.let { text ->
                if (text.isNotBlank()) {
                    emitEvent(
                        "response", mapOf(
                            "content" to text,
                            "tokenUsage" to mapOf(
                                "promptTokens" to activeClient.tokenUsage.promptTokens,
                                "completionTokens" to activeClient.tokenUsage.completionTokens,
                                "totalTokens" to activeClient.tokenUsage.totalTokens
                            ),
                        )
                    )
                }
            }

            if (result.toolCalls.isNullOrEmpty()) {
                result.responseText?.let { text ->
                    appendAssistantMessage(text, null)
                }
                break
            }

            val processedCalls: List<ProcessedToolCall> = prepareToolCalls(result.toolCalls)
            appendAssistantMessage(result.responseText ?: "", processedCalls)

            for (processedCall in processedCalls)
                executeSingleTool(processedCall)
        }

        finishSession(startTimeMillis)
    }

    private fun loadSystemPrompt() {
        val promptContent: String = try {
            Agent::class.java.getResourceAsStream("/system_prompt.md")?.use { stream ->
                stream.reader(Charsets.UTF_8).readText()
            } ?: throw IllegalStateException("system_prompt.md not found on classpath")
        } catch (exception: Exception) {
            logger.warn("Could not load system prompt, reason: ${exception.message}")
            "You are a helpful AI assistant. You can't call any tool and report it"
        }

        val osName: String = System.getProperty("os.name")
        val osVersion: String = System.getProperty("os.version")
        val substitutedContent: String = promptContent.replace("{{OS}}", "$osName $osVersion")

        conversationHistory.add(0, mapOf("role" to "system", "content" to substitutedContent))
    }

    private fun processLlmTurn(toolSchemas: List<Map<String, Any>>): AgentTurnResult {
        val contentParts: MutableList<String> = mutableListOf()
        val thinkingParts: MutableList<String> = mutableListOf()
        var toolCallsResult: List<ToolCallEntry>? = null
        var errorMessage: String? = null

        val responseFlow: Flow<LLMResponseChunk> = activeClient.sendChat(conversationHistory, toolSchemas)

        // TODO(tech-debt): migrate this to a `suspend fun` so the Netty event loop is not
        // blocked while streaming. Not safe to convert until `processLlmTurn` and all 9 skill
        // execute() paths are suspend-clean; tracked as the "Agent.runBlocking removal" item.
        runBlocking {
            responseFlow.collect { chunk ->
                when (chunk) {
                    is LLMResponseChunk.TextContent -> contentParts.add(chunk.text)
                    is LLMResponseChunk.ToolCallBatch -> toolCallsResult = chunk.toolCalls
                    is LLMResponseChunk.ReasoningContent -> thinkingParts.add(chunk.text)
                    is LLMResponseChunk.ErrorMessage -> errorMessage = chunk.description
                }
            }
        }

        if (thinkingParts.isNotEmpty()) {
            if (thinkingParts.joinToString("").isNotBlank())
                emitEvent("thinking", mapOf("content" to thinkingParts.joinToString("")))
        }

        return AgentTurnResult(responseText = contentParts.joinToString(""), toolCalls = toolCallsResult, errorMessage = errorMessage)
    }

    private fun prepareToolCalls(rawCalls: List<ToolCallEntry>): List<ProcessedToolCall> {
        return rawCalls.map { call: ToolCallEntry ->
            val existingId: String = call.callIdentifier
            if (existingId.isNotBlank()) {
                ProcessedToolCall(callIdentifier = existingId, callData = call)
            } else {
                toolCallCounter++
                ProcessedToolCall(callIdentifier = "call_$toolCallCounter", callData = call)
            }
        }
    }

    private fun appendAssistantMessage(content: String, processedCalls: List<ProcessedToolCall>?): Unit {
        val assistantMessage: Map<String, Any> = if (!processedCalls.isNullOrEmpty()) {
            val toolCallsList: List<Map<String, Any>> = if (configuration.provider == Provider.OPENAI) {
                processedCalls.map { call ->
                    mapOf(
                        "id" to call.callIdentifier,
                        "type" to "function",
                        "function" to mapOf(
                            "name" to call.callData.functionTitle,
                            "arguments" to Json.encodeToString(
                                serializer<Map<String, JsonElement>>(),
                                call.callData.functionArguments
                            ),
                        ),
                    )
                }
            } else {
                processedCalls.map { call ->
                    mapOf(
                        "function" to mapOf(
                            "name" to call.callData.functionTitle,
                            "arguments" to call.callData.functionArguments.entries.associate {
                                it.key to JsonUtil.fromJsonElement(it.value)
                            }
                        ),
                    )
                }
            }

            mapOf("role" to "assistant", "content" to content, "tool_calls" to toolCallsList)
        } else {
            mapOf("role" to "assistant", "content" to content)
        }

        conversationHistory.add(assistantMessage)
    }

    private fun executeSingleTool(processedCall: ProcessedToolCall): Unit {
        val functionName: String = processedCall.callData.functionTitle
        val rawArguments: Map<String, JsonElement> = processedCall.callData.functionArguments

        val convertedArguments: MutableMap<String, Any> = mutableMapOf()
        for ((key: String, value: JsonElement) in rawArguments) {
            val converted: Any? = JsonUtil.fromJsonElement(value)
            if (converted != null) {
                convertedArguments[key] = converted
            }
        }

        val skillInstance: Skill? = skillRegistry.getSkill(functionName)
        val executionResult: Map<String, Any> = if (skillInstance == null) {
            mapOf(
                "success" to false,
                "error" to mapOf("code" to "SKILL_NOT_FOUND", "message" to "Skill '$functionName' not found"),
            )
        } else {
            when (val result: SkillResult = skillInstance.execute(convertedArguments)) {
                is SkillResult.Success -> mapOf("success" to true).plus(result.data)
                is SkillResult.Failure -> mapOf("success" to false, "error" to mapOf("code" to result.code, "message" to result.message))
            }
        }

        val historyResult: Map<String, Any> = skillInstance?.prepareHistoryResult(executionResult) ?: executionResult

        val callSuccess: Boolean = executionResult["success"] as? Boolean ?: false

        emitEvent(
            "tool_call", mapOf(
                "tool" to functionName,
                "arguments" to convertedArguments,
                "toolCallId" to processedCall.callIdentifier,
                "success" to callSuccess,
                "result" to executionResult
            )
        )

        if (!callSuccess) {
            @Suppress("UNCHECKED_CAST")
            val errorInfo: Map<String, Any> = executionResult["error"] as? Map<String, Any> ?: emptyMap()
            emitEvent(
                "error", mapOf(
                    "code" to (errorInfo["code"] ?: "EXECUTION_ERROR"),
                    "message" to (errorInfo["message"] ?: "Unknown error"),
                    "tool" to functionName,
                    "toolCallId" to processedCall.callIdentifier
                )
            )
        }

        val resultString: String = JsonUtil.encodeMap(historyResult)

        val todoReminder: String? = getTodoManagerInstance().getTaskReminder()
        val finalResult: String = todoReminder?.let { "$resultString\n\n$it" } ?: resultString

        val toolMessage: Map<String, Any> = if (configuration.provider == Provider.OPENAI) {
            mapOf("role" to "tool", "tool_call_id" to processedCall.callIdentifier, "content" to finalResult)
        } else {
            mapOf("role" to "tool", "content" to finalResult)
        }

        conversationHistory.add(toolMessage)
    }

    private fun finishSession(startTimeMillis: Long): Unit {
        val elapsedSeconds: Long = (System.currentTimeMillis() - startTimeMillis) / 1000

        emitEvent(
            "session_end", mapOf(
                "version" to GRADUM_VERSION,
                "elapsedSeconds" to elapsedSeconds,
                "model" to configuration.modelName,
                "tokenUsage" to mapOf(
                    "promptTokens" to activeClient.tokenUsage.promptTokens,
                    "completionTokens" to activeClient.tokenUsage.completionTokens,
                    "totalTokens" to activeClient.tokenUsage.totalTokens
                ),
            )
        )

        contextManager.saveContext(conversationHistory, configuration.modelName, emptySet())
    }

    private data class AgentTurnResult(
        val responseText: String?,
        val toolCalls: List<ToolCallEntry>?,
        val errorMessage: String?
    )

    data class ProcessedToolCall(
        val callIdentifier: String,
        val callData: ToolCallEntry
    )
}
