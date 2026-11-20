package gradum.desktop

import gradum.server.ServerAuth
import gradum.server.ServerConfiguration
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

internal val UI_PAGE_PREFIXES: List<String> = listOf("/legatus", "/skills/editor")

private const val LEGATUS_PATH: String = "/legatus"

private const val EDITOR_PATH: String = "/skills/editor"

private const val RESOURCE_ROOT: String = "/skills-editor"

private object UiResources

internal fun installUiRoutes(serverConfiguration: ServerConfiguration, routing: Routing) {
  routing.apply {
    get(LEGATUS_PATH) {
      call.respondWorkspacePage(serverConfiguration)
    }

    get("$LEGATUS_PATH/{asset...}") {
      call.respondBundledAsset(RESOURCE_ROOT)
    }

    get(EDITOR_PATH) {
      call.respondWorkspacePage(serverConfiguration)
    }

    get("$EDITOR_PATH/{asset...}") {
      call.respondBundledAsset(RESOURCE_ROOT)
    }
  }
}

private suspend fun ApplicationCall.respondWorkspacePage(serverConfiguration: ServerConfiguration) {
  val pageHtml: String? = readBundledResource("$RESOURCE_ROOT/index.html")
  if (pageHtml == null) {
    respondText(
      text = "Workspace page is missing from the server resources",
      status = HttpStatusCode.NotFound,
    )
    return
  }
  injectStampsAndRespond(pageHtml, serverConfiguration)
}

private suspend fun ApplicationCall.respondBundledAsset(resourceRoot: String) {
  val segments: List<String> = parameters.getAll("asset").orEmpty()
  val asset: String = segments.joinToString("/")
  val isSafe: Boolean = segments.isNotEmpty() && segments.all { segment ->
    segment.isNotBlank() && segment != "." && segment != ".." && !segment.contains('\\')
  }

  val contentType: ContentType = assetContentType(asset)
  if (!isSafe) {
    respondText(status = HttpStatusCode.NotFound, text = "Unknown asset: $asset")
    return
  }

  if (contentType == ContentType.Image.PNG) {
    val bytes: ByteArray? = readBundledBytes("$resourceRoot/$asset")
    if (bytes == null) {
      respondText(status = HttpStatusCode.NotFound, text = "Unknown asset: $asset")
      return
    }
    response.header(HttpHeaders.CacheControl, "no-store")
    respondBytes(bytes = bytes, contentType = contentType)
    return
  }

  val body: String? = readBundledResource("$resourceRoot/$asset")
  if (body == null) {
    respondText(status = HttpStatusCode.NotFound, text = "Unknown asset: $asset")
    return
  }
  response.header(HttpHeaders.CacheControl, "no-store")
  respondText(text = body, contentType = contentType)
}

private suspend fun ApplicationCall.injectStampsAndRespond(
  pageHtml: String,
  serverConfiguration: ServerConfiguration,
) {
  val defaultRoot: String = System.getProperty("user.dir") ?: ""
  val home: String = System.getProperty("user.home") ?: ""
  val scope: String = if (ServerAuth.isLoopback(request.local.remoteHost)) "local" else "remote"
  val markedHtml: String = pageHtml.replaceFirst(
    "<html",
    "<html data-default-root=\"${defaultRoot.replace("\"", "&quot;")}\"" +
      " data-home=\"${home.replace("\"", "&quot;")}\"" +
      " data-session-scope=\"$scope\"",
  )

  val authToken: String? = serverConfiguration.authToken?.takeIf { it.isNotEmpty() }
  if (authToken == null) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(text = markedHtml, contentType = ContentType.Text.Html)
    return
  }

  val queryToken: String? = request.queryParameters[ServerAuth.TOKEN_QUERY_PARAM]
  val queryMatches: Boolean = ServerAuth.tokenMatches(queryToken, authToken)
  val sessionRemaining: Long? = ServerAuth.sessionRemainingSeconds(
    request.cookies[ServerAuth.SESSION_COOKIE_NAME],
    authToken,
  )

  if (!queryMatches && sessionRemaining == null) {
    val invalidMarker: String =
      if (queryToken != null) " data-reader=\"invalid\""
      else " data-reader"

    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(
      text = markedHtml.replaceFirst("<html", "<html$invalidMarker"),
      contentType = ContentType.Text.Html,
    )
    return
  }

  val remaining: Long = sessionRemaining ?: run {
    response.header(
      HttpHeaders.SetCookie,
      ServerAuth.sessionCookieHeader(authToken, serverConfiguration.sessionTtlSeconds),
    )
    serverConfiguration.sessionTtlSeconds
  }

  response.header(HttpHeaders.CacheControl, "no-store")
  respondText(
    text = markedHtml.replaceFirst("<html", "<html data-session-remaining=\"$remaining\""),
    contentType = ContentType.Text.Html,
  )
}

private fun assetContentType(asset: String): ContentType =
  when {
    asset.endsWith(".css") -> ContentType.Text.CSS
    asset.endsWith(".js") -> ContentType.Text.JavaScript
    asset.endsWith(".html") -> ContentType.Text.Html
    asset.endsWith(".json") -> ContentType.Application.Json
    asset.endsWith(".svg") -> ContentType.Image.SVG
    asset.endsWith(".png") -> ContentType.Image.PNG
    else -> ContentType.Application.OctetStream
  }

private fun readBundledResource(resourcePath: String): String? =
  UiResources::class.java.getResourceAsStream(resourcePath)
    ?.bufferedReader()
    ?.use { reader -> reader.readText() }

private fun readBundledBytes(resourcePath: String): ByteArray? =
  UiResources::class.java.getResourceAsStream(resourcePath)
    ?.use { stream -> stream.readBytes() }
