package gradum.mode

/**
 * Where a tool is registered from. The `$mcp` and `$external` selectors in a
 * mode's `tools` section match on this category; everything not matched by a
 * selector is treated as a plain skill name (exact or `*` glob).
 */
enum class ToolOrigin {
  MCP,
  BUILTIN,
  EXTERNAL;
}

/**
 * The three permission gates a mode can switch off independently. The former
 * `YOLO` mode is just the all-false corner of this cube: every gate that used
 * to short-circuit on `isYolo` now reads its own flag.
 */
data class ModeGates(
  val approvals: Boolean = true,
  val commandFilter: Boolean = true,
  val protectedPaths: Boolean = true
)

/**
 * One fully-resolved agent mode: what the model may see (`tools`), what the
 * user must approve (`gates`), and which prompt fragment speaks (`prompt`).
 *
 * Modes are declarative JSON under `~/.gradum/modes/` with the built-in four
 * shipped as jar resources in the same format; see [ModeRegistry].
 */
data class AgentMode(
  val id: String,
  val label: String,
  val description: String,
  val aliases: List<String> = emptyList(),
  val allow: List<String> = listOf(SELECTOR_ALL),
  val deny: List<String> = emptyList(),
  val gates: ModeGates = ModeGates(),
  val prompt: String = id,
) {

  /**
   * Whether [skillName] (registered from [origin]) is on this mode's tool
   * surface. `deny` is checked first and always wins over `allow`, so a
   * broad `allow: ["*"]` plus targeted denials expresses every built-in
   * mode without listing the whole skill catalogue.
   */
  fun allowsTool(skillName: String, origin: ToolOrigin = ToolOrigin.BUILTIN): Boolean {
    if (deny.any { selector -> selectorMatches(selector, skillName, origin) }) return false
    return allow.any { selector -> selectorMatches(selector, skillName, origin) }
  }

  companion object {
    const val SELECTOR_ALL: String = "*"
    const val SELECTOR_MCP: String = $$"$mcp"
    const val SELECTOR_EXTERNAL: String = $$"$external"

    internal fun selectorMatches(selector: String, skillName: String, origin: ToolOrigin): Boolean =
      when {
        selector == SELECTOR_MCP -> origin == ToolOrigin.MCP
        selector == SELECTOR_EXTERNAL -> origin == ToolOrigin.EXTERNAL
        selector == SELECTOR_ALL -> true
        selector.contains('*') -> globToRegex(selector).matches(skillName)
        else -> selector == skillName
      }

    private fun globToRegex(glob: String): Regex {
      val pattern: String = buildString {
        for (character: Char in glob) {
          if (character == '*') append(".*") else append(Regex.escape(character.toString()))
        }
      }
      return Regex(pattern)
    }
  }
}
