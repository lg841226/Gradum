package gradum.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val logger: org.slf4j.Logger = LoggerFactory.getLogger("ContextManager")
private val jsonFormatter: Json = Json { prettyPrint = true }

/**
 * Persists the agent's conversation history and the set of files that have
 * been fully read, so a later session can resume where the previous one
 * left off without re-reading the same files.
 */
class ContextManager(private val outputDirectory: Path) {

    private val contextFilePath: Path = outputDirectory.resolve("context.json")

    fun loadContext(): List<Map<String, Any>> {
        val contextFile: java.io.File = contextFilePath.toFile()

        if (!contextFile.exists()) {
            logger.info("No context file at ${contextFilePath.toAbsolutePath()}")
            return emptyList()
        }

        logger.info("Loading context (${contextFile.length()} bytes)")

        return try {
            val rawContent: String = contextFile.readText(Charsets.UTF_8)
            val parsedJson: JsonObject = jsonFormatter.parseToJsonElement(rawContent).jsonObject

            val rawMessages: JsonArray = parsedJson["messages"]?.jsonArray ?: run {
                logger.warn("Context file has no 'messages' field")
                return emptyList()
            }

            logger.info("Found ${rawMessages.size} messages in context file")

            val decryptedMessages: List<Map<String, Any>> = rawMessages.map { element ->
                val messageMap: MutableMap<String, Any> = parseJsonObject(element.jsonObject)
                decryptMessageIfNeeded(messageMap)
            }

            logMessageStats(decryptedMessages)
            decryptedMessages
        } catch (exception: Exception) {
            logger.error("Failed to load context: ${exception.message}", exception)
            emptyList()
        }
    }

    fun saveContext(messages: List<Map<String, Any>>, modelName: String, fullyReadFiles: Set<String>) {
        outputDirectory.toFile().mkdirs()

        logger.info("Saving context: ${messages.size} messages, model=$modelName")

        val cleanedMessages: List<Map<String, Any>> = cleanMessageHistory(messages, fullyReadFiles)

        logger.info("After cleanup: ${cleanedMessages.size} messages (dropped ${messages.size - cleanedMessages.size})")

        val serializedMessages: List<Map<String, Any>> = cleanedMessages.map { message ->
            encryptMessageIfNeeded(message)
        }

        val contextMap: Map<String, Any> = mapOf("version" to "1", "model" to modelName, "messages" to serializedMessages)
        val contextJson: String = JsonUtil.encodeMap(contextMap)

        try {
            contextFilePath.toFile().writeText(contextJson, Charsets.UTF_8)
            val writtenSize: Long = contextFilePath.toFile().length()
            logger.info("Context saved: $writtenSize bytes, ${serializedMessages.size} messages encrypted")
        } catch (exception: Exception) {
            logger.error("Failed to save context: ${exception.message}", exception)
        }
    }

    private fun cleanMessageHistory(messages: List<Map<String, Any>>, fullyReadFiles: Set<String>): List<Map<String, Any>> {
        val cleanedMessages: MutableList<Map<String, Any>> = mutableListOf()

        for (message in messages) {
            val role: String = message["role"] as? String ?: ""
            val content: String = message["content"] as? String ?: ""

            val isSystemOrTool: Boolean = role in listOf("system", "tool")
            val isEmptyAssistant: Boolean = role == "assistant" && content.isBlank()
            val isFullyRead: Boolean = fullyReadFiles.any { filePath ->
                content.startsWith("Success: Read $filePath") || content.startsWith("Success: Read '$filePath")
            }

            if (isSystemOrTool || isEmptyAssistant || isFullyRead) continue

            cleanedMessages.add(mapOf("role" to role, "content" to content.trim().replace(Regex("\\s+"), " ")))
        }

        return cleanedMessages.takeLast(60)
    }

    private fun parseJsonObject(jsonObject: JsonObject): MutableMap<String, Any> {
        val result: MutableMap<String, Any> = mutableMapOf()
        for ((key: String, jsonElement) in jsonObject) result[key] = convertJsonElement(jsonElement)
        return result
    }

    private fun convertJsonElement(element: JsonElement): Any {
        return when {
            element is JsonPrimitive && element.isString -> element.content
            element is JsonPrimitive -> element.toString()
            else -> element.toString()
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
