/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelCatalog.kt  2026-06-30 23:35:47 Changed by gwy
 */

package gradum.discovery

import io.ktor.client.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val logger: Logger = LoggerFactory.getLogger("ModelCatalog")

data class ModelMetadata(
    val contextLimit: Int = 0,
    val reasoning: Boolean = false,
    val toolCall: Boolean = false,
    val openWeights: Boolean = false,
    val attachment: Boolean = false
)

private val catalogCache: MutableMap<String, ModelMetadata> = mutableMapOf()

fun loadCatalog() {
    try {
        val httpClient = HttpClient()
        httpClient.use { client ->
            val response: HttpResponse = runBlocking {
                client.get("https://models.dev/api.json") {
                    timeout { requestTimeoutMillis = 10000 }
                }
            }
            if (response.status.value == 200) {
                val body: String = runBlocking { response.bodyAsText() }
                val root: JsonObject = Json.parseToJsonElement(body).jsonObject
                var count = 0
                for ((_, providerData) in root) {
                    val models = providerData.jsonObject["models"]?.jsonObject ?: continue
                    for ((_, modelData) in models) {
                        val modelObject = modelData.jsonObject
                        val id = modelObject["id"]?.jsonPrimitive?.content ?: continue
                        val normalizedKey = normalizeModelName(id)
                        catalogCache[normalizedKey] = ModelMetadata(
                            contextLimit = modelObject["limit"]?.jsonObject?.get("context")?.jsonPrimitive?.int ?: 0,
                            reasoning = modelObject["reasoning"]?.jsonPrimitive?.boolean ?: false,
                            toolCall = modelObject["tool_call"]?.jsonPrimitive?.boolean ?: false,
                            openWeights = modelObject["open_weights"]?.jsonPrimitive?.boolean ?: false,
                            attachment = modelObject["attachment"]?.jsonPrimitive?.boolean ?: false
                        )
                        count++
                    }
                }
                logger.info("Loaded $count model entries from models.dev catalog")
            } else {
                logger.warn("Failed to fetch models.dev catalog: HTTP ${response.status.value}")
            }
        }
    } catch (exception: Exception) {
        logger.warn("Failed to load models.dev catalog: ${exception.message}")
    }
}

fun lookupMetadata(modelName: String): ModelMetadata? {
    val normalized = normalizeModelName(modelName)

    catalogCache[normalized]?.let { return it }

    for ((key, metadata) in catalogCache) {
        if (key.contains(normalized) || normalized.contains(key)) {
            return metadata
        }
    }

    val modelBase = normalized.split("-").takeWhile { part ->
        !part.startsWith("7b") && !part.startsWith("8b") && !part.startsWith("13b") &&
            !part.startsWith("14b") && !part.startsWith("32b") && !part.startsWith("70b") &&
            !part.startsWith("72b") && !part.startsWith("30b") && !part.startsWith("80b")
    }.joinToString("-")

    if (modelBase.length >= 4) {
        for ((key, metadata) in catalogCache) {
            if (key.startsWith(modelBase) || modelBase.startsWith(key)) {
                return metadata
            }
        }
    }

    return null
}

private fun normalizeModelName(name: String): String {
    return name.lowercase()
        .replace(".", "-")
        .replace(":", "-")
        .replace("_", "-")
        .replace(Regex("-(instruct|chat|hf|gguf|ggml|awq|gptq|exl2|fp16|bf16)$"), "")
        .replace(
            Regex(":?(7b|8b|13b|14b|32b|70b|72b|30b|80b|3b|1b|0.5b|0.6b|1.5b|2b|4b|9b|11b|22b|34b|40b|65b|110b|180b|405b)(-|$)"),
            "-"
        )
        .replace(Regex("-+"), "-")
        .trim('-')
}
