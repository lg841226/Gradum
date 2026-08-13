/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolCallRendererRegistry.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill.spi

import gradum.idea.chat.ui.chat.skill.*
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRendererRegistry.RENDERERS

/**
 * In-process registry of [ToolCallRenderer]s. The Gradum chat panel
 * calls [find] once per tool invocation and dispatches to the
 * matching renderer's `render` composable.
 *
 * **Why a plain code list and not an IntelliJ Platform
 * `ExtensionPoint`?** Because the Platform's `ExtensionPointName`
 * lookup path has several practical drawbacks for our case:
 *
 * - The EP must be declared as a direct child of `<idea-plugin>`,
 *   which is fragile to refactors and easy to break with a copy
 *   / paste of an `<extensions>` block.
 * - EP resolution goes through the IDE's `Extensions` area, which
 *   throws `IllegalArgumentException: Missing extension point` at
 *   the first chat render if anything is misconfigured — a
 *   non-recoverable runtime crash.
 * - The EP cannot be defined per-alias in a way that's easy to
 *   discover: a third-party developer has to read the EP interface
 *   + the Gradum source to learn the convention.
 *
 * A plain `val RENDERERS: List<ToolCallRenderer>` solves all three
 * problems at the cost of one line of code per new alias. Each
 * renderer is a single file under `chat/ui/chat/skill/` named
 * `<Alias>Renderer.kt` and is added to [RENDERERS] with a single
 * line.
 *
 * **Order matters.** The first renderer whose [ToolCallRenderer.alias]
 * matches is used. The default catch-all must be last and use the
 * alias `"*"` (see [DefaultRenderer]).
 */
object ToolCallRendererRegistry {

  /**
   * The exhaustive list of renderers the chat panel will consult.
   *
   * To add a new tool-call row, append a new `Renderer()` instance
   * here and place the file at
   * `chat/ui/chat/skill/<Alias>Renderer.kt`. See
   * `docs/PLUGIN_DEVELOPMENT.md` section 16 for the full tutorial.
   */
  private val RENDERERS: List<ToolCallRenderer> = listOf(
    // Skill-specific renderers. One per server-side `Skill.alias`
    // the Gradum plugin can emit. Order does not matter between
    // specific-alias entries; the wildcard `*` entry must be
    // last because it is the catch-all.
    RanRenderer(),
    EditedRenderer(),
    ReadRenderer(),
    SavedRenderer(),
    ExploredRenderer(),
    GrepRenderer(),
    GlobRenderer(),
    SearchedRenderer(),
    PlannedRenderer(),
    CompletedRenderer(),

    // Catch-all. Must use the literal alias "*" and be the
    // last entry in this list — it handles every alias that
    // has no specific renderer registered.
    DefaultRenderer()
  )

  /**
   * The alias reserved for the wildcard catch-all renderer.
   * Lookups that miss every specific renderer fall back to the
   * renderer whose [ToolCallRenderer.alias] returns this value.
   */
  const val DEFAULT_ALIAS: String = "*"

  /**
   * Look up the renderer registered for [aliasName]. Falls back
   * to the renderer registered for [DEFAULT_ALIAS] (the
   * catch-all) when no specific renderer matches.
   *
   * Returns `null` only if [RENDERERS] is empty AND no default
   * renderer is registered. In a normal Gradum build this is
   * unreachable because [DefaultRenderer] is always present.
   */
  fun find(aliasName: String): ToolCallRenderer? {
    var fallbackRenderer: ToolCallRenderer? = null
    for (currentRenderer in RENDERERS) {
      val rendererAlias: String = currentRenderer.alias()
      if (rendererAlias == aliasName) return currentRenderer
      if (rendererAlias == DEFAULT_ALIAS) fallbackRenderer = currentRenderer
    }
    return fallbackRenderer
  }
}
