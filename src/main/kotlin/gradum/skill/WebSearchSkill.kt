/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * WebSearchSkill.kt  2026-08-19 17:24:33 Changed by gwy
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
 * Requires the web-search API key to be configured.
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
      return makeFailure(
        ErrorCode.INVALID_PARAMETER,
        buildXmlError(
          code = "INVALID_PARAMETER", message = "Query must not be empty.",
          fixHint = "Provide a search query, e.g. 'Kotlin coroutines best practices'."
        )
      )
    if (query.length > 5000)
      return makeFailure(
        ErrorCode.INVALID_PARAMETER,
        buildXmlError(
          code = "INVALID_PARAMETER", message = "Query too long (max 5000 chars).",
          fixHint = "Shorten the query."
        )
      )

    val maxResults = when (val value = arguments["max_results"]) {
      is Number -> value.toInt().coerceIn(1, 10)
      is String -> value.toIntOrNull()?.coerceIn(1, 10) ?: 5
      else -> 5
    }

    val searchDepth = (arguments["search_depth"] as? String)?.trim()?.lowercase()
      ?.takeIf { it == "basic" || it == "advanced" } ?: "basic"

    val apiKey = System.getenv("TAVILY_API_KEY")
    if (apiKey.isNullOrBlank())
      return makeFailure(
        "SEARCH_FAILED",
        buildXmlError(
          code = "SEARCH_FAILED", message = "Web search API key not configured.",
          fixHint = "Configure the web search API key and try again."
        )
      )

    // `include_favicon` is a UI-rendering concern, not a search behavior
    // concern — the LLM doesn't need to know it exists. Hardcoded so a
    // tool-call argument can't accidentally disable favicons.
    return try {
      val requestBody = buildJsonObject {
        put("query", query)
        put("max_results", maxResults)
        put("search_depth", searchDepth)
        put("include_answer", false)
        put("include_raw_content", false)
        put("include_favicon", true)
      }

      logger.info("Searching: query='{}' depth={} max={}", query, searchDepth, maxResults)

      val response = runBlocking {
        httpClient.post("https://api.tavily.com/search") {
          contentType(ContentType.Application.Json)
          setBody(requestBody.toString())
          header("Authorization", "Bearer $apiKey")
        }
      }

      if (response.status == HttpStatusCode.TooManyRequests)
        return makeFailure(
          "SEARCH_FAILED",
          buildXmlError(
            code = "SEARCH_FAILED", message = "Tavily rate limit exceeded (HTTP 429).",
            fixHint = "Wait a moment and try again."
          )
        )
      if (response.status.value >= 500)
        return makeFailure(
          "SEARCH_FAILED",
          buildXmlError(
            code = "SEARCH_FAILED", message = "Tavily server error (HTTP ${response.status.value}).",
            fixHint = "The service is temporarily down. Try again later."
          )
        )

      val bodyText = runBlocking { response.bodyAsText() }
      logger.debug("Tavily response status={} bodyLength={}", response.status, bodyText.length)

      val responseJson = try {
        jsonParser.parseToJsonElement(bodyText).jsonObject
      } catch (parseException: Exception) {
        logger.error("Failed to parse Tavily response: {}", parseException.message)
        return makeFailure(
          "SEARCH_FAILED",
          buildXmlError(
            code = "SEARCH_FAILED", message = "Invalid response from Tavily API.",
            fixHint = "The API returned unexpected data. Try again."
          )
        )
      }

      val results = responseJson["results"]?.jsonArray?.take(maxResults)?.mapNotNull { element ->
        val jsonObject = element.jsonObject
        val webTitle = jsonObject["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val content = jsonObject["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val webUrl = jsonObject["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val faviconUrl: String = jsonObject["favicon"]?.jsonPrimitive?.contentOrNull.orEmpty()
        linkedMapOf(
          "url" to webUrl,
          "title" to webTitle,
          "snippet" to content,
          "faviconUrl" to faviconUrl,
        )
      } ?: emptyList()

      logger.info("Got {} results for query='{}'", results.size, query)

      makeSuccess(
        linkedMapOf(
          "query" to query,
          "max_results" to maxResults,
          "search_depth" to searchDepth,
          "results" to results
        )
      )
    } catch (networkException: Exception) {
      logger.error("Web search failed for query='{}': {}", query, networkException.message, networkException)
      makeFailure(
        "SEARCH_FAILED",
        buildXmlError(
          code = "SEARCH_FAILED", message = "Web search failed: ${networkException.message}",
          fixHint = "Check your API key and network connection."
        )
      )
    }
  }
}
