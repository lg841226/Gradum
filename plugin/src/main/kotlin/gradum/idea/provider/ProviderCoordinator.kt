/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderCoordinator.kt  2026-08-18 12:45:23 Changed by gwy
 */

package gradum.idea.provider

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
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
  private val probe: ProviderProbeContract = ProviderProbe()
  private val runtimes: Map<ProviderKind, ProviderRuntime> = ProviderKind.entries.associateWith { kind ->
    ProviderRuntime(kind = kind, probe = probe, appScope = appScope) { probeSucceededKind ->
      _probeSucceeded.tryEmit(probeSucceededKind)
    }
  }
  private val _probeSucceeded: MutableSharedFlow<ProviderKind> = MutableSharedFlow(extraBufferCapacity = 8)

  /**
   * Emits the [ProviderKind] whenever a manual probe (the settings page
   * "Test" button) reports the provider reachable. Consumers (e.g. the chat
   * session) refresh the model roster on this event so a successful
   * reconfiguration shows up immediately instead of on the next poll tick.
   */
  val probeSucceeded: SharedFlow<ProviderKind> = _probeSucceeded.asSharedFlow()

  fun statusFlow(kind: ProviderKind): StateFlow<ProviderStatus> = runtimes.getValue(kind).status

  fun isTestingFlow(kind: ProviderKind): StateFlow<Boolean> = runtimes.getValue(kind).isTesting

  /**
   * Push the latest configuration to the runtime for [kind] and restart
   * the poll loop at [pollIntervalMs] when [autoDetect] is enabled. A
   * single probe fires immediately when [autoDetect] is disabled but the
   * URL is valid.
   */
  fun reconfigure(
    kind: ProviderKind, baseUrl: String, apiKey: String, pollIntervalMs: Long,
    autoDetect: Boolean
  ) {
    runtimes.getValue(kind).reconfigure(baseUrl.trim(), apiKey.trim(), pollIntervalMs, autoDetect)
  }

  fun probeNow(kind: ProviderKind) {
    runtimes.getValue(kind).probeNow()
  }

  internal class ProviderRuntime(
    private val kind: ProviderKind,
    private val appScope: CoroutineScope,
    private val probe: ProviderProbeContract,
    private val onProbeSucceeded: (ProviderKind) -> Unit,
  ) {
    private val probeMutex: Mutex = Mutex()
    private val configMutex: Mutex = Mutex()
    private val _status: MutableStateFlow<ProviderStatus> = MutableStateFlow(ProviderStatus.Untested)
    private val _isTesting: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private var pollJob: Job? = null
    private var currentPollIntervalMs: Long = 0L
    private var currentConfig: ProviderConfig = ProviderConfig(kind, "", "")

    val status: StateFlow<ProviderStatus> = _status.asStateFlow()
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    fun reconfigure(
      baseUrl: String, apiKey: String, pollIntervalMs: Long, autoDetect: Boolean
    ) {
      appScope.launch {
        configMutex.withLock {
          val newConfig = ProviderConfig(kind, baseUrl, apiKey)
          val wasPolling: Boolean = pollJob?.isActive == true
          // pollIntervalMs is NOT part of ProviderConfig (its equals only
          // compares kind/baseUrl/apiKey), so it must be tracked separately:
          // an interval-only edit must still restart the poll loop.
          if (newConfig == currentConfig && wasPolling == autoDetect && pollIntervalMs == currentPollIntervalMs)
            return@withLock
          currentConfig = newConfig
          currentPollIntervalMs = pollIntervalMs
          pollJob?.cancel(CancellationException("Gradum stop polling"))
          pollJob = null
          when {
            // The URL is no longer valid — drop any stale "ok/latency" badge
            // instead of letting a previous success linger next to a red field.
            !newConfig.isValid -> _status.value = ProviderStatus.Failed("URL is invalid")
            !autoDetect && newConfig.isValid -> probeOnce(newConfig)
            autoDetect -> pollJob = launch {
              while (isActive) {
                if (currentConfig.isValid) probeOnce(currentConfig)
                // Auth failures are deterministic — a bad key will not heal
                // by itself, and retrying just burns the provider's rate limit.
                if (_status.value is ProviderStatus.AuthError) return@launch
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
          val result: ProviderStatus = probe.probe(config.apiKey, config.baseUrl, kind)
          _status.value = result
          if (result is ProviderStatus.Ok) onProbeSucceeded(kind)
        } finally {
          _isTesting.value = false
        }
      }
    }
  }

  private data class ProviderConfig(val kind: ProviderKind, val baseUrl: String, val apiKey: String) {
    val isValid: Boolean get() = baseUrl.isNotBlank() && isValidBaseUrl(baseUrl, kind)
  }
}

/**
 * Strict base-URL validator for the local OpenAI-compatible providers
 * (Ollama / LM Studio / vLLM / LocalAI).
 *
 * Parses the value as a real [URI] and requires:
 * - an `http` / `https` scheme;
 * - a non-blank host that is a valid IPv4 literal or hostname
 *   (no spaces, control chars or garbage such as `/v1832483294239482394`);
 * - a port in `1..65535` when one is present;
 * - a path that is empty, `/`, `/v1` or `/v1/` — the API base prefixes
 *   these providers accept. Anything else (arbitrary junk paths) fails.
 *
 * A pure string/scheme check is not enough: users can paste garbage after
 * the scheme and still see a green URL field.
 */
internal fun isValidBaseUrl(value: String, kind: ProviderKind? = null): Boolean {
  val trimmed: String = value.trim()
  if (trimmed.isEmpty()) return false
  val uri: URI = try {
    URI(trimmed)
  } catch (_: Exception) {
    return false
  }
  val scheme: String = uri.scheme ?: return false
  if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
    return false
  }
  val host: String = uri.host ?: return false
  if (!isValidHost(host)) return false
  if (uri.port != -1 && (uri.port !in 1..65535)) return false
  if (uri.query != null || uri.fragment != null || uri.userInfo != null) return false
  val path: String = uri.path ?: ""
  // Cloud providers (Zhipu / DeepSeek / MiniMax) use deep fixed base
  // URLs such as `https://open.bigmodel.cn/api/coding/paas/v4`, so their
  // path is not restricted. Local providers only accept the empty path or
  // the `/v1` OpenAI prefix.
  return kind?.isCloud == true || path.isEmpty() || path == "/" || path == "/v1" || path == "/v1/"
}

private fun isValidHost(host: String): Boolean {
  if (host.isEmpty()) return false
  val ipv4: Boolean = host.matches(IPV4_PATTERN) &&
    host.split(".").all { octet -> octet.toInt() in 0..255 }
  return ipv4 || host.matches(HOSTNAME_PATTERN)
}

private val IPV4_PATTERN: Regex = Regex("""\d{1,3}(\.\d{1,3}){3}""")
private val HOSTNAME_PATTERN: Regex = Regex("""[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)*""")
