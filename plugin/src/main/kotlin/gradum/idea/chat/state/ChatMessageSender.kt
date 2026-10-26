package gradum.idea.chat.state

import gradum.idea.chat.api.AttachmentRenderer
import gradum.idea.chat.api.GradumApiClient
import gradum.idea.chat.model.ChatErrorCode
import gradum.idea.chat.model.ModelInfo
import gradum.idea.chat.model.RenderBlock
import gradum.idea.editor.*
import gradum.idea.provider.ProviderKind
import gradum.idea.provider.ProviderSettings
import gradum.idea.utils.GradumBundle.message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.milliseconds


// Adopts the server-named session id and this turn's user-message id.
internal fun GradumChatSession.applySessionStart(payload: JsonObject?) {
  payload?.get("sessionId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    ?.let { serverSessionId -> activeSessionId = serverSessionId }
  payload?.get("messageId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    ?.let { serverMessageId -> adoptServerMessageId(serverMessageId) }
}

// Stamps the id on this turn's user message, which `rewind` looks up.
private fun GradumChatSession.adoptServerMessageId(serverMessageId: String) {
  val userIndex: Int = messages.indexOfLast { it.isUserMessage }
  if (userIndex >= 0 && messages[userIndex].messageId != serverMessageId) {
    messages[userIndex] = messages[userIndex].copy(messageId = serverMessageId)
  }
}

internal suspend fun GradumChatSession.sendMessage(
  userMessage: String, attachments: List<AttachedContext> = emptyList(),
  contextPath: String = "", toolCallXml: String? = null
) {
  var wasCancelled = false
  var receivedSessionEnd = false
  var streamGapDetected = false
  val modelConfig: Map<String, String> = buildModelConfig()
  val textAttachments: List<AttachedText> = attachments.filterIsInstance<AttachedText>()
  val fileAttachments: List<AttachedFile> = attachments.filterIsInstance<AttachedFile>()
  val symbolAttachments: List<AttachedSymbol> = attachments.filterIsInstance<AttachedSymbol>()
  val attachmentPaths: List<String> = fileAttachments.map { it.file.path } + symbolAttachments.map { it.file.path }
  val prefix: String = buildString {
    if (contextPath.isNotEmpty())
      append("<Context path=\"$contextPath\"/>")
    if (attachmentPaths.isNotEmpty())
      append("<Attachments paths=\"${attachmentPaths.joinToString(separator = ", ")}\"/>")
    textAttachments.forEach {
      append("<Context text=\"${it.content}\"/>")
    }
  }

  val messageWithHint = "${prefix}${userMessage}"

  sendingPhase = message("gradum.phase.synthesizing")
  val sendStart: Long = System.currentTimeMillis()
  var lastFailure: Exception? = null
  var serverReachable = false

  for (attempt: Int in 1..GradumChatSession.MAX_CONNECT_ATTEMPTS) {
    try {
      apiClient.getModels()
      serverReachable = true
      break
    } catch (exception: Exception) {
      if (exception is CancellationException) throw exception
      log.warn(
        "Cannot reach server at ${apiClient.baseUrl} (attempt $attempt/" +
          "$GradumChatSession.MAX_CONNECT_ATTEMPTS)",
        exception
      )
      lastFailure = exception
      if (attempt < GradumChatSession.MAX_CONNECT_ATTEMPTS) {
        sendingPhase = message("gradum.phase.connecting", attempt, GradumChatSession.MAX_CONNECT_ATTEMPTS - 1)
        delay(duration = (GradumChatSession.CONNECT_BACKOFF_MS shl (attempt - 1)).milliseconds)
      }
    }
  }

  if (!serverReachable) {
    log.warn("Cannot reach server at ${apiClient.baseUrl}", lastFailure)
    val assistantIndex: Int = messages.lastIndex
    if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
      messages[assistantIndex] = messages[assistantIndex].appendBlock(
        RenderBlock.Error(
          code = ChatErrorCode.CLIENT_ERROR,
          message = "Cannot reach server at ${apiClient.baseUrl}"
        )
      )
    }
    sendingPhase = ""
    isSending = false
    isWaitingForResponse = false
    processPendingQueue()
    return
  }

  val elapsed: Long = System.currentTimeMillis() - sendStart
  if (elapsed < GradumChatSession.MIN_SENDING_MS)
    delay(duration = (GradumChatSession.MIN_SENDING_MS - elapsed).milliseconds)

  try {
    val loadContext = true
    sendingPhase = message("gradum.phase.distilling")

    val imageAttachments: List<GradumApiClient.ApiImageAttachment> =
      attachments.filterIsInstance<AttachedImage>()
        .map { attachment ->
          GradumApiClient.ApiImageAttachment(
            mime = attachment.mime,
            data = attachment.data,
            filename = attachment.originalName
          )
        }

    val fileApiAttachments: List<GradumApiClient.ApiFileAttachment> =
      (attachments.filterIsInstance<AttachedFile>() + attachments.filterIsInstance<AttachedSymbol>() + attachments.filterIsInstance<AttachedFileErrors>())
        .map { attachment ->
          when (attachment) {
            is AttachedSymbol ->
              GradumApiClient.ApiFileAttachment(
                path = attachment.file.path,
                symbolName = attachment.symbolName,
                endLine = attachment.lineRange.last,
                startLine = attachment.lineRange.first,
                attachmentText = AttachmentRenderer.renderSymbolTag(
                  path = attachment.file.path,
                  symbolName = attachment.symbolName,
                  startLine = attachment.lineRange.first,
                  endLine = attachment.lineRange.last
                )
              )

            is AttachedFile ->
              GradumApiClient.ApiFileAttachment(
                path = attachment.file.path,
                attachmentText = AttachmentRenderer.renderFileTag(path = attachment.file.path)
              )

            is AttachedFileErrors ->
              GradumApiClient.ApiFileAttachment(
                path = attachment.file.path,
                errorCount = attachment.errors.size,
                attachmentText = AttachmentRenderer.renderDiagnosticsTag(
                  path = attachment.file.path,
                  errors = attachment.errors
                )
              )

            else -> throw IllegalArgumentException("Unexpected attachment type: ${attachment.javaClass.simpleName}")
          }
        }

    apiClient.sendMessage(
      request = GradumApiClient.SendMessageRequest(
        toolMode = toolMode,
        modelParams = modelConfig,
        toolCallXml = toolCallXml,
        loadContext = loadContext,
        displayText = userMessage,
        message = messageWithHint,
        sessionId = activeSessionId,
        modelName = selectedModel?.name,
        projectRoot = project?.basePath,
        thinkingLevel = thinkingLevel.name.lowercase(),
        think = true,
        imageAttachments = imageAttachments,
        fileAttachments = fileApiAttachments
      ),
      onStreamGap = { _, _, _ -> streamGapDetected = true }
    ).catch { exception: Throwable ->
      if (exception is CancellationException) {
        cleanupSubAgent()
        val assistantIndex: Int = messages.lastIndex
        if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
          val hasOutput: Boolean = messages[assistantIndex].content.isNotEmpty() ||
            messages[assistantIndex].renderBlocks.isNotEmpty()
          if (hasOutput) {
            wasCancelled = true
            messages[assistantIndex] = messages[assistantIndex].appendBlock(
              RenderBlock.Error(message = "", code = ChatErrorCode.INTERRUPTED)
            )
            sendingPhase = message("gradum.phase.stopped")
          } else {
            messages[assistantIndex] = messages[assistantIndex].appendBlock(
              RenderBlock.Response(content = "\u2026\u2026")
            )
            sendingPhase = ""
          }
        } else sendingPhase = ""
        isSending = false
        return@catch
      }
      log.warn("Failed to send message to ${apiClient.baseUrl}", exception)
      val assistantIndex: Int = messages.lastIndex
      if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
        messages[assistantIndex] = messages[assistantIndex].appendBlock(
          RenderBlock.Error(
            message = exception.message ?: "Connection failed",
            code = ChatErrorCode.CLIENT_ERROR
          )
        )
      }
      isSending = false
      sendingPhase = ""
      processPendingQueue()
    }.collect { event: JsonObject ->
      val messageType: String = event["type"]?.jsonPrimitive?.content ?: return@collect
      val payload: JsonObject? = event["data"]?.jsonObject

      when (messageType) {
        "session_start" -> {
          runId = event["runId"]?.jsonPrimitive?.content
          applySessionStart(payload = payload)
        }

        "response" -> {
          isWaitingForResponse = false
          handleResponseEvent(responseData = payload)
        }

        "thinking" -> {
          isWaitingForResponse = false
          handleThinkingEvent(data = payload)
        }

        "tool_call_start" -> handleToolCallStartEvent(data = payload)
        "tool_call" -> handleToolCallEvent(data = payload)

        "tool_expect_mismatch" -> {
          isWaitingForResponse = false
          handleToolExpectMismatch(data = payload)
        }

        "error" -> handleErrorEvent(data = payload)
        "sub_agent:start" -> handleSubAgentStart(data = payload)
        "sub_agent:response" -> handleSubAgentResponse(data = payload)
        "sub_agent:tool_call" -> handleSubAgentToolCall(data = payload)
        "sub_agent:error" -> handleSubAgentError(data = payload)
        "sub_agent:session_end" -> handleSubAgentEnd(data = payload)
        "ask_interaction" -> handleAskInteractionEvent(data = payload)

        "session_end" -> {
          receivedSessionEnd = true
          isWaitingForResponse = false
          isSending = false
          currentJob = null
          runId = null
          sendingPhase = ""
          processPendingQueue()
        }
      }
    }

    if (streamGapDetected && receivedSessionEnd) {
      val sessionKey: String? = activeSessionId
      if (sessionKey != null) {
        log.warn("Stream gap in session $sessionKey; adopting server projection")
        adoptServerProjection(sessionId = sessionKey)
      }
    }
  } catch (exception: Exception) {
    if (exception is CancellationException) {
      cleanupSubAgent()
      val assistantIndex: Int = messages.lastIndex
      if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
        val hasOutput: Boolean = messages[assistantIndex].content.isNotEmpty() ||
          messages[assistantIndex].renderBlocks.isNotEmpty()
        if (hasOutput) {
          wasCancelled = true
          messages[assistantIndex] = messages[assistantIndex].appendBlock(
            RenderBlock.Error(message = "", code = ChatErrorCode.INTERRUPTED)
          )
          sendingPhase = message("gradum.phase.stopped")
        } else {
          messages[assistantIndex] = messages[assistantIndex].appendBlock(
            RenderBlock.Response(content = "\u2026\u2026")
          )
          sendingPhase = ""
        }
      } else {
        sendingPhase = ""
      }
      isSending = false
      isWaitingForResponse = false
      return
    }
    log.warn("Streaming interrupted for ${apiClient.baseUrl}", exception)
    val assistantIndex: Int = messages.lastIndex
    if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
      messages[assistantIndex] = messages[assistantIndex].appendBlock(
        RenderBlock.Error(
          message = exception.message ?: "Streaming interrupted", code = ChatErrorCode.CLIENT_ERROR
        )
      )
    }
    sendingPhase = ""
    isSending = false
    isWaitingForResponse = false
    processPendingQueue()
  } finally {
    if (!receivedSessionEnd) {
      if (!wasCancelled) {
        sendingPhase = ""
      }
      isSending = false
      currentJob = null
      runId = null
      isWaitingForResponse = false
      processPendingQueue()
    }
  }
}

private fun GradumChatSession.buildModelConfig(): Map<String, String> {
  val requestParams = mutableMapOf<String, String>()
  val snapshot = ProviderSettings.getInstance().snapshot

  selectedModel?.let { model: ModelInfo ->
    if (model.provider.isNotBlank()) requestParams["provider"] = model.provider
    if (model.server.isNotBlank()) requestParams["baseUrl"] = model.server

    if (snapshot.keepAliveEnabled)
      requestParams["keepAliveMinutes"] = snapshot.keepAliveMinutes.toString()

    val configuredKind: ProviderKind? = ProviderKind.entries.firstOrNull { kind: ProviderKind ->
      snapshot.isEnabled(kind) && model.server.isNotBlank() &&
        snapshot.configFor(kind).first.trimEnd('/') == model.server.trimEnd('/')
    }
    configuredKind?.let { kind: ProviderKind ->
      val key: String = snapshot.configFor(kind).second
      if (key.isNotBlank()) requestParams["apiKey"] = key
    }
  }
  return requestParams
}
