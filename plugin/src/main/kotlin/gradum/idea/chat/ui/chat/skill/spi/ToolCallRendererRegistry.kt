package gradum.idea.chat.ui.chat.skill.spi

import com.intellij.openapi.diagnostic.logger
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer

/**
 * Lazy-resolution registry for [ToolCallRenderer]s. Built on top of
 * the IntelliJ Platform's `ExtensionPointName` so that
 *
 * - the Gradum plugin's built-in renderers (Ran / Edited / Read /
 *   Saved / Explored / Planned / Completed / Default) are picked up
 *   automatically from `META-INF/plugin.xml`;
 * - any third-party IDE plugin can register an additional
 *   `<toolCallRenderer implementation="…"/>` in its own
 *   `plugin.xml` without touching the Gradum source.
 *
 * The registry is `object`-scoped because extension point data is
 * process-global — there is no need to inject it through DI.
 */
object ToolCallRendererRegistry {

    private val logger = logger<ToolCallRendererRegistry>()

    /**
     * In-memory cache, keyed by alias. Lazily populated on the first
     * `find` call to avoid hitting the extension point on every chat
     * tick. Subsequent `find` calls are O(1).
     */
    private val renderersByAlias: MutableMap<String, ToolCallRenderer> = HashMap()
    private var defaultRenderer: ToolCallRenderer? = null
    @Volatile private var initialized: Boolean = false

    /**
     * Look up the renderer registered for [alias]. Falls back to the
     * renderer registered for the special alias `"*"` (the default
     * catch-all) when no specific renderer is found, so the chat UI
     * never has to special-case the empty state.
     *
     * Returns `null` only if the extension point is empty AND no
     * default renderer is registered. In a normal Gradum build this
     * is impossible because [gradum.idea.chat.ui.chat.skill.default.DefaultRenderer]
     * is always registered.
     */
    fun find(alias: String): ToolCallRenderer? {
        ensureInitialized()
        return renderersByAlias[alias] ?: defaultRenderer
    }

    /**
     * Force a re-read of the extension point. Useful in tests that
     * register mock renderers; production code should never need to
     * call this.
     */
    fun reset() {
        synchronized(this) {
            renderersByAlias.clear()
            defaultRenderer = null
            initialized = false
        }
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val extensions: List<ToolCallRenderer> =
                ToolCallRenderer.EP_NAME.extensionList
            for (renderer in extensions) {
                val alias: String = renderer.alias()
                if (alias == DEFAULT_ALIAS) {
                    if (defaultRenderer == null) {
                        defaultRenderer = renderer
                    } else {
                        logger.warn(
                            "Multiple default (alias='*') ToolCallRenderer " +
                                "extensions registered; ignoring duplicate from " +
                                renderer::class.qualifiedName
                        )
                    }
                } else {
                    val existing: ToolCallRenderer? = renderersByAlias[alias]
                    if (existing == null) {
                        renderersByAlias[alias] = renderer
                    } else {
                        logger.warn(
                            "Multiple ToolCallRenderer extensions for alias " +
                                "'$alias' (existing: ${existing::class.qualifiedName}, " +
                                "duplicate: ${renderer::class.qualifiedName}); " +
                                "keeping first registration"
                        )
                    }
                }
            }
            initialized = true
        }
    }

    private const val DEFAULT_ALIAS: String = "*"
}
