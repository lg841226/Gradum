/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * MaterializedMcpTools.kt  2026-09-26 11:18:02 Changed by gwy
 */
package gradum.skill

/**
 * Session-scoped registry of MCP tools materialized through the `mcp_tools`
 * directory skill, with round-based retention.
 *
 * Every `mcp_tools` search that materializes matches advances a "round"; each
 * tool it exposes is stamped with the round it was materialized in. A tool
 * stays exposed only while it falls within the last [maxRounds] rounds; once
 * it ages out it is trimmed, so the model's per-turn tool list (and therefore
 * the context size) does not grow without bound across a long session.
 *
 * Trimming is a deliberate trade-off: an aged-out tool becomes invisible and
 * uncallable again (see [gradum.agent.ToolExecutor]) until the model searches
 * for it afresh.
 */
class MaterializedMcpTools(private val maxRounds: Int = DEFAULT_MAX_ROUNDS) {
  private var currentRound: Int = 0
  private val toolsByRound: LinkedHashMap<String, Int> = linkedMapOf()

  /**
   * Advances to a new materialization round and trims every tool whose round
   * is older than the newest [maxRounds] rounds. Call once per materializing
   * `mcp_tools` search, before [add]ing the matches.
   */
  fun newRound() {
    currentRound += 1
    val oldestKeptRound: Int = currentRound - maxRounds + 1
    toolsByRound.entries.removeIf { entry -> entry.value < oldestKeptRound }
  }

  /** Records [name] as materialized in the current round. Re-exposing an
   * already-held tool refreshes its round so it does not age out early. */
  fun add(name: String) {
    toolsByRound[name] = currentRound
  }

  /** True when [name] is currently within the retention window. */
  operator fun contains(name: String): Boolean = toolsByRound.containsKey(name)

  /** Tool names currently within the retention window, in materialization order. */
  val names: Set<String> get() = toolsByRound.keys

  /** True when no tool is currently retained. */
  val isEmpty: Boolean get() = toolsByRound.isEmpty()

  companion object {
    /** Number of materialization rounds retained before older tools are trimmed. */
    const val DEFAULT_MAX_ROUNDS: Int = 8
  }
}
