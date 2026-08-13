/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * WebSearchSkill.kt  2026-08-13 22:10:00 Changed by gwy
 */

package gradum.skill

import gradum.*
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val logger: Logger = LoggerFactory.getLogger("WebSearchSkill")
private val jsonParser: Json = Json { ignoreUnknownKeys = true }

/**
 * Searches the web via Tavily Search API.
 * Requires `TAVILY_API_KEY` environment variable.
 */
class WebSearchSkill : Skill() {

  override val skillName: String = "search_web"
  override val alias: String = "Searched"
  override val description: String =
    "Search the web for information. Use this when you need up-to-date information, " +
      "documentation, or answers to questions about current events, libraries, or APIs."

  override val allowedToolModes: Set<ToolMode> =
    setOf(ToolMode.AGENT, ToolMode.EDIT, ToolMode.READ_ONLY)

  override val historyKeepCount: Int = 3
  override val historyVolatileKeys: List<String> = listOf("results")

  private val httpClient: HttpClient = HttpClient {
    install(HttpTimeout) {
      connectTimeoutMillis = 30_000L
      socketTimeoutMillis = 30_000L
      requestTimeoutMillis = 60_000L
    }
  }

  override fun getSchema(context: SkillContext?): Map<String, Any> {
    val useSimple = context?.isSimpleModel == true
    return buildFunctionSchema(
      description = if (useSimple) "Search the web for information" else description,
      properties = mapOf(
        "query" to mapOf(
          "type" to "string",
          "description" to "Search query"
        ),
        "max_results" to mapOf(
          "type" to "integer",
          "description" to "Max results (1-10, default 5)",
          "minimum" to 1,
          "maximum" to 10
        ),
        "search_depth" to mapOf(
          "type" to "string",
          "description" to "Search depth: basic (fast) or advanced (thorough). Default basic.",
          "enum" to listOf("basic", "advanced")
        )
      ),
      required = listOf("query"),
    )
  }

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val query = (arguments["query"] as? String)?.trim()
    if (query.isNullOrBlank())
      return makeFailure(ErrorCode.INVALID_PARAMETER,
        buildXmlError(code = "INVALID_PARAMETER", message = "Query must not be empty.",
          fixHint = "Provide a search query, e.g. 'Kotlin coroutines best practices'."))
    if (query.length > 500)
      return makeFailure(ErrorCode.INVALID_PARAMETER,
        buildXmlError(code = "INVALID_PARAMETER", message = "Query too long (max 500 chars).",
          fixHint = "Shorten the query."))

    val maxResults = when (val value = arguments["max_results"]) {
      is Number -> value.toInt().coerceIn(1, 10)
      is String -> value.toIntOrNull()?.coerceIn(1, 10) ?: 5
      else -> 5
    }

    val searchDepth = (arguments["search_depth"] as? String)?.trim()?.lowercase()
      ?.takeIf { it == "basic" || it == "advanced" } ?: "basic"

    val apiKey = System.getenv("TAVILY_API_KEY")
    if (apiKey.isNullOrBlank())
      return makeFailure("SEARCH_FAILED",
        buildXmlError(code = "SEARCH_FAILED", message = "TAVILY_API_KEY not set.",
          fixHint = "Set TAVILY_API_KEY environment variable."))

    return try {
      val requestBody = buildJsonObject {
        put("query", query)
        put("max_results", maxResults)
        put("search_depth", searchDepth)
        put("include_answer", false)
        put("include_raw_content", false)
        put("include_images", false)
      }

      logger.info("Searching: query='{}' depth={} max={}", query, searchDepth, maxResults)

      val response = runBlocking {
        httpClient.post("https://api.tavily.com/search") {
          contentType(ContentType.Application.Json)
          setBody(requestBody.toString())
          header("Authorization", "Bearer $apiKey")
        }
      }

      val bodyText = runBlocking { response.bodyAsText() }
      logger.debug("Tavily response status={} bodyLength={}", response.status, bodyText.length)

      val json = jsonParser.parseToJsonElement(bodyText).jsonObject

      val results = json["results"]?.jsonArray?.take(maxResults)?.mapNotNull { element ->
        val obj = element.jsonObject
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val content = obj["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        linkedMapOf("title" to title, "snippet" to content, "url" to url)
      } ?: emptyList()

      logger.info("Got {} results for query='{}'", results.size, query)

      makeSuccess(linkedMapOf(
        "query" to query,
        "max_results" to maxResults,
        "search_depth" to searchDepth,
        "results" to results
      ))
    } catch (exception: Exception) {
      logger.error("Web search failed for query='{}': {}", query, exception.message, exception)
      makeFailure("SEARCH_FAILED",
        buildXmlError(code = "SEARCH_FAILED", message = "Web search failed: ${exception.message}",
          fixHint = "Check your API key and network connection."))
    }
  }
}
