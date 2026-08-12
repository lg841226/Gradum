/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelIdentity.kt  2026-08-11 14:53:20 Changed by gwy
 */

package gradum

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory


data class ModelEntry(
  val modelName: String,
  val providerType: String,
  val serverUrl: String,
  val serverName: String,
  val contextLimit: Int = 0,
  val reasoning: Boolean = false,
  val toolCall: Boolean = false,
  val openWeights: Boolean = false,
  val attachment: Boolean = false,
  val available: Boolean = true,
  val unavailableReason: UnavailableReason? = null,
)

data class RecommendationContext(val availableRamGB: Double) {
  companion object {
    private const val FREE_RAM_HEADROOM_FACTOR: Double = 0.75

    fun fromSystemMemory(): RecommendationContext {
      val operatingSystemMXBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
        as com.sun.management.OperatingSystemMXBean
      val availableRamGB = operatingSystemMXBean.freeMemorySize.toDouble() / 1024 / 1024 / 1024 * FREE_RAM_HEADROOM_FACTOR
      return RecommendationContext(availableRamGB)
    }
  }
}

enum class UnavailableReason {
  AUTH, QUOTA_EXCEEDED, RATE_LIMIT, NETWORK, OTHER
}

/**
 * Single authority for the full model lifecycle: discovery, catalog,
 * recommendation, capability inference, and schema variant resolution.
 *
 * Internal concerns are isolated behind private inner classes
 * (Discovery, Catalog, Recommender) so the public API stays thin
 * while the implementation can evolve independently.
 */
object ModelIdentity {

  private const val MAX_SMALL_MODEL_PARAMETERS_B: Double = 32.0

  private val PARAMETER_PATTERN: Regex =
    Regex("""(\d+\.?\d*)b(?:\s|$|:|[-_])""", RegexOption.IGNORE_CASE)

  private val CLOUD_KEYWORDS: Set<String> = setOf(
    "cloud", "api", "gpt", "claude", "gemini",
    "sonnet", "haiku", "opus", "pro", "flash",
    "turbo", "mini", "large", "xxl",
  )

  val LOCAL_SERVER_NAMES: Set<String> = setOf("Ollama", "LM Studio", "vLLM", "LocalAI")

  fun parameterCountInBillions(modelName: String): Double {
    val match = PARAMETER_PATTERN.find(modelName) ?: return 0.0
    return match.groupValues[1].toDoubleOrNull() ?: 0.0
  }

  fun isCloudTagged(modelName: String): Boolean =
    modelName.contains("cloud", ignoreCase = true)

  fun isSmallModel(modelName: String): Boolean {
    if (modelName.isBlank()) return false
    val lowerName = modelName.lowercase()
    if (CLOUD_KEYWORDS.any { lowerName.contains(it) }) return false
    val match = PARAMETER_PATTERN.find(lowerName) ?: return false
    val size = match.groupValues[1].toDoubleOrNull() ?: return false
    return size <= MAX_SMALL_MODEL_PARAMETERS_B
  }

  fun schemaVariant(modelName: String): SchemaVariant =
    if (isSmallModel(modelName)) SchemaVariant.SIMPLE else SchemaVariant.FULL

  fun normalizeCatalogKey(name: String): String =
    name.lowercase()
      .replace(".", "-").replace(":", "-").replace("_", "-")
      .replace(REGEX_SUFFIX_STRIP, "")
      .replace(REGEX_SIZE_STRIP, "-")
      .replace(REGEX_DASH_COLLAPSE, "-")
      .trim('-')


  fun discoverModels(): List<ModelEntry> = Discovery.probe()

  fun recommend(
    models: List<ModelEntry>,
    context: RecommendationContext = RecommendationContext.fromSystemMemory(),
  ): ModelEntry? = Recommender.recommend(models, context)

  fun parameterCountInBillions(model: ModelEntry): Double =
    parameterCountInBillions(model.modelName)

  private object Discovery {
    private val logger: Logger = LoggerFactory.getLogger("ModelIdentity.Discovery")

    private data class ServerDef(
      val name: String, val providerType: String,
      val baseUrl: String, val endpoint: String,
    )

    private val knownServers = listOf(
      ServerDef("Ollama", Provider.OLLAMA.wireType, AgentConfiguration.DEFAULT_OLLAMA_BASE_URL, "/api/tags"),
      ServerDef("LM Studio", Provider.OPENAI.wireType, "http://localhost:1234", "/v1/models"),
      ServerDef("vLLM", Provider.OPENAI.wireType, "http://localhost:8000", "/v1/models"),
      ServerDef("LocalAI", Provider.OPENAI.wireType, "http://localhost:8080", "/v1/models"),
    )

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private sealed class ProbeResult {
      data class Ok(val models: List<ModelEntry>) : ProbeResult()
      data class HttpError(val status: HttpStatusCode) : ProbeResult()
      data object Unreachable : ProbeResult()
    }

    fun probe(): List<ModelEntry> = HealthCache.getOrCompute { doProbe() }

    private fun doProbe(): List<ModelEntry> {
      Catalog.load()
      val discovered = mutableListOf<ModelEntry>()
      for (server in knownServers) {
        when (val result = probeServer(server)) {
          is ProbeResult.Ok -> discovered.addAll(result.models.map { it.copy(available = true) })
          is ProbeResult.HttpError -> logger.debug("Skipping ${server.name}: HTTP ${result.status.value}")
          ProbeResult.Unreachable -> logger.debug("Skipping ${server.name}: unreachable")
        }
      }

      val withHealth = runBlocking {
        coroutineScope {
          val cloudHealth = discovered
            .filter { isOllamaCloudModel(it) }
            .associate { it.modelName to async { probeCloudModel(it) } }
          discovered.map { entry ->
            if (isOllamaCloudModel(entry)) {
              cloudHealth[entry.modelName]?.await()?.let { reason ->
                entry.copy(available = false, unavailableReason = reason)
              } ?: entry
            } else entry
          }
        }
      }

      return withHealth.map { entry ->
        Catalog.lookup(entry.modelName)?.let { meta ->
          entry.copy(
            contextLimit = meta.contextLimit, reasoning = meta.reasoning,
            toolCall = meta.toolCall, openWeights = meta.openWeights,
            attachment = meta.attachment,
          )
        } ?: entry
      }
    }

    private fun probeServer(server: ServerDef): ProbeResult = try {
      HttpClient().use { client ->
        val response = runBlocking {
          client.get("${server.baseUrl}${server.endpoint}") {
            timeout { requestTimeoutMillis = 2000 }
          }
        }
        if (response.status != HttpStatusCode.OK) return ProbeResult.HttpError(response.status)

        val responseBody = runBlocking { response.bodyAsText() }
        val responseJson = jsonParser.parseToJsonElement(responseBody).jsonObject

        val modelNames = when (server.providerType) {
          Provider.OLLAMA.wireType -> responseJson["models"]?.jsonArray?.map {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: ""
          }?.filter { it.isNotBlank() } ?: emptyList()

          else -> responseJson["data"]?.jsonArray?.map {
            it.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: ""
          }?.filter { it.isNotBlank() } ?: emptyList()
        }

        ProbeResult.Ok(modelNames.map { ModelEntry(it, server.providerType, server.baseUrl, server.name) })
      }
    } catch (probeException: Exception) {
      logger.debug("Failed to probe ${server.name} at ${server.baseUrl}: ${probeException.message}", probeException)
      ProbeResult.Unreachable
    }

    private fun isOllamaCloudModel(entry: ModelEntry): Boolean =
      entry.providerType == Provider.OLLAMA.wireType && isCloudTagged(entry.modelName)

    private fun probeCloudModel(entry: ModelEntry): UnavailableReason? = try {
      HttpClient().use { client ->
        val response = runBlocking {
          client.post("${entry.serverUrl}/api/chat") {
            contentType(ContentType.Application.Json)
            timeout { requestTimeoutMillis = 5000 }
            setBody("""{"model":"${entry.modelName}","messages":[{"role":"user","content":"."}],"stream":false,"options":{"num_predict":1}}""")
          }
        }
        if (response.status != HttpStatusCode.OK) return classifyHttpStatus(response.status)

        val responseBody = runBlocking { response.bodyAsText() }
        val errorElement = jsonParser.parseToJsonElement(responseBody).jsonObject["error"]
        if (errorElement != null) {
          classifyErrorMessage(errorElement.jsonPrimitive.contentOrNull ?: errorElement.toString())
        } else null
      }
    } catch (networkException: Exception) {
      logger.debug("Failed to probe cloud model ${entry.modelName}: ${networkException.message}", networkException)
      UnavailableReason.NETWORK
    }

    private fun classifyHttpStatus(status: HttpStatusCode): UnavailableReason = when (status.value) {
      401 -> UnavailableReason.AUTH
      402, 403 -> UnavailableReason.QUOTA_EXCEEDED
      429 -> UnavailableReason.RATE_LIMIT
      in 500..599 -> UnavailableReason.NETWORK
      else -> UnavailableReason.OTHER
    }

    private fun classifyErrorMessage(message: String): UnavailableReason {
      val lower = message.lowercase()
      return when {
        "subscription" in lower || "quota" in lower || "upgrade" in lower -> UnavailableReason.QUOTA_EXCEEDED
        "rate limit" in lower || "too many requests" in lower -> UnavailableReason.RATE_LIMIT
        "sign in" in lower || "signin" in lower || "unauthorized" in lower || "auth" in lower -> UnavailableReason.AUTH
        "network" in lower || "connection" in lower || "timeout" in lower -> UnavailableReason.NETWORK
        else -> UnavailableReason.OTHER
      }
    }

    private object HealthCache {
      private const val TTL_MS = 60_000L

      private data class Snapshot(val timestamp: Long, val models: List<ModelEntry>)

      @Volatile
      private var snap: Snapshot? = null

      fun getOrCompute(computeBlock: () -> List<ModelEntry>): List<ModelEntry> {
        val currentTimeMillis = System.currentTimeMillis()
        snap?.let { if (currentTimeMillis - it.timestamp < TTL_MS) return it.models }
        return computeBlock().also { snap = Snapshot(currentTimeMillis, it) }
      }
    }
  }

  private object Catalog {
    private val logger: Logger = LoggerFactory.getLogger("ModelIdentity.Catalog")
    private val cache = mutableMapOf<String, ModelMetadata>()

    fun load() {
      try {
        HttpClient().use { client ->
          val response = runBlocking {
            client.get("https://models.dev/api.json") {
              timeout { requestTimeoutMillis = 10_000 }
            }
          }
          if (response.status.value != 200) {
            logger.warn("Failed to fetch models.dev catalog: HTTP ${response.status.value}")
            return
          }
          val catalogJson = Json.parseToJsonElement(runBlocking { response.bodyAsText() }).jsonObject
          var loadedCount = 0
          for ((_, providerData) in catalogJson) {
            val models = providerData.jsonObject["models"]?.jsonObject ?: continue
            for ((_, modelData) in models) {
              val modelObject = modelData.jsonObject
              val modelId = modelObject["id"]?.jsonPrimitive?.content ?: continue
              cache[normalizeCatalogKey(modelId)] = ModelMetadata(
                contextLimit = modelObject["limit"]?.jsonObject?.get("context")?.jsonPrimitive?.int ?: 0,
                toolCall = modelObject["tool_call"]?.jsonPrimitive?.boolean ?: false,
                reasoning = modelObject["reasoning"]?.jsonPrimitive?.boolean ?: false,
                attachment = modelObject["attachment"]?.jsonPrimitive?.boolean ?: false,
                openWeights = modelObject["open_weights"]?.jsonPrimitive?.boolean ?: false,
              )
              loadedCount++
            }
          }
          logger.info("Loaded $loadedCount model entries from models.dev catalog")
        }
      } catch (modelException: Exception) {
        logger.warn("Failed to load models.dev catalog", modelException)
      }
    }

    fun lookup(modelName: String): ModelMetadata? {
      val normalizedKey = normalizeCatalogKey(modelName)
      cache[normalizedKey]?.let { return it }
      for ((cachedKey, modelMetadata) in cache) {
        if (cachedKey.contains(normalizedKey) || normalizedKey.contains(cachedKey)) return modelMetadata
      }
      val normalizedPrefix = normalizedKey.split("-").takeWhile { part ->
        !part.startsWith("7b") && !part.startsWith("8b") && !part.startsWith("13b") &&
          !part.startsWith("14b") && !part.startsWith("32b") && !part.startsWith("70b") &&
          !part.startsWith("72b") && !part.startsWith("30b") && !part.startsWith("80b")
      }.joinToString("-")
      if (normalizedPrefix.length >= 4) {
        for ((cachedKey, meta) in cache) {
          if (cachedKey.startsWith(normalizedPrefix) || normalizedPrefix.startsWith(cachedKey)) return meta
        }
      }
      return null
    }
  }

  private object Recommender {
    fun recommend(models: List<ModelEntry>, context: RecommendationContext): ModelEntry? {
      if (models.isEmpty()) return null
      return models.maxByOrNull { score(it, context) }
    }

    private fun score(model: ModelEntry, context: RecommendationContext): Double {
      var totalScore = minOf(model.contextLimit, 200_000) / 2_000.0
      if (isCloudTagged(model.modelName) || model.serverName !in LOCAL_SERVER_NAMES) {
        totalScore += 200.0
      } else {
        val parameterBillions = parameterCountInBillions(model.modelName)
        totalScore += parameterBillions * 1.5
        if (parameterBillions * 0.8 > context.availableRamGB) totalScore -= 500.0
      }
      if (model.reasoning) totalScore += 20.0
      if (model.toolCall) totalScore += 10.0
      if (model.attachment) totalScore += 5.0
      return totalScore
    }
  }
}


data class ModelMetadata(
  val contextLimit: Int = 0,
  val toolCall: Boolean = false,
  val reasoning: Boolean = false,
  val attachment: Boolean = false,
  val openWeights: Boolean = false,
)

private val REGEX_SUFFIX_STRIP = Regex("-(instruct|chat|hf|gguf|ggml|awq|gptq|exl2|fp16|bf16)$")
private val REGEX_SIZE_STRIP = Regex(":?(7b|8b|13b|14b|32b|70b|72b|30b|80b|3b|1b|0.5b|0.6b|1.5b|2b|4b|9b|11b|22b|34b|40b|65b|110b|180b|405b)(-|$)")
private val REGEX_DASH_COLLAPSE = Regex("-+")
