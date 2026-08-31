/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelIdentity.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum

import gradum.ModelIdentity.Discovery.baseKnownServers
import gradum.ModelIdentity.Discovery.cloudApiKeyEnvCandidates
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
import java.net.URI
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
  val configKey: String? = null,
  val apiKeyEnvVar: String? = null
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

  private val jsonParser: Json = Json { ignoreUnknownKeys = true }

  private const val MAX_SMALL_MODEL_PARAMETERS_B: Double = 32.0

  /**
   * Whether [url] points at a loopback address (`localhost`,
   * `127.x.x.x`, `::1`). Used to enforce the per-provider
   * "allow remote" flag: non-loopback base URLs are refused unless
   * the flag is enabled. Returns `false` for unparseable URLs so a
   * malformed override never slips past the guard.
   */
  fun isLocalHostUrl(url: String): Boolean {
    val host: String = runCatching { URI(url).host }.getOrNull() ?: return false
    val normalized: String = host.removePrefix("[").removeSuffix("]")

    if (normalized.equals(other = "localhost", ignoreCase = true)) return true
    if (normalized == "::1") return true
    if (normalized.startsWith(prefix = "127.")) return true
    if (normalized == "0.0.0.0") return true
    return false
  }

  /**
   * Builds the models-list endpoint for an OpenAI-compatible base URL.
   * Users may paste a base URL that already ends with `/v1` (e.g.
   * `http://192.168.1.5:1234/v1`), so the `/v1/models` suffix must not be
   * appended twice — otherwise the request hits `/v1/v1/models` and fails.
   */
  fun resolveModelsEndpoint(baseUrl: String, endpoint: String): String {
    val base: String = baseUrl.trimEnd('/')
    val suffix: String = endpoint.trimStart('/')

    return if (base.endsWith(suffix = "/v1") && suffix.startsWith(prefix = "v1/")) {
      base.removeSuffix("/v1") + "/" + suffix
    } else {
      "$base/$suffix"
    }
  }

  /**
   * Parses a model-list response body and returns the model names.
   * Ollama returns `{"models":[{name,…}]}`, OpenAI-compatible providers
   * return `{"data":[{id,…}]}`. Used both by settings-page probes and
   * model discovery, so a probe can verify it actually received a real
   * model list instead of trusting an HTTP 200 blindly.
   */
  fun parseModelNamesFromBody(providerType: String, body: String): List<String> {
    if (body.isBlank()) return emptyList()

    val responseJson: JsonObject = try {
      jsonParser.parseToJsonElement(string = body).jsonObject
    } catch (_: Exception) {
      return emptyList()
    }

    val modelNames: List<String> = when (providerType) {
      Provider.OLLAMA.wireType -> responseJson["models"]?.jsonArray?.map {
        it.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: ""
      } ?: emptyList()

      else -> responseJson["data"]?.jsonArray?.map {
        it.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: ""
      } ?: emptyList()
    }
    return modelNames.filter { it.isNotBlank() }
  }

  /**
   * Defensive URL-structure check applied by [probeProvider]. Local
   * providers (Ollama, LM Studio, vLLM, LocalAI) only accept a bare
   * `http(s)://host[:port]` or a `/v1` OpenAI prefix — arbitrary junk
   * paths like `/v1832483294239482394` are rejected before any dial.
   * Cloud providers keep their built-in multi-segment base URLs
   * (e.g. `.../api/coding/paas/v4`), so only their host is validated.
   */
  private fun isWellFormedProviderUrl(kind: String, baseUrl: String): Boolean {
    val parsedUri = runCatching { URI(baseUrl) }.getOrNull() ?: return false

    val uriScheme = parsedUri.scheme
    if (uriScheme == null || !uriScheme.matches(
        Regex(pattern = "https?", option = RegexOption.IGNORE_CASE)
      )
    ) return false

    if (parsedUri.host == null || !isValidHost(parsedUri.host))
      return false

    if (parsedUri.port != -1 && parsedUri.port !in 1..65535)
      return false

    if (parsedUri.query != null || parsedUri.fragment != null || parsedUri.userInfo != null)
      return false

    val isLocalKind = kind.lowercase() in setOf("ollama", "lmstudio", "vllm", "localai")
    if (!isLocalKind) return true

    val uriPath = parsedUri.path ?: ""
    return uriPath in setOf("", "/", "/v1", "/v1/")
  }

  private fun isValidHost(host: String): Boolean {
    if (host.isEmpty()) return false
    val ipv4: Boolean = host.matches(regex = IPV4_PATTERN) && host.split(".")
      .all { octet -> octet.toInt() in 0..255 }
    return ipv4 || host.matches(regex = HOSTNAME_PATTERN)
  }

  private val IPV4_PATTERN: Regex = Regex(
    pattern = """\d{1,3}(\.\d{1,3}){3}"""
  )
  private val HOSTNAME_PATTERN: Regex = Regex(
    pattern = """[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)*"""
  )
  private val PARAMETER_PATTERN: Regex = Regex(
    pattern = """(\d+\.?\d*)b(?:\s|$|:|[-_])""", option = RegexOption.IGNORE_CASE
  )

  private val CLOUD_KEYWORDS: Set<String> = setOf(
    "cloud", "api", "gpt", "claude", "gemini",
    "sonnet", "haiku", "opus", "pro", "flash",
    "turbo", "mini", "large", "xxl",
  )

  fun isCloudTagged(modelName: String): Boolean =
    modelName.contains(other = "cloud", ignoreCase = true)

  fun isSmallModel(modelName: String): Boolean {
    if (modelName.isBlank()) return false
    val lowerName = modelName.lowercase()

    if (CLOUD_KEYWORDS.any { lowerName.contains(other = it) }) return false
    val match = PARAMETER_PATTERN.find(input = lowerName) ?: return false
    val modelSize = match.groupValues[1].toDoubleOrNull() ?: return false

    return modelSize <= MAX_SMALL_MODEL_PARAMETERS_B
  }

  fun schemaVariant(modelName: String): SchemaVariant =
    if (isSmallModel(modelName)) SchemaVariant.SIMPLE
    else SchemaVariant.FULL

  fun discoverModels(): List<ModelEntry> = Discovery.probe()

  /**
   * Result of a single one-shot provider probe, driven from the plugin
   * settings page. Mirrors the plugin's `ProviderStatus` so the UI can
   * render a latency badge and a localized error reason without the
   * plugin ever dialing the provider directly.
   */
  data class ProviderProbeResult(
    // "ok" | "unreachable" | "auth" | "failed"
    val status: String,
    val latencyMs: Long,
    val error: String? = null
  )

  /**
   * Shared HTTP dial used by both the settings probe (`/provider/probe`)
   * and model discovery. Performs a GET against [url] with an optional
   * bearer token, a bounded timeout, and latency measurement. Returns the
   * raw status + body; throws on connect/timeout so callers can classify
   * the failure as unreachable.
   */
  private data class HttpProbe(val status: HttpStatusCode, val body: String, val latencyMs: Long)

  private fun httpProbe(url: String, apiKey: String?, method: String = "GET", body: String? = null): HttpProbe {
    val startedAt: Long = System.nanoTime()
    HttpClient().use { client ->
      val response = runBlocking {
        when (method.uppercase()) {
          "POST" -> client.post(urlString = url) {
            timeout { requestTimeoutMillis = 5000 }
            apiKey?.takeIf { it.isNotBlank() }?.let { key ->
              header("Authorization", "Bearer $key")
            }
            if (body != null) {
              contentType(ContentType.Application.Json)
              setBody(body)
            }
          }

          else -> client.get(urlString = url) {
            timeout { requestTimeoutMillis = 5000 }
            apiKey?.takeIf { it.isNotBlank() }?.let { key ->
              header("Authorization", "Bearer $key")
            }
          }
        }
      }
      val latencyMs: Long = (System.nanoTime() - startedAt) / 1_000_000
      return HttpProbe(response.status, body = runBlocking {
        response.bodyAsText()
      }, latencyMs)
    }
  }

  /**
   * One-shot connectivity probe for an arbitrary provider, used by
   * `POST /provider/probe`. The server (not the plugin) dials the
   * provider so settings-page checks share the server's network stack,
   * timeout rules, and auth handling. Local (Ollama) providers are
   * probed without a token; cloud endpoints are sent a bearer token when
   * [apiKey] is present.
   */
  fun probeProvider(kind: String, baseUrl: String, apiKey: String?): ProviderProbeResult {
    val trimmedBaseUrl: String = baseUrl.trim().trimEnd('/')
    if (trimmedBaseUrl.isEmpty()) {
      return ProviderProbeResult(status = "failed", latencyMs = 0, error = "URL is empty")
    }

    if (!isWellFormedProviderUrl(kind, trimmedBaseUrl)) {
      return ProviderProbeResult(
        latencyMs = 0,
        status = "failed",
        error = "Malformed provider URL: expected http(s)://host[:port] with an optional /v1 path"
      )
    }

    val configKey: String? = ProviderConfigStore.configKeyFor(kind)
    if (configKey == "lmstudio" && !isLocalHostUrl(trimmedBaseUrl)
      && !ProviderConfigStore.isAllowRemote(configKey)
    ) {
      return ProviderProbeResult(
        latencyMs = 0,
        status = "failed",
        error = "Remote provider connections are disabled; enable \"Allow connection to a local network or remote server\"",
      )

    }
    val endpoint: String = when (kind.lowercase()) {
      "ollama" -> "$trimmedBaseUrl/api/tags"
      "zhipu" -> resolveModelsEndpoint(trimmedBaseUrl, endpoint = "/models")
      else -> resolveModelsEndpoint(trimmedBaseUrl, endpoint = "/v1/models")
    }

    val startedAt: Long = System.nanoTime()
    return try {
      val httpProbe: HttpProbe = httpProbe(url = endpoint, apiKey)
      val wireType: String =
        if (kind.equals(other = "ollama", ignoreCase = true))
          Provider.OLLAMA.wireType
        else Provider.OPENAI.wireType

      when (httpProbe.status.value) {
        in 200..299 -> {
          val modelNames: List<String> = parseModelNamesFromBody(providerType = wireType, httpProbe.body)
          if (modelNames.isEmpty()) {
            ProviderProbeResult(
              status = "failed",
              latencyMs = httpProbe.latencyMs,
              error = "HTTP 200 but no model list in the response body"
            )
          } else {
            ProviderProbeResult(status = "ok", httpProbe.latencyMs)
          }
        }

        401, 403 -> ProviderProbeResult(
          status = "auth", httpProbe.latencyMs, error = "HTTP ${httpProbe.status.value}"
        )

        else -> ProviderProbeResult(
          status = "failed", httpProbe.latencyMs, error = "HTTP ${httpProbe.status.value}"
        )
      }
    } catch (probeException: Exception) {
      ProviderProbeResult(
        status = "unreachable",
        error = probeException.javaClass.simpleName,
        latencyMs = (System.nanoTime() - startedAt) / 1_000_000
      )
    }
  }

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
     * (see resolveCloudApiKey). Adding a new cloud provider here is
     * the supported way to "promote" it to first-class — no plugin
     * restart, no env-var JSON config, no extra injection method.
     *
     * Each hosted provider declares its own apiKeyEnvVar
     * so users with multiple cloud accounts (DeepSeek + MiniMax +
     * Zhipu) can keep keys separate. When that env var is unset the
     * shared [cloudApiKeyEnvCandidates] list is tried as a fallback.
     */
    private val baseKnownServers = listOf(
      ServerDef(
        name = "vLLM",
        baseUrl = "",
        endpoint = "/v1/models",
        providerType = Provider.OPENAI.wireType,
        configKey = "vllm"
      ),
      ServerDef(
        name = "LocalAI",
        baseUrl = "",
        endpoint = "/v1/models",
        providerType = Provider.OPENAI.wireType,
        configKey = "localai"
      ),
      ServerDef(
        name = "LM Studio",
        baseUrl = "",
        endpoint = "/api/v0/models",
        providerType = Provider.OPENAI.wireType,
        configKey = "lmstudio"
      ),
      ServerDef(
        name = "Ollama",
        baseUrl = "",
        endpoint = "/api/tags",
        providerType = Provider.OLLAMA.wireType,
        configKey = "ollama"
      ),
      ServerDef(
        configKey = "zhipu",
        endpoint = "/models",
        name = "Zhipu BigModel",
        apiKeyEnvVar = "ZHIPU_API_KEY",
        providerType = Provider.OPENAI.wireType,
        baseUrl = "https://open.bigmodel.cn/api/coding/paas/v4"
      ),
      ServerDef(
        name = "DeepSeek",
        endpoint = "/models",
        configKey = "deepseek",
        apiKeyEnvVar = "DEEPSEEK_API_KEY",
        baseUrl = "https://api.deepseek.com/v1",
        providerType = Provider.OPENAI.wireType
      ),
      ServerDef(
        name = "MiniMax",
        endpoint = "/models",
        configKey = "minimax",
        apiKeyEnvVar = "MiniMax_API_KEY",
        baseUrl = "https://api.minimaxi.com/v1",
        providerType = Provider.OPENAI.wireType
      ),
    )

    /**
     * Env-var lookup order for the bearer token shared by every
     * built-in hosted provider. Mirrors
     * [gradum.server.ServerConfiguration.resolveDefaultApiKeyFromEnv]
     * so the probe key and the chat fallback key always agree —
     * including the exact spelling of `MiniMax_API_KEY` (mixed
     * case), which must match the `apiKeyEnvVar` declared on the
     * MiniMax [ServerDef]. A mismatch here means a user who sets
     * `MiniMax_API_KEY` gets working chat fallback on the server
     * while discovery silently probes with no key (or vice versa).
     * Adding a new provider to [baseKnownServers] needs no change
     * here — the same key is reused.
     */
    private val cloudApiKeyEnvCandidates: List<String> = listOf(
      "GRADUM_OPENAI_API_KEY",
      "BIGMODEL_API_KEY",
      "ZHIPU_API_KEY",
      "DEEPSEEK_API_KEY",
      "MiniMax_API_KEY",
      "OPENAI_API_KEY"
    )

    private sealed class ProbeResult {
      data class Ok(val models: List<ModelEntry>) : ProbeResult()
      data class HttpError(val status: HttpStatusCode) : ProbeResult()
      data object Unreachable : ProbeResult()
    }

    /** Per-server snapshot used to detect state changes across probes. */
    private data class ServerSnapshot(val name: String, val status: String, val modelCount: Int)

    @Volatile
    private var previousSnapshots: List<ServerSnapshot> = emptyList()

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

        val resolvedUrl: String? = configuredUrl
          ?: server.baseUrl.trim().takeIf { it.isNotEmpty() }
        if (resolvedUrl == null) return@mapNotNull null

        var effective: ServerDef = server.copy(baseUrl = resolvedUrl.trimEnd('/'))

        if (effective.apiKey == null) {
          val providerKey: String? = ProviderConfigStore.apiKeyKey(server.configKey)
            ?.let { providerEnv.getProperty(it) }?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: effective.apiKeyEnvVar?.let { resolveEnvVar(envVarName = it) }

          val effectiveKey: String? = providerKey ?: cloudApiKey

          if (effectiveKey != null && effective.providerType == Provider.OPENAI.wireType) {
            effective = effective.copy(apiKey = effectiveKey)
          }
        }

        if (effective.apiKeyEnvVar != null && effective.apiKey.isNullOrBlank())
          return@mapNotNull null

        if (effective.configKey == "lmstudio" && !isLocalHostUrl(effective.baseUrl) &&
          !ProviderConfigStore.isAllowRemote(effective.configKey)
        ) {
          logger.info(
            "Skipping ${effective.name}: remote base URL ${effective.baseUrl} " +
              "is disabled (allow-remote flag off)"
          )
          return@mapNotNull null
        }
        effective
      }

      logger.info("Probing ${serversToProbe.size} server(s)")

      val currentSnapshots = mutableListOf<ServerSnapshot>()
      val discovered = mutableListOf<ModelEntry>()
      for ((index, server) in serversToProbe.withIndex()) {
        val isLastServer = index == serversToProbe.lastIndex
        val branchPrefix = if (isLastServer) "\u2514\u2500\u2500" else "\u251C\u2500\u2500"
        val probeStart: Long = System.nanoTime()
        val result: ProbeResult =
          try {
            probeServer(server)
          } catch (any: Throwable) {
            logger.error(
              "{} {} — {} ({}ms)", branchPrefix, server.name,
              any.javaClass.simpleName, (System.nanoTime() - probeStart) / 1_000_000, any
            )
            ProbeResult.Unreachable
          }

        val probeDurationMs: Long = (System.nanoTime() - probeStart) / 1_000_000
        val snapshot: ServerSnapshot =
          when (result) {
            is ProbeResult.Ok -> ServerSnapshot(server.name, status = "ok", modelCount = result.models.size)
            is ProbeResult.HttpError -> ServerSnapshot(server.name, status = "http-${result.status.value}", modelCount = 0)
            ProbeResult.Unreachable -> ServerSnapshot(server.name, status = "unreachable", modelCount = 0)
          }
        currentSnapshots.add(snapshot)

        val previous = previousSnapshots.find { it.name == server.name }
        val isChanged = previous == null || previous != snapshot

        if (isChanged) {
          when (result) {
            is ProbeResult.Ok -> {
              logger.info("$branchPrefix ${server.name} — ${result.models.size} model(s) in ${probeDurationMs}ms")
              discovered.addAll(elements = result.models.map { it.copy(available = true) })
            }

            is ProbeResult.HttpError -> logger.warn("{} {} — HTTP {}", branchPrefix, server.name, result.status.value)
            ProbeResult.Unreachable -> logger.warn("{} {} — unreachable", branchPrefix, server.name)
          }
        } else {
          if (result is ProbeResult.Ok)
            discovered.addAll(elements = result.models.map { it.copy(available = true) })
        }
      }
      previousSnapshots = currentSnapshots

      val withHealth = runBlocking {
        coroutineScope {
          val cloudHealth = discovered
            .filter { isOllamaCloudModel(entry = it) }
            .associate { it.modelName to async { probeCloudModel(entry = it) } }
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
      return try {
        val probe: HttpProbe = httpProbe(url = resolveModelsEndpoint(server.baseUrl, server.endpoint), server.apiKey)
        if (probe.status != HttpStatusCode.OK) {
          return ProbeResult.HttpError(probe.status)
        }

        val modelNames: List<String> = parseModelNamesFromBody(server.providerType, probe.body)
        val visionModels: Set<String> = detectVisionModels(server = server, probeBody = probe.body, modelNames = modelNames)

        ProbeResult.Ok(models = modelNames.map {
          ModelEntry(
            modelName = it,
            serverName = server.name,
            serverUrl = server.baseUrl,
            attachment = it in visionModels,
            providerType = server.providerType
          )
        })
      } catch (_: Exception) {
        ProbeResult.Unreachable
      } catch (probeError: Throwable) {
        throw probeError
      }
    }

    /**
     * Detects which models in [modelNames] support image inputs by
     * querying the provider's own capability API. Returns the subset of
     * model names that have vision capability.
     *
     * Detection strategy per provider:
     * - **Ollama**: calls `/api/show` for each model in parallel and
     *   checks the `capabilities` array for `"vision"`.
     * - **LM Studio**: parses the already-fetched [probeBody] from the
     *   `/api/v0/models` endpoint, looking for `type: "vlm"`.
     * - **Other OpenAI-compatible**: returns empty set (no reliable
     *   capability API).
     */
    private fun detectVisionModels(
      server: ServerDef, probeBody: String, modelNames: List<String>
    ): Set<String> {
      if (modelNames.isEmpty()) return emptySet()

      return when (server.providerType) {
        Provider.OLLAMA.wireType -> detectOllamaVision(server.baseUrl, modelNames)
        Provider.OPENAI.wireType -> detectLmStudioVision(probeBody, modelNames)
        else -> emptySet()
      }
    }

    /**
     * Probes Ollama's `/api/show` for each model in parallel and returns
     * the subset whose `capabilities` array includes `"vision"`.
     */
    private fun detectOllamaVision(baseUrl: String, modelNames: List<String>): Set<String> = runBlocking {
      coroutineScope {
        modelNames.map { name ->
          async {
            try {
              val showProbe: HttpProbe = httpProbe(
                url = "$baseUrl/api/show",
                apiKey = null,
                method = "POST",
                body = """{"name":"$name"}"""
              )
              if (showProbe.status != HttpStatusCode.OK) return@async null

              val json: JsonObject = jsonParser.parseToJsonElement(string = showProbe.body).jsonObject
              val capabilities: JsonArray? = json["capabilities"]?.jsonArray

              if (capabilities != null && capabilities.any { it.jsonPrimitive.content == "vision" }) name
              else null
            } catch (_: Exception) {
              null
            }
          }
        }.mapNotNull { it.await() }.toSet()
      }
    }

    /**
     * Parses the LM Studio `/api/v0/models` response body and returns
     * model names whose `type` field equals `"vlm"` (Vision Language Model).
     */
    private fun detectLmStudioVision(probeBody: String, modelNames: List<String>): Set<String> {
      if (probeBody.isBlank()) return emptySet()
      return try {
        val json: JsonObject = jsonParser.parseToJsonElement(string = probeBody).jsonObject
        json["data"]?.jsonArray?.mapNotNull { element ->
          val jsonObject: JsonObject = element.jsonObject
          val modelId: String = jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          val modelType: String = jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          if (modelType == "vlm" && modelId in modelNames) modelId else null
        }?.toSet() ?: emptySet()
      } catch (_: Exception) {
        emptySet()
      }
    }

    private fun resolveCloudApiKey(): String? {
      for (envVarName in cloudApiKeyEnvCandidates)
        resolveEnvVar(envVarName)?.let { return it }
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
              {"model":"${entry.modelName}",
              "messages":[{"role":"user","content":"."}],"stream":false,"options":{"num_predict":1}}
              """.trimIndent()
            )
          }
        }
        if (response.status != HttpStatusCode.OK)
          return classifyHttpStatus(response.status)

        val responseBody = runBlocking { response.bodyAsText() }
        val errorElement = jsonParser.parseToJsonElement(string = responseBody).jsonObject["error"]
        if (errorElement != null) {
          classifyErrorMessage(errorElement.jsonPrimitive.contentOrNull ?: errorElement.toString())
        } else null
      }
    } catch (networkException: Exception) {
      logger.debug("Failed to probe cloud model ${entry.modelName}: ${networkException.message}", networkException)
      UnavailableReason.NETWORK
    }

    private fun classifyHttpStatus(status: HttpStatusCode): UnavailableReason =
      when (status.value) {
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

      @Volatile
      private var configFingerprint: String? = null

      fun getOrCompute(computeBlock: () -> List<ModelEntry>): List<ModelEntry> {
        val currentTimeMillis = System.currentTimeMillis()
        val currentFingerprint: String = ProviderConfigStore.fingerprint()
        val configChanged: Boolean = currentFingerprint != configFingerprint
        snap?.let {
          if (!configChanged && currentTimeMillis - it.timestamp < TTL_MS)
            return it.models
        }
        return computeBlock().also {
          snap = Snapshot(timestamp = currentTimeMillis, models = it)
          configFingerprint = currentFingerprint
        }
      }
    }
  }
}
