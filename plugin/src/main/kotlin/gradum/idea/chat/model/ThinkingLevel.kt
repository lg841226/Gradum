/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThinkingLevel.kt  2026-08-14 14:10:00 Changed by gwy
 */
package gradum.idea.chat.model

import kotlinx.serialization.Serializable

/**
 * Strength of the reasoning the user wants the model to apply on a turn.
 *
 * **Wire contract.** Each level is realized purely on the plugin side:
 * [gradum.idea.chat.ui.util.ThinkingPromptInjector] appends a short
 * instruction suffix to the user message before it crosses the plugin → server
 * boundary. The server is unaware of this enum; lowering the surface area
 * keeps the protocol stable across plugin versions.
 *
 * - [LOW]    — a one-sentence "think briefly" hint is appended. Cheapest,
 *              almost no latency cost; useful for routine questions where
 *              the user wants a slight quality bump.
 * - [MEDIUM] — a short "think about the main constraints and approach"
 *              hint is appended. Balanced cost / quality; the default for
 *              brand-new sessions.
 * - [HIGH]   — a multi-sentence "think deeply, step-by-step" hint is
 *              appended. Increases planning depth at the cost of longer
 *              responses; worth it for refactors, multi-file edits, and
 *              architecture questions.
 *
 * No explicit "off" level — the plugin-side prompt-injection approach
 * degrades gracefully on any model (including ones without a native
 * `reasoning` field), so Low is effectively the minimum the user can
 * pick. The dropdown is always enabled, regardless of the selected
 * model's `reasoning` catalog flag.
 */
@Serializable
enum class ThinkingLevel {
  LOW,
  MEDIUM,
  HIGH;

  companion object {
    /**
     * Parse a stored / wire string back into a [ThinkingLevel]. Unknown
     * values fall back to [MEDIUM] (the current default) so a future
     * plugin version that adds or removes a level doesn't crash an
     * older one loading its state. The "OFF" name from the previous
     * 3-level-with-off design is also accepted and mapped to [LOW] so
     * sessions stored by the old build keep their minimum-thinking
     * intent rather than silently upgrading to medium.
     */
    fun fromStringOrDefault(rawValue: String?): ThinkingLevel {
      if (rawValue.isNullOrBlank()) return MEDIUM
      if (rawValue.equals("OFF", ignoreCase = true)) return LOW
      return entries.firstOrNull { it.name.equals(rawValue, ignoreCase = true) } ?: MEDIUM
    }
  }
}
