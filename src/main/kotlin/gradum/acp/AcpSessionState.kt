package gradum.acp

import gradum.ToolMode
import gradum.agent.Agent
import java.io.BufferedWriter
import kotlin.coroutines.CoroutineContext

/**
 * Per-session mutable state tracked by the ACP server. Created on
 * `session/new` and looked up by sessionId for every subsequent request.
 */
internal class AcpSessionState(
  val sessionId: String, val projectRoot: String, defaultModelName: String
) {
  var currentAgent: Agent? = null
  var selectedModelName: String = defaultModelName
  var selectedToolMode: ToolMode = ToolMode.AGENT
}

/**
 * Coroutine context element carrying the BufferedWriter for the current
 * frame, so prompt handlers can stream `session/update` notifications back
 * over the same stdio stream that received the request.
 */
internal data class FrameWriterElement(val writer: BufferedWriter) : CoroutineContext.Element {
  override val key: CoroutineContext.Key<*> = Key

  companion object Key : CoroutineContext.Key<FrameWriterElement>
}
