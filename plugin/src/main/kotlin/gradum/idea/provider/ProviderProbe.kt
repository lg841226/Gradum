/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderProbe.kt  2026-08-15 20:05:03 Changed by gwy
 */
package gradum.idea.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class ProviderProbe {

  private val client: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
    .build()

  suspend fun probe(kind: ProviderKind, baseUrl: String, apiKey: String): ProviderStatus = withContext(Dispatchers.IO) {
    val trimmedBaseUrl: String = baseUrl.trim().trimEnd('/')

    if (trimmedBaseUrl.isEmpty()) return@withContext ProviderStatus.Failed("URL is empty")

    val endpoint: String = when (kind) {
      ProviderKind.OLLAMA -> "$trimmedBaseUrl/api/tags"
      ProviderKind.LM_STUDIO -> "$trimmedBaseUrl/v1/models"
    }

    val startedAt: Long = System.currentTimeMillis()
    try {
      val requestBuilder: HttpRequest.Builder = HttpRequest.newBuilder()
        .uri(URI.create(endpoint))
        .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
        .GET()

      if (apiKey.isNotBlank()) requestBuilder.header("Authorization", "Bearer ${apiKey.trim()}")

      val response: HttpResponse<String> = client.send(
        requestBuilder.build(),
        HttpResponse.BodyHandlers.ofString()
      )
      val latencyMs: Long = System.currentTimeMillis() - startedAt
      when (response.statusCode()) {
        in 200..299 -> ProviderStatus.Ok(latencyMs)
        401, 403 -> ProviderStatus.AuthError(latencyMs)
        else -> ProviderStatus.Failed("HTTP ${response.statusCode()}")
      }
    } catch (_: java.net.ConnectException) {
      ProviderStatus.Unreachable(System.currentTimeMillis() - startedAt)
    } catch (_: java.net.http.HttpTimeoutException) {
      ProviderStatus.Unreachable(System.currentTimeMillis() - startedAt)
    } catch (exception: Exception) {
      ProviderStatus.Failed(exception.javaClass.simpleName)
    }
  }

  private companion object {
    const val CONNECT_TIMEOUT_SECONDS: Long = 3
    const val REQUEST_TIMEOUT_SECONDS: Long = 5
  }
}
