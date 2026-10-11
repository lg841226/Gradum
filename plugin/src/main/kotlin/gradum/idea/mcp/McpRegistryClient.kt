package gradum.idea.mcp

import gradum.idea.PluginConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * An environment variable an imported server expects up front: required
 * variables land with an empty value (or their advertised default) so the
 * entry is complete enough to be filled in and started.
 */
data class McpRegistryEnvHint(
  val name: String, val value: String
)

/**
 * One server from the official MCP registry, flattened for the settings UI.
 *
 * @property name namespaced registry name (`io.github.example/server`).
 * @property command argv that starts the server over stdio, or null when the
 *   registry only advertises transports/packages this runtime cannot run yet
 *   (remote-only servers, unknown package types).
 * @property envHints environment variables pre-filled into the imported entry.
 * @property version server-level version from the registry, or null.
 * @property packageLine `identifier@version` for the package the command
 *   installs from (or the first package when none is importable), rendered
 *   as a meta line; null when the server lists no packages.
 * @property registryType package registry of that same package (`npm`,
 *   `pypi`, …) for the ecosystem badge; null when the server lists none.
 * @property repositoryUrl repository link opened by the info-link icon.
 * @property publishedAt official publish month (`yyyy-MM`) taken from the
 *   registry's `_meta` block, or null for entries without it.
 */
data class McpRegistryResult(
  val name: String,
  val description: String,
  val command: List<String>?,
  val envHints: List<McpRegistryEnvHint>,
  val version: String? = null,
  val packageLine: String? = null,
  val registryType: String? = null,
  val repositoryUrl: String? = null,
  val publishedAt: String? = null,
) {
  val shortName: String get() = name.substringAfterLast(delimiter = '/')
}

/**
 * Client for the official MCP registry directory (`registry.modelcontextprotocol.io`),
 * the keyless canonical index of publicly published MCP servers. [search] is a
 * blocking HTTP call: run it on a background dispatcher (the settings panel
 * does this via `Dispatchers.IO`).
 *
 * Only stdio packages are imported: the embedded runtime spawns local
 * processes and has no remote (streamable HTTP / SSE) transport yet, so
 * remote-only results surface with a null [McpRegistryResult.command].
 */
internal object McpRegistryClient {

  private const val BASE_URL: String = "https://registry.modelcontextprotocol.io/v0.1/servers"
  private const val SEARCH_LIMIT: Int = 20

  private val client: HttpClient = HttpClient.newBuilder()
    .connectTimeout(PluginConfig.API_CONNECT_TIMEOUT)
    .build()

  /**
   * Search endpoint for [query]: the term is URL-encoded (space becomes
   * `+`), capped at [SEARCH_LIMIT] rows of the latest version each so one
   * server appears at most once.
   */
  internal fun searchUrl(query: String): String {
    val encoded: String = URLEncoder.encode(query.trim(), Charsets.UTF_8)
    return "$BASE_URL?search=$encoded&limit=$SEARCH_LIMIT&version=latest"
  }

  /**
   * Searches the registry by name substring and returns importable results.
   * `version=latest` keeps one row per server; failures (network, non-200,
   * malformed body) throw so callers can distinguish empty from broken.
   */
  fun search(query: String): List<McpRegistryResult> {
    val request: HttpRequest = HttpRequest.newBuilder(URI.create(searchUrl(query)))
      .timeout(PluginConfig.API_REQUEST_TIMEOUT)
      .GET()
      .build()
    val response: HttpResponse<String> =
      client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200)
      throw IOException("registry search failed: HTTP ${response.statusCode()}")
    return parse(json = response.body())
  }

  /**
   * Parses a registry `ServerList` body. Lenient by design: entries without a
   * name are skipped, missing descriptions become empty, and any server whose
   * packages cannot be turned into a stdio command keeps a null command so the
   * UI can show it as not-yet-importable instead of dropping it. Importable
   * results (non-null command) sort above remote-only ones while the
   * registry's relevance order is preserved inside each group.
   */
  internal fun parse(json: String): List<McpRegistryResult> {
    val root: JsonObject = Json.parseToJsonElement(json).let { it as? JsonObject } ?: return emptyList()
    val servers: JsonArray = root["servers"] as? JsonArray ?: return emptyList()
    return servers.mapNotNull { element ->
      val server: JsonObject = (element as? JsonObject)?.get("server") as? JsonObject
        ?: return@mapNotNull null
      val name: String = server.string(key = "name")?.takeIf { it.isNotBlank() }
        ?: return@mapNotNull null
      val packages: List<JsonObject> = server.jsonArray(key = "packages").orEmpty()
      val stdioPackage: JsonObject? =
        packages.firstOrNull { pkg -> pkg.isStdio() && buildCommand(pkg) != null }
      val displayPackage: JsonObject? = stdioPackage ?: packages.firstOrNull()
      val repositoryUrl: String? =
        (server["repository"] as? JsonObject)?.string(key = "url")
      McpRegistryResult(
        name = name,
        description = server.string(key = "description").orEmpty(),
        command = stdioPackage?.let(::buildCommand),
        envHints = stdioPackage?.let(::envHints).orEmpty(),
        version = server.string(key = "version")?.takeIf { it.isNotBlank() },
        packageLine = displayPackage?.let(::packageLine),
        registryType = displayPackage?.string(key = "registryType"),
        repositoryUrl = repositoryUrl,
        publishedAt = server.officialPublishedAt(),
      )
    }.distinctBy { it.name }.sortedBy { it.command == null }
  }

  private fun packageLine(pkg: JsonObject): String? {
    val identifier: String = pkg.string(key = "identifier") ?: return null
    val version: String? = pkg.string(key = "version")
    return identifier + (version?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "")
  }

  private fun JsonObject.officialPublishedAt(): String? {
    val meta: JsonObject = this["_meta"] as? JsonObject ?: return null
    val official: JsonObject =
      meta["io.modelcontextprotocol.registry/official"] as? JsonObject ?: return null
    return official.string(key = "publishedAt")?.takeIf { it.length >= 7 }?.take(n = 7)
  }

  /**
   * Builds argv for one registry package:
   * `[runtime, ...runtimeArguments, identifier, ...packageArguments]`: the
   * runner receives `runtimeArguments` (e.g. npx's `-y`) before the package
   * name, the package binary receives `packageArguments` after it. Arguments
   * without a fixed value are user-supplied and skipped (the settings panel
   * lets the user finish the command by hand). Missing `runtimeHint` falls
   * back to the conventional runner for well-known registries.
   */
  private fun buildCommand(pkg: JsonObject): List<String>? {
    val identifier: String = pkg.string(key = "identifier") ?: return null
    val registryType: String = pkg.string(key = "registryType") ?: return null
    val runtime: String = pkg.string(key = "runtimeHint") ?: inferRuntime(registryType = registryType)
    ?: return null
    return buildList {
      add(runtime)
      addAll(pkg.argTokens(key = "runtimeArguments"))
      add(identifier)
      addAll(pkg.argTokens(key = "packageArguments"))
    }
  }

  private fun inferRuntime(registryType: String): String? =
    when (registryType.lowercase()) {
      "npm" -> "npx"
      "pypi" -> "uvx"
      "oci" -> "docker"
      "nuget" -> "dnx"
      else -> null
    }

  private fun JsonObject.argTokens(key: String): List<String> =
    this[key].let { it as? JsonArray }.orEmpty().mapNotNull { argument ->
      val jsonObject: JsonObject = argument as? JsonObject ?: return@mapNotNull null
      val value: String = jsonObject.primitiveContent(key = "value") ?: return@mapNotNull null
      if (jsonObject.string(key = "type") == "named") {
        val flag: String = jsonObject.string(key = "name") ?: return@mapNotNull null
        listOf(flag, value)
      } else {
        listOf(value)
      }
    }.flatten()

  private fun envHints(pkg: JsonObject): List<McpRegistryEnvHint> =
    pkg.jsonArray(key = "environmentVariables").orEmpty().mapNotNull { element ->
      val jsonObject: JsonObject = element
      val name: String = jsonObject.string(key = "name")?.takeIf { it.isNotBlank() }
        ?: return@mapNotNull null
      val defaultValue: String? = jsonObject.primitiveContent(key = "default")
      val required: Boolean = jsonObject.primitiveContent(key = "isRequired")?.toBooleanStrictOrNull() == true
      when {
        required -> McpRegistryEnvHint(name = name, value = defaultValue.orEmpty())
        defaultValue != null -> McpRegistryEnvHint(name = name, value = defaultValue)
        else -> null
      }
    }

  private fun JsonObject.isStdio(): Boolean =
    (this["transport"] as? JsonObject)?.let { transport ->
      transport.string(key = "type")?.let { it == "stdio" } ?: true
    } ?: true

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

  private fun JsonObject.primitiveContent(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.content != "null" }?.content

  private fun JsonObject.jsonArray(key: String): List<JsonObject>? =
    (this[key] as? JsonArray)?.filterIsInstance<JsonObject>()
}
