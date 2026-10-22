package gradum.idea.chat.model

import kotlinx.serialization.Serializable

/**
 * Strength of the reasoning the user wants the model to apply on a turn.
 *
 * Wire contract. The selected level crosses the plugin -> server
 * boundary as the lowercase `thinkingLevel` field of the `/events` request
 * body; the server turns it into the provider's native structured thinking
 * parameter (Ollama `think`, OpenAI-style `reasoning_effort`). The old
 * prompt-suffix injection ([gradum.idea.chat.ui.util.ThinkingPromptInjector])
 * is retired, but the class stays for reference by tests / docs.
 *
 * - [LOW]   : a one-sentence "think briefly" hint is appended. Cheapest,
 *              almost no latency cost; useful for routine questions where
 *              the user wants a slight quality bump.
 * - [MEDIUM]: a short "think about the main constraints and approach"
 *              hint is appended. Balanced cost / quality; the default for
 *              brand-new sessions.
 * - [HIGH]  : a multi-sentence "think deeply, step-by-step" hint is
 *              appended. Increases planning depth at the cost of longer
 *              responses; worth it for refactors, multi-file edits, and
 *              architecture questions.
 *
 * No explicit "off" level: whether thinking happens at all is resolved
 * server-side from the master think switch. The plugin-side selector is
 * gated by the selected model's catalog flags -- hidden when the model
 * cannot think (`reasoning = false`) and grayed when it thinks but exposes
 * no depth levels (`reasoningLevels = false`).
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
      if (rawValue.equals(other = "OFF", ignoreCase = true)) return LOW
      return entries.firstOrNull { it.name.equals(other = rawValue, ignoreCase = true) } ?: MEDIUM
    }
  }
}
