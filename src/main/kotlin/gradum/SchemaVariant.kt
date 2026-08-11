/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SchemaVariant.kt  2026-07-14 21:27:12 Changed by gwy
 */

package gradum

/**
 * Determines the tool schema complexity variant for a model.
 *
 * - [FULL] — complete parameter set, batch operations, advanced features.
 *   For large/cloud models (≥70B parameters or cloud API models).
 * - [SIMPLE] — minimal parameter set, one action per call, simplified output.
 *   For small/local models (≤32B parameters).
 */
enum class SchemaVariant {
  FULL,
  SIMPLE;

  companion object {
    /**
     * Resolves the schema variant for a given model name.
     *
     * @param modelName the model identifier string (e.g. "qwen2.5:14b", "gpt-4o")
     * @return the appropriate [SchemaVariant] based on model capability inference
     */
    fun resolve(modelName: String): SchemaVariant {
      if (modelName.isBlank()) return FULL
      return if (ModelCapability.isSmall(modelName)) SIMPLE else FULL
    }
  }
}

/**
 * Model capability inference based on model name heuristics.
 *
 * Detection rules:
 * 1. Cloud/API indicators (cloud, gpt, claude, gemini, etc.) → large
 * 2. Parameter size tag (7b, 14b, 70b, etc.) → compare against threshold
 * 3. Unknown/unrecognized → default to large (assume capable)
 */
object ModelCapability {

  /** Parameter count threshold in billions. Models at or below this are considered small. */
  private const val MAX_SMALL_MODEL_PARAMETERS_B: Double = 32.0

  /**
   * Regex to extract parameter size from model names.
   * Matches patterns like "7b", "14b", "72b", "0.5b", "321b"
   * followed by whitespace, colon, hyphen, underscore, or end of string.
   */
  private val PARAMETER_SIZE_PATTERN: Regex = Regex("""(\d+\.?\d*)b(?:\s|$|:|[-_])""")

  private val CLOUD_KEYWORDS: Set<String> = setOf(
    "cloud", "api", "gpt", "claude", "gemini",
    "sonnet", "haiku", "opus", "pro", "flash",
    "turbo", "mini", "large", "xxl",
  )

  /**
   * Infers whether a model is small (≤32B parameters) based on its name.
   */
  fun isSmall(modelName: String): Boolean {
    if (modelName.isBlank()) return false

    val lowerName = modelName.lowercase()

    if (CLOUD_KEYWORDS.any { lowerName.contains(it) }) return false

    val sizeMatch = PARAMETER_SIZE_PATTERN.find(lowerName) ?: return false
    val parameterCountBillions: Double = sizeMatch.groupValues[1].toDoubleOrNull() ?: return false

    return parameterCountBillions <= MAX_SMALL_MODEL_PARAMETERS_B
  }
}
