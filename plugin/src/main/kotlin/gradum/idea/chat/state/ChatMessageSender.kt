/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatMessageSender.kt  2026-09-24 23:15:04 Changed by gwy
 */

package gradum.idea.chat.state

import gradum.idea.chat.api.GradumApiClient
import gradum.idea.chat.history.ChatSessionStore
import gradum.idea.chat.model.ChatEvent
import gradum.idea.chat.model.ErrorCode
import gradum.idea.chat.model.ModelInfo
import gradum.idea.chat.ui.util.ThinkingPromptInjector
import gradum.idea.editor.AttachedContext
import gradum.idea.editor.AttachedFile
import gradum.idea.editor.AttachedImage
import gradum.idea.editor.AttachedText
import gradum.idea.provider.ProviderKind
import gradum.idea.provider.ProviderSettings
import gradum.idea.utils.GradumBundle.message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.milliseconds

/**
 * Raw response from the `/models` endpoint, containing the list of discovered LLM models.
 */
@Serializable
internal data class ModelsListResponse(
  val models: List<ModelInfo>
)

internal suspend fun GradumChatSession.sendMessage(
  userMessage: String, attachments: List<AttachedContext> = emptyList(),
  contextPath: String = "", toolCallXml: String? = null, messageId: String? = null
) {
  var wasCancelled = false
  var receivedSessionEnd = false
  val modelConfig: Map<String, String> = buildModelConfig()
  val textAttachments: List<AttachedText> = attachments.filterIsInstance<AttachedText>()
  val attachmentPaths: List<String> = attachments.filterIsInstance<AttachedFile>().map { it.file.path }
  val prefix: String = buildString {
    if (contextPath.isNotEmpty())
      append("<Context path=\"$contextPath\"/>")
    if (attachmentPaths.isNotEmpty())
      append("<Attachments paths=\"${attachmentPaths.joinToString(separator = ", ")}\"/>")
    textAttachments.forEach {
      append("<Context text=\"${it.content}\"/>")
    }
  }

  val systemRule = """
      <Rule>
        - Answer in English by default. Use another language only if the user asks.
        - No use emojis in anywhere (eg: Code or Text).
        - Do not use text-based drawings.
      </Rule>
  """.trimIndent()

  val messageWithHint: String = "${prefix}${userMessage}\n\n$systemRule" +
    "\n\n${ThinkingPromptInjector.guideFor(thinkingLevel)}"

  sendingPhase = message("gradum.phase.synthesizing")
  val validationStart: Long = System.currentTimeMillis()
  var lastFailure: Exception? = null
  var response: ModelsListResponse? = null

  for (attempt: Int in 1..GradumChatSession.MAX_CONNECT_ATTEMPTS) {
    try {
      val modelsJson: String = apiClient.getModels()
      response = GradumChatSession.jsonFormat.decodeFromString<ModelsListResponse>(modelsJson)
      break
    } catch (exception: Exception) {
      if (exception is CancellationException) throw exception
      log.warn(
        "Model validation failed for ${apiClient.baseUrl} (attempt $attempt/" +
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
  val currentModel: ModelInfo? = selectedModel

  if (response != null && currentModel != null && response.models.none {
      it.name == currentModel.name
    }) {
    val assistantIndex: Int = messages.lastIndex

    if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
      messages[assistantIndex] = messages[assistantIndex].appendEvent(
        ChatEvent.Error(
          code = ErrorCode.CLIENT_ERROR.code,
          message = "Model ${currentModel.name} is no longer available"
        )
      )
    }
    sendingPhase = ""
    isSending = false
    isWaitingForResponse = false
    processPendingQueue()
    return
  }

  if (response == null) {
    log.warn("Cannot reach server at ${apiClient.baseUrl}", lastFailure)
    val assistantIndex: Int = messages.lastIndex
    if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
      messages[assistantIndex] = messages[assistantIndex].appendEvent(
        ChatEvent.Error(
          code = ErrorCode.CLIENT_ERROR.code,
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

  // Ensure the "Sending" animation is visible for at least MIN_SENDING_MS.
  val elapsed: Long = System.currentTimeMillis() - validationStart
  if (elapsed < GradumChatSession.MIN_SENDING_MS)
    delay(duration = (GradumChatSession.MIN_SENDING_MS - elapsed).milliseconds)

  try {
    val loadContext = true
    sendingPhase = message("gradum.phase.distilling")

    if (activeSessionId == null) activeSessionId = ChatSessionStore.nextSessionId()

    val imageAttachments: List<GradumApiClient.ApiImageAttachment> =
      attachments.filterIsInstance<AttachedImage>()
        .map { attachment ->
          GradumApiClient.ApiImageAttachment(
            mime = attachment.mime,
            data = attachment.data,
            filename = attachment.originalName
          )
        }

    if (imageAttachments.isNotEmpty() && selectedModel?.attachment != true) {
      val userIndex: Int = messages.lastIndex
      if (userIndex >= 0 && messages[userIndex].isUserMessage)
        messages.removeAt(userIndex)

      val assistantIndex: Int = messages.lastIndex
      if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
        messages[assistantIndex] = messages[assistantIndex].appendEvent(
          ChatEvent.Error(
            code = ErrorCode.CLIENT_ERROR.code,
            message = message("gradum.model.no.vision")
          )
        )
      }
      isSending = false
      sendingPhase = ""
      return
    }

    apiClient.sendMessage(
      request = GradumApiClient.SendMessageRequest(
        message = messageWithHint,
        modelName = selectedModel?.name,
        modelParams = modelConfig,
        loadContext = loadContext,
        toolMode = toolMode,
        projectRoot = project?.basePath,
        imageAttachments = imageAttachments,
        toolCallXml = toolCallXml,
        sessionId = activeSessionId,
        messageId = messageId
      )
    ).catch { exception: Throwable ->
      if (exception is CancellationException) {
        cleanupSubAgent()
        val assistantIndex: Int = messages.lastIndex
        if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
          val hasOutput: Boolean = messages[assistantIndex].content.isNotEmpty() ||
            messages[assistantIndex].events.isNotEmpty()
          if (hasOutput) {
            wasCancelled = true
            messages[assistantIndex] = messages[assistantIndex].appendEvent(
              ChatEvent.Error(message = "", code = ErrorCode.INTERRUPTED.code)
            )
            sendingPhase = message("gradum.phase.stopped")
          } else {
            messages[assistantIndex] = messages[assistantIndex].appendEvent(
              ChatEvent.Response(content = "\u2026\u2026")
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
        messages[assistantIndex] = messages[assistantIndex].appendEvent(
          ChatEvent.Error(
            message = exception.message ?: "Connection failed",
            code = ErrorCode.CLIENT_ERROR.code
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
          sessionId = event["sessionId"]?.jsonPrimitive?.content
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
        "ask_interaction" -> handleAskInteractionEvent(eventData = payload)

        "session_end" -> {
          receivedSessionEnd = true
          isWaitingForResponse = false
          isSending = false
          currentJob = null
          sessionId = null
          sendingPhase = ""
          saveCurrentSession()
          processPendingQueue()
        }
      }
    }
  } catch (exception: Exception) {
    if (exception is CancellationException) {
      cleanupSubAgent()
      val assistantIndex: Int = messages.lastIndex
      if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
        val hasOutput: Boolean = messages[assistantIndex].content.isNotEmpty() ||
          messages[assistantIndex].events.isNotEmpty()
        if (hasOutput) {
          wasCancelled = true
          messages[assistantIndex] = messages[assistantIndex].appendEvent(
            ChatEvent.Error(message = "", code = ErrorCode.INTERRUPTED.code)
          )
          sendingPhase = message("gradum.phase.stopped")
        } else {
          messages[assistantIndex] = messages[assistantIndex].appendEvent(
            ChatEvent.Response(content = "\u2026\u2026")
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
      messages[assistantIndex] = messages[assistantIndex].appendEvent(
        ChatEvent.Error(
          message = exception.message ?: "Streaming interrupted", code = ErrorCode.CLIENT_ERROR.code
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
      sessionId = null
      isWaitingForResponse = false
      saveCurrentSession()
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
      requestParams["keepAlive"] = snapshot.keepAlive.trim().ifEmpty { "5m" }

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
