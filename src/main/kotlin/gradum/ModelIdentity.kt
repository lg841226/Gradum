/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelIdentity.kt  2026-08-16 00:08:59 Changed by gwy
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
import java.util.*


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
  val apiKeyEnvVar: String? = null,
  val configKey: String? = null
)

/**
  * Single authority for the full model lifecycle: discovery, capability
  * inference, and schema variant resolution.
  *
  * Internal concerns are isolated behind private inner classes
  * (Discovery) so the public API stays thin
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

  fun discoverModels(): List<ModelEntry> = Discovery.probe()

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
      ServerDef("vLLM", "", "/v1/models", Provider.OPENAI.wireType, configKey = "vllm"),
      ServerDef("LocalAI", "", "/v1/models", Provider.OPENAI.wireType, configKey = "localai"),
      ServerDef("LM Studio", "", "/v1/models", Provider.OPENAI.wireType, configKey = "lmstudio"),
      ServerDef("Ollama", "", "/api/tags", Provider.OLLAMA.wireType, configKey = "ollama"),
      ServerDef(
        name = "Zhipu BigModel",
        endpoint = "/models",
        apiKeyEnvVar = "ZHIPU_API_KEY",
        providerType = Provider.OPENAI.wireType,
        baseUrl = "https://open.bigmodel.cn/api/coding/paas/v4",
        configKey = "zhipu"
      ),
      ServerDef(
        name = "DeepSeek",
        endpoint = "/models",
        apiKeyEnvVar = "DEEPSEEK_API_KEY",
        providerType = Provider.OPENAI.wireType,
        baseUrl = "https://api.deepseek.com/v1",
        configKey = "deepseek"
      ),
      ServerDef(
        name = "MiniMax",
        endpoint = "/models",
        apiKeyEnvVar = "MiniMax_API_KEY",
        providerType = Provider.OPENAI.wireType,
        baseUrl = "https://api.minimaxi.com/v1",
        configKey = "minimax"
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
      it.providerType == Provider.OPENAI.wireType && it.apiKeyEnvVar != null
    }

    fun doProbe(): List<ModelEntry> {
      val cloudApiKey: String? = resolveCloudApiKey()
      val providerEnv: Properties = ProviderConfigStore.load()
      val serversToProbe: List<ServerDef> = baseKnownServers.mapNotNull { server ->
        val configuredUrl: String? = ProviderConfigStore.baseUrlKey(server.configKey)
          ?.let { providerEnv.getProperty(it) }
          ?.trim()
          ?.takeIf { it.isNotEmpty() }
        // Resolve the base URL: an explicitly configured value wins; a local
        // provider with no configured URL (and no fixed default) is skipped;
        // a cloud provider falls back to its built-in fixed endpoint.
        val resolvedUrl: String? = configuredUrl
          ?: server.baseUrl.trim().takeIf { it.isNotEmpty() }
        if (resolvedUrl == null) return@mapNotNull null
        var effective: ServerDef = server.copy(baseUrl = resolvedUrl.trimEnd('/'))
        if (effective.apiKey == null) {
          val providerKey: String? = ProviderConfigStore.apiKeyKey(server.configKey)
            ?.let { providerEnv.getProperty(it) }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: effective.apiKeyEnvVar?.let { resolveEnvVar(it) }
          val effectiveKey: String? = providerKey ?: cloudApiKey
          if (effectiveKey != null && effective.providerType == Provider.OPENAI.wireType) {
            effective = effective.copy(apiKey = effectiveKey)
          }
        }
        // A provider that requires an API key (declares an env-var source,
        // e.g. the hosted cloud providers) is skipped when no key resolved —
        // probing it without auth would only burn a network call and fail.
        if (effective.apiKeyEnvVar != null && effective.apiKey.isNullOrBlank()) {
          return@mapNotNull null
        }
        effective
      }
      logger.info(
        "Probing ${serversToProbe.size} server(s): " +
          serversToProbe.joinToString { "${it.name}@${it.baseUrl}" })
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

      return withHealth
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
}
