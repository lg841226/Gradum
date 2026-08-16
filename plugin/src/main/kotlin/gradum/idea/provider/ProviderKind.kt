/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderKind.kt  2026-08-15 18:30:00 Changed by gwy
 */
package gradum.idea.provider

/**
 * Supported model providers, used to dispatch probe endpoints and to
 * resolve the localized section title through [displayKey].
 */
enum class ProviderKind(val displayKey: String) {
  OLLAMA("gradum.settings.provider.ollama"),
  LM_STUDIO("gradum.settings.provider.lmstudio");

  /** Wire name sent to the server's `/provider/probe` endpoint. */
  val wireName: String
    get() = when (this) {
      OLLAMA -> "ollama"
      LM_STUDIO -> "lmstudio"
    }
}
