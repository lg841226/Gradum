/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThumbnailDiskCacheTest.kt  2026-08-14 12:40:00 Changed by gwy
 */
package gradum.idea.chat.ui.util

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * Unit tests for the on-disk thumbnail byte cache.
 *
 * These tests construct a cache rooted in a temp directory and
 * exercise the read / write / eviction invariants without touching
 * the IDE config dir or the network. The cache is the only thing
 * standing between a re-opened project and another HTTP fetch of
 * every favicon in a search result row — the LRU + mtime logic
 * here directly determines whether the second visit is fast.
 */
class ThumbnailDiskCacheTest {

  private lateinit var root: Path
  private lateinit var cache: ThumbnailDiskCache

  @Before
  fun setUp() {
    root = Files.createTempDirectory("gradum-thumb-cache-test")
    cache = ThumbnailDiskCache(maxBytes = 1024L * 1024, rootDirectory = root)
  }

  @After
  fun tearDown() {
    cache.clear()
    Files.deleteIfExists(root)
  }

  @Test
  fun `read returns null for an unwritten URL`() {
    assertNull(cache.read("https://example.com/favicon.ico"))
  }

  @Test
  fun `write then read returns the same bytes`() {
    val url = "https://example.com/favicon.ico"
    // PNG header. `0x89` exceeds `Byte.MAX_VALUE` (0x7F), so the
    // high bit has to be applied via `.toByte()` rather than a bare
    // hex literal — otherwise Kotlin refuses the implicit narrowing.
    val payload: ByteArray = byteArrayOf(
      0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
    cache.write(url, payload)
    val restored = cache.read(url)
    assertNotNull(restored)
    assertArrayEquals(payload, restored)
  }

  @Test
  fun `empty payload is not written`() {
    cache.write("https://example.com/empty", ByteArray(0))
    assertNull(cache.read("https://example.com/empty"))
  }

  @Test
  fun `different URLs map to different files`() {
    val payloadA = "alpha".toByteArray()
    val payloadB = "beta".toByteArray()
    cache.write("https://a.example/favicon.ico", payloadA)
    cache.write("https://b.example/favicon.ico", payloadB)
    assertArrayEquals(payloadA, cache.read("https://a.example/favicon.ico"))
    assertArrayEquals(payloadB, cache.read("https://b.example/favicon.ico"))
  }

  @Test
  fun `writing the same URL twice overwrites the previous entry`() {
    val url = "https://example.com/favicon.ico"
    cache.write(url, "old".toByteArray())
    cache.write(url, "new".toByteArray())
    assertArrayEquals("new".toByteArray(), cache.read(url))
  }

  @Test
  fun `eviction removes the oldest entry when the cache is full`() {
    // 100-byte cap so we can trigger eviction deterministically.
    val tightCache = ThumbnailDiskCache(maxBytes = 100L, rootDirectory = root)
    tightCache.clear() // start from a known state
    try {
      val payload = ByteArray(60)
      // Write 3 entries — after the third, the first (oldest by
      // mtime) should be evicted.
      tightCache.write("https://a/", payload)
      Thread.sleep(20)
      tightCache.write("https://b/", payload)
      Thread.sleep(20)
      tightCache.write("https://c/", payload)

      // a/ was written first; should be gone.
      assertNull("oldest entry must be evicted when over capacity", tightCache.read("https://a/"))
      // b/ and c/ should still be there.
      assertNotNull(tightCache.read("https://b/"))
      assertNotNull(tightCache.read("https://c/"))
    } finally {
      tightCache.clear()
    }
  }

  @Test
  fun `reading an entry refreshes its mtime so LRU promotes it`() {
    val tightCache = ThumbnailDiskCache(maxBytes = 100L, rootDirectory = root)
    tightCache.clear()
    try {
      val payload = ByteArray(60)
      tightCache.write("https://a/", payload)
      Thread.sleep(20)
      tightCache.write("https://b/", payload)
      Thread.sleep(20)
      tightCache.write("https://c/", payload)

      // Touch a/ by reading — this should promote it ahead of b/
      // in mtime order. The next eviction should then drop b/
      // instead of a/.
      assertNotNull(tightCache.read("https://a/"))
      // Make sure the mtime moved into the future relative to b/.
      Thread.sleep(50)
      assertNotNull(tightCache.read("https://a/"))

      // Now force eviction: write a fourth entry that pushes us
      // over the cap.
      tightCache.write("https://d/", payload)

      // After eviction, the oldest of {a, b, c} should be gone.
      // We promoted a/, so b/ is the LRU.
      assertNotNull("recently-read entry should survive eviction", tightCache.read("https://a/"))
      assertNull("unread stale entry should be evicted", tightCache.read("https://b/"))
    } finally {
      tightCache.clear()
    }
  }

  @Test
  fun `clear removes every entry`() {
    cache.write("https://a/", "x".toByteArray())
    cache.write("https://b/", "y".toByteArray())
    cache.clear()
    assertNull(cache.read("https://a/"))
    assertNull(cache.read("https://b/"))
    assertEquals(0L, cache.sizeBytes())
  }

  @Test
  fun `read survives a corrupt entry by deleting it`() {
    val url = "https://example.com/corrupt"
    cache.write(url, "valid".toByteArray())
    // Find the on-disk file and corrupt it so the next read fails.
    val files = Files.list(root).use { it.toList() }
    assertEquals(1, files.size)
    Files.write(files[0], byteArrayOf(0x00, 0x01, 0x02))
    // Read should return null and the bad file should be cleaned up.
    assertNull(cache.read(url))
    assertTrue(Files.list(root).use { it.toList() }.isEmpty())
  }

  @Test
  fun `sizeBytes reflects the on-disk footprint after writes`() {
    val payload = ByteArray(100)
    cache.write("https://a/", payload)
    cache.write("https://b/", payload)
    val size = cache.sizeBytes()
    assertTrue("sizeBytes=$size should be at least 200 bytes", size >= 200)
  }

  @Test
  fun `mtime touch does not break under read-heavy workloads`() {
    // The read path calls setLastModifiedTime on every hit. Make
    // sure 1000 reads of the same URL don't blow up.
    val url = "https://example.com/popular"
    cache.write(url, "data".toByteArray())
    repeat(1000) {
      assertArrayEquals("data".toByteArray(), cache.read(url))
    }
  }
}
