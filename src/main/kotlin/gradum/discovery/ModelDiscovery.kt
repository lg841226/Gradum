package gradum.discovery

import io.ktor.client.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import kotlin.math.pow
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

private val logger = LoggerFactory.getLogger("ModelDiscovery")

data class ModelEntry(
    val modelName: String,
    val providerType: String,
    val serverUrl: String,
    val serverName: String,
)

private val knownServers: List<ServerDefinition> = listOf(
    ServerDefinition("Ollama", "ollama", "http://localhost:11434", "/api/tags"),
    ServerDefinition("LM Studio", "openai", "http://localhost:1234", "/v1/models"),
    ServerDefinition("vLLM", "openai", "http://localhost:8000", "/v1/models"),
    ServerDefinition("LocalAI", "openai", "http://localhost:8080", "/v1/models"),
)

private data class ServerDefinition(
    val serverName: String,
    val providerType: String,
    val baseUrl: String,
    val apiEndpoint: String,
)

private val jsonParser: Json = Json { ignoreUnknownKeys = true }

fun discoverModels(): List<ModelEntry> {
    val discoveredModels: MutableList<ModelEntry> = mutableListOf()

    for (server in knownServers) {
        val serverModels: List<ModelEntry> = probeServer(server)
        discoveredModels.addAll(serverModels)
    }

    return discoveredModels
}

private fun probeServer(server: ServerDefinition, maxRetries: Int = 2): List<ModelEntry> {
    for (attempt in 0..maxRetries) {
        try {
            val httpClient = HttpClient()
            val response: HttpResponse = runBlocking {
                httpClient.get("${server.baseUrl}${server.apiEndpoint}") {
                    timeout {
                        requestTimeoutMillis = (1.5 * 1000).toLong()
                    }
                }
            }
            httpClient.close()

            if (response.status == HttpStatusCode.OK) {
                val responseBody: String = runBlocking { response.bodyAsText() }
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
                        serverName = server.serverName,
                    )
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to probe ${server.serverName} at ${server.baseUrl}: ${e.message}")
            if (attempt < maxRetries) {
                runBlocking {
                    delay((5_000L * 2.0.pow(attempt.toDouble())).toLong().milliseconds)
                }
            }
        }
    }

    return emptyList()
}
