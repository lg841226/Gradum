/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderCoordinatorRuntimeTest.kt  2026-08-25 13:21:36 Changed by gwy
 */

package gradum.idea.provider

import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Behavioral tests for ProviderRuntime — the state machine behind
 * [ProviderCoordinator]. Covers the poll-loop lifecycle, auth-error
 * short-circuit, interval-change restarts, and the probe mutex that
 * serializes concurrent probes.
 *
 * The probe is injected as a [ProviderProbeContract] fake (never a real
 * HTTP call), so the poll loop, retry decisions and status transitions
 * run against deterministic, in-process results.
 */
class ProviderCoordinatorRuntimeTest {

  /**
   * Scriptable fake probe: serves results from [results] in order, then
   * keeps serving the last one. Records every probe in [calls].
   */
  private class FakeProbe(
    private val results: List<ProviderStatus>,
    private val onProbe: suspend () -> Unit = {},
    val calls: MutableList<ProviderKind> = mutableListOf()
  ) : ProviderProbeContract {
    override suspend fun probe(
      apiKey: String, baseUrl: String, kind: ProviderKind,
    ): ProviderStatus {
      calls.add(kind)
      onProbe()
      val index: Int = calls.size - 1
      return results[index.coerceAtMost(results.lastIndex)]
    }
  }

  private fun runtime(
    probe: ProviderProbeContract,
    scope: CoroutineScope,
    onSucceeded: MutableList<ProviderKind> = mutableListOf(),
  ): ProviderCoordinator.ProviderRuntime = ProviderCoordinator.ProviderRuntime(
    probe = probe,
    appScope = scope,
    kind = ProviderKind.OLLAMA,
    onProbeSucceeded = { onSucceeded.add(it) }
  )

  private fun testScope(): CoroutineScope =
    CoroutineScope(context = SupervisorJob() + Dispatchers.Default)

  @Test
  fun `reconfigure with invalid url marks Failed and never probes`() {
    val probe = FakeProbe(results = listOf(ProviderStatus.Ok(latencyMs = 5)))
    val scope: CoroutineScope = testScope()
    val runtime = runtime(probe, scope)

    runBlocking {
      runtime.reconfigure(baseUrl = "not a url", apiKey = "key", pollIntervalMs = 100, autoDetect = true)
      delay(duration = 150.milliseconds)
    }

    assertEquals(
      ProviderStatus.Failed("URL is invalid"),
      runtime.status.value
    )
    assertTrue(
      "invalid URL must never reach the probe",
      probe.calls.isEmpty()
    )
    scope.cancel()
  }

  @Test
  fun `autoDetect true polls repeatedly until stopped`() {
    val probe = FakeProbe(results = listOf(ProviderStatus.Ok(latencyMs = 7)))
    val scope: CoroutineScope = testScope()
    val successList: MutableList<ProviderKind> = mutableListOf()
    val runtime = runtime(probe, scope, onSucceeded = successList)

    runBlocking {
      runtime.reconfigure(
        baseUrl = "http://localhost:11434", apiKey = "key", pollIntervalMs = 30, autoDetect = true
      )
      delay(duration = 250.milliseconds)
    }

    assertTrue(
      "auto-detect poll loop must probe repeatedly, got ${probe.calls.size}",
      probe.calls.size >= 4
    )
    assertEquals(
      ProviderStatus.Ok(latencyMs = 7),
      runtime.status.value
    )
    assertTrue(successList.contains(ProviderKind.OLLAMA))
    scope.cancel()
  }

  @Test
  fun `auth error short-circuits polling immediately`() {
    val probe = FakeProbe(results = listOf(ProviderStatus.AuthError(latencyMs = 12)))
    val scope: CoroutineScope = testScope()
    val runtime = runtime(probe, scope)

    runBlocking {
      runtime.reconfigure(
        baseUrl = "http://localhost:11434", apiKey = "wrong-key", pollIntervalMs = 20, autoDetect = true
      )
      delay(duration = 200.milliseconds)
    }

    assertEquals(
      "AuthError must stop the loop after the first probe",
      1,
      probe.calls.size
    )
    assertEquals(
      ProviderStatus.AuthError(latencyMs = 12),
      runtime.status.value
    )
    scope.cancel()
  }

  @Test
  fun `autoDetect false with valid url probes exactly once`() {
    val probe = FakeProbe(results = listOf(ProviderStatus.Ok(latencyMs = 3)))
    val scope: CoroutineScope = testScope()
    val runtime = runtime(probe, scope)

    runBlocking {
      runtime.reconfigure(
        baseUrl = "http://localhost:1234", apiKey = "key", pollIntervalMs = 1000, autoDetect = false
      )
      delay(duration = 200.milliseconds)
    }

    assertEquals(
      1,
      probe.calls.size
    )
    assertEquals(
      ProviderStatus.Ok(latencyMs = 3),
      runtime.status.value
    )
    scope.cancel()
  }

  @Test
  fun `interval-only reconfigure restarts the poll loop`() {
    val probe = FakeProbe(results = listOf(ProviderStatus.Ok(latencyMs = 1)))
    val scope: CoroutineScope = testScope()
    val runtime = runtime(probe, scope)

    runBlocking {
      runtime.reconfigure(
        baseUrl = "http://localhost:11434", apiKey = "key", pollIntervalMs = 1000, autoDetect = true
      )
      delay(duration = 120.milliseconds)
      runtime.reconfigure(
        baseUrl = "http://localhost:11434", apiKey = "key", pollIntervalMs = 20, autoDetect = true
      )
      delay(duration = 120.milliseconds)
    }

    assertTrue(
      "interval-only change must restart the poll loop (got ${probe.calls.size})",
      probe.calls.size >= 2,
    )
    scope.cancel()
  }

  @Test
  fun `probeNow triggers a one-off probe under autoDetect false`() {
    val probe = FakeProbe(results = listOf(ProviderStatus.Unreachable(latencyMs = 9)))
    val scope: CoroutineScope = testScope()
    val runtime = runtime(probe, scope)

    runBlocking {
      runtime.reconfigure(
        baseUrl = "http://localhost:11434", apiKey = "key", pollIntervalMs = 1000, autoDetect = false
      )

      withTimeout(2_000.milliseconds) {
        while (probe.calls.isEmpty()) delay(duration = 5.milliseconds)
      }
      runtime.probeNow()
      withTimeout(2_000.milliseconds) {
        while (probe.calls.size < 2) delay(duration = 5.milliseconds)
      }
    }

    assertEquals(
      2,
      probe.calls.size
    )
    assertEquals(
      ProviderStatus.Unreachable(latencyMs = 9),
      runtime.status.value
    )
    scope.cancel()
  }

  @Test
  fun `concurrent probes serialize through the probe mutex`() {
    val probe = FakeProbe(
      results = listOf(ProviderStatus.Ok(latencyMs = 4)),
      onProbe = { delay(duration = 80.milliseconds) },
    )
    val scope: CoroutineScope = testScope()
    val runtime = runtime(probe, scope)

    runBlocking {
      runtime.reconfigure(
        baseUrl = "http://localhost:11434", apiKey = "key", pollIntervalMs = 1000, autoDetect = false
      )
      runtime.probeNow()
      runtime.probeNow()
      val started: Long = System.currentTimeMillis()
      while (probe.calls.size < 3) delay(duration = 5.milliseconds)
      val elapsed: Long = System.currentTimeMillis() - started

      assertTrue(
        "Expected serialized probes (~160ms), got ${elapsed}ms",
        elapsed >= 150
      )
    }

    assertEquals(
      3,
      probe.calls.size
    )
    scope.cancel()
  }
}
