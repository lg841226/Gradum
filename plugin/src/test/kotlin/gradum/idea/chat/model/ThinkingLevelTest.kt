/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThinkingLevelTest.kt  2026-08-14 14:10:00 Changed by gwy
 */
package gradum.idea.chat.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Round-trip and tolerant-parsing tests for [ThinkingLevel]. The enum
 * is part of the persistent UI state contract (it's stored in
 * `ChatInputState` and surfaced in test fixtures), so unknown future
 * values must degrade to [ThinkingLevel.MEDIUM] (the current default)
 * rather than throwing. The legacy "OFF" name from the previous
 * 3-level-with-off design is also accepted and mapped to [LOW] so
 * sessions stored by the old build keep their minimum-thinking
 * intent.
 */
class ThinkingLevelTest {

  @Test
  fun `every enum value round-trips through its name`() {
    ThinkingLevel.entries.forEach { level ->
      assertEquals(level, ThinkingLevel.valueOf(level.name))
    }
  }

  @Test
  fun `fromStringOrDefault returns the matching enum ignoring case`() {
    assertEquals(ThinkingLevel.LOW, ThinkingLevel.fromStringOrDefault("low"))
    assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromStringOrDefault("Medium"))
    assertEquals(ThinkingLevel.HIGH, ThinkingLevel.fromStringOrDefault("HIGH"))
  }

  @Test
  fun `fromStringOrDefault maps the legacy OFF name to LOW`() {
    // Old session stores from the previous 3-level-with-off design
    // must not silently upgrade "off" to medium — that would make
    // every returning user land on a heavier-thinking default
    // without warning.
    assertEquals(ThinkingLevel.LOW, ThinkingLevel.fromStringOrDefault("OFF"))
    assertEquals(ThinkingLevel.LOW, ThinkingLevel.fromStringOrDefault("off"))
  }

  @Test
  fun `fromStringOrDefault falls back to MEDIUM for null blank and unknown`() {
    // All three shapes must be safe to feed from a deserialized payload;
    // an old plugin version opening a new session store should never crash.
    assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromStringOrDefault(null))
    assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromStringOrDefault(""))
    assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromStringOrDefault("   "))
    assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromStringOrDefault("meditation"))
  }

  @Test
  fun `kotlinx serialization round-trip preserves the value`() {
    val json: Json = Json { ignoreUnknownKeys = true }
    ThinkingLevel.entries.forEach { level ->
      val encoded: String = json.encodeToString(ThinkingLevel.serializer(), level)
      // Enum serializes as the bare name string; assert that explicitly
      // so a future change to the wire format shows up here.
      assertEquals("\"${level.name}\"", encoded)
      val decoded: ThinkingLevel = json.decodeFromString(ThinkingLevel.serializer(), encoded)
      assertEquals(level, decoded)
    }
  }
}
