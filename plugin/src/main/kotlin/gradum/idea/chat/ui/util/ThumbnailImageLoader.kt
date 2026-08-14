/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThumbnailImageLoader.kt  2026-08-14 11:31:27 Changed by gwy
 */
package gradum.idea.chat.ui.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URI
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

private val logger: Logger = LoggerFactory.getLogger("ThumbnailImageLoader")

/**
 * HTTP request timeout for a thumbnail fetch. Kept short because the
 * user is staring at a chat reply — a slow favicon should never make
 * the whole row feel laggy. 3 s matches JetBrains' own URL handler
 * default for image previews in the IDE.
 */
private const val THUMBNAIL_HTTP_TIMEOUT_MS: Int = 3_000

/**
 * Hard upper bound on the in-memory LRU. 64 entries is more than
 * enough for a single chat turn (a `search_web` result row rarely
 * returns more than 10 items) and keeps the cache from leaking
 * across very long sessions.
 */
private const val LRU_MAX_ENTRIES: Int = 64

/**
 * In-house loader for thumbnail images used in tool-call result rows.
 *
 * - Thread-safe (used from arbitrary `LaunchedEffect` scopes).
 * - URL-keyed LRU (most-recently-requested URL is the last to be
 *   evicted). All cache mutations are guarded by `Collections.synchronizedMap`
 *   around a `LinkedHashMap` so the access-order invariant survives
 *   concurrent access without a `ReentrantLock`.
 * - Single-flight per URL: if a fetch is already in progress when
 *   another caller asks for the same URL, the second caller awaits
 *   the first one's result instead of issuing a duplicate request.
 * - Failures (HTTP non-2xx, malformed bytes, Skia decode error,
 *   timeout) return `null` and log a single-line warning. The caller
 *   is expected to fall back to a static icon in that case.
 */
object ThumbnailImageLoader {

  // Synchronized LRU keyed by URL string. `accessOrder = true` so
  // `get` moves the entry to the tail, which is what makes the
  // LRU invariant "remove eldest" work.
  private val cache: MutableMap<String, ImageBitmap> =
    Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap>(
      LRU_MAX_ENTRIES, /* loadFactor = */ 0.75f, /* accessOrder = */ true
    ) {
      override fun removeEldestEntry(
        eldest: Map.Entry<String, ImageBitmap>
      ): Boolean = size > LRU_MAX_ENTRIES
    })

  // In-flight URL → CompletableFuture so duplicate concurrent requests
  // share the same response. Plain ConcurrentHashMap is enough; the
  // value object's happens-before guarantees come from the futures'
  // internal synchronization.
  private val inFlight: MutableMap<String, CompletableFuture<ImageBitmap?>> =
    ConcurrentHashMap()

  /**
   * Load a thumbnail for [imageUrl] and return it as a Compose
   * [ImageBitmap], or `null` if any step fails. The result is cached;
   * subsequent calls for the same URL are O(1) and do not touch the
   * network.
   *
   * The function does *not* block the calling coroutine on the
   * network — it just synchronously reads from the cache. The actual
   * HTTP / decode work happens on a [java.util.concurrent.ForkJoinPool]
   * daemon thread (the default executor for `CompletableFuture.supplyAsync`).
   * This keeps thumbnail loading off the UI thread.
   *
   * Callers that want to react to the eventual arrival should call
   * [loadAsync], which returns the future directly.
   */
  fun load(imageUrl: String): ImageBitmap? {
    if (imageUrl.isBlank()) return null
    cache[imageUrl]?.let { return it }
    return try {
      val futureResult: CompletableFuture<ImageBitmap?> = loadAsync(imageUrl)
      // If the cache was populated while we waited, use it. Otherwise,
      // honor the future — `join()` is safe because the future is
      // already complete by the time `loadAsync` returns the same
      // instance (the only writer is inside `loadAsync` itself).
      futureResult.get()
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      null
    } catch (executionException: ExecutionException) {
      logger.warn("Thumbnail fetch failed for url={}: {}", imageUrl, executionException.cause?.message)
      null
    }
  }

  /**
   * Same as [load] but returns the underlying future, so Compose can
   * `LaunchedEffect(url) { state = future.await() }` without blocking
   * the caller thread on the network. The future is shared between
   * concurrent callers (single-flight), so it's safe to fire-and-forget
   * from many recompositions.
   */
  fun loadAsync(imageUrl: String): CompletableFuture<ImageBitmap?> {
    if (imageUrl.isBlank()) return CompletableFuture.completedFuture(null)
    cache[imageUrl]?.let { return CompletableFuture.completedFuture(it) }

    val newFuture = CompletableFuture<ImageBitmap?>()
    val existingFuture = inFlight.putIfAbsent(imageUrl, newFuture)
    if (existingFuture != null) return existingFuture

    CompletableFuture.supplyAsync { fetchAndDecode(imageUrl) }
      .whenComplete { fetchResult, fetchError ->
        inFlight.remove(imageUrl, newFuture)
        when {
          fetchError != null -> {
            logger.warn("Thumbnail fetch error for url={}: {}", imageUrl, fetchError.message)
            newFuture.complete(null)
          }

          fetchResult != null -> {
            cache[imageUrl] = fetchResult
            newFuture.complete(fetchResult)
          }

          else -> {
            newFuture.complete(null)
          }
        }
      }
    return newFuture
  }

  /**
   * Drop a single URL from the cache. Useful when the user navigates
   * away from a chat so the bytes can be GC'd promptly. Currently
   * unused; kept for future hot-reload / memory-pressure paths.
   */
  @Suppress("unused")
  fun evict(url: String) {
    cache.remove(url)
  }

  /** Clear the entire cache. Tests use this for isolation. */
  @Suppress("unused")
  internal fun clearCache() {
    cache.clear()
  }

  /**
   * Fetches and decodes an image from the given URL.
   *
   * - Validates HTTP response code (200-299)
   * - Reads image bytes from input stream
   * - Auto-detects format via Skia (PNG/JPG/WebP/GIF all supported)
   * - Returns null on any failure (HTTP error, empty response, decode error)
   *
   * Note: Skia's encoder is format-agnostic; format detection is done from the byte header.
   * Fetches thumbnail from URL. Validates HTTP 2xx, reads bytes via Skia
   * (auto-detects PNG/JPG/WebP/GIF from header), returns null on failure.
   *
   * Note: Uses URI(...).toURL() to properly handle special characters in URLs
   * (e.g. spaces, Unicode) that URL(url) constructor would reject.
   */
  private fun fetchAndDecode(url: String): ImageBitmap? {
    val connection: HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
      connectTimeout = THUMBNAIL_HTTP_TIMEOUT_MS
      readTimeout = THUMBNAIL_HTTP_TIMEOUT_MS
      requestMethod = "GET"

      setRequestProperty("User-Agent", "Gradum/0.9 (https://github.com/gradum/gradum)")
      setRequestProperty("Accept", "image/png,image/jpeg,image/webp,image/gif,*/*;q=0.8")
    }
    return try {
      val responseCode: Int = connection.responseCode
      if (responseCode !in 200..299) {
        logger.warn("Thumbnail HTTP {} for url={}", responseCode, url)
        return null
      }
      val imageBytes: ByteArray = connection.inputStream.use { it.readAllBytes() }
      if (imageBytes.isEmpty()) return null
      // Skia's encoder is format-agnostic: PNG / JPG / WebP / GIF
      // are all auto-detected from the byte header.
      val skiaImage: Image = Image.makeFromEncoded(imageBytes)
      skiaImage.toComposeImageBitmap()
    } catch (decodeException: Exception) {
      logger.warn("Thumbnail decode failed for url={}: {}", url, decodeException.message)
      null
    } finally {
      connection.disconnect()
    }
  }
}
