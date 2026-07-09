package gradum.idea.chat.ui.chat.skill.spi

/**
 * Built-in user actions that any [ToolCallRenderer] may attach to its
 * [ToolCallContent] without having to ship its own action bar.
 *
 * Third-party renderers are free to invent their own action shapes by
 * subclassing `ToolCallAction.Custom` and pairing the data with a
 * matching render in their composable.
 */
sealed class ToolCallAction {

    /**
     * Open a file at the given absolute or project-relative path in the
     * IDE editor. [startLine] is 1-based; `0` means "don't jump to a
     * specific line". [endLine] is the inclusive end of a range (used
     * by the Read renderer to highlight a multi-line excerpt); `0`
     * means "no specific end line".
     */
    data class OpenInEditor(
        val path: String,
        val startLine: Int = 0,
        val endLine: Int = 0,
        val displayLabel: String? = null,
    ) : ToolCallAction()

    /**
     * Open an inline diff view for a file the tool just modified. The
     * [diffType] is a hint the renderer can use to choose between
     * `DiffViewer`, `MergeRequest` etc.
     */
    data class ViewDiff(
        val path: String,
        val diffType: String = "default",
    ) : ToolCallAction()

    /**
     * Copy a short string to the system clipboard (e.g. the shell
     * command that was just run, or a URL the tool produced).
     */
    data class CopyToClipboard(
        val payload: String,
        val displayLabel: String? = null,
    ) : ToolCallAction()

    /**
     * Free-form action a third-party renderer defines. Pair this with a
     * matching branch in the renderer's `render` composable — the
     * built-in `DefaultRenderer` will ignore unknown custom actions.
     */
    data class Custom(
        val id: String,
        val displayLabel: String,
        val data: Map<String, Any?> = emptyMap(),
    ) : ToolCallAction()
}
