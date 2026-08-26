/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThumbnailDiskCacheTest.kt  2026-08-25 23:14:30 Changed by gwy
 */
package gradum.idea.chat.ui.util

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

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
    cache = ThumbnailDiskCache(rootDirectory = root, maxBytes = 1024L * 1024)
  }

  @After
  fun tearDown() {
    cache.clear()
    Files.deleteIfExists(root)
  }

  @Test
  fun `read returns null for an unwritten URL`() {
    assertNull(cache.read(url = "https://example.com/favicon.ico"))
  }

  @Test
  fun `write then read returns the same bytes`() {
    val urlString = "https://example.com/favicon.ico"

    val payload: ByteArray = byteArrayOf(
      0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
    cache.write(urlString, bytes = payload)
    val restored = cache.read(urlString)

    assertNotNull(restored)
    assertArrayEquals(
      payload,
      restored
    )
  }

  @Test
  fun `empty payload is not written`() {
    cache.write(url = "https://example.com/empty", bytes = ByteArray(size = 0))
    assertNull(cache.read(url = "https://example.com/empty"))
  }

  @Test
  fun `different URLs map to different files`() {
    val payloadA = "alpha".toByteArray()
    val payloadB = "beta".toByteArray()
    cache.write(url = "https://a.example/favicon.ico", bytes = payloadA)
    cache.write(url = "https://b.example/favicon.ico", bytes = payloadB)

    assertArrayEquals(
      payloadA,
      cache.read(url = "https://a.example/favicon.ico")
    )
    assertArrayEquals(
      payloadB,
      cache.read(url = "https://b.example/favicon.ico")
    )
  }

  @Test
  fun `writing the same URL twice overwrites the previous entry`() {
    val url = "https://example.com/favicon.ico"
    cache.write(url, bytes = "old".toByteArray())
    cache.write(url, bytes = "new".toByteArray())

    assertArrayEquals(
      "new".toByteArray(),
      cache.read(url)
    )
  }

  @Test
  fun `eviction removes the oldest entry when the cache is full`() {
    val tightCache = ThumbnailDiskCache(rootDirectory = root, maxBytes = 100L)
    tightCache.clear()
    try {
      val payload = ByteArray(size = 40)
      tightCache.write(url = "https://a/", bytes = payload)
      Thread.sleep(20)
      tightCache.write(url = "https://b/", bytes = payload)
      Thread.sleep(20)
      tightCache.write(url = "https://c/", bytes = payload)
      assertNull(
        "oldest entry must be evicted when over capacity",
        tightCache.read(url = "https://a/")
      )
      assertNotNull(tightCache.read(url = "https://b/"))
      assertNotNull(tightCache.read(url = "https://c/"))
    } finally {
      tightCache.clear()
    }
  }

  @Test
  fun `reading an entry refreshes its mtime so LRU promotes it`() {
    val tightCache = ThumbnailDiskCache(rootDirectory = root, maxBytes = 120L)
    tightCache.clear()
    try {
      val payload = ByteArray(size = 40)
      tightCache.write(url = "https://a/", bytes = payload)
      Thread.sleep(20)
      tightCache.write(url = "https://b/", bytes = payload)
      Thread.sleep(20)
      tightCache.write(url = "https://c/", bytes = payload)


      assertNotNull(tightCache.read(url = "https://a/"))
      Thread.sleep(50)
      assertNotNull(tightCache.read(url = "https://a/"))
      tightCache.write(url = "https://d/", bytes = payload)

      assertNotNull(
        "recently-read entry should survive eviction",
        tightCache.read(url = "https://a/")
      )
      assertNull(
        "unread stale entry should be evicted",
        tightCache.read(url = "https://b/")
      )
    } finally {
      tightCache.clear()
    }
  }

  @Test
  fun `clear removes every entry`() {
    cache.write(url = "https://a/", bytes = "x".toByteArray())
    cache.write(url = "https://b/", bytes = "y".toByteArray())
    cache.clear()
    assertNull(cache.read(url = "https://a/"))
    assertNull(cache.read(url = "https://b/"))
    assertEquals(
      0L,
      cache.sizeBytes()
    )
  }

  @Test
  fun `read survives a corrupt entry by deleting it`() {
    val url = "https://example.com/corrupt"
    cache.write(url, bytes = "valid".toByteArray())
    val files = Files.list(root).use { it.toList() }

    assertEquals(
      1,
      files.size
    )

    Files.delete(files[0])
    Files.createDirectory(files[0])

    assertNull(cache.read(url))
    assertTrue(Files.list(root).use { it.toList() }.isEmpty())
  }

  @Test
  fun `sizeBytes reflects the on-disk footprint after writes`() {
    val payload = ByteArray(size = 100)
    cache.write(url = "https://a/", bytes = payload)
    cache.write(url = "https://b/", bytes = payload)
    val size = cache.sizeBytes()

    assertTrue(
      "sizeBytes=$size should be at least 200 bytes",
      size >= 200
    )
  }

  @Test
  fun `mtime touch does not break under read-heavy workloads`() {
    val url = "https://example.com/popular"
    cache.write(url, bytes = "data".toByteArray())

    repeat(times = 1000) {
      assertArrayEquals(
        "data".toByteArray(),
        cache.read(url)
      )
    }
  }
}
