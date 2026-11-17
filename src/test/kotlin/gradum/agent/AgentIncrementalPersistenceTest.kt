package gradum.agent

import gradum.AgentConfiguration
import gradum.client.LLMResponseChunk
import gradum.client.LlmClient
import gradum.client.TokenUsageSnapshot
import gradum.client.ToolCallEntry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.fail

/**
 * The transcript must land on disk at message-completion nodes, not only when
 * the run finishes: a stream cancelled mid-flight (client refresh) has to keep
 * everything produced so far instead of nothing.
 */
class AgentIncrementalPersistenceTest {

  @Test
  fun `user turn and finished assistant turns are persisted before the run ends`() {
    val project = Files.createTempDirectory("gradum_incremental_")
    val sessionId = "incremental-session"
    val transcript = project.resolve(".gradum").resolve("sessions").resolve(sessionId)
      .resolve("conversation.md")

    val toolCall = ToolCallEntry(
      functionName = "read_file",
      callIdentifier = "call-1",
      functionArguments = mapOf("path" to JsonPrimitive(value = "does-not-exist.txt")),
    )
    var midRunTranscript: String? = null
    var sendChatCallCount = 0
    val mockClient: LlmClient = mockk {
      every {
        sendChat(
          messageHistory = any(),
          toolDefinitions = any()
        )
      } answers {
        sendChatCallCount++
        if (sendChatCallCount == 1) {
          flowOf(
            LLMResponseChunk.TextContent("first answer"),
            LLMResponseChunk.ToolCallBatch(toolCalls = listOf(toolCall)),
          )
        } else {
          // The second turn starts only after the first assistant message and
          // its tool results were persisted; read the file mid-run to prove it.
          midRunTranscript = if (Files.isRegularFile(transcript)) Files.readString(transcript) else null
          flowOf(value = LLMResponseChunk.TextContent("done"))
        }
      }
      every { tokenUsage } returns TokenUsageSnapshot()
    }

    val agent = Agent(
      llmClient = mockClient,
      configuration = AgentConfiguration(projectRoot = project.toString(), sessionId = sessionId),
    ) { _, _ -> }

    agent.executeTask(userInput = "the user question")

    val midRun: String = midRunTranscript
      ?: fail("transcript should already exist while the run is still streaming")
    assertContains(midRun, "the user question", message = "the user turn must be persisted at run start")
    assertContains(midRun, "first answer", message = "the finished assistant turn must be persisted mid-run")

    // The normal completion path must not regress: the final transcript holds
    // the last turn as well.
    val finalTranscript: String = Files.readString(transcript)
    assertContains(finalTranscript, "done")
  }
}
