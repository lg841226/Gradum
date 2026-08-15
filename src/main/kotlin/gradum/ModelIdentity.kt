/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelIdentity.kt  2026-08-15 10:49:30 Changed by gwy
 */

package gradum

import gradum.ModelIdentity.Discovery.baseKnownServers
import gradum.ModelIdentity.Discovery.cloudApiKeyEnvCandidates
import gradum.ModelIdentity.Discovery.resolveCloudApiKey
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
  val serverUrl: String,
  val serverName: String,
  val providerType: String,
  val contextLimit: Int = 0,
  val toolCall: Boolean = false,
  val available: Boolean = true,
  val reasoning: Boolean = false,
  val attachment: Boolean = false,
  val openWeights: Boolean = false,
  val unavailableReason: UnavailableReason? = null
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
 * Description of a server to probe for model discovery.
 *
 * [apiKey] is the bearer token to send on the GET against
 * [endpoint]; pass null/blank for unauthenticated servers (local
 * Ollama / LM Studio / vLLM / LocalAI). The chat request itself
 * reads its key from [gradum.AgentConfiguration] — this field is
 * only for the discovery probe.
 *
 * [apiKeyEnvVar] is the **per-provider** environment variable
 * holding the bearer token for this server. When non-null,
 * [gradum.ModelIdentity.Discovery] resolves the key from this env
 * var first and only falls back to the shared
 * [gradum.ModelIdentity.Discovery.cloudApiKeyEnvCandidates] list
 * if it is unset / blank. This lets DeepSeek, MiniMax and Zhipu
 * each keep their own key (different vendors, different accounts)
 * without forcing the user to multiplex a single env var.
 */
data class ServerDef(
  val name: String,
  val baseUrl: String,
  val endpoint: String,
  val providerType: String,
  val apiKey: String? = null,
  val apiKeyEnvVar: String? = null
)

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

  /**
   * Built-in hosted (non-local) [ServerDef]s, exposed at `internal`
   * scope so ModelIdentityTest can assert that each new provider
   * we onboard (Zhipu, DeepSeek, MiniMax, …) is wired up with the
   * expected URL, env var, and OpenAI-compatible protocol — a
   * hand-edit deleting one of them will fail the build rather than
   * silently dropping the provider from the model selector.
   */
  internal val knownCloudServers: List<ServerDef>
    get() = Discovery.publicCloudServers()

  private object Discovery {
    private val logger: Logger = LoggerFactory.getLogger("ModelIdentity.Discovery")

    /**
     * Built-in servers always probed at startup. Local services first
     * (no auth, fastest to fail when offline), then hosted providers
     * that need a bearer token resolved at probe time
     * (see [resolveCloudApiKey]). Adding a new cloud provider here is
     * the supported way to "promote" it to first-class — no plugin
     * restart, no env-var JSON config, no extra injection method.
     *
     * Each hosted provider declares its own [ServerDef.apiKeyEnvVar]
     * so users with multiple cloud accounts (DeepSeek + MiniMax +
     * Zhipu) can keep keys separate. When that env var is unset the
     * shared [cloudApiKeyEnvCandidates] list is tried as a fallback.
     */
    private val baseKnownServers = listOf(
      ServerDef("vLLM", "http://localhost:8000", "/v1/models", Provider.OPENAI.wireType),
      ServerDef("LocalAI", "http://localhost:8080", "/v1/models", Provider.OPENAI.wireType),
      ServerDef("LM Studio", "http://localhost:1234", "/v1/models", Provider.OPENAI.wireType),
      ServerDef("Ollama", AgentConfiguration.DEFAULT_OLLAMA_BASE_URL, "/api/tags", Provider.OLLAMA.wireType),
      ServerDef(
        name = "Zhipu BigModel",
        endpoint = "/models",
        apiKeyEnvVar = "ZHIPU_API_KEY",
        providerType = Provider.OPENAI.wireType,
        baseUrl = "https://open.bigmodel.cn/api/coding/paas/v4"
      ),
      ServerDef(
        name = "DeepSeek",
        endpoint = "/models",
        apiKeyEnvVar = "DEEPSEEK_API_KEY",
        providerType = Provider.OPENAI.wireType,
        baseUrl = "https://api.deepseek.com/v1"
      ),
      ServerDef(
        name = "MiniMax",
        endpoint = "/models",
        apiKeyEnvVar = "MiniMax_API_KEY",
        providerType = Provider.OPENAI.wireType,
        baseUrl = "https://api.minimaxi.com/v1"
      ),
    )

    /**
     * Env-var lookup order for the bearer token shared by every
     * built-in hosted provider (today: just Zhipu). The order mirrors
     * [gradum.server.ServerConfiguration.resolveDefaultApiKeyFromEnv]
     * so the probe key and the chat fallback key always agree. Adding
     * a new provider to [baseKnownServers] needs no change here — the
     * same key is reused.
     */
    private val cloudApiKeyEnvCandidates: List<String> = listOf(
      "ZHIPU_API_KEY",
      "OPENAI_API_KEY",
      "MINIMAX_API_KEY",
      "BIGMODEL_API_KEY",
      "DEEPSEEK_API_KEY",
      "GRADUM_OPENAI_API_KEY"
    )

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private sealed class ProbeResult {
      data class Ok(val models: List<ModelEntry>) : ProbeResult()
      data class HttpError(val status: HttpStatusCode) : ProbeResult()
      data object Unreachable : ProbeResult()
    }

    fun probe(): List<ModelEntry> = HealthCache.getOrCompute { doProbe() }

    /**
     * Cloud-only [ServerDef]s (i.e. every non-local server from
     * [baseKnownServers]). Surfaced through
     * [gradum.ModelIdentity.knownCloudServers] for unit tests so
     * they can assert the URL / env-var wiring without poking at
     * private internals via reflection.
     *
     * The filter is "OpenAI protocol AND not on localhost" so
     * local OpenAI-compatible servers (LM Studio, vLLM, LocalAI)
     * that share the [Provider.OPENAI] wire type but have no
     * host-side `apiKey` stay out of the cloud list.
     */
    fun publicCloudServers(): List<ServerDef> = baseKnownServers.filter {
      it.providerType == Provider.OPENAI.wireType && !it.baseUrl.contains("localhost") && !it.baseUrl.contains("127.0.0.1")
    }

    fun doProbe(): List<ModelEntry> {
      val cloudApiKey: String? = resolveCloudApiKey()
      val serversToProbe: List<ServerDef> = baseKnownServers.map { server ->
        if (server.apiKey != null) return@map server
        val providerKey: String? = server.apiKeyEnvVar
          ?.let { resolveEnvVar(it) }
        val effectiveKey: String? = providerKey ?: cloudApiKey
        if (effectiveKey != null && server.providerType == Provider.OPENAI.wireType) {
          server.copy(apiKey = effectiveKey)
        } else server
      }
      logger.info(
        "Probing ${serversToProbe.size} server(s): " +
          serversToProbe.joinToString { "${it.name}@${it.baseUrl}" })
      Catalog.load()
      val discovered = mutableListOf<ModelEntry>()
      for (server in serversToProbe) {
        logger.info("Probing ${server.name} at ${server.baseUrl}${server.endpoint}")
        val probeStart: Long = System.nanoTime()
        val result: ProbeResult = try {
          probeServer(server)
        } catch (any: Throwable) {
          logger.error(
            "Uncaught throwable while probing ${server.name} at " +
              "${server.baseUrl}${server.endpoint} (after " +
              "${(System.nanoTime() - probeStart) / 1_000_000} ms)",
            any
          )
          ProbeResult.Unreachable
        }
        val probeDurationMs: Long = (System.nanoTime() - probeStart) / 1_000_000
        when (result) {
          is ProbeResult.Ok -> {
            logger.info("Discovered ${result.models.size} model(s) from ${server.name} at ${server.baseUrl} in ${probeDurationMs}ms")
            discovered.addAll(result.models.map { it.copy(available = true) })
          }

          is ProbeResult.HttpError -> Unit
          ProbeResult.Unreachable -> Unit
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

    private fun probeServer(server: ServerDef): ProbeResult {
      val authPreview: String? = server.apiKey?.takeIf { it.isNotBlank() }?.let { it.take(6) + "xxx" }
      logger.info("probeServer entered: ${server.name} ${server.baseUrl}${server.endpoint} (apiKey=$authPreview)")
      return try {
        HttpClient().use { client ->
          val response = runBlocking {
            client.get("${server.baseUrl}${server.endpoint}") {
              timeout { requestTimeoutMillis = 5000 }
              server.apiKey?.takeIf { it.isNotBlank() }?.let { key ->
                header("Authorization", "Bearer $key")
              }
            }
          }
          logger.info("probeServer got response: ${server.name} status=${response.status.value}")
          if (response.status != HttpStatusCode.OK) {
            logger.warn(
              "Skipping ${server.name} at ${server.baseUrl}${server.endpoint}: " +
                "HTTP ${response.status.value} (${response.status.description})"
            )
            return@use ProbeResult.HttpError(response.status)
          }

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

          ProbeResult.Ok(modelNames.map { ModelEntry(it, server.baseUrl, server.name, server.providerType) })
        }
      } catch (probeException: Exception) {
        logger.warn(
          "Skipping ${server.name} at ${server.baseUrl}${server.endpoint}: " +
            "${probeException.javaClass.simpleName}: ${probeException.message}",
          probeException
        )
        ProbeResult.Unreachable
      } catch (probeError: Throwable) {
        logger.error(
          "Uncaught throwable while probing ${server.name} at ${server.baseUrl}${server.endpoint}",
          probeError
        )
        ProbeResult.Unreachable
      }
    }

    private fun resolveCloudApiKey(): String? {
      for (envVarName in cloudApiKeyEnvCandidates) {
        resolveEnvVar(envVarName)?.let { return it }
      }
      return null
    }

    private fun resolveEnvVar(envVarName: String): String? {
      val rawValue: String? = System.getenv(envVarName)
      return rawValue?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun isOllamaCloudModel(entry: ModelEntry): Boolean =
      entry.providerType == Provider.OLLAMA.wireType && isCloudTagged(entry.modelName)

    private fun probeCloudModel(entry: ModelEntry): UnavailableReason? = try {
      HttpClient().use { client ->
        val response = runBlocking {
          client.post("${entry.serverUrl}/api/chat") {
            contentType(ContentType.Application.Json)
            timeout { requestTimeoutMillis = 5000 }
            setBody(
              """
              {"model":"${entry.modelName}","messages":[{"role":"user","content":"."}],"stream":false,"options":{"num_predict":1}}
              """.trimIndent()
            )
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
      429 -> UnavailableReason.RATE_LIMIT
      402, 403 -> UnavailableReason.QUOTA_EXCEEDED
      in 500..599 -> UnavailableReason.NETWORK
      else -> UnavailableReason.OTHER
    }

    private fun classifyErrorMessage(message: String): UnavailableReason {
      val lower = message.lowercase()
      return when {
        "rate limit" in lower || "too many requests" in lower -> UnavailableReason.RATE_LIMIT
        "network" in lower || "connection" in lower || "timeout" in lower -> UnavailableReason.NETWORK
        "subscription" in lower || "quota" in lower || "upgrade" in lower -> UnavailableReason.QUOTA_EXCEEDED
        "sign in" in lower || "signin" in lower || "unauthorized" in lower || "auth" in lower -> UnavailableReason.AUTH
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
                toolCall = modelObject["tool_call"]?.jsonPrimitive?.boolean ?: false,
                reasoning = modelObject["reasoning"]?.jsonPrimitive?.boolean ?: false,
                attachment = modelObject["attachment"]?.jsonPrimitive?.boolean ?: false,
                openWeights = modelObject["open_weights"]?.jsonPrimitive?.boolean ?: false,
                contextLimit = modelObject["limit"]?.jsonObject?.get("context")?.jsonPrimitive?.int ?: 0,
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
  val openWeights: Boolean = false
)

private val REGEX_DASH_COLLAPSE = Regex("-+")
private val REGEX_SUFFIX_STRIP = Regex("-(instruct|chat|hf|gguf|ggml|awq|gptq|exl2|fp16|bf16)$")
private val REGEX_SIZE_STRIP = Regex(":?(7b|8b|13b|14b|32b|70b|72b|30b|80b|3b|1b|0.5b|0.6b|1.5b|2b|4b|9b|11b|22b|34b|40b|65b|110b|180b|405b)(-|$)")
