/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * FaviconHostCache.kt  2026-08-26 00:13:50 Changed by gwy
 */
package gradum.idea.chat.ui.util

import gradum.idea.chat.ui.util.FaviconHostCache.Companion.MAX_ENTRIES


/**
 * Per-host "winning favicon URL" cache.
 *
 * ## Why
 *
 * [SearchedRenderer] resolves a thumbnail through a 3-step fallback
 * chain on every render:
 *
 *  1. The favicon URL returned by Tavily (often stale or 404 for
 *     smaller sites).
 *  2. `https://{host}/favicon.ico` (most sites still serve one here).
 *  3. `https://www.favicon.vip/get.php?url={host}` (third-party
 *     aggregator, China-friendly).
 *
 * The per-URL byte cache ([ThumbnailDiskCache]) only stores
 * *successful* responses. A URL that 404s is forgotten as soon as the
 * session ends, so the next render of the same row pays the full
 * 3-second HTTP timeout for the dead URL all over again.
 *
 * This cache remembers the *single* URL that worked for each host
 * across renders. Next time the same host is rendered, we only try the
 * remembered URL. The other two chain members stay in the disk cache
 * for emergency use, but they're not on the hot path anymore.
 *
 * ## Why a host key, not a URL key
 *
 * A `host → URL` mapping is the natural shape: the result row gives us
 * the host (from `result.url`), the chain is a function of the host,
 * and once we've discovered "github.com's working favicon is at
 * `https://github.githubassets.com/favicons/favicon.svg`", that's true
 * for every future search result that points at github.com. The
 * per-URL cache below this one already handles byte-level reuse; this
 * is purely about avoiding the network round-trip for known-failed
 * chain members.
 *
 * ## Lifetime
 *
 * In-memory, scoped to the IDE process. The persistent [ThumbnailDiskCache]
 * is the right place for byte-level reuse across restarts; this cache
 * is just an in-session optimisation for the chain walk. Losing it on
 * restart is fine — the worst case is one extra HTTP probe per host,
 * which is the same as a cold start.
 *
 * ## Concurrency
 *
 * Reads and writes go through a single `synchronized` block on a
 * dedicated lock. The map's read path is the hot one (every render),
 * the write path runs at most once per host per session. A single lock
 * is simpler than the access-ordered LRU the byte cache uses, and
 * contention is negligible at the size we cap it at.
 *
 * ## Eviction
 *
 * Bounded by [MAX_ENTRIES] (default 256). On overflow, drops the
 * eldest insertion — an LRU-by-insertion approximation is fine here
 * because a hot host that gets re-rendered will get re-promoted to
 * "winning URL" on the very next render, well before the entry has
 * a chance to age out.
 *
 * ## Failure tracking
 *
 * When *no* URL in the chain works, [recordFailure] records the host
 * so the next render skips the chain entirely and goes straight to
 * the gray placeholder. The negative entry is bounded by the same
 * [MAX_ENTRIES] cap and never overwrites a positive entry.
 */
internal class FaviconHostCache(private val maxEntries: Int = MAX_ENTRIES) {

  private val lock: Any = Any()

  /** `host → URL` for hosts where we know which chain member works. */
  private val winningUrls: MutableMap<String, String> = LinkedHashMap(maxEntries)

  /** Hosts where every chain member has failed. Capped at [maxEntries]. */
  private val failedHosts: MutableSet<String> = LinkedHashSet()

  /**
   * Returns the remembered winning favicon URL for [host], or `null`
   * if we don't have one. A `null` result does *not* mean the host
   * has no favicon — only that we haven't tried yet, or that the
   * last attempt failed. Callers must handle both cases.
   */
  fun getWinningUrl(host: String): String? = synchronized(lock) {
    winningUrls[host]
  }

  /**
   * Returns `true` if [host] is on the negative-result list, i.e. the
   * chain has been walked and every member failed. Callers should
   * skip the network and render the gray placeholder.
   */
  fun isFailed(host: String): Boolean = synchronized(lock) {
    failedHosts.contains(host)
  }

  /**
   * Record that [url] is the working favicon for [host]. Future
   * renders will try this URL first. If the URL later 404s, the
   * caller should follow up with [recordFailure] to evict.
   */
  fun recordSuccess(host: String, url: String) {
    synchronized(lock) {
      failedHosts.remove(element = host)
      winningUrls[host] = url
      evictIfNeeded()
    }
  }

  /**
   * Record that *no* URL in the chain worked for [host]. Future
   * renders will render the gray placeholder without touching the
   * network. The negative entry never overwrites a positive one —
   * if the host later gets a success, [recordSuccess] clears the
   * negative entry and the cache becomes a positive cache again.
   */
  fun recordFailure(host: String) {
    synchronized(lock) {
      if (host in winningUrls) return
      failedHosts.add(host)
      evictIfNeeded()
    }
  }

  /** Drop everything. Test-only. */
  internal fun clear() {
    synchronized(lock) {
      winningUrls.clear()
      failedHosts.clear()
    }
  }

  private fun evictIfNeeded() {
    val totalSize: Int = winningUrls.size + failedHosts.size
    if (totalSize <= maxEntries) return

    val toDrop: Int = totalSize - maxEntries
    repeat(times = toDrop) {
      if (failedHosts.size > winningUrls.size) {
        failedHosts.iterator().next().let { failedHosts.remove(element = it) }
      } else
        if (winningUrls.isNotEmpty()) {
          val eldest: String = winningUrls.keys.iterator().next()
          winningUrls.remove(key = eldest)
        }
    }
  }

  companion object {
    /**
     * 256 hosts is more than enough for a single chat session — a
     * 10-result search page referencing 10 unique hosts only adds 10
     * entries, and the user has to keep the IDE open and search
     * through 25+ distinct domains to ever overflow this.
     */
    private const val MAX_ENTRIES: Int = 256
  }
}
