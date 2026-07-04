/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ContextManager.kt  2026-06-30 23:35:47 Changed by gwy
 */

package gradum.utils

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

private val logger: org.slf4j.Logger = LoggerFactory.getLogger("ContextManager")
private val jsonFormatter: Json = Json { prettyPrint = true }

/** Maximum number of messages to keep in context history. */
private const val MAX_CONTEXT_MESSAGES: Int = 30

/**
 * Persists the agent's conversation history and the set of files that have
 * been fully read, so a later session can resume where the previous one
 * left off without re-reading the same files.
 */
class ContextManager(private val outputDirectory: Path) {

    private val contextFilePath: Path = outputDirectory.resolve("context.json")

    /** Cached decrypted messages to avoid re-reading and decrypting from disk on every call. */
    private var cachedMessages: List<Map<String, Any>>? = null

    fun loadContext(): List<Map<String, Any>> {
        cachedMessages?.let { return it }

        val contextFile: File = contextFilePath.toFile()

        if (!contextFile.exists()) {
            logger.info("No context file at ${contextFilePath.toAbsolutePath()}"); return emptyList()
        }

        logger.info("Loading context (${contextFile.length()} bytes)")

        return try {
            val rawContent: String = contextFile.readText(Charsets.UTF_8)
            val parsedJson: JsonObject = jsonFormatter.parseToJsonElement(rawContent).jsonObject

            val rawMessages: JsonArray = parsedJson["messages"]?.jsonArray ?: run {
                logger.warn("Context file has no 'messages' field"); return emptyList()
            }

            logger.info("Found ${rawMessages.size} messages in context file")

            val decryptedMessages: List<Map<String, Any>> = rawMessages.map { element ->
                val messageMap: MutableMap<String, Any> = parseJsonObject(element.jsonObject)
                decryptMessageIfNeeded(messageMap)
            }

            logMessageStats(decryptedMessages); cachedMessages = decryptedMessages
            decryptedMessages
        } catch (exception: Exception) {
            logger.error("Failed to load context: ${exception.message}", exception)
            emptyList()
        }
    }

    fun saveContext(messages: List<Map<String, Any>>, modelName: String, fullyReadFiles: Set<String>): Boolean {
        outputDirectory.toFile().mkdirs()
        cachedMessages = null

        logger.info("Saving context: ${messages.size} messages, model=$modelName")

        val cleanedMessages: List<Map<String, Any>> = cleanMessageHistory(messages, fullyReadFiles)

        logger.info("After cleanup: ${cleanedMessages.size} messages (dropped ${messages.size - cleanedMessages.size})")

        val serializedMessages: List<Map<String, Any>> = cleanedMessages.map { message ->
            encryptMessageIfNeeded(message)
        }

        val contextMap: Map<String, Any> =
            mapOf("version" to "1", "model" to modelName, "messages" to serializedMessages)
        val contextJson: String = JsonUtil.encodeMap(contextMap)

        return try {
            contextFilePath.toFile().writeText(contextJson, Charsets.UTF_8)
            val writtenSize: Long = contextFilePath.toFile().length()

            logger.info("Context saved: $writtenSize bytes, ${serializedMessages.size} messages encrypted"); true
        } catch (exception: Exception) {
            logger.error("Failed to save context: ${exception.message}", exception); false
        }
    }

    private fun cleanMessageHistory(
        messages: List<Map<String, Any>>,
        fullyReadFiles: Set<String>
    ): List<Map<String, Any>> {
        val cleanedMessages: MutableList<Map<String, Any>> = mutableListOf()

        // Step 1: Identify tool calls from read_file and explore_project
        val preservedToolCallIds: MutableSet<String> = mutableSetOf()
        for (message in messages) {
            val role: String = message["role"] as? String ?: ""
            if (role != "assistant") continue

            @Suppress("UNCHECKED_CAST")
            val toolCalls: List<Map<String, Any>> = message["tool_calls"] as? List<Map<String, Any>> ?: continue
            for (toolCall in toolCalls) {
                val function: Map<String, Any> = toolCall["function"] as? Map<String, Any> ?: continue
                val functionName: String = function["name"] as? String ?: ""
                val callId: String = toolCall["id"] as? String ?: ""

                if (functionName in listOf("read_file", "explore_project") && callId.isNotBlank()) {
                    preservedToolCallIds.add(callId)
                }
            }
        }

        // Step 2: Filter messages, preserving tool results from read_file/explore_project
        for (message in messages) {
            val role: String = message["role"] as? String ?: ""
            val content: String = message["content"] as? String ?: ""

            val isSystemOrEmptyAssistant: Boolean = (role == "system") || (role == "assistant" && content.isBlank())

            if (isSystemOrEmptyAssistant) continue

            // Preserve tool messages from read_file and explore_project
            if (role == "tool") {
                val callId: String = message["tool_call_id"] as? String ?: ""
                if (callId in preservedToolCallIds) {
                    cleanedMessages.add(message)
                    continue
                }
                // Skip other tool messages
                continue
            }

            // Skip other fully-read file messages
            val isFullyRead: Boolean = fullyReadFiles.any { filePath ->
                content.startsWith("Success: Read $filePath") || content.startsWith("Success: Read '$filePath")
            }
            if (isFullyRead) continue

            cleanedMessages.add(mapOf("role" to role, "content" to content.trim().replace(Regex("\\s+"), " ")))
        }

        return cleanedMessages.takeLast(MAX_CONTEXT_MESSAGES)
    }

    private fun parseJsonObject(jsonObject: JsonObject): MutableMap<String, Any> {
        val parsedMap: MutableMap<String, Any> = mutableMapOf()
        for ((key: String, jsonElement) in jsonObject) parsedMap[key] = convertJsonElement(jsonElement)
        return parsedMap
    }

    /** Recursively converts a kotlinx.serialization JsonElement to a plain Kotlin/Java object. */
    private fun convertJsonElement(element: JsonElement): Any {
        return when (element) {
            is JsonPrimitive if element.isString -> element.content
            // Number/boolean primitives: try to parse as typed value, fall back to string
            is JsonPrimitive -> JsonUtil.fromJsonElement(element) ?: element.toString()
            // Nested objects/arrays: serialize back to string representation
            is JsonArray, is JsonObject -> JsonUtil.fromJsonElement(element).toString()
        }
    }

    private fun decryptMessageIfNeeded(message: MutableMap<String, Any>): MutableMap<String, Any> {
        val isEncrypted: Boolean = message["_encrypted"]?.toString() == "true"
        val encryptedContent: String = message["content"] as? String ?: ""

        if (!isEncrypted) return message

        try {
            message["content"] = decryptMessageContent(encryptedContent)
            message.remove("_encrypted")
        } catch (exception: Exception) {
            logger.warn("Failed to decrypt message: ${exception.message}")
        }

        return message
    }

    private fun logMessageStats(messages: List<Map<String, Any>>) {
        val roleCounts: Map<String, Int> = messages
            .mapNotNull { it["role"] as? String }
            .groupingBy { it }
            .eachCount()

        logger.info("Context loaded: ${messages.size} messages (roles: $roleCounts)")
    }

    private fun encryptMessageIfNeeded(message: Map<String, Any>): Map<String, Any> {
        val role: String = message["role"] as? String ?: ""
        val contentText: String = message["content"] as? String ?: ""
        val encryptedContent: String = encryptMessageContent(contentText)
        val hasContent: Boolean = message["content"] != null
        val shouldEncrypt: Boolean = role in listOf("user", "assistant") && hasContent

        if (!shouldEncrypt) return message

        return message.toMutableMap().apply {
            this["content"] = encryptedContent
            this["_encrypted"] = "true"
        }
    }
}
