/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumApiClient.kt  2026-06-26 15:51:49 Changed by gwy
 */

package gradum.idea

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class GradumApiClient(val baseUrl: String = "http://localhost:8765") {

    private val client: HttpClient = HttpClient.newHttpClient()

    suspend fun getModels(): String = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/models"))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        response.body()
    }

    fun close() {
        client.close()
    }
}
