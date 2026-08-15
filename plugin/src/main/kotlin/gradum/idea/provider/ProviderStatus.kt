/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderStatus.kt  2026-08-15 18:30:00 Changed by gwy
 */
package gradum.idea.provider

/**
 * Outcome of a single connection probe.
 *
 * Carries a measured latency in every terminal state so the UI can show
 * a "455 ms" badge; transient states ([Untested] / [Testing]) carry
 * nothing because the spinner already speaks.
 */
sealed class ProviderStatus {
  data object Untested : ProviderStatus()
  data object Testing : ProviderStatus()
  data class Ok(val latencyMs: Long) : ProviderStatus()
  data class Unreachable(val latencyMs: Long) : ProviderStatus()
  data class AuthError(val latencyMs: Long) : ProviderStatus()
  data class Failed(val message: String) : ProviderStatus()
}
