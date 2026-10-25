package gradum.idea.chat.model

import kotlinx.serialization.Serializable

/**
 * Strength of the reasoning the user wants the model to apply on a turn.
 *
 * Wire contract. The selected level crosses the plugin -> server
 * boundary as the lowercase `thinkingLevel` field of the `/events` request
 * body; the server turns it into the provider's native structured thinking
 * parameter (Ollama `think`, OpenAI-style `reasoning_effort`).
 *
 * - [LOW]   : minimal reasoning effort. Cheapest, almost no latency
 *              cost; useful for routine questions where the user wants a
 *              slight quality bump.
 * - [MEDIUM]: balanced cost / quality; the default for brand-new
 *              sessions.
 * - [HIGH]  : maximum reasoning effort. Increases planning depth at the
 *              cost of longer responses; worth it for refactors,
 *              multi-file edits, and architecture questions.
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
    fun fromStringOrDefault(rawValue: String?): ThinkingLevel {
      return when {
        rawValue.isNullOrBlank() -> MEDIUM
        rawValue.equals("OFF", ignoreCase = true) -> LOW
        else -> entries.firstOrNull { it.name.equals(rawValue, ignoreCase = true) } ?: MEDIUM
      }
    }
  }
}
