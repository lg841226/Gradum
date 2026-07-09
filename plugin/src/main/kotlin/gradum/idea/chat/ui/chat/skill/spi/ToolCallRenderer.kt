package gradum.idea.chat.ui.chat.skill.spi

import androidx.compose.runtime.Composable
import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.jewel.ui.icon.IconKey
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext

/**
 * SPI for a tool-call row in the chat UI.
 *
 * The Gradum chat panel calls
 * [ToolCallRendererRegistry.find] once per tool invocation and routes
 * the result to the matching renderer's `render` composable. The
 * renderer is responsible for:
 *
 * - Recognising a server-side skill alias via [alias] (e.g. "Ran",
 *   "Edited", "Read", "Saved", "Explored", "Planned", "Completed").
 *   The alias must match the value emitted by the server-side
 *   `Skill.alias`.
 * - Parsing the server `arguments` + `result` JSON into a
 *   [ToolCallContent] view-model via [parseContent].
 * - Choosing the row's icon ([iconKey]) and localised label
 *   ([labelKey], a Gradum resource bundle key under
 *   `messages/gradum/`).
 * - Drawing the actual row via [render]. The default `DefaultRenderer`
 *   shows a uniform fallback for any alias that has no dedicated
 *   renderer registered.
 *
 * Third-party IDE plugins implement this interface and register it
 * under the `gradum.skill.toolCallRenderer` extension point in their
 * own `plugin.xml`:
 *
 * ```xml
 * <extensions defaultExtensionNs="com.gradum.idea">
 *   <toolCallRenderer
 *       implementation="com.example.MyRenderer"/>
 * </extensions>
 * ```
 *
 * No Gradum source change is required to add a new tool call UI.
 */
interface ToolCallRenderer {

    /**
     * Server-side skill alias this renderer handles. Multiple
     * `ToolCallRenderer` extensions for the same alias are
     * unsupported — the registry uses first-registered wins.
     */
    fun alias(): String

    /**
     * Convert the server `arguments` + `result` JSON into a
     * [ToolCallContent]. Returning an empty / minimal content is
     * valid; the registry will not retry with a different renderer.
     */
    fun parseContent(
        arguments: Map<String, Any?>,
        result: Map<String, Any?>,
    ): ToolCallContent

    /**
     * Icon key for the row's status indicator. Default returns null,
     * which lets the chat UI pick a generic `Nodes.Plugin` icon.
     */
    fun iconKey(): IconKey? = null

    /**
     * Gradum resource-bundle key for the localised label (e.g.
     * `"gradum.tool.ran"`). Default returns null, in which case the
     * chat UI falls back to the literal alias string.
     */
    fun labelKey(): String? = null

    /**
     * Compose the actual row. Receives the parsed [content] (output
     * of [parseContent]) and a [ctx] giving access to the active IDE
     * project and the chat-level "open in editor" / "view diff"
     * callbacks.
     */
    @Composable
    fun render(content: ToolCallContent, ctx: ToolCallRenderContext)

    companion object {
        /**
         * Extension point name used by both the Gradum plugin's
         * built-in renderers and any third-party plugin. The
         * namespace `com.gradum.idea` matches `group` in
         * [plugin/build.gradle.kts](file:///Users/gwy/Documents/Code_Project/Gradum/plugin/build.gradle.kts).
         */
        val EP_NAME: ExtensionPointName<ToolCallRenderer> =
            ExtensionPointName("com.gradum.idea.toolCallRenderer")
    }
}
