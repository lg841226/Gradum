/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolCallRenderer.kt  2026-07-09 17:55:26 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill.spi

import androidx.compose.runtime.Composable
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * Renderer for a tool-call row in the chat UI.
 *
 * The Gradum chat panel calls
 * [ToolCallRendererRegistry.find] once per tool invocation and
 * routes the result to the matching renderer's `render` composable.
 * The renderer is responsible for:
 *
 * - Recognising a server-side skill alias via [alias] (e.g. `"Ran"`,
 *   `"Edited"`, `"Read"`, `"Saved"`, `"Explored"`, `"Planned"`,
 *   `"Completed"`). The alias must match the value emitted by the
 *   server-side `Skill.alias`.
 * - Parsing the server `arguments` + `result` JSON into a
 *   [ToolCallContent] view-model via [parseContent].
 * - Choosing the row's icon ([iconKey]) and localised label
 *   ([labelKey], a Gradum resource-bundle key under
 *   `messages/GradumBundle*.properties`).
 * - Drawing the actual row via [render]. The default `DefaultRenderer`
 *   (alias `"*"`) shows a uniform fallback for any alias that has
 *   no dedicated renderer.
 *
 * To add a new tool-call row, see
 * [ToolCallRendererRegistry] — it holds the explicit list of
 * renderer instances the chat panel consults. New code lives in
 * its own folder under `chat/ui/chat/skill/<alias>/` and is
 * registered with a single line in
 * [ToolCallRendererRegistry.RENDERERS].
 */
interface ToolCallRenderer {

  /**
   * Server-side skill alias this renderer handles. Multiple
   * renderers for the same alias are unsupported — the registry
   * uses first-registered wins.
   */
  fun alias(): String

  /**
   * Convert the server `arguments` + `result` JSON into a
   * [ToolCallContent]. Returning an empty / minimal content is
   * valid; the registry will not retry with a different renderer.
   */
  fun parseContent(arguments: Map<String, Any?>, result: Map<String, Any?>): ToolCallContent

  /**
   * Icon key for the row's status indicator. Default returns
   * `null`, which lets the chat UI pick a generic `Nodes.Plugin`
   * icon.
   */
  fun iconKey(): IconKey? = null

  /**
   * Gradum resource-bundle key for the localised label (e.g.
   * `"gradum.tool.ran"`). Default returns `null`, in which case
   * the chat UI falls back to the literal alias string.
   */
  fun labelKey(): String? = null

  /**
   * Compose the actual row. Receives the parsed [content]
   * (output of [parseContent]) and a [ctx] giving access to the
   * active IDE project and the chat-level "open in editor" /
   * "view diff" / "copy" callbacks.
   */
  @Composable
  fun render(content: ToolCallContent, ctx: ToolCallRenderContext)
}
