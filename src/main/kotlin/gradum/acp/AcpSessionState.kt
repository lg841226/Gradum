package gradum.acp

import gradum.agent.Agent
import java.io.BufferedWriter
import kotlin.coroutines.CoroutineContext

internal class AcpSessionState(
  val sessionId: String, val projectRoot: String, defaultModelName: String
) {
  var currentAgent: Agent? = null
  var selectedModelName: String = defaultModelName
  var selectedToolMode: String = "agent"
}

internal data class FrameWriterElement(val writer: BufferedWriter) : CoroutineContext.Element {
  override val key: CoroutineContext.Key<*> = Key

  companion object Key : CoroutineContext.Key<FrameWriterElement>
}
