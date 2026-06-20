package gradum.util

import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("ContextManager")
private val jsonFormatter: Json = Json { prettyPrint = true }

class ContextManager(private val outputDirectory: Path) {

    private val contextFilePath: Path = outputDirectory.resolve("context.json")

    fun loadContext(): List<Map<String, Any>> {
        val contextFile: java.io.File = contextFilePath.toFile()
        if (!contextFile.exists()) {
            return emptyList()
        }

        return try {
            val rawContent: String = contextFile.readText(Charsets.UTF_8)
            val parsedJson: JsonObject = jsonFormatter.parseToJsonElement(rawContent).jsonObject
            val rawMessages: List<JsonObject> = parsedJson["messages"]?.jsonArray?.map {
                it.jsonObject
            } ?: return emptyList()

            rawMessages.map { messageObject: JsonObject ->
                val mutableMap: MutableMap<String, Any> = mutableMapOf()
                for ((key: String, element) in messageObject) {
                    mutableMap[key] = when {
                        element is JsonPrimitive && element.isString -> element.content
                        element is JsonPrimitive -> element.toString()
                        else -> element.toString()
                    }
                }

                val isEncrypted: Boolean = mutableMap["_encrypted"] == true
                if (isEncrypted) {
                    val encryptedContent: String = mutableMap["content"] as? String ?: ""
                    try {
                        mutableMap["content"] = decryptMessageContent(encryptedContent)
                        mutableMap.remove("_encrypted")
                    } catch (e: Exception) {
                        logger.warn("Failed to decrypt message: ${e.message}")
                    }
                }

                mutableMap
            }
        } catch (e: Exception) {
            logger.warn("Failed to load context: ${e.message}")
            emptyList()
        }
    }

    fun saveContext(messages: List<Map<String, Any>>, modelName: String, fullyReadFiles: Set<String>) {
        try {
            outputDirectory.toFile().mkdirs()

            val cleanedMessages: List<Map<String, Any>> = cleanMessageHistory(messages, fullyReadFiles)

            val serializedMessages: List<Map<String, Any>> = cleanedMessages.map { message: Map<String, Any> ->
                val role: String = message["role"] as? String ?: ""
                if (role in listOf("user", "assistant") && message["content"] != null) {
                    val contentText: String = message["content"] as? String ?: ""
                    val encryptedContent: String = encryptMessageContent(contentText)
                    message.toMutableMap().apply {
                        this["content"] = encryptedContent
                        this["_encrypted"] = true
                    }
                } else {
                    message
                }
            }

            val contextMap = mapOf(
                "version" to "1",
                "model" to modelName,
                "messages" to serializedMessages,
            )
            val contextJson: String = jsonFormatter.encodeToString(serializer<Map<String, Any>>(), contextMap)

            contextFilePath.toFile().writeText(contextJson, Charsets.UTF_8)
            logger.info("Context saved to ${contextFilePath.toAbsolutePath()}")
        } catch (e: Exception) {
            logger.error("Failed to save context: ${e.message}")
        }
    }

    private fun cleanMessageHistory(
        messages: List<Map<String, Any>>,
        fullyReadFiles: Set<String>,
    ): List<Map<String, Any>> {
        val cleanedMessages: MutableList<Map<String, Any>> = mutableListOf()

        for (message in messages) {
            val role: String = message["role"] as? String ?: ""
            if (role in listOf("system", "tool")) continue

            val content: String = message["content"] as? String ?: ""

            if (role == "assistant" && content.isBlank()) continue

            val shouldDrop: Boolean = fullyReadFiles.any { filePath: String ->
                content.startsWith("Success: Read $filePath") || content.startsWith("Success: Read '$filePath")
            }
            if (shouldDrop) continue

            cleanedMessages.add(
                mapOf(
                    "role" to role,
                    "content" to simplifyContent(content),
                ),
            )
        }

        return cleanedMessages.takeLast(60)
    }

    private fun simplifyContent(content: String): String {
        return content
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
