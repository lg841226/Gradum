/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderKind.kt  2026-08-15 18:30:00 Changed by gwy
 */
package gradum.idea.provider

enum class ProviderKind(val displayKey: String) {
  OLLAMA("gradum.settings.provider.ollama"),
  LM_STUDIO("gradum.settings.provider.lmstudio"),
}
