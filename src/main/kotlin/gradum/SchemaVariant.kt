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
  FULL, SIMPLE;

  companion object {
    fun resolve(modelName: String): SchemaVariant =
      ModelIdentity.schemaVariant(modelName)
  }
}
