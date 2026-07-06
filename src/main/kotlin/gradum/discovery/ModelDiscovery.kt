/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelDiscovery.kt  2026-07-05 22:56:12 Changed by gwy
 */

package gradum.discovery

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
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
  val attachment: Boolean = false,
  /**
   * `true` when the last health check confirmed the model is
   * reachable. For local Ollama / LM Studio / vLLM / LocalAI models
   * this is always `true` once the listing endpoint answered 200,
   * because the listing endpoint IS the health check — local
   * servers have no per-model auth.
   *
   * For Ollama cloud models (`:cloud` tag) and other paid upstream
   * endpoints reachable through the same daemon, this can flip to
   * `false` when the signin session has expired, the quota is
   * exhausted, or the upstream returns 5xx. The plugin UI grays
   * out such rows and replaces the pin icon with a lock icon so
   * the user knows the choice is unavailable right now, not just
   * "no longer pinned".
   */
  val available: Boolean = true,
  /**
   * When [available] is `false`, the reason the model is
   * unreachable. `null` when [available] is `true`. The plugin
   * turns this into a short, official-sounding tooltip on the
   * Lock icon — never blaming the user, always attributing the
   * cause to the upstream service (subscription gate, rate
   * limit, auth, network).
   */
  val unavailableReason: UnavailableReason? = null
)

private val knownServers: List<ServerDefinition> = listOf(
  ServerDefinition("Ollama", "ollama", "http://localhost:11434", "/api/tags"),
  ServerDefinition("LM Studio", "openai", "http://localhost:1234", "/v1/models"),
  ServerDefinition("vLLM", "openai", "http://localhost:8000", "/v1/models"),
  ServerDefinition("LocalAI", "openai", "http://localhost:8080", "/v1/models")
)

/**
 * Why a model is currently unreachable. The plugin turns this into a
 * short, official-sounding tooltip on the Lock icon — never
 * blaming the user, always attributing the cause to the upstream
 * service (subscription gate, rate limit, auth, network). The enum
 * names are wire-stable: they are sent verbatim in the `/models`
 * JSON and the plugin maps them to localized strings.
 */
enum class UnavailableReason {
  /** 401 — signin token missing or invalid. */
  AUTH,

  /** 402 / 403 — quota exhausted or "requires a subscription" gate. */
  QUOTA_EXCEEDED,

  /** 429 — upstream rate limit hit. */
  RATE_LIMIT,

  /** 5xx or transport-level error (timeout, connection refused). */
  NETWORK,

  /** Anything else the probe could not classify. */
  OTHER
}

private data class ServerDefinition(
  val serverName: String,
  val providerType: String,
  val baseUrl: String,
  val apiEndpoint: String
)

private val jsonParser: Json = Json { ignoreUnknownKeys = true }

/**
 * Per-server probe result, kept in a small ADT so the caller can
 * distinguish "we got an answer and the server is up" from "we got
 * an error code we can act on" from "we never heard back".
 */
private sealed class ProbeResult {
  data class Ok(val models: List<ModelEntry>) : ProbeResult()
  data class HttpError(val status: HttpStatusCode) : ProbeResult()
  data object Unreachable : ProbeResult()
}

/**
 * Public entry point used by `Routes` (and from tests). Backed by a
 * 60-second TTL cache so the plugin's 5-second `/models` polling
 * does not actually re-probe the upstream on every tick — the cache
 * serves the same answer for a full minute, then re-probes once and
 * serves the fresh result for the next minute.
 *
 * Cache invalidation: nothing currently forces an early refresh; the
 * plugin's "Refresh" button still hits `/models` and gets the cached
 * answer until the TTL expires. If the user wants the absolute
 * latest they can restart the server, which is acceptable for the
 * 60-second window — the alternative (a cache-bust header) would
 * complicate the wire contract for a use case the chat flow does
 * not depend on.
 */
fun discoverModels(): List<ModelEntry> = HealthCache.getOrCompute { doDiscoverModels() }

private fun doDiscoverModels(): List<ModelEntry> {
  loadCatalog()
  val discoveredModels: MutableList<ModelEntry> = mutableListOf()

  for (server in knownServers) {
    when (val result: ProbeResult = probeServer(server)) {
      is ProbeResult.Ok -> {
        // Local-server models inherit the listing-endpoint
        // health (200 means the daemon is up); they have no
        // per-model auth and no quota.
        val entries: List<ModelEntry> = result.models.map { it.copy(available = true) }
        discoveredModels.addAll(entries)
      }

      is ProbeResult.HttpError -> {
        // We could not even list models, so there is nothing
        // to mark unavailable. The next listing attempt will
        // pick up the recovered state when the cache expires.
        logger.debug("Skipping ${server.serverName}: HTTP ${result.status.value}")
      }

      ProbeResult.Unreachable -> {
        logger.debug("Skipping ${server.serverName}: unreachable")
      }
    }
  }

  // Per-model health for Ollama cloud models. The list endpoint
  // returns them in the same payload as local models (when the
  // user has signed in), but listing success does not guarantee
  // the cloud call succeeds — signin can be stale, the upstream
  // can be 5xx, or the specific cloud model can be gated behind
  // a subscription. OpenAI-compatible providers do not have a
  // per-model probe, so we trust their list — the listing endpoint
  // is auth-gated, so a 200 there already implies the key is valid.
  //
  // Each cloud probe is a real `POST /api/chat` round-trip to
  // ollama.com with `num_predict=1`, which can take up to 5s. We
  // fan them out in parallel via `async`/`await` so the total
  // probe time is one round-trip rather than N — a user with 6
  // cloud models should not wait 30s on every refresh.
  val withPerModelHealth: List<ModelEntry> = runBlocking {
    coroutineScope {
      val cloudHealth: Map<String, Deferred<UnavailableReason?>> = discoveredModels
        .filter { isOllamaCloudModel(it) }
        .associate { entry -> entry.modelName to async { probeOllamaCloudModel(entry) } }

      discoveredModels.map { entry: ModelEntry ->
        if (isOllamaCloudModel(entry)) {
          val reason: UnavailableReason? = cloudHealth[entry.modelName]?.await()
          if (reason == null) {
            entry
          } else entry.copy(available = false, unavailableReason = reason)
        } else entry
      }
    }
  }

  return withPerModelHealth.map { entry: ModelEntry ->
    val entryMetadata = lookupMetadata(entry.modelName)
    if (entryMetadata != null) {
      entry.copy(
        contextLimit = entryMetadata.contextLimit,
        reasoning = entryMetadata.reasoning,
        toolCall = entryMetadata.toolCall,
        openWeights = entryMetadata.openWeights,
        attachment = entryMetadata.attachment
      )
    } else {
      entry
    }
  }
}

private fun probeServer(server: ServerDefinition): ProbeResult {
  return try {
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

        val entries: List<ModelEntry> = modelNames.map { name ->
          ModelEntry(
            modelName = name,
            providerType = server.providerType,
            serverUrl = server.baseUrl,
            serverName = server.serverName
          )
        }
        logger.info("Found ${entries.size} models from ${server.serverName}: ${entries.map { it.modelName }}")
        ProbeResult.Ok(entries)
      } else {
        ProbeResult.HttpError(response.status)
      }
    }
  } catch (exception: Exception) {
    logger.debug("Failed to probe ${server.serverName} at ${server.baseUrl}: ${exception.message}")
    ProbeResult.Unreachable
  }
}

/**
 * Probe a single Ollama cloud model with `POST /api/chat` using
 * `stream=false`, an empty-ish user turn, and `options.num_predict=1`
 * — a real but token-cheap request that exercises the full cloud
 * auth / subscription / quota path. We cannot use `POST /api/show`
 * for this because the daemon serves it from its own metadata cache
 * and returns 200 even when the upstream Ollama account is
 * unsubscribed, signin has expired, or the free-tier quota is gone.
 *
 * Ollama's `/api/chat` failure mode is also unusual: the daemon
 * answers `200 OK` and embeds the error in a top-level `error`
 * JSON field instead of returning 4xx. So the probe has to read the
 * body and treat a non-null `error` as a hard unavailability
 * signal, on top of the usual non-200 short-circuit.
 *
 * Returns `null` when the model is reachable, or an
 * [UnavailableReason] when it is not — the reason is sent to the
 * plugin so the Lock icon's tooltip can say "Requires a
 * subscription" / "Rate limit reached" / etc., rather than a
 * generic "Not available".
 */
private fun probeOllamaCloudModel(entry: ModelEntry): UnavailableReason? {
  return try {
    val httpClient = HttpClient()
    httpClient.use { _ ->
      val response: HttpResponse = runBlocking {
        httpClient.post("${entry.serverUrl}/api/chat") {
          contentType(ContentType.Application.Json)
          timeout {
            // /api/chat is round-tripped to ollama.com for
            // the signin / subscription check before
            // returning, so even with num_predict=1 it
            // can take 2-4s on a healthy connection. 5s
            // gives one full retry window without
            // blowing the cache TTL budget.
            requestTimeoutMillis = 5000
          }
          setBody(
            """{"model":"${entry.modelName}","messages":[{"role":"user","content":"."}],"stream":false,"options":{"num_predict":1}}"""
          )
        }
      }
      if (response.status != HttpStatusCode.OK) {
        val reason: UnavailableReason = classifyHttpStatus(response.status)
        logger.info("Ollama cloud model ${entry.modelName} probe HTTP ${response.status.value} → $reason")
        return@use reason
      }
      val body: String = runBlocking { response.bodyAsText() }
      val parsed: JsonElement = jsonParser.parseToJsonElement(body)
      val errorElement: JsonElement? = parsed.jsonObject["error"]
      if (errorElement != null) {
        val errorMessage: String = errorElement.jsonPrimitive.contentOrNull ?: errorElement.toString()
        val reason: UnavailableReason = classifyErrorMessage(errorMessage)
        logger.info("Ollama cloud model ${entry.modelName} unavailable: $errorMessage → $reason")
        return@use reason
      }
      null
    }
  } catch (exception: Exception) {
    // Transport-level failure (timeout, connection refused, DNS
    // error) — treat as NETWORK, not as the user doing something
    // wrong.
    logger.debug("Failed to probe Ollama cloud model ${entry.modelName}: ${exception.message}")
    UnavailableReason.NETWORK
  }
}

/**
 * Map a non-200 HTTP status from the Ollama cloud probe to the
 * [UnavailableReason] the plugin should surface. The buckets are
 * chosen so the plugin tooltip text is short and framed as an
 * upstream service decision, not a user error:
 *  - 401 → AUTH (signin missing / expired)
 *  - 402 / 403 → QUOTA_EXCEEDED (subscription or quota gate)
 *  - 429 → RATE_LIMIT
 *  - 5xx → NETWORK (upstream degraded)
 *  - everything else → OTHER
 */
private fun classifyHttpStatus(status: HttpStatusCode): UnavailableReason {
  return when (status.value) {
    401 -> UnavailableReason.AUTH
    402 -> UnavailableReason.QUOTA_EXCEEDED
    403 -> UnavailableReason.QUOTA_EXCEEDED
    429 -> UnavailableReason.RATE_LIMIT
    in 500..599 -> UnavailableReason.NETWORK
    else -> UnavailableReason.OTHER
  }
}

/**
 * Classify Ollama's "200 OK with `error` field in body" responses
 * (which it uses for things like subscription-gated models) by
 * sniffing the error message. Substring matching is intentionally
 * permissive — the daemon's wording is stable but not part of a
 * documented contract, so we err on the side of catching
 * `QUOTA_EXCEEDED` for any "subscription / quota / upgrade"
 * mention, and fall back to [UnavailableReason.OTHER] for
 * anything we cannot read.
 */
private fun classifyErrorMessage(message: String): UnavailableReason {
  val lower: String = message.lowercase()
  return when {
    "subscription" in lower || "quota" in lower || "upgrade" in lower -> UnavailableReason.QUOTA_EXCEEDED
    "rate limit" in lower || "too many requests" in lower -> UnavailableReason.RATE_LIMIT
    "sign in" in lower || "signin" in lower || "unauthorized" in lower || "auth" in lower -> UnavailableReason.AUTH
    "network" in lower || "connection" in lower || "timeout" in lower -> UnavailableReason.NETWORK
    else -> UnavailableReason.OTHER
  }
}

/**
 * Identify Ollama cloud-tagged models. Ollama's listing endpoint
 * surfaces cloud models with a `:cloud` suffix (e.g.
 * `qwen3-coder:480b-cloud`); the daemon itself runs locally, so
 * `providerType == "ollama"` is not enough on its own.
 */
private fun isOllamaCloudModel(entry: ModelEntry): Boolean {
  if (entry.providerType != "ollama") return false
  return entry.modelName.endsWith(":cloud", ignoreCase = true) ||
    entry.modelName.contains("-cloud", ignoreCase = true) ||
    entry.modelName.contains("cloud", ignoreCase = true)
}

/**
 * Process-wide TTL cache for [discoverModels] results. The plugin
 * polls `/models` every 5 seconds; without a cache, every poll
 * would issue 4+ HTTP probes (one per known server) plus per-cloud-
 * model `POST /api/show` calls. The 60-second window keeps the
 * upstream cost bounded while still letting the UI recover from a
 * transient failure within a minute.
 */
private object HealthCache {
  private const val TTL_MS: Long = 60_000L

  private data class Snapshot(val timestamp: Long, val models: List<ModelEntry>)

  @Volatile
  private var snapshot: Snapshot? = null

  fun getOrCompute(computation: () -> List<ModelEntry>): List<ModelEntry> {
    val now: Long = System.currentTimeMillis()
    val current: Snapshot? = snapshot
    if (current != null && now - current.timestamp < TTL_MS)
      return current.models

    val fresh: List<ModelEntry> = computation()
    snapshot = Snapshot(now, fresh)
    return fresh
  }
}
