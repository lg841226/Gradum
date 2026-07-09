package gradum.idea.chat.ui.chat.skill.spi

import gradum.idea.chat.ui.chat.skill.completed.CompletedRenderer
import gradum.idea.chat.ui.chat.skill.default.DefaultRenderer
import gradum.idea.chat.ui.chat.skill.edited.EditedRenderer
import gradum.idea.chat.ui.chat.skill.explored.ExploredRenderer
import gradum.idea.chat.ui.chat.skill.planned.PlannedRenderer
import gradum.idea.chat.ui.chat.skill.ran.RanRenderer
import gradum.idea.chat.ui.chat.skill.read.ReadRenderer
import gradum.idea.chat.ui.chat.skill.saved.SavedRenderer

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
 * renderer lives in its own folder (`chat/ui/chat/skill/<alias>/`)
 * and is added to [RENDERERS] with a single line.
 *
 * **Order matters.** The first renderer whose [ToolCallRenderer.alias]
 * matches is used. The default catch-all must be last and use the
 * alias `"*"` (see [gradum.idea.chat.ui.chat.skill.default.DefaultRenderer]).
 */
object ToolCallRendererRegistry {

    /**
     * The exhaustive list of renderers the chat panel will consult.
     *
     * To add a new tool-call row, append a new `Renderer()` instance
     * here and place the file under
     * `chat/ui/chat/skill/<alias>/<Alias>Renderer.kt`. See
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
        PlannedRenderer(),
        CompletedRenderer(),

        // Catch-all. Must use the literal alias "*" and be the
        // last entry in this list — it handles every alias that
        // has no specific renderer registered.
        DefaultRenderer(),
    )

    /**
     * The alias reserved for the wildcard catch-all renderer.
     * Lookups that miss every specific renderer fall back to the
     * renderer whose [ToolCallRenderer.alias] returns this value.
     */
    const val DEFAULT_ALIAS: String = "*"

    /**
     * Look up the renderer registered for [alias]. Falls back to
     * the renderer registered for [DEFAULT_ALIAS] (the catch-all)
     * when no specific renderer matches.
     *
     * Returns `null` only if [RENDERERS] is empty AND no default
     * renderer is registered. In a normal Gradum build this is
     * unreachable because [DefaultRenderer] is always present.
     */
    fun find(alias: String): ToolCallRenderer? {
        var fallback: ToolCallRenderer? = null
        for (renderer in RENDERERS) {
            val rendererAlias = renderer.alias()
            if (rendererAlias == alias) return renderer
            if (rendererAlias == DEFAULT_ALIAS) fallback = renderer
        }
        return fallback
    }
}
