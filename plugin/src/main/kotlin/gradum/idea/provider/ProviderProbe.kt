/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderProbe.kt  2026-08-18 12:45:23 Changed by gwy
 */
package gradum.idea.provider

import gradum.idea.PluginConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Abstraction over a single health probe so tests can inject a fake
 * instead of dialing the real embedded server. The plugin's production
 * implementation is [ProviderProbe]; [ProviderCoordinator] owns the
 * probe lifecycle and only depends on this contract.
 */
interface ProviderProbeContract {
  suspend fun probe(
    apiKey: String,
    baseUrl: String,
    kind: ProviderKind
  ): ProviderStatus
}

/**
 * Single-shot probe for a model provider, routed through the Gradum server.
 *
 * The plugin never dials the provider directly — it asks the embedded
 * server (`POST /provider/probe`) to run the health check on the shared
 * network stack. The server answers with `{ status, latencyMs, error }`,
 * which is folded into a [ProviderStatus] for the settings page badge.
 */
class ProviderProbe(
  private val serverBaseUrl: String = "http://localhost:8765",
) : ProviderProbeContract {

  private val client: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
    .build()

  private val jsonParser: Json = Json { ignoreUnknownKeys = true }

  override suspend fun probe(
    apiKey: String, baseUrl: String, kind: ProviderKind
  ): ProviderStatus = withContext(Dispatchers.IO) {
    val requestBody: JsonObject = buildJsonObject {
      put("kind", kind.wireName)
      put("baseUrl", baseUrl.trim())
      if (apiKey.isNotBlank()) put("apiKey", apiKey.trim())
    }
    val request: HttpRequest = HttpRequest.newBuilder()
      .uri(URI.create("${serverBaseUrl.trimEnd('/')}/provider/probe"))
      .header("Content-Type", "application/json")
      .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
      .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
      .build()
    try {
      val response: HttpResponse<String> =
        client.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() !in 200..299) {
        return@withContext ProviderStatus.Failed("HTTP ${response.statusCode()}")
      }
      val responseObject: JsonObject = jsonParser.parseToJsonElement(response.body()).jsonObject
      val status: String = responseObject["status"]?.jsonPrimitive?.contentOrNull ?: "failed"
      val latencyMs: Long = responseObject["latencyMs"]?.jsonPrimitive?.long ?: 0L
      when (status) {
        "ok" -> ProviderStatus.Ok(latencyMs)
        "auth" -> ProviderStatus.AuthError(latencyMs)
        "unreachable" -> ProviderStatus.Unreachable(latencyMs)
        else -> ProviderStatus.Failed(responseObject["error"]?.jsonPrimitive?.contentOrNull ?: status)
      }
    } catch (exception: CancellationException) {
      throw exception
    } catch (_: ConnectException) {
      ProviderStatus.Unreachable(0L)
    } catch (_: java.net.http.HttpTimeoutException) {
      ProviderStatus.Unreachable(0L)
    } catch (exception: Exception) {
      ProviderStatus.Failed(exception.javaClass.simpleName)
    }
  }

  private companion object {
    val CONNECT_TIMEOUT_SECONDS: Long = PluginConfig.PROBE_CONNECT_TIMEOUT.seconds
    val REQUEST_TIMEOUT_SECONDS: Long = PluginConfig.PROBE_REQUEST_TIMEOUT.seconds
  }
}
