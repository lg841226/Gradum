/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * FaviconHostCacheTest.kt  2026-08-14 Changed by gwy
 */
package gradum.idea.chat.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FaviconHostCache].
 *
 * The cache is the host-level short-circuit that lets
 * [SearchedRenderer] skip dead chain members on re-render. The
 * invariants under test:
 *  1. `recordSuccess` populates `getWinningUrl`; `isFailed` is false.
 *  2. `recordFailure` populates `isFailed`; `getWinningUrl` is null.
 *  3. `recordSuccess` over a previously-failed host flips it back to
 *     a positive entry — the negative entry is cleared, not stuck.
 *  4. `recordFailure` over a previously-successful host is a no-op —
 *     a positive entry is never silently downgraded.
 *  5. The cache is bounded by `maxEntries` and evicts the eldest
 *     insertion on overflow.
 */
class FaviconHostCacheTest {

  @Test
  fun `recordSuccess stores the winning URL and clears any prior failure`() {
    val cache: FaviconHostCache = FaviconHostCache()
    cache.recordFailure("github.com")
    assertTrue("negative entry should be present", cache.isFailed("github.com"))

    cache.recordSuccess("github.com", "https://github.githubassets.com/favicon.svg")

    assertEquals(
      "https://github.githubassets.com/favicon.svg",
      cache.getWinningUrl("github.com"),
    )
    assertFalse(
      "recording a success must clear the negative entry",
      cache.isFailed("github.com"),
    )
  }

  @Test
  fun `recordSuccess overwrites a previous winning URL for the same host`() {
    val cache: FaviconHostCache = FaviconHostCache()
    cache.recordSuccess("github.com", "https://old.example/favicon.ico")
    cache.recordSuccess("github.com", "https://new.example/favicon.svg")

    assertEquals(
      "newer success must win over older success for the same host",
      "https://new.example/favicon.svg",
      cache.getWinningUrl("github.com"),
    )
  }

  @Test
  fun `recordFailure stores the negative entry when no success exists`() {
    val cache: FaviconHostCache = FaviconHostCache()
    cache.recordFailure("example.com")

    assertTrue(cache.isFailed("example.com"))
    assertNull("no winning URL should exist for a never-successful host", cache.getWinningUrl("example.com"))
  }

  @Test
  fun `recordFailure is a no-op when a success already exists for the host`() {
    val cache: FaviconHostCache = FaviconHostCache()
    cache.recordSuccess("github.com", "https://ok.example/favicon.ico")
    cache.recordFailure("github.com")

    assertEquals(
      "positive entry must not be silently downgraded by a later failure",
      "https://ok.example/favicon.ico",
      cache.getWinningUrl("github.com"),
    )
    assertFalse("negative entry must not overwrite a positive one", cache.isFailed("github.com"))
  }

  @Test
  fun `fresh cache is empty for any host`() {
    val cache: FaviconHostCache = FaviconHostCache()

    assertNull(cache.getWinningUrl("anywhere.test"))
    assertFalse(cache.isFailed("anywhere.test"))
  }

  @Test
  fun `cache evicts the eldest insertion when maxEntries is exceeded`() {
    val cache: FaviconHostCache = FaviconHostCache(maxEntries = 4)
    cache.recordSuccess("a.test", "https://a/favicon.ico")
    cache.recordSuccess("b.test", "https://b/favicon.ico")
    cache.recordSuccess("c.test", "https://c/favicon.ico")
    cache.recordSuccess("d.test", "https://d/favicon.ico")
    // a.test is now the eldest insertion.
    cache.recordSuccess("e.test", "https://e/favicon.ico")

    assertNull("eldest entry must have been evicted", cache.getWinningUrl("a.test"))
    assertEquals("https://b/favicon.ico", cache.getWinningUrl("b.test"))
    assertEquals("https://e/favicon.ico", cache.getWinningUrl("e.test"))
  }

  @Test
  fun `eviction drops from whichever side is the larger contributor`() {
    val cache: FaviconHostCache = FaviconHostCache(maxEntries = 3)
    cache.recordFailure("fail1.test")
    cache.recordFailure("fail2.test")
    cache.recordFailure("fail3.test")
    // All three slots are taken by failures; the next insert must
    // evict the eldest failure (fail1.test).
    cache.recordSuccess("ok.test", "https://ok/favicon.ico")

    assertFalse("eldest failure should have been evicted", cache.isFailed("fail1.test"))
    assertTrue("younger failures should still be present", cache.isFailed("fail2.test"))
    assertTrue(cache.isFailed("fail3.test"))
    assertEquals("https://ok/favicon.ico", cache.getWinningUrl("ok.test"))
  }

  @Test
  fun `clear drops both positive and negative entries`() {
    val cache: FaviconHostCache = FaviconHostCache()
    cache.recordSuccess("github.com", "https://github/favicon.ico")
    cache.recordFailure("broken.test")

    cache.clear()

    assertNull(cache.getWinningUrl("github.com"))
    assertFalse(cache.isFailed("broken.test"))
  }
}
