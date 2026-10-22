package gradum.client

import gradum.AgentConfiguration
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val logger: Logger = LoggerFactory.getLogger("OllamaModelCatalog")

// JSON parser with lenient key handling
private val jsonParser: Json = Json { ignoreUnknownKeys = true }

// Conservative fallback when probing fails: keeps existing behavior working
private val FALLBACK_CAPABILITIES = OllamaModelCapabilities(
  supportsThinking = true,
  supportsThinkingLevels = false,
  supportsTools = true,
  supportsVision = false,
  contextLength = 0,
  parameterSizeB = null
)

// Probe timeout in milliseconds
private const val PROBE_TIMEOUT_MS: Long = 2_000L

// Shared HTTP client with a short timeout for capability probing
private val probeHttpClient: HttpClient = HttpClient {
  install(HttpTimeout) {
    connectTimeoutMillis = PROBE_TIMEOUT_MS
    socketTimeoutMillis = PROBE_TIMEOUT_MS
    requestTimeoutMillis = PROBE_TIMEOUT_MS
  }
}

/**
 * Capability profile of a single Ollama model, discovered via `POST /api/show`.
 *
 * Returned by [OllamaModelCatalog.capabilitiesFor] to let callers encode
 * request parameters based on what the model actually supports.
 *
 * [contextLength] is 0 when unknown; [parameterSizeB] is null when unknown.
 */
data class OllamaModelCapabilities(
  val supportsThinking: Boolean,
  val supportsThinkingLevels: Boolean,
  val supportsTools: Boolean,
  val supportsVision: Boolean,
  val contextLength: Int,
  val parameterSizeB: Double?
)

/**
 * Discovers Ollama model capabilities by probing the `/api/show` endpoint.
 *
 * Results are cached for the process lifetime keyed by `"baseUrl|modelName"`:
 * model capabilities are immutable (even across Ollama restarts), so a
 * single successful probe is sufficient.
 *
 * Failure mode: any exception (connection refused, timeout, parse error)
 * returns [FALLBACK_CAPABILITIES] and logs at debug level. The probe never
 * blocks or breaks normal chat flow.
 */
class OllamaModelCatalog(private val baseUrl: String) {

  // Convenience constructor for callers that hold an AgentConfiguration
  constructor(configuration: AgentConfiguration) : this(baseUrl = configuration.baseUrl)

  companion object {
    // Process-lifetime cache: "baseUrl|modelName" -> capabilities
    private val capabilityCache: ConcurrentHashMap<String, OllamaModelCapabilities> = ConcurrentHashMap()
  }

  /**
   * Returns capabilities for [modelName], probing the Ollama server on
   * first access and caching the result for the process lifetime.
   * Returns conservative defaults (thinking on, no levels, tools on)
   * when the server is unreachable or the answer cannot be parsed.
   */
  suspend fun capabilitiesFor(modelName: String): OllamaModelCapabilities {
    val cacheKey = "$baseUrl|$modelName"

    capabilityCache[cacheKey]?.let { cachedCapabilities ->
      return cachedCapabilities
    }

    val probedCapabilities = probeCapabilities(modelName = modelName)
    capabilityCache[cacheKey] = probedCapabilities
    return probedCapabilities
  }

  // Performs the actual probe: version check followed by model capability query
  private suspend fun probeCapabilities(modelName: String): OllamaModelCapabilities {
    return try {
      probeVersion()

      val requestBody = buildJsonObject {
        put("model", modelName)
      }.toString()

      val response: HttpResponse = withTimeoutOrNull(timeMillis = PROBE_TIMEOUT_MS) {
        probeHttpClient.post("$baseUrl/api/show") {
          contentType(ContentType.Application.Json)
          setBody(requestBody)
        }
      } ?: throw RuntimeException("Probe timed out after ${PROBE_TIMEOUT_MS}ms")

      if (response.status.value !in 200..299) {
        logger.debug(
          "Capability probe for {} returned HTTP {}: using fallback",
          modelName, response.status.value
        )
        return FALLBACK_CAPABILITIES
      }

      val responseText: String = response.bodyAsText()
      val parsedCapabilities = parseCapabilities(modelName = modelName, responseText = responseText)

      logger.debug(
        "Probed {}: thinking={}, levels={}, tools={}, vision={}, ctx={}, params={}B",
        modelName,
        parsedCapabilities.supportsThinking,
        parsedCapabilities.supportsThinkingLevels,
        parsedCapabilities.supportsTools,
        parsedCapabilities.supportsVision,
        parsedCapabilities.contextLength,
        parsedCapabilities.parameterSizeB
      )

      parsedCapabilities
    } catch (probeException: Exception) {
      logger.debug(
        "Capability probe for {} failed ({}): using fallback",
        modelName, probeException.message ?: probeException::class.simpleName
      )
      FALLBACK_CAPABILITIES
    }
  }

  // Logs the Ollama server version (best-effort, non-blocking)
  private suspend fun probeVersion() {
    try {
      val versionResponse: HttpResponse = withTimeoutOrNull(timeMillis = PROBE_TIMEOUT_MS) {
        probeHttpClient.get("$baseUrl/api/version")
      } ?: return

      if (versionResponse.status.value in 200..299) {
        val versionJson: JsonObject = jsonParser.parseToJsonElement(versionResponse.bodyAsText()).jsonObject
        val version: String = versionJson["version"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        logger.debug("Ollama server version: {}", version)
      }
    } catch (versionException: Exception) {
      logger.debug("Failed to fetch Ollama version: {}", versionException.message)
    }
  }

  // Parses the /api/show response into an OllamaModelCapabilities
  private fun parseCapabilities(modelName: String, responseText: String): OllamaModelCapabilities {
    val responseJson: JsonObject = jsonParser.parseToJsonElement(responseText).jsonObject

    val capabilitiesArray: JsonArray = responseJson["capabilities"]?.jsonArray ?: JsonArray(emptyList())
    val capabilityStrings: Set<String> = capabilitiesArray.map { it.jsonPrimitive.contentOrNull.orEmpty() }.toSet()

    val supportsThinking: Boolean = "thinking" in capabilityStrings
    val supportsTools: Boolean = "tools" in capabilityStrings
    val supportsVision: Boolean = "vision" in capabilityStrings

    val modelInfo: JsonObject = responseJson["model_info"]?.jsonObject ?: JsonObject(emptyMap())
    val architecture: String = modelInfo["general.architecture"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val supportsThinkingLevels: Boolean = architecture == "gptoss"

    val contextLength: Int = modelInfo["context_length"]?.jsonPrimitive?.intOrNull ?: 0
    val parameterSizeB: Double? = modelInfo["general.parameter_size"]?.jsonPrimitive?.contentOrNull
      ?.removeSuffix("B")
      ?.toDoubleOrNull()

    return OllamaModelCapabilities(
      supportsThinking = supportsThinking,
      supportsThinkingLevels = supportsThinkingLevels,
      supportsTools = supportsTools,
      supportsVision = supportsVision,
      contextLength = contextLength,
      parameterSizeB = parameterSizeB
    )
  }
}