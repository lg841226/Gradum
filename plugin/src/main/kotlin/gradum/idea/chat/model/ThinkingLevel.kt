package gradum.idea.chat.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThinkingLevel {
  OFF,
  LOW,
  MEDIUM,
  HIGH;

  companion object {
    fun fromStringOrDefault(rawValue: String?): ThinkingLevel {
      if (rawValue.isNullOrBlank()) return MEDIUM
      return entries.firstOrNull { it.name.equals(rawValue, ignoreCase = true) } ?: MEDIUM
    }
  }
}
