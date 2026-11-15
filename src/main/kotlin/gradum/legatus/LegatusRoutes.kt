package gradum.legatus

import gradum.server.ServerAuth
import gradum.server.ServerConfiguration
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private const val PAGE_PATH: String = "/legatus"

private const val RESOURCE_ROOT: String = "/skills-editor"

/**
 * Routes for Gradum Legatus, the browser entry point that pairs with the
 * running server over the LAN.
 *
 * The auth model mirrors the skill editor: the request guard lets GETs to
 * the page and its assets through unauthenticated, and the page itself
 * decides between two boot states:
 *
 * - a valid cookie or `?token=` query parameter boots the full workspace
 *   (a matching query re-issues the cookie so the session persists);
 * - anything else boots the workspace in reader mode (`data-reader`), and
 *   the visitor trades the five-character code shown on the host machine
 *   for the same cookie via `POST /skills/pair` ([ServerAuth.PAIR_PATH]).
 *   The long-lived bearer token never appears on the wire here.
 */
fun Route.legatusRoutes(serverConfiguration: ServerConfiguration) {
  get(PAGE_PATH) {
    val pageHtml: String? = readBundledResource("$RESOURCE_ROOT/index.html")
    if (pageHtml == null) {
      call.respondText(
        text = "Workspace page is missing from the server resources",
        status = HttpStatusCode.NotFound,
      )
      return@get
    }

    call.respondWorkspacePage(pageHtml, serverConfiguration)
  }

  get("$PAGE_PATH/{asset...}") {
    val segments: List<String> = call.parameters.getAll("asset").orEmpty()
    val asset: String = segments.joinToString("/")
    val isSafe: Boolean = segments.isNotEmpty() && segments.all { segment ->
      segment.isNotBlank() && segment != "." && segment != ".." && !segment.contains('\\')
    }

    val body: String? =
      if (isSafe) readBundledResource("$RESOURCE_ROOT/$asset")
      else null

    if (body == null) {
      call.respondText(status = HttpStatusCode.NotFound, text = "Unknown asset: $asset")
      return@get
    }

    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.respondText(text = body, contentType = assetContentType(asset))
  }
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

private object LegatusResources

private fun readBundledResource(resourcePath: String): String? =
  LegatusResources::class.java.getResourceAsStream(resourcePath)
    ?.bufferedReader()
    ?.use { reader -> reader.readText() }

/**
 * Serves the shared workspace page with its boot stamps. Both entry points
 * (`/legatus` and `/skills/editor`) answer through here so the token check,
 * the reader marker, and the `data-default-root` / `data-home` stamps can
 * never drift apart. A `?token=` that matches re-issues a session ticket
 * cookie; a still-valid ticket keeps its own deadline (no sliding extension).
 * Authenticated boots carry `data-session-remaining` seconds for the page's
 * countdown; anything else boots in reader mode.
 */
internal suspend fun ApplicationCall.respondWorkspacePage(
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
  if (authToken != null) {
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
    return
  }
  response.header(HttpHeaders.CacheControl, "no-store")
  respondText(text = markedHtml, contentType = ContentType.Text.Html)
}
