/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelDiscovery.kt  2026-06-21 07:53:44 Changed by gwy
 */

package gradum.discovery

import io.ktor.client.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val logger: Logger = LoggerFactory.getLogger("ModelDiscovery")

data class ModelEntry(
    val modelName: String,
    val providerType: String,
    val serverUrl: String,
    val serverName: String,
    val contextLimit: Int = 0,
    val reasoning: Boolean = false,
    val toolCall: Boolean = false,
    val openWeights: Boolean = false,
    val attachment: Boolean = false
)

private val knownServers: List<ServerDefinition> = listOf(
    ServerDefinition("Ollama", "ollama", "http://localhost:11434", "/api/tags"),
    ServerDefinition("LM Studio", "openai", "http://localhost:1234", "/v1/models"),
    ServerDefinition("vLLM", "openai", "http://localhost:8000", "/v1/models"),
    ServerDefinition("LocalAI", "openai", "http://localhost:8080", "/v1/models")
)

private data class ServerDefinition(
    val serverName: String,
    val providerType: String,
    val baseUrl: String,
    val apiEndpoint: String
)

private val jsonParser: Json = Json { ignoreUnknownKeys = true }

fun discoverModels(): List<ModelEntry> {
    loadCatalog()
    val discoveredModels: MutableList<ModelEntry> = mutableListOf()

    for (server in knownServers) {
        val serverModels: List<ModelEntry> = probeServer(server)
        discoveredModels.addAll(serverModels)
    }

    return discoveredModels.map { entry ->
        val metadata = lookupMetadata(entry.modelName)
        if (metadata != null) {
            entry.copy(
                contextLimit = metadata.contextLimit,
                reasoning = metadata.reasoning,
                toolCall = metadata.toolCall,
                openWeights = metadata.openWeights,
                attachment = metadata.attachment
            )
        } else {
            entry
        }
    }
}

private fun probeServer(server: ServerDefinition): List<ModelEntry> {
    try {
        logger.info("Probing ${server.serverName} at ${server.baseUrl}${server.apiEndpoint}")
        val httpClient = HttpClient()

        httpClient.use { _ ->
            val response: HttpResponse = runBlocking {
                httpClient.get("${server.baseUrl}${server.apiEndpoint}") {
                    timeout {
                        requestTimeoutMillis = 2000
                    }
                }
            }

            if (response.status == HttpStatusCode.OK) {
                val responseBody: String = runBlocking { response.bodyAsText() }
                logger.info("Got response from ${server.serverName}: ${responseBody.take(200)}")
                val parsedData: JsonObject = jsonParser.parseToJsonElement(responseBody).jsonObject

                val modelNames: List<String> = when (server.providerType) {
                    "ollama" -> parsedData["models"]?.jsonArray?.map {
                        it.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    }?.filter { it.isNotBlank() } ?: emptyList()

                    else -> parsedData["data"]?.jsonArray?.map {
                        it.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    }?.filter { it.isNotBlank() } ?: emptyList()
                }

                return modelNames.map { name ->
                    ModelEntry(
                        modelName = name,
                        providerType = server.providerType,
                        serverUrl = server.baseUrl,
                        serverName = server.serverName
                    )
                }.also { entries ->
                    logger.info("Found ${entries.size} models from ${server.serverName}: ${entries.map { it.modelName }}")
                }
            }
        }
    } catch (exception: Exception) {
        logger.debug("Failed to probe ${server.serverName} at ${server.baseUrl}: ${exception.message}")
    }
    return emptyList()
}
