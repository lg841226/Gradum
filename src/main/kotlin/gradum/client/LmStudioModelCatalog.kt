package gradum.client

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val logger: Logger = LoggerFactory.getLogger("LmStudioModelCatalog")

// JSON parser with lenient key handling
private val jsonParser: Json = Json { ignoreUnknownKeys = true }

// Probe timeout in milliseconds
private const val PROBE_TIMEOUT_MS: Long = 2_000L

// Path of LM Studio's root-level REST model listing (not under /v1 prefix)
private const val LM_STUDIO_MODELS_PATH: String = "api/v1/models"

// Shared HTTP client with a short timeout for capability probing
private val probeHttpClient: HttpClient = HttpClient {
  install(HttpTimeout) {
    connectTimeoutMillis = PROBE_TIMEOUT_MS
    socketTimeoutMillis = PROBE_TIMEOUT_MS
    requestTimeoutMillis = PROBE_TIMEOUT_MS
  }
}

// Process-lifetime cache for /v1/responses endpoint availability.
// Keyed by baseUrl (stripped of /v1). Value: true=2xx, false=404/405/timeout/exception.
// Conservative default: assume unavailable until proven otherwise.
private val responsesAvailabilityCache: ConcurrentHashMap<String, Boolean> = ConcurrentHashMap()

// Probes currently in flight, so concurrent sessions sharing one server issue
// a single request instead of racing on a cold cache.
private val inFlightResponseProbes: ConcurrentHashMap<String, CompletableDeferred<Boolean>> =
  ConcurrentHashMap()

// Probes whether the `/v1/responses` endpoint is available on the given
// LM Studio server. Result is cached for the process lifetime.
//
// A minimal POST is sent; 404 or 405 means the server is too old and
// the Responses wire must not be used. Any other error (timeout, connection
// refused, etc.) is treated as unavailable to avoid blocking the chat flow.
internal suspend fun probeResponsesAvailability(
  baseUrl: String,
  httpClient: HttpClient = probeHttpClient,
): Boolean {
  val cacheKey = baseUrl.trimEnd('/').removeSuffix(suffix = "/v1")
  responsesAvailabilityCache[cacheKey]?.let { cachedAvailability -> return cachedAvailability }

  val newProbe = CompletableDeferred<Boolean>()
  inFlightResponseProbes.putIfAbsent(cacheKey, newProbe)?.let { runningProbe ->
    return runningProbe.await()
  }

  val isAvailable: Boolean = try {
    probeResponsesEndpoint(cacheKey = cacheKey, httpClient = httpClient)
  } catch (cancellation: CancellationException) {
    // Release the waiters, but cache nothing: the server was never disproved.
    inFlightResponseProbes.remove(cacheKey, newProbe)
    newProbe.complete(false)
    throw cancellation
  } catch (probeException: Exception) {
    logger.debug(
      "Responses probe for {} failed ({}): treating as unavailable",
      cacheKey, probeException.message ?: probeException::class.simpleName
    )
    false
  }

  responsesAvailabilityCache[cacheKey] = isAvailable
  inFlightResponseProbes.remove(cacheKey, newProbe)
  newProbe.complete(isAvailable)
  return isAvailable
}

// Sends the minimal request and maps the reply to "endpoint supported?".
private suspend fun probeResponsesEndpoint(cacheKey: String, httpClient: HttpClient): Boolean {
  val responsesUrl = "$cacheKey/v1/responses"
  val minimalPayload = buildJsonObject {
    put("model", "probe")
    putJsonArray("input") {
      add(buildJsonObject {
        put("role", "user")
        put("content", "probe")
      })
    }
    put("store", false)
  }.toString()

  val response: HttpResponse = httpClient.post(responsesUrl) {
    contentType(ContentType.Application.Json)
    setBody(minimalPayload)
  }

  val isAvailable = response.status.value in 200..299
  if (!isAvailable && response.status.value !in setOf(404, 405)) {
    logger.debug(
      "Responses probe for {} returned HTTP {}: treating as unavailable",
      cacheKey, response.status.value
    )
  }
  return isAvailable
}

/**
 * Capability profile of a single LM Studio model, discovered via
 * `GET /api/v1/models`.
 *
 * [reasoningOptions] mirrors `capabilities.reasoning.allowed_options`
 * (e.g. `["off", "on", "low", "medium", "high"]`); empty = no reasoning dial.
 * [isLoaded] mirrors `state` (`"loaded"` / `"not-loaded"`).
 */
data class LmStudioModelCapabilities(
  val modelName: String,
  val isLoaded: Boolean,
  val supportsVision: Boolean,
  val supportsTools: Boolean,
  val reasoningOptions: List<String>
)

// Conservative fallback when server unreachable or model absent: claim no
// unobserved capability so unprobed model is never promoted.
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
 * Results cached for process lifetime keyed by `baseUrl` (one request returns
 * all models). Failure yields empty map; callers keep existing capabilities.
 * Probe never blocks or breaks normal chat flow.
 */
class LmStudioModelCatalog(
  private val baseUrl: String,
  // Overridable for tests; production uses shared short-timeout client
  private val httpClient: HttpClient = probeHttpClient
) {

  companion object {
    // Process-lifetime cache: baseUrl -> capabilities keyed by model name
    private val capabilityCache: ConcurrentHashMap<String, Map<String, LmStudioModelCapabilities>> =
      ConcurrentHashMap()
  }

  /**
   * Returns capabilities for [modelName], probing on first access and caching
   * per-server result for process lifetime. Returns [fallbackCapabilities] when
   * server unreachable or model absent.
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

  // Performs the actual probe against root-level /api/v1/models
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

  // Builds {root}/api/v1/models, stripping trailing /v1 from baseUrl
  private fun resolveModelsUrl(): String {
    val rootUrl: String = baseUrl.trimEnd('/').removeSuffix(suffix = "/v1")
    return "$rootUrl/$LM_STUDIO_MODELS_PATH"
  }

  // Parses the /api/v1/models payload into per-model capabilities
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