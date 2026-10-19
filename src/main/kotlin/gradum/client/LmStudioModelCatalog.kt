package gradum.client

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

private val logger: Logger = LoggerFactory.getLogger("LmStudioModelCatalog")

/** JSON parser with lenient key handling. */
private val jsonParser: Json = Json { ignoreUnknownKeys = true }

/** Probe timeout in milliseconds. */
private const val PROBE_TIMEOUT_MS: Long = 2_000L

/**
 * Path of LM Studio's root-level REST model listing. It is *not* mounted
 * under the `/v1` OpenAI-compatible prefix, so the base URL must have that
 * suffix stripped before this path is appended (otherwise the request hits
 * `/v1/api/v1/models` and 404s).
 */
private const val LM_STUDIO_MODELS_PATH: String = "api/v1/models"

/** Shared HTTP client with a short timeout for capability probing. */
private val probeHttpClient: HttpClient = HttpClient {
  install(HttpTimeout) {
    connectTimeoutMillis = PROBE_TIMEOUT_MS
    socketTimeoutMillis = PROBE_TIMEOUT_MS
    requestTimeoutMillis = PROBE_TIMEOUT_MS
  }
}

/**
 * Capability profile of a single LM Studio model, discovered via
 * `GET /api/v1/models`.
 *
 * [reasoningOptions] mirrors `capabilities.reasoning.allowed_options`
 * (e.g. `["off", "on", "low", "medium", "high"]`); an empty list means the
 * model does not expose a reasoning dial. [isLoaded] mirrors `state`
 * (`"loaded"` / `"not-loaded"`).
 */
data class LmStudioModelCapabilities(
  val modelName: String,
  val isLoaded: Boolean,
  val supportsVision: Boolean,
  val supportsTools: Boolean,
  val reasoningOptions: List<String>
)

/**
 * Conservative fallback for [LmStudioModelCatalog.capabilitiesFor] when the
 * server is unreachable or reports nothing for [modelName]: claim no
 * capability that was not observed, so an unprobed model is never promoted
 * to "supports thinking / tools / vision".
 */
private fun fallbackCapabilities(modelName: String): LmStudioModelCapabilities =
  LmStudioModelCapabilities(
    modelName = modelName,
    isLoaded = false,
    supportsVision = false,
    supportsTools = false,
    reasoningOptions = emptyList()
  )

/**
 * Discovers LM Studio model capabilities by probing `GET /api/v1/models`.
 *
 * Results are cached for the process lifetime keyed by `baseUrl`: the
 * capability set is a property of the server (one request returns every
 * model), so a single probe answers for all of them.
 *
 * Failure mode: any exception (connection refused, timeout, parse error)
 * yields an empty map, so callers keep the capabilities they already have
 * instead of applying guessed ones. The probe never blocks or breaks the
 * normal chat flow.
 */
class LmStudioModelCatalog(
  private val baseUrl: String,
  /** Overridable for tests; production uses the shared short-timeout client. */
  private val httpClient: HttpClient = probeHttpClient
) {

  companion object {
    /** Process-lifetime cache: `baseUrl` -> capabilities keyed by model name. */
    private val capabilityCache: ConcurrentHashMap<String, Map<String, LmStudioModelCapabilities>> =
      ConcurrentHashMap()
  }

  /**
   * Returns capabilities for [modelName], probing the LM Studio server on
   * first access and caching the per-server result for the process
   * lifetime. Returns [fallbackCapabilities] when the server is unreachable
   * or the model is absent from the response.
   */
  suspend fun capabilitiesFor(modelName: String): LmStudioModelCapabilities {
    val capabilitiesByModelName: Map<String, LmStudioModelCapabilities> = capabilitiesByModel()
    return capabilitiesByModelName[modelName] ?: fallbackCapabilities(modelName = modelName)
  }

  /**
   * Returns every model's capabilities for this server, probing on first
   * access and caching the result for the process lifetime.
   */
  suspend fun capabilitiesByModel(): Map<String, LmStudioModelCapabilities> {
    capabilityCache[baseUrl]?.let { cachedCapabilities ->
      return cachedCapabilities
    }

    val probedCapabilities: Map<String, LmStudioModelCapabilities> = probeCapabilities()
    capabilityCache[baseUrl] = probedCapabilities
    return probedCapabilities
  }

  /** Performs the actual probe against the root-level `/api/v1/models`. */
  private suspend fun probeCapabilities(): Map<String, LmStudioModelCapabilities> {
    return try {
      val requestUrl: String = resolveModelsUrl()

      val response: HttpResponse = withTimeoutOrNull(timeMillis = PROBE_TIMEOUT_MS) {
        httpClient.get(requestUrl)
      } ?: throw RuntimeException("Probe timed out after ${PROBE_TIMEOUT_MS}ms")

      if (response.status.value !in 200..299) {
        logger.debug(
          "Capability probe for {} returned HTTP {}: no capabilities",
          baseUrl, response.status.value
        )
        return emptyMap()
      }

      val parsedCapabilities: Map<String, LmStudioModelCapabilities> =
        parseCapabilities(responseText = response.bodyAsText())

      logger.debug(
        "Probed {} model(s) from {}: {}",
        parsedCapabilities.size, requestUrl,
        parsedCapabilities.values.joinToString { it.modelName }
      )

      parsedCapabilities
    } catch (probeException: Exception) {
      logger.debug(
        "Capability probe for {} failed ({}): no capabilities",
        baseUrl, probeException.message ?: probeException::class.simpleName
      )
      emptyMap()
    }
  }

  /**
   * Builds `{root}/api/v1/models`, stripping a trailing `/v1` from the
   * configured base URL so the root-level REST path is not nested under the
   * OpenAI-compatible prefix.
   */
  private fun resolveModelsUrl(): String {
    val rootUrl: String = baseUrl.trimEnd('/').removeSuffix(suffix = "/v1")
    return "$rootUrl/$LM_STUDIO_MODELS_PATH"
  }

  /** Parses the `/api/v1/models` payload into per-model capabilities. */
  private fun parseCapabilities(responseText: String): Map<String, LmStudioModelCapabilities> {
    val responseJson: JsonObject = jsonParser.parseToJsonElement(responseText).jsonObject
    val dataArray: JsonArray = responseJson["data"]?.jsonArray ?: JsonArray(emptyList())

    val capabilitiesByModelName: MutableMap<String, LmStudioModelCapabilities> = linkedMapOf()
    for (element in dataArray) {
      val modelJson: JsonObject = element.jsonObject
      val modelName: String = modelJson["id"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() } ?: continue

      val capabilitiesObject: JsonObject? = modelJson["capabilities"]?.jsonObject
      val reasoningOptions: List<String> = capabilitiesObject
        ?.get("reasoning")?.jsonObject
        ?.get("allowed_options")?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?: emptyList()

      capabilitiesByModelName[modelName] = LmStudioModelCapabilities(
        modelName = modelName,
        isLoaded = modelJson["state"]?.jsonPrimitive?.contentOrNull == "loaded",
        supportsVision = capabilitiesObject?.get("vision")?.jsonPrimitive?.booleanOrNull == true,
        supportsTools = capabilitiesObject?.get("trained_for_tool_use")?.jsonPrimitive?.booleanOrNull == true,
        reasoningOptions = reasoningOptions
      )
    }
    return capabilitiesByModelName
  }
}
