/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ContextManager.kt  2026-07-08 18:30:20 Changed by gwy
 */

package gradum.utils

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val logger: org.slf4j.Logger = LoggerFactory.getLogger("ContextManager")
private val jsonFormatter: Json = Json { prettyPrint = true }

/**
 * Maximum number of messages to keep in persisted context history.
 *
 * Mirrors `Agent.maxHistoryMessages` so in-memory and persisted
 * truncation use the same budget. If you change one, change the other
 * — or refactor both to read from a single source of truth.
 */
const val MAX_CONTEXT_MESSAGES: Int = 30

/**
 * Persists the agent's conversation history and the set of files that have
 * been fully read, so a later session can resume where the previous one
 * left off without re-reading the same files.
 */
class ContextManager(private val outputDirectory: Path) {

    private val contextFilePath: Path = outputDirectory.resolve("context.json")
    private val contextTempFilePath: Path = outputDirectory.resolve("context.json.tmp")

    /**
     * Cached decrypted messages to avoid re-reading and decrypting from
     * disk on every call.
     *
     * `@Volatile` because [loadContext] and [saveContext] can race
     * across coroutines / threads (load reads the field, save writes
     * `null` to invalidate it). Without the volatile, a load on
     * another thread could see a stale non-null value after a save
     * has invalidated it.
     */
    @Volatile
    private var cachedMessages: List<Map<String, Any>>? = null

    fun loadContext(): List<Map<String, Any>> {
        cachedMessages?.let { return it }

        val contextFile: File = contextFilePath.toFile()

        if (!contextFile.exists()) {
            logger.info("No context file at ${contextFilePath.toAbsolutePath()}")
            return emptyList()
        }

        logger.info("Loading context (${contextFile.length()} bytes)")

        return try {
            val rawContent: String = contextFile.readText(Charsets.UTF_8)
            val parsedJson: JsonObject = jsonFormatter.parseToJsonElement(rawContent).jsonObject

            val rawMessages: JsonArray = parsedJson["messages"]?.jsonArray ?: run {
                logger.warn("Context file has no 'messages' field"); return emptyList()
            }

            logger.info("Found ${rawMessages.size} messages in context file")

            var decryptionFailures = 0
            val decryptedMessages: List<Map<String, Any>> = rawMessages.mapNotNull { element ->
                val messageMap: MutableMap<String, Any> = parseJsonObject(element.jsonObject)
                val decrypted: Map<String, Any>? = decryptMessageIfNeeded(messageMap)
                if (decrypted == null) {
                    decryptionFailures++; null
                } else decrypted
            }
            if (decryptionFailures > 0) {
                logger.warn(
                    "Dropped $decryptionFailures message(s) that failed to decrypt — " +
                        "they would have been fed to the LLM as base64 ciphertext otherwise"
                )
            }

            logMessageStats(decryptedMessages); cachedMessages = decryptedMessages
            decryptedMessages
        } catch (exception: Exception) {
            logger.error("Failed to load context: ${exception.message}", exception)
            emptyList()
        }
    }

    fun saveContext(messages: List<Map<String, Any>>, modelName: String): Boolean {
        outputDirectory.toFile().mkdirs()
        cachedMessages = null

        logger.info("Saving context: ${messages.size} messages, model=$modelName")

        val cleanedMessages: List<Map<String, Any>> = cleanMessageHistory(messages)

        logger.info("After cleanup: ${cleanedMessages.size} messages (dropped ${messages.size - cleanedMessages.size})")

        val serializedMessages: List<Map<String, Any>> = cleanedMessages.map { message ->
            encryptMessageIfNeeded(message)
        }

        val contextMap: Map<String, Any> =
            mapOf("version" to "1", "model" to modelName, "messages" to serializedMessages)
        val contextJson: String = JsonUtil.encodeMap(contextMap)

        return try {
            val tempFile: File = contextTempFilePath.toFile()
            tempFile.writeText(contextJson, Charsets.UTF_8)
            try {
                Files.move(
                    contextTempFilePath,
                    contextFilePath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                logger.warn("ATOMIC_MOVE not supported on this filesystem; falling back")
                Files.move(contextTempFilePath, contextFilePath, StandardCopyOption.REPLACE_EXISTING)
            }
            val writtenSize: Long = contextFilePath.toFile().length()

            logger.info("Context saved: $writtenSize bytes, ${serializedMessages.size} messages encrypted"); true
        } catch (exception: Exception) {
            logger.error("Failed to save context: ${exception.message}", exception); false
        }
    }

    private fun cleanMessageHistory(messages: List<Map<String, Any>>): List<Map<String, Any>> {
        val cleanedMessages: MutableList<Map<String, Any>> = mutableListOf()

        val preservedToolCallIds: MutableSet<String> = mutableSetOf()
        for (message in messages) {
            val role: String = message["role"] as? String ?: ""
            if (role != "assistant") continue

            for (toolCall: Map<String, Any> in readListOfMaps(message["tool_calls"])) {
                val function: Map<String, Any> = readStringMap(toolCall["function"])
                if (function.isEmpty()) continue
                val functionName: String = function["name"] as? String ?: ""
                val callId: String = toolCall["id"] as? String ?: ""

                if (functionName in listOf("read_file", "explore_project") && callId.isNotBlank()) {
                    preservedToolCallIds.add(callId)
                }
            }
        }

        for (message in messages) {
            val role: String = message["role"] as? String ?: ""
            val content: String = message["content"] as? String ?: ""
            val isSystemOrEmptyAssistant: Boolean = (role == "system") || (role == "assistant" && content.isBlank())

            if (isSystemOrEmptyAssistant) continue

            if (role == "tool") {
                val callId: String = message["tool_call_id"] as? String ?: ""
                if (callId in preservedToolCallIds) cleanedMessages.add(message)

                continue
            }

            if (role == "assistant") {
                val toolCalls: List<Map<String, Any>> = readListOfMaps(message["tool_calls"])
                if (toolCalls.isNotEmpty()) {
                    val preservedCalls: List<Map<String, Any>> = toolCalls.filter { toolCall ->
                        val callId: String = toolCall["id"] as? String ?: ""
                        callId in preservedToolCallIds
                    }
                    val trimmedContent: String = content.trim()
                    if (preservedCalls.isNotEmpty()) {
                        val droppedCallCount: Int = toolCalls.size - preservedCalls.size
                        val noticeContent: String = if (droppedCallCount > 0) {
                            "$trimmedContent\n\n[Some tool results were trimmed to fit context]".trim()
                        } else trimmedContent

                        cleanedMessages.add(
                            mapOf(
                                "role" to role,
                                "content" to noticeContent,
                                "tool_calls" to preservedCalls
                            )
                        )
                    } else {
                        if (droppedCallCount(toolCalls, preservedToolCallIds) > 0) {
                            cleanedMessages.add(
                                mapOf(
                                    "role" to role,
                                    "content" to "$trimmedContent\n\n[All tool results trimmed to fit context]".trim()
                                )
                            )
                        } else {
                            cleanedMessages.add(
                                mapOf("role" to role, "content" to trimmedContent)
                            )
                        }
                    }
                } else cleanedMessages.add(mapOf("role" to role, "content" to content.trim()))
            } else cleanedMessages.add(mapOf("role" to role, "content" to content.trim()))
        }
        return takeLastTurns(cleanedMessages, MAX_CONTEXT_MESSAGES)
    }

    private fun droppedCallCount(
        toolCalls: List<Map<String, Any>>, preservedToolCallIds: Set<String>
    ): Int = toolCalls.count {
        (it["id"] as? String ?: "") !in preservedToolCallIds
    }

    /**
     * Runtime-safe coercion of a `Any?` value (typically read from a
     * `Map<String, Any>` produced by [convertJsonElement]) into a
     * `Map<String, Any>`. The previous `as? Map<String, Any>` shortcut
     * compiled with an unchecked-cast warning at L149 because Kotlin
     * can't prove the key/value types at the call site. This helper
     * walks the map at runtime: non-`String` keys are dropped, null
     * values become empty strings. Returns an empty map if the input
     * is not a map at all, so callers can use `?.let { }` or a simple
     * `isEmpty()` check rather than `?: continue`.
     */
    private fun readStringMap(rawMap: Any?): Map<String, Any> {
        if (rawMap !is Map<*, *>) return emptyMap()

        return rawMap
            .filterKeys { it is String }
            .mapKeys { it.key as String }
            .mapValues { it.value ?: "" }
            .toMap()
    }

    /** Runtime-safe coercion of a `Any?` into a `List<Map<String, Any>>`.
     *  Non-map elements are dropped; see [readStringMap] for the per-map rules. */
    private fun readListOfMaps(rawList: Any?): List<Map<String, Any>> {
        if (rawList !is List<*>) return emptyList()
        val parsedMaps: MutableList<Map<String, Any>> = mutableListOf()
        for (rawItem: Any? in rawList) {
            val stringMap: Map<String, Any> = readStringMap(rawItem)
            if (stringMap.isNotEmpty()) parsedMaps.add(stringMap)
        }
        return parsedMaps
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
            is JsonPrimitive -> JsonUtil.fromJsonElement(element) ?: element.toString()
            is JsonArray, is JsonObject -> JsonUtil.fromJsonElement(element).toString()
        }
    }

    private fun decryptMessageIfNeeded(message: MutableMap<String, Any>): MutableMap<String, Any>? {
        val isEncrypted: Boolean = message["_encrypted"]?.toString() == "true"
        val encryptedContent: String = message["content"] as? String ?: ""

        if (!isEncrypted) return message

        return try {
            message["content"] = decryptMessageContent(encryptedContent)
            message.remove("_encrypted")
            message
        } catch (exception: Exception) {
            logger.warn("Failed to decrypt message: ${exception.message}"); null
        }
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
