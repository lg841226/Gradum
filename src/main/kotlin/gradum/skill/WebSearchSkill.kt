/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * WebSearchSkill.kt  2026-08-13 21:12:52 Changed by gwy
 */

package gradum.skill

import gradum.SkillResult
import gradum.ToolMode
import gradum.makeFailure
import gradum.makeSuccess
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger: Logger = LoggerFactory.getLogger("WebSearchSkill")
private val jsonParser: Json = Json { ignoreUnknownKeys = true }

/**
 * Searches the web via Tavily Search API.
 *
 * Requires `TAVILY_API_KEY` environment variable.
 * Provider is hardcoded — the LLM only decides query, max_results, and search_depth.
 *
 * ## Validation layers
 * 1. Query length limit (500 chars) + control-char stripping
 * 2. API key presence check
 * 3. HTTP timeout (connect 10s / socket 20s / request 30s)
 * 4. Retry with exponential backoff (3 attempts)
 * 5. Response body size cap (5 MB) + JSON parse validation
 * 6. Content sanitization: control-char strip + snippet truncation (1000 chars)
 * 7. URL validation: only http/https schemes, length cap (2048)
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
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
      socketTimeoutMillis = SOCKET_TIMEOUT_MS
      requestTimeoutMillis = REQUEST_TIMEOUT_MS
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
          "maximum" to MAX_RESULTS
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
    val startTime = Instant.now()

    val rawQuery = (arguments["query"] as? String)
    if (rawQuery.isNullOrBlank())
      return reject(
        "INVALID_PARAMETER", "Query must not be empty.",
        "Provide a search query, e.g. 'Kotlin coroutines best practices'."
      )

    if (rawQuery.trim().length > MAX_QUERY_LENGTH)
      return reject(
        "INVALID_PARAMETER",
        "Query exceeds maximum length of $MAX_QUERY_LENGTH characters (got ${rawQuery.trim().length}).",
        "Shorten the query to $MAX_QUERY_LENGTH characters or fewer."
      )

    val query = sanitizeQuery(rawQuery)

    val maxResults = clampMaxResults(arguments["max_results"])

    val searchDepth = (arguments["search_depth"] as? String)?.trim()?.lowercase()
      ?.takeIf { it in VALID_SEARCH_DEPTH } ?: "basic"

    val apiKey = System.getenv("TAVILY_API_KEY")
    if (apiKey.isNullOrBlank())
      return reject(
        "SEARCH_FAILED", "TAVILY_API_KEY environment variable not set.",
        "Set the TAVILY_API_KEY environment variable with your Tavily API key."
      )

    var lastException: Exception? = null
    for (attempt in 1..MAX_RETRIES) {
      try {
        val results = searchTavily(query, maxResults, searchDepth, apiKey)

        val elapsed = ChronoUnit.MILLIS.between(startTime, Instant.now())
        logger.info(
          "query='{}' depth={} results={} attempt={} elapsed={}ms",
          query, searchDepth, results.size, attempt, elapsed
        )

        return makeSuccess(
          linkedMapOf(
            "query" to query,
            "max_results" to maxResults,
            "search_depth" to searchDepth,
            "results" to results
          )
        )
      } catch (searchException: Exception) {
        lastException = searchException
        logger.warn(
          "Attempt {}/{} failed for query='{}': {}",
          attempt, MAX_RETRIES, query, searchException.message
        )

        if (attempt < MAX_RETRIES) {
          val delayMs = RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))
          logger.info("Retrying in {}ms...", delayMs)
          Thread.sleep(delayMs.coerceAtMost(RETRY_MAX_DELAY_MS))
        }
      }
    }

    val elapsed = ChronoUnit.MILLIS.between(startTime, Instant.now())
    logger.error(
      "All {} retries exhausted for query='{}': {} elapsed={}ms",
      MAX_RETRIES, query, lastException?.message, elapsed
    )

    return reject(
      "SEARCH_FAILED",
      "Web search failed after $MAX_RETRIES attempts: ${lastException?.message}",
      "Check your API key and network connection. The service may be temporarily down."
    )
  }

  private fun searchTavily(
    query: String, maxResults: Int, searchDepth: String, apiKey: String
  ): List<Map<String, Any>> {
    val requestBody = buildJsonObject {
      put("query", query)
      put("max_results", maxResults)
      put("search_depth", searchDepth)
      put("include_answer", false)
      put("include_raw_content", false)
      put("include_images", false)
    }

    val response = runBlocking {
      httpClient.post(TAVILY_SEARCH_URL) {
        contentType(ContentType.Application.Json)
        setBody(requestBody.toString())
        header("Authorization", "Bearer $apiKey")
      }
    }

    validateResponseStatus(response.status)

    val bodyText = runBlocking { response.bodyAsText() }
    if (bodyText.isBlank())
      throw SearchApiException("Tavily API returned empty response body")
    if (bodyText.length > MAX_RESPONSE_BODY_BYTES)
      throw SearchApiException("Tavily API response exceeds size limit (${bodyText.length} bytes)")

    val json = try {
      jsonParser.parseToJsonElement(bodyText).jsonObject
    } catch (jsonException: Exception) {
      throw SearchApiException("Tavily API returned invalid JSON: ${jsonException.message}")
    }

    return parseTavilyResults(json, maxResults)
  }

  private fun parseTavilyResults(json: JsonObject, maxResults: Int): List<Map<String, Any>> {
    val resultsArray = json["results"]?.jsonArray ?: return emptyList()
    return resultsArray
      .take(maxResults)
      .mapNotNull { element -> parseSingleResult(element) }
      .take(maxResults)
  }

  private fun parseSingleResult(element: JsonElement): Map<String, Any>? {
    val obj = try {
      element.jsonObject
    } catch (parseException: Exception) {
      logger.debug("Skipping non-object element in results: {}", parseException.message)
      return null
    }

    val title = obj["title"]?.jsonPrimitive?.contentOrNull
      ?.takeIf { it.isNotBlank() }
      ?.let { sanitizeContent(it) }
      ?: return null

    val content = obj["content"]?.jsonPrimitive?.contentOrNull
      ?.takeIf { it.isNotBlank() }
      ?.let { truncateSnippet(sanitizeContent(it)) }
      ?: return null

    val url = sanitizeUrl(obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty())
    val score = obj["score"]?.jsonPrimitive?.doubleOrNull

    val resultMap = linkedMapOf<String, Any>("title" to title, "snippet" to content, "url" to url)
    if (score != null) resultMap["score"] = score
    return resultMap
  }

  private fun validateResponseStatus(status: HttpStatusCode) {
    if (status == HttpStatusCode.TooManyRequests)
      throw SearchRateLimitedException("Tavily API rate limit exceeded (HTTP 429)")
    if (status.value in 500..599)
      throw SearchServerException("Tavily API server error (HTTP ${status.value})")
    if (status != HttpStatusCode.OK)
      throw SearchApiException("Tavily API returned HTTP ${status.value}: ${status.description}")
  }

  private fun sanitizeQuery(raw: String): String =
    raw.trim().replace(CONTROL_CHAR_REGEX, "").take(MAX_QUERY_LENGTH)

  private fun sanitizeContent(raw: String): String =
    raw.replace(CONTROL_CHAR_REGEX, "").take(MAX_SNIPPET_LENGTH)

  private fun truncateSnippet(content: String): String {
    if (content.length <= MAX_SNIPPET_LENGTH) return content
    return content.take(MAX_SNIPPET_LENGTH - 3) + "..."
  }

  private fun sanitizeUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    if (!ALLOWED_URL_SCHEMES.any { trimmed.lowercase().startsWith(it) }) {
      logger.debug("Rejected URL with disallowed scheme: {}", trimmed)
      return ""
    }
    if (trimmed.length > MAX_URL_LENGTH) return trimmed.take(MAX_URL_LENGTH)
    return trimmed
  }

  private fun clampMaxResults(value: Any?): Int = when (value) {
    is Number -> value.toInt().coerceIn(1, MAX_RESULTS)
    is String -> value.toIntOrNull()?.coerceIn(1, MAX_RESULTS) ?: DEFAULT_MAX_RESULTS
    else -> DEFAULT_MAX_RESULTS
  }

  private fun reject(code: String, message: String, fixHint: String): SkillResult =
    makeFailure(code, buildXmlError(code = code, message = message, fixHint = fixHint))

  private class SearchRateLimitedException(message: String) : RuntimeException(message)
  private class SearchServerException(message: String) : RuntimeException(message)
  private class SearchApiException(message: String) : RuntimeException(message)

  companion object {
    private const val MAX_QUERY_LENGTH: Int = 500
    private const val MAX_RESULTS: Int = 10
    private const val DEFAULT_MAX_RESULTS: Int = 5
    private const val MAX_SNIPPET_LENGTH: Int = 1000
    private const val MAX_URL_LENGTH: Int = 2048
    private const val MAX_RESPONSE_BODY_BYTES: Int = 5 * 1024 * 1024

    private const val CONNECT_TIMEOUT_MS: Long = 10_000L
    private const val SOCKET_TIMEOUT_MS: Long = 20_000L
    private const val REQUEST_TIMEOUT_MS: Long = 30_000L

    private const val MAX_RETRIES: Int = 3
    private const val RETRY_BASE_DELAY_MS: Long = 1_000L
    private const val RETRY_MAX_DELAY_MS: Long = 10_000L

    private val ALLOWED_URL_SCHEMES = listOf("https:", "http:")
    private val VALID_SEARCH_DEPTH = setOf("basic", "advanced")
    private const val TAVILY_SEARCH_URL = "https:api.tavily.com/search"
    private val CONTROL_CHAR_REGEX = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]")
  }
}
