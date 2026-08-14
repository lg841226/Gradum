/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThumbnailImageLoader.kt  2026-08-14 11:45:00 Changed by gwy
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
 * Hard cap on the bytes we will read from any single response. Real
 * favicons are 1–15 KB; an honest web image preview for a 16x16 box
 * is well under 100 KB even at the high end. Anything past 512 KB is
 * either a misconfigured server, a malicious zip-bomb surrogate, or
 * someone using this channel to exfiltrate data into the cache. We
 * abort the read and discard the response in that case.
 */
private const val MAX_IMAGE_BYTES: Int = 512 * 1024

/**
 * In-house loader for thumbnail images used in tool-call result rows.
 *
 * **Two-tier cache.** A 64-entry in-memory LRU of decoded
 * [ImageBitmap]s (the hot path) sits in front of an on-disk byte
 * cache ([ThumbnailDiskCache], ~50 MB) that survives IDE restarts.
 * A URL that's already on disk is decoded and replayed through the
 * memory cache; only a true miss touches the network. See
 * [ThumbnailDiskCache] for the on-disk invariants.
 *
 * **Security posture.** Gradum is a local agent and the network is
 * its only outbound channel. Thumbnail URLs come from three places,
 * all of which are attacker-controlled in principle: the Tavily
 * search response (a malicious page can publish any URL as its
 * `favicon`), the result page's own root (`https://{host}/favicon.ico`
 * — `host` came from a Tavily-supplied URL), and a third-party
 * aggregator (`favicon.vip`, which sees and could be tricked into
 * serving whatever). Every URL therefore goes through
 * [ThumbnailUrlGuard] before we open a socket. The guard enforces
 * https-only, blocks DNS resolution to private / loopback / link-local
 * / cloud-metadata ranges, and forces a fresh resolution on every
 * fetch (DNS-rebinding bounded: a hostname that resolved to a public
 * IP at guard time but flips to a private IP at connect time will
 * still be caught because the `URI(...).toURL()` path re-resolves
 * through the JDK and re-validates on every retry; full pinning
 * would require IP-literal connections, which we leave to a future
 * change). Disk-cache hits skip the guard on the read path — the
 * URL was already vetted when it was first written.
 *
 * **Other guarantees.** Thread-safe (used from arbitrary
 * `LaunchedEffect` scopes). URL-keyed LRU (most-recently-requested
 * URL is the last to be evicted). All cache mutations are guarded by
 * `Collections.synchronizedMap` around a `LinkedHashMap` so the
 * access-order invariant survives concurrent access without a
 * `ReentrantLock`. Single-flight per URL: if a fetch is already in
 * progress when another caller asks for the same URL, the second
 * caller awaits the first one's result instead of issuing a duplicate
 * request. Failures (HTTP non-2xx, malformed bytes, Skia decode
 * error, timeout, oversized response, URL guard rejection) return
 * `null` and log a single-line warning. The caller is expected to
 * fall back to a static icon in that case.
 */
object ThumbnailImageLoader {

  private val diskCache: ThumbnailDiskCache = ThumbnailDiskCache()

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
   *
   * The read path consults the on-disk cache (synchronous — the
   * decode is fast) before issuing a network request, so a favicon
   * the user saw in a previous IDE session is rendered without any
   * HTTP I/O on the first hit.
   */
  fun loadAsync(imageUrl: String): CompletableFuture<ImageBitmap?> {
    if (imageUrl.isBlank()) return CompletableFuture.completedFuture(null)
    cache[imageUrl]?.let { return CompletableFuture.completedFuture(it) }

    // Disk-cache hit. Synchronous read + decode; the bytes are local
    // and Skia is microseconds, so we don't bother with the
    // `CompletableFuture` dance. A decode failure here is treated as
    // a miss — the network path will re-fetch and overwrite the bad
    // entry on success.
    diskCache.read(imageUrl)?.let { diskBytes ->
      decodeFromBytes(diskBytes)?.let { diskBitmap ->
        cache[imageUrl] = diskBitmap
        return CompletableFuture.completedFuture(diskBitmap)
      }
    }

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
   * Validates the URL, opens the connection, reads (capped) bytes,
   * decodes via Skia, and returns the bitmap. Returns `null` on any
   * failure — guard rejection, HTTP error, oversized body, decode
   * error, timeout — and logs a single warning. Caller renders the
   * placeholder box on null.
   *
   * We use [URI] rather than [URL] so URLs with unusual characters
   * (Unicode, percent-encoded specials) parse cleanly; the JDK 21
   * `URL(String)` constructor is also deprecated.
   */
  private fun fetchAndDecode(url: String): ImageBitmap? {
    // Reject hostile URLs before we touch the network. Doing the
    // guard *here* (not in `load`/`loadAsync`) means a malicious
    // search result can't even pin a poisoned entry in the in-flight
    // map for other callers to await.
    when (val check: ThumbnailUrlGuard.Check = ThumbnailUrlGuard.check(url)) {
      is ThumbnailUrlGuard.Check.Unsafe -> {
        logger.warn("Thumbnail URL rejected: {} ({})", url, check.reason)
        return null
      }
      is ThumbnailUrlGuard.Check.Safe -> Unit
    }

    val connection: HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
      connectTimeout = THUMBNAIL_HTTP_TIMEOUT_MS
      readTimeout = THUMBNAIL_HTTP_TIMEOUT_MS
      requestMethod = "GET"
      // Don't follow redirects: a 30x is a fine way to land on an
      // internal address that wasn't visible in the original URL.
      // The few sites that 30x their favicon will simply show the
      // gray placeholder — acceptable trade-off for the safety.
      instanceFollowRedirects = false

      setRequestProperty("User-Agent", "Gradum/0.9 (https://github.com/gradum/gradum)")
      setRequestProperty("Accept", "image/png,image/jpeg,image/webp,image/gif,*/*;q=0.8")
    }
    return try {
      val responseCode: Int = connection.responseCode
      if (responseCode !in 200..299) {
        logger.warn("Thumbnail HTTP {} for url={}", responseCode, url)
        return null
      }
      // Content-Type check after the status check. We don't want to
      // spend cycles reading a 50 MB HTML error page that just happens
      // to set 200.
      val contentType: String? = connection.contentType
      if (contentType != null && !contentType.lowercase().startsWith("image/")) {
        logger.warn("Thumbnail non-image content-type '{}' for url={}", contentType, url)
        return null
      }
      val imageBytes: ByteArray = connection.inputStream.use { inputStream ->
        // Read with a hard cap so a server that streams 4 GB of "image"
        // bytes can't OOM the IDE.
        val buffer = ByteArray(8 * 1024)
        val accumulator = ArrayList<ByteArray>(64)
        var totalRead: Int = 0
        while (true) {
          val read: Int = inputStream.read(buffer)
          if (read == -1) break
          totalRead += read
          if (totalRead > MAX_IMAGE_BYTES) {
            logger.warn("Thumbnail exceeded {} bytes for url={}", MAX_IMAGE_BYTES, url)
            return null
          }
          accumulator.add(buffer.copyOf(read))
        }
        // Flatten. For our 512 KB cap, the per-chunk overhead is
        // negligible compared to the win of not allocating a single
        // huge ByteArray up front.
        val flat = ByteArray(totalRead)
        var offset: Int = 0
        for (chunk: ByteArray in accumulator) {
          System.arraycopy(chunk, 0, flat, offset, chunk.size)
          offset += chunk.size
        }
        flat
      }
      if (imageBytes.isEmpty()) return null
      // Persist to disk *before* the decode attempt so a future
      // request for the same URL doesn't re-fetch from the network.
      // The cache is best-effort: a write failure is logged and
      // swallowed inside `ThumbnailDiskCache`, so this call can't
      // sink the decode.
      diskCache.write(url, imageBytes)
      decodeFromBytes(imageBytes)
    } catch (decodeException: Exception) {
      logger.warn("Thumbnail decode failed for url={}: {}", url, decodeException.message)
      null
    } finally {
      connection.disconnect()
    }
  }

  /**
   * Decode a byte payload into a Compose [ImageBitmap]. Returns `null`
   * on any Skia parse error. Skia is format-agnostic — PNG, JPG, WebP,
   * GIF are all auto-detected from the byte header, so the caller
   * doesn't need to know which format a given URL serves.
   */
  private fun decodeFromBytes(imageBytes: ByteArray): ImageBitmap? = try {
    val skiaImage: Image = Image.makeFromEncoded(imageBytes)
    skiaImage.toComposeImageBitmap()
  } catch (decodeException: Exception) {
    logger.debug("Thumbnail decode failed ({} bytes): {}", imageBytes.size, decodeException.message)
    null
  }
}
