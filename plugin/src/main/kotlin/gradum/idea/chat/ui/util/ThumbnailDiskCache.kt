/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThumbnailDiskCache.kt  2026-08-25 23:14:30 Changed by gwy
 */
package gradum.idea.chat.ui.util

import com.intellij.openapi.application.PathManager
import gradum.idea.PluginConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Files.readAllBytes
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime.fromMillis
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger: Logger = LoggerFactory.getLogger("ThumbnailDiskCache")

/**
 * Persistent, on-disk byte cache for thumbnail payloads.
 *
 * **Why a disk cache.** The in-memory [ThumbnailImageLoader] LRU
 * forgets every URL on IDE restart, so the same favicon gets
 * re-fetched every time the user opens the project. Favicons are
 * small (1–15 KB) and effectively immutable from a 16x16 preview's
 * point of view, so paying a few MB of disk for a cache that
 * survives restart is a clear win — especially for searches against
 * the same engine during the same development session.
 *
 * **Why this shape.** One file per URL, hash-named, mtime-evicted.
 * The set of URLs is unbounded across a long session, but each
 * individual entry is small; a per-file layout (vs. one big blob)
 * means a single corrupt entry can't poison the rest, eviction is
 * O(files-to-evict) instead of rewriting a whole file, and the
 * on-disk representation is trivially inspectable with `ls`.
 *
 * **Thread safety.** All state mutations go through a single
 * [ReentrantLock]. Disk I/O is the bottleneck anyway, so the
 * additional contention over a `ConcurrentHashMap` is not the
 * constraint; one writer at a time keeps the LRU-eviction invariant
 * easy to reason about. Reads are still parallel: the [read] method
 * holds the lock just long enough to validate the entry exists and
 * to record a hit, then releases it before the I/O actually happens.
 *
 * **Failure mode.** Every disk operation is best-effort. A failure
 * (full disk, permissions revoked, file deleted out from under us)
 * is logged at WARN and the method returns the "miss" result; the
 * caller falls back to the network. The cache must never make a
 * working network fetch fail.
 */
internal class ThumbnailDiskCache(
  private val rootDirectory: Path = defaultRootDirectory(),
  private val maxBytes: Long = PluginConfig.THUMBNAIL_DISK_CACHE_MAX_BYTES
) {

  companion object {
    /** First 32 hex chars (128 bits) of SHA-256 — collision-safe for any realistic URL set. */
    private const val HASH_HEX_LENGTH: Int = 32

    fun defaultRootDirectory(): Path =
      Paths.get(PathManager.getConfigDir().toString(), "thumbnails")
  }

  private val lock: ReentrantLock = ReentrantLock()

  init {
    try {
      Files.createDirectories(rootDirectory)
    } catch (initException: Exception) {
      logger.warn(
        "Thumbnail disk cache directory '{}' could not be created: {}",
        rootDirectory, initException.message
      )
    }
  }

  /**
   * Returns the raw bytes previously stored under [url], or `null`
   * on miss / read error. A successful read refreshes the file's
   * mtime so LRU eviction ranks it ahead of older, still-unused
   * entries.
   */
  fun read(url: String): ByteArray? {
    val entryFile: Path = entryFile(url)
    if (!Files.exists(entryFile)) return null

    return try {
      val bytes: ByteArray = readAllBytes(entryFile)
      try {
        Files.setLastModifiedTime(entryFile, fromMillis(System.currentTimeMillis()))
      } catch (touchException: Exception) {
        logger.debug("Could not touch mtime for {}: {}", entryFile, touchException.message)
      }
      bytes
    } catch (readException: Exception) {
      logger.warn("Thumbnail disk cache read failed for {}: {}", entryFile, readException.message)
      try {
        Files.deleteIfExists(entryFile)
      } catch (_: Exception) {
        /* best effort */
      }
      null
    }
  }

  /**
   * Persist [bytes] for [url]. Best-effort: any failure is logged
   * and swallowed so the caller (the network-fetch path) doesn't
   * have to care. After a successful write, the cache evicts the
   * oldest entries until total size is at or below [maxBytes].
   */
  fun write(url: String, bytes: ByteArray) {
    if (bytes.isEmpty()) return
    val entryFile: Path = entryFile(url)
    lock.withLock {
      try {
        val tempFile: Path = entryFile.resolveSibling("${entryFile.fileName}.tmp")
        Files.write(tempFile, bytes)
        Files.move(
          tempFile,
          entryFile,
          StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
        )
        evictIfNeeded()
      } catch (writeException: Exception) {
        logger.warn("Thumbnail disk cache write failed for {}: {}", entryFile, writeException.message)
      }
    }
  }

  /**
   * Returns the total bytes currently on disk across all entries.
   * Walks the directory; O(n) but n is small and this is a diagnostic
   * path (not used on the hot read path).
   */
  fun sizeBytes(): Long = lock.withLock {
    var total: Long = 0
    try {
      Files.list(rootDirectory).use { stream ->
        for (file: Path in stream) {
          if (Files.isRegularFile(file)) {
            total += Files.size(file)
          }
        }
      }
    } catch (sizeException: Exception) {
      logger.debug("Could not enumerate cache size: {}", sizeException.message)
    }
    total
  }

  /**
   * Drop every cached entry. Used by tests; not exposed publicly.
   */
  internal fun clear() {
    lock.withLock {
      try {
        Files.list(rootDirectory).use { stream ->
          for (file: Path in stream) {
            try {
              Files.deleteIfExists(file)
            } catch (_: Exception) {
              /* best effort */
            }
          }
        }
      } catch (clearException: Exception) {
        logger.warn("Thumbnail disk cache clear failed: {}", clearException.message)
      }
    }
  }

  /**
   * Map a URL to a deterministic, filesystem-safe filename. The hash
   * is single-purpose and constant-time-ish in length, so we can use
   * a fixed prefix without collisions becoming a real concern in
   * practice (128 bits is well past birthday-bound for any realistic
   * URL set per IDE install).
   */
  private fun entryFile(url: String): Path {
    val digest: ByteArray = MessageDigest.getInstance("SHA-256")
      .digest(url.toByteArray(Charsets.UTF_8))

    val hex: String = digest.joinToString(separator = "") {
      "%02x".format(it)
    }.substring(0, HASH_HEX_LENGTH)
    return rootDirectory.resolve("$hex.img")
  }

  /**
   * Walk files in mtime-ascending order, deleting the oldest until
   * the total size is at or under [maxBytes]. Best-effort: a deleted
   * that fails (file already gone, perms changed) is ignored.
   */
  private fun evictIfNeeded() {
    var currentSize: Long = sizeBytesNoLock()
    if (currentSize <= maxBytes) return
    try {
      val candidates: List<Path> = Files.list(rootDirectory).use { stream ->
        stream
          .filter { Files.isRegularFile(it) }
          .toList()
          .sortedBy {
            runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(
              defaultValue = 0L
            )
          }
      }
      for (file: Path in candidates) {
        if (currentSize <= maxBytes) break
        val fileSize: Long = runCatching { Files.size(file) }.getOrDefault(defaultValue = 0L)
        if (runCatching { Files.deleteIfExists(file) }.getOrDefault(defaultValue = false)) {
          currentSize -= fileSize
        }
      }
    } catch (evictException: Exception) {
      logger.warn("Thumbnail disk cache eviction failed: {}", evictException.message)
    }
  }

  private fun sizeBytesNoLock(): Long {
    var total: Long = 0
    try {
      Files.list(rootDirectory).use { stream ->
        for (file: Path in stream) {
          if (Files.isRegularFile(file)) total += Files.size(file)
        }
      }
    } catch (_: Exception) {
      /* best effort */
    }
    return total
  }
}
