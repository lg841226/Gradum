/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThinkingLevelTest.kt  2026-08-31 19:21:55 Changed by gwy
 */
package gradum.idea.chat.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trip and tolerant-parsing tests for [ThinkingLevel]. The enum
 * is part of the persistent UI state contract (it's stored in
 * `ChatInputState` and surfaced in test fixtures), so unknown future
 * values must degrade to [ThinkingLevel.MEDIUM] (the current default)
 * rather than throwing. The legacy "OFF" name from the previous
 * 3-level-with-off design is also accepted and mapped to LOW so
 * sessions stored by the old build keep their minimum-thinking
 * intent.
 */
class ThinkingLevelTest {

  @Test
  fun `every enum value round-trips through its name`() {
    ThinkingLevel.entries.forEach { level ->
      assertEquals(
        level,
        ThinkingLevel.valueOf(level.name)
      )
    }
  }

  @Test
  fun `fromStringOrDefault returns the matching enum ignoring case`() {
    assertEquals(
      ThinkingLevel.LOW,
      ThinkingLevel.fromStringOrDefault(rawValue = "low")
    )
    assertEquals(
      ThinkingLevel.MEDIUM,
      ThinkingLevel.fromStringOrDefault(rawValue = "Medium")
    )
    assertEquals(
      ThinkingLevel.HIGH,
      ThinkingLevel.fromStringOrDefault("HIGH")
    )
  }

  @Test
  fun `fromStringOrDefault maps the legacy OFF name to LOW`() {
    assertEquals(
      ThinkingLevel.LOW,
      ThinkingLevel.fromStringOrDefault(rawValue = "OFF")
    )
    assertEquals(
      ThinkingLevel.LOW,
      ThinkingLevel.fromStringOrDefault(rawValue = "off")
    )
  }

  @Test
  fun `fromStringOrDefault falls back to MEDIUM for null blank and unknown`() {
    // All three shapes must be safe to feed from a deserialized payload;
    // an old plugin version opening a new session store should never crash.
    assertEquals(
      ThinkingLevel.MEDIUM,
      ThinkingLevel.fromStringOrDefault(rawValue = null)
    )
    assertEquals(
      ThinkingLevel.MEDIUM,
      ThinkingLevel.fromStringOrDefault(rawValue = "")
    )
    assertEquals(
      ThinkingLevel.MEDIUM,
      ThinkingLevel.fromStringOrDefault(rawValue = "   ")
    )
    assertEquals(
      ThinkingLevel.MEDIUM,
      ThinkingLevel.fromStringOrDefault(rawValue = "meditation")
    )
  }

  @Test
  fun `kotlinx serialization round-trip preserves the value`() {
    val json = Json { ignoreUnknownKeys = true }
    ThinkingLevel.entries.forEach { level ->
      val encoded: String = json.encodeToString(serializer = ThinkingLevel.serializer(), value = level)
      val decoded: ThinkingLevel = json.decodeFromString(deserializer = ThinkingLevel.serializer(), string = encoded)

      assertEquals(
        "\"${level.name}\"",
        encoded
      )
      assertEquals(
        level,
        decoded
      )
    }
  }
}
