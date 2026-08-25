/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SearchedRenderer.kt  2026-08-25 19:21:54 Changed by gwy
 */
package gradum.idea.chat.ui.chat.skill

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gradum.idea.chat.ui.chat.skill.SearchedRenderer.Companion.faviconCache
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import gradum.idea.chat.ui.chat.skill.spi.string
import gradum.idea.chat.ui.markdown.rememberGradumParagraphTextStyle
import gradum.idea.chat.ui.util.FaviconHostCache
import gradum.idea.chat.ui.util.ThumbnailImageLoader
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.ExternalLink
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.net.URI
import java.util.concurrent.CompletableFuture

/**
 * Default renderer for the server-side `search_web` skill (alias
 * "Searched"). Renders a single-line capsule header (web icon +
 * "Searched" + query + "N results" counter + chevron) that
 * expands/collapses to reveal the full result list below, each
 * result showing its favicon thumbnail and an external link.
 */
class SearchedRenderer : ToolCallRenderer {

  override fun alias(): String = ALIAS

  override fun iconKey(): IconKey = GradumIcons.Web

  override fun labelKey(): String = LABEL_KEY

  override fun parseContent(
    arguments: Map<String, Any?>, result: Map<String, Any?>
  ): ToolCallContent {
    val query: String = arguments.string(key = "query")

    @Suppress("UNCHECKED_CAST")
    val results: List<Map<String, Any>> = (result["results"] as? List<Map<String, Any>>) ?: emptyList()

    return ToolCallContent(
      aliasName = ALIAS,
      fieldMap = linkedMapOf(
        "query" to query,
        "results" to results,
        "totalResults" to results.size
      )
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    val query: String = (content.fieldMap["query"] as? String).orEmpty()
    val totalResults: Int = (content.fieldMap["totalResults"] as? Number)?.toInt() ?: 0
    val uriHandler = LocalUriHandler.current
    val infoColor = JewelTheme.globalColors.text.info
    val textColor = JewelTheme.globalColors.text.normal
    val bodyStyle = rememberGradumParagraphTextStyle()

    @Suppress("UNCHECKED_CAST")
    val results: List<Map<String, Any>> = (content.fieldMap["results"]
      as? List<Map<String, Any>>) ?: emptyList()

    var isExpanded by remember { mutableStateOf(value = true) }

    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { isExpanded = !isExpanded },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
      ) {
        Icon(GradumIcons.Web, contentDescription = null)
        Text(
          color = textColor,
          text = message(LABEL_KEY),
          fontWeight = FontWeight.Medium,
          style = bodyStyle
        )
        Text(
          text = query,
          maxLines = 1,
          color = infoColor,
          overflow = TextOverflow.Ellipsis,
          style = bodyStyle
        )
        Text(
          maxLines = 1,
          color = JewelTheme.globalColors.text.disabled,
          overflow = TextOverflow.Ellipsis,
          text = message(key = LABEL_KEY_DISPLAY, totalResults),
          style = bodyStyle
        )
        Icon(
          key =
            if (isExpanded) AllIconsKeys.General.ChevronDown
            else AllIconsKeys.General.ChevronRight,
          contentDescription = null
        )
      }

      AnimatedVisibility(visible = isExpanded) {
        if (results.isNotEmpty()) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(GradumSpacing.md)
          ) {
            Spacer(modifier = Modifier.height(GradumSpacing.sml))
            results.forEach { source ->
              val title: String = (source["title"] as? String).orEmpty()
              val pageUrl: String = (source["url"] as? String).orEmpty()
              val faviconUrl: String = (source["faviconUrl"] as? String).orEmpty()

              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
              ) {
                ResultThumbnail(
                  pageUrl = pageUrl,
                  faviconUrl = faviconUrl,
                  size = THUMBNAIL_SIZE_DP.dp
                )
                ExternalLink(
                  text = title.ifBlank { pageUrl },
                  onClick = { uriHandler.openUri(pageUrl) }
                )
              }
            }
          }
        }
      }
    }
  }

  /**
   * Renders the leading icon for a single search result.
   *
   * Three states, same outer footprint (size + corner radius) so the
   * result row never reflows while a thumbnail is in flight:
   *  1. No URL in the fallback chain → no upstream favicon available.
   *     Renders a neutral gray placeholder box. Returning a real Web
   *     icon here would make rows with images wider than rows without,
   *     which looks like a layout bug.
   *  2. URL present, fetch still in progress → same gray placeholder
   *     so the row height stays stable and the user sees a "loading"
   *     affordance that matches the final shape.
   *  3. URL present, fetch resolved (success or failure) → success
   *     shows the bitmap, failure (null) falls through to the next
   *     URL in the chain. If every URL fails, we land back on the
   *     gray placeholder.
   *
   * **Fallback chain** (in order):
   *  1. The favicon URL returned by Tavily (best signal — usually
   *     matches the actual page's icon).
   *  2. `https://{host}/favicon.ico` — most sites still serve their
   *     favicon at the root, no third-party dependency.
   *  3. `https://www.favicon.vip/get.php?url={host}` — China-friendly
   *     aggregator (Google's `s2/favicons` is GFW-blocked).
   *
   * **Host-level short-circuit ([faviconCache]).** Without caching,
   * the chain is walked top-to-bottom on every render — a host whose
   * Tavily favicon 404s will pay a 3-second timeout for that URL
   * every time the row re-composes. The cache remembers the *single*
   * URL that worked for each host, so a host that's been seen before
   * jumps straight to the known winner. Hosts where every chain
   * member failed are also remembered, so we skip the chain entirely
   * and go straight to the gray placeholder. See [FaviconHostCache]
   * for the cache invariants.
   *
   * **Threading:** the loader exposes a `CompletableFuture`. Calling
   * `future.get()` on the main thread would block the UI for the
   * entire HTTP round-trip — instead we bridge the future into a
   * suspending coroutine via [suspendCancellableCoroutine], so the
   * main thread is free to keep painting frames while bytes flow in.
   */
  @Composable
  private fun ResultThumbnail(pageUrl: String, faviconUrl: String, size: Dp) {
    val cornerRadius = RoundedCornerShape(THUMBNAIL_CORNER_DP.dp)
    val sizeModifier = Modifier.size(size).clip(cornerRadius)
    val placeholderModifier = sizeModifier.background(JewelTheme.globalColors.panelBackground)

    val host: String = remember(pageUrl) { runCatching { URI(pageUrl).host }.getOrNull().orEmpty() }
    if (host.isBlank() || faviconCache.isFailed(host)) {
      Box(modifier = placeholderModifier)
      return
    }

    val cachedUrl: String? = remember(host) { faviconCache.getWinningUrl(host) }
    val fallbackChain: List<String> = remember(pageUrl, faviconUrl, cachedUrl) {
      if (cachedUrl != null) listOf(cachedUrl)
      else buildFaviconFallbackChain(host, faviconUrl)
    }
    if (fallbackChain.isEmpty()) {
      Box(modifier = placeholderModifier)
      return
    }
    var bitmap: ImageBitmap? by remember(fallbackChain) { mutableStateOf(null) }
    LaunchedEffect(fallbackChain) {
      for (url in fallbackChain) {
        val loadedBitmap: ImageBitmap? = awaitBitmap(ThumbnailImageLoader.loadAsync(url))
        if (loadedBitmap != null) {
          faviconCache.recordSuccess(host, url)
          bitmap = loadedBitmap
          return@LaunchedEffect
        }
      }

      faviconCache.recordFailure(host)
    }
    val currentBitmap: ImageBitmap? = bitmap
    if (currentBitmap == null) {
      Box(modifier = placeholderModifier)
    } else {
      Image(
        bitmap = currentBitmap,
        modifier = sizeModifier,
        contentDescription = null,
        contentScale = ContentScale.Crop
      )
    }
  }

  /**
   * Bridge [ThumbnailImageLoader]'s [CompletableFuture] into the
   * Compose coroutine scope without blocking the UI thread. The 3-arg
   * `resume` keeps the result alive if cancellation lands in the gap
   * between `whenComplete` firing and the continuation being resumed.
   */
  private suspend fun awaitBitmap(
    imageFuture: CompletableFuture<ImageBitmap?>
  ): ImageBitmap? = suspendCancellableCoroutine { continuation ->
    imageFuture.whenComplete { result, _ -> continuation.resume(result) { _, _, _ -> } }
    continuation.invokeOnCancellation { imageFuture.cancel(true) }
  }

  /**
   * Build the favicon URL fallback chain for [host]. Returns an
   * empty list when [host] is blank so the caller renders the gray
   * placeholder instead of trying a blank URL. [tavilyFaviconUrl] is
   * prepended verbatim when non-blank — it's the highest-signal
   * member of the chain when Tavily has a real one.
   */
  private fun buildFaviconFallbackChain(host: String, tavilyFaviconUrl: String): List<String> {
    if (host.isBlank()) return emptyList()
    return buildList {
      if (tavilyFaviconUrl.isNotBlank()) add(tavilyFaviconUrl)
      add("https://$host/favicon.ico")
      add("https://www.favicon.vip/get.php?url=$host")
    }
  }

  companion object {
    const val ALIAS: String = "Searched"
    const val LABEL_KEY: String = "gradum.tool.searched"
    const val LABEL_KEY_DISPLAY: String = "gradum.tool.search.web.display"

    private const val THUMBNAIL_SIZE_DP: Int = 16
    private const val THUMBNAIL_CORNER_DP: Int = 4

    /**
     * Process-wide favicon short-circuit. Lives in the companion so
     * it survives every renderer instance and every recomposition.
     * See [FaviconHostCache] for the rationale.
     */
    private val faviconCache: FaviconHostCache = FaviconHostCache()
  }
}
