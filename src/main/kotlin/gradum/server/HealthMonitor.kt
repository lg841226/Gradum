package gradum.server

import gradum.ModelEntry
import gradum.ModelIdentity
import gradum.server.persistence.SessionTranscriptWriter
import gradum.workspace.Workspace
import gradum.workspace.WorkspaceHost
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.round

/**
 * Aggregated health snapshot behind `GET /health`: JVM system resources plus
 * provider / legatus / workspace component probes. The whole block is cached
 * for a short TTL so polling stays cheap; provider reachability itself comes
 * from [ModelIdentity]'s 60s discovery cache, never from fresh HTTP dials.
 */
internal object HealthMonitor {

  private const val SNAPSHOT_TTL_MS: Long = 4_000L

  private const val BYTES_PER_MB: Long = 1024L * 1024L

  private data class Snapshot(val timestamp: Long, val payload: Map<String, Any?>)

  @Volatile
  private var snap: Snapshot? = null

  /** Cached `status` / `system` / `components` block; request-scoped fields stay in the route. */
  fun snapshot(): Map<String, Any?> {
    val now: Long = System.currentTimeMillis()
    snap?.takeIf { now - it.timestamp < SNAPSHOT_TTL_MS }?.let { return it.payload }
    return compute().also { snap = Snapshot(timestamp = now, payload = it) }
  }

  private fun compute(): Map<String, Any?> {
    val providers: List<Map<String, Any?>> = probeProviders()
    val legatus: Map<String, Any?> = probeLegatus()
    val workspace: Map<String, Any?> = probeWorkspace()
    val degraded: Boolean =
      providers.any { entry -> entry["ok"] != true } ||
        legatus["ok"] != true ||
        workspace["ok"] != true
    return mapOf(
      "status" to if (degraded) "degraded" else "healthy",
      "system" to systemBlock(),
      "components" to mapOf(
        "providers" to providers,
        "legatus" to legatus,
        "workspace" to workspace,
      ),
    )
  }

  // One entry per distinct (providerType, serverUrl) reported by discovery,
  // which is served by the 60s HealthCache inside ModelIdentity.discoverModels().
  // The cache tracks no latency, so `latencyMs` stays null.
  private fun probeProviders(): List<Map<String, Any?>> = try {
    ModelIdentity.discoverModels()
      .groupBy { entry: ModelEntry -> entry.providerType to entry.serverUrl }
      .map { (server, entries) ->
        val reachable: Boolean = entries.any { entry -> entry.available }
        mapOf(
          "kind" to server.first,
          "server" to server.second,
          "ok" to reachable,
          "latencyMs" to null,
          "error" to if (reachable) null
          else entries.firstNotNullOfOrNull { entry -> entry.unavailableReason?.name }
            ?: "server responded but no model is available",
        )
      }
  } catch (failure: Exception) {
    listOf(
      mapOf(
        "kind" to "discovery",
        "server" to "",
        "ok" to false,
        "latencyMs" to null,
        "error" to (failure.message ?: failure.javaClass.simpleName),
      )
    )
  }

  // Cheapest read through the same entry as `GET /sessions`: listing session
  // transcripts under the server's default root (the process working directory).
  private fun probeLegatus(): Map<String, Any?> = try {
    val defaultRoot: Path = Paths.get(System.getProperty("user.dir") ?: ".")
    SessionTranscriptWriter.listSessions(projectRoot = defaultRoot)
    mapOf("ok" to true, "error" to null)
  } catch (failure: Exception) {
    mapOf("ok" to false, "error" to (failure.message ?: failure.javaClass.simpleName))
  }

  // Directory-existence check per registered root; a null workspace mirrors
  // the 503 `GET /workspace/tree` answers when the workspace is not initialized.
  private fun probeWorkspace(): Map<String, Any?> {
    val workspace: Workspace = WorkspaceHost.workspace
      ?: return mapOf(
        "ok" to false,
        "error" to "Workspace is not initialized",
        "roots" to emptyList<Map<String, Any?>>(),
      )
    return try {
      val roots: List<Map<String, Any?>> = workspace.registeredRoots().map { root ->
        mapOf("path" to root.directory.path, "ok" to root.directory.isDirectory)
      }
      mapOf(
        "ok" to roots.all { root -> root["ok"] == true },
        "error" to null,
        "roots" to roots,
      )
    } catch (failure: Exception) {
      mapOf(
        "ok" to false,
        "error" to (failure.message ?: failure.javaClass.simpleName),
        "roots" to emptyList<Map<String, Any?>>(),
      )
    }
  }

  private fun systemBlock(): Map<String, Any?> {
    val runtime: Runtime = Runtime.getRuntime()
    val heapUsedMb: Long = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB
    val heapMaxMb: Long = runtime.maxMemory() / BYTES_PER_MB
    val processors: Int = runtime.availableProcessors()
    val loadAverage: Double = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage
    val cpuLoad: Double? =
      if (loadAverage < 0.0) null
      else round(loadAverage / processors * 100.0) / 100.0
    return mapOf(
      "heapUsedMb" to heapUsedMb,
      "heapMaxMb" to heapMaxMb,
      "cpuLoad" to cpuLoad,
      "availableProcessors" to processors,
    )
  }
}
