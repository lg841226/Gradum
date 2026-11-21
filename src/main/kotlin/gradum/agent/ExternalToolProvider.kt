package gradum.agent

import gradum.skill.Skill

interface ExternalToolProvider {
  fun adapterFor(name: String): Skill?
  fun fullSchemas(names: Set<String>, toolMode: String): List<Map<String, Any>>
  fun toolCount(): Int
}

object ExternalToolRegistry {
  @Volatile
  var provider: ExternalToolProvider? = null
}
