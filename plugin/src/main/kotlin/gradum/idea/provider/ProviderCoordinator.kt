/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderCoordinator.kt  2026-08-16 09:30:00 Changed by gwy
 */

package gradum.idea.provider

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Application-level singleton that owns the connection-health probe loop
 * for every registered [ProviderKind].
 *
 * UI components observe the exposed [StateFlow]s and push configuration
 * through [reconfigure] or one-off probes through [probeNow]. Probe work
 * runs on an internal scope, so UI code never holds a [Job]. Each
 * [ProviderKind] owns a runtime that serializes probes through a [Mutex]
 * and keeps a poll loop running while auto-detect is enabled.
 */
object ProviderCoordinator {

  private val appScope: CoroutineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default + CoroutineName("ProviderCoordinator")
  )
  private val probe: ProviderProbe = ProviderProbe()
  private val runtimes: Map<ProviderKind, ProviderRuntime> = ProviderKind.entries.associateWith { kind ->
    ProviderRuntime(kind = kind, appScope = appScope, probe = probe)
  }

  fun statusFlow(kind: ProviderKind): StateFlow<ProviderStatus> = runtimes.getValue(kind).status

  fun isTestingFlow(kind: ProviderKind): StateFlow<Boolean> = runtimes.getValue(kind).isTesting

  /**
   * Push the latest configuration to the runtime for [kind] and restart
   * the poll loop at [pollIntervalMs] when [autoDetect] is enabled. A
   * single probe fires immediately when [autoDetect] is disabled but the
   * URL is valid.
   */
  fun reconfigure(
    kind: ProviderKind,
    baseUrl: String,
    apiKey: String,
    pollIntervalMs: Long,
    autoDetect: Boolean,
  ) {
    runtimes.getValue(kind).reconfigure(baseUrl.trim(), apiKey.trim(), pollIntervalMs, autoDetect)
  }

  fun probeNow(kind: ProviderKind) {
    runtimes.getValue(kind).probeNow()
  }

  fun shutdown() {
    appScope.cancel()
  }

  private class ProviderRuntime(
    private val kind: ProviderKind,
    private val appScope: CoroutineScope,
    private val probe: ProviderProbe,
  ) {
    private val probeMutex: Mutex = Mutex()
    private val configMutex: Mutex = Mutex()
    private val _status: MutableStateFlow<ProviderStatus> = MutableStateFlow(ProviderStatus.Untested)
    private val _isTesting: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private var pollJob: Job? = null
    private var currentConfig: ProviderConfig = ProviderConfig("", "")

    val status: StateFlow<ProviderStatus> = _status.asStateFlow()
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    fun reconfigure(
      baseUrl: String,
      apiKey: String,
      pollIntervalMs: Long,
      autoDetect: Boolean,
    ) {
      appScope.launch {
        configMutex.withLock {
          val newConfig: ProviderConfig = ProviderConfig(baseUrl, apiKey)
          val wasPolling: Boolean = pollJob?.isActive == true
          if (newConfig == currentConfig && wasPolling == autoDetect) return@withLock
          currentConfig = newConfig
          pollJob?.cancel()
          pollJob = null
          when {
            !autoDetect && newConfig.isValid -> probeOnce(newConfig)
            autoDetect -> pollJob = launch {
              while (isActive) {
                if (currentConfig.isValid) probeOnce(currentConfig)
                if (pollIntervalMs <= 0L) return@launch
                delay(pollIntervalMs.milliseconds)
              }
            }
          }
        }
      }
    }

    fun probeNow() {
      appScope.launch { probeOnce(currentConfig) }
    }

    private suspend fun probeOnce(config: ProviderConfig) {
      if (!config.isValid) {
        _status.value = ProviderStatus.Failed("URL is empty")
        return
      }
      probeMutex.withLock {
        _isTesting.value = true
        _status.value = ProviderStatus.Testing
        try {
          _status.value = probe.probe(kind, config.baseUrl, config.apiKey)
        } finally {
          _isTesting.value = false
        }
      }
    }
  }

  private data class ProviderConfig(val baseUrl: String, val apiKey: String) {
    val isValid: Boolean get() = baseUrl.isNotBlank() && isValidBaseUrl(baseUrl)
  }
}

/** Accepts `http://...` and `https://...` schemes with a non-empty host. */
internal fun isValidBaseUrl(value: String): Boolean {
  val trimmed: String = value.trim()
  if (trimmed.isEmpty()) return false
  if (!trimmed.contains("://")) return false
  val scheme: String = trimmed.substringBefore("://")
  return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
}
