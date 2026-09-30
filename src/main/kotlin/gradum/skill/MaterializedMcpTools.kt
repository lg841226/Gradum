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
 *
 * newRound() advances to a new materialization round and trims every tool
 * whose round is older than the newest maxRounds rounds: call it once per
 * materializing `mcp_tools` search, before add() registers the matches.
 * add(name) stamps a tool as materialized in the current round; re-exposing
 * an already-held tool refreshes its round so it does not age out early.
 * contains(name) reports whether a tool is within the retention window,
 * names lists the retained tool names in materialization order, and isEmpty
 * reports whether nothing is retained. DEFAULT_MAX_ROUNDS (8) is the number
 * of rounds retained before older tools are trimmed.
 */
class MaterializedMcpTools(private val maxRounds: Int = DEFAULT_MAX_ROUNDS) {
  private var currentRound: Int = 0
  private val toolsByRound: LinkedHashMap<String, Int> = linkedMapOf()

  fun newRound() {
    currentRound += 1
    val oldestKeptRound: Int = currentRound - maxRounds + 1
    toolsByRound.entries.removeIf { entry -> entry.value < oldestKeptRound }
  }

  fun add(name: String) {
    toolsByRound[name] = currentRound
  }

  operator fun contains(name: String): Boolean = toolsByRound.containsKey(name)

  val names: Set<String> get() = toolsByRound.keys

  val isEmpty: Boolean get() = toolsByRound.isEmpty()

  companion object {
    const val DEFAULT_MAX_ROUNDS: Int = 8
  }
}
