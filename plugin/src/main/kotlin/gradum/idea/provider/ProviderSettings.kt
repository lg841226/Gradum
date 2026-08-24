/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderSettings.kt  2026-08-25 01:43:26 Changed by gwy
 */

package gradum.idea.provider

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persisted, app-level provider configuration.
 *
 * Owns the IntelliJ-saved XML state for URLs / API keys and a parallel
 * in-memory [MutableState] so Compose can recompose without polling.
 * All edits must go through [update] so the shared env file the
 * embedded server reads stays in sync.
 */
@State(
  name = "GradumProviderSettings",
  storages = [Storage("gradum.xml")]
)
@Service(Service.Level.APP)
class ProviderSettings : PersistentStateComponent<ProviderSettings.State> {

  data class State(
    var ollamaApiKey: String = "",
    var lmStudioApiKey: String = "",
    var ollamaBaseUrl: String = "http://localhost:11434",
    var lmStudioBaseUrl: String = "http://localhost:1234",
    var pollIntervalSeconds: Int = 5,
    var ollamaAutoFilter: Boolean = true,
    var autoDetectEnabled: Boolean = true,
    var lmStudioAllowRemote: Boolean = false,
    var zhipuEnabled: Boolean = false,
    var zhipuApiKey: String = "",
    var zhipuBaseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    var deepseekEnabled: Boolean = false,
    var deepseekApiKey: String = "",
    var deepseekBaseUrl: String = "https://api.deepseek.com/v1",
    var minimaxEnabled: Boolean = false,
    var minimaxApiKey: String = "",
    var minimaxBaseUrl: String = "https://api.minimaxi.com/v1",
  ) {
    fun configFor(kind: ProviderKind): Pair<String, String> = when (kind) {
      ProviderKind.OLLAMA -> ollamaBaseUrl to ollamaApiKey
      ProviderKind.LM_STUDIO -> lmStudioBaseUrl to lmStudioApiKey
      ProviderKind.ZHIPU -> zhipuBaseUrl to zhipuApiKey
      ProviderKind.DEEPSEEK -> deepseekBaseUrl to deepseekApiKey
      ProviderKind.MINIMAX -> minimaxBaseUrl to minimaxApiKey
    }

    fun setBaseUrl(kind: ProviderKind, value: String) {
      when (kind) {
        ProviderKind.OLLAMA -> ollamaBaseUrl = value
        ProviderKind.LM_STUDIO -> lmStudioBaseUrl = value
        ProviderKind.ZHIPU -> zhipuBaseUrl = value
        ProviderKind.DEEPSEEK -> deepseekBaseUrl = value
        ProviderKind.MINIMAX -> minimaxBaseUrl = value
      }
    }

    fun setApiKey(kind: ProviderKind, value: String) {
      when (kind) {
        ProviderKind.OLLAMA -> ollamaApiKey = value
        ProviderKind.LM_STUDIO -> lmStudioApiKey = value
        ProviderKind.ZHIPU -> zhipuApiKey = value
        ProviderKind.DEEPSEEK -> deepseekApiKey = value
        ProviderKind.MINIMAX -> minimaxApiKey = value
      }
    }

    fun isEnabled(kind: ProviderKind): Boolean = when (kind) {
      ProviderKind.OLLAMA, ProviderKind.LM_STUDIO -> true
      ProviderKind.ZHIPU -> zhipuEnabled
      ProviderKind.DEEPSEEK -> deepseekEnabled
      ProviderKind.MINIMAX -> minimaxEnabled
    }

    fun setEnabled(kind: ProviderKind, enabled: Boolean) {
      when (kind) {
        ProviderKind.OLLAMA, ProviderKind.LM_STUDIO -> Unit
        ProviderKind.ZHIPU -> zhipuEnabled = enabled
        ProviderKind.DEEPSEEK -> deepseekEnabled = enabled
        ProviderKind.MINIMAX -> minimaxEnabled = enabled
      }
    }
  }

  private val current: MutableState<State> = mutableStateOf(State())

  val snapshot: State
    get() = current.value

  override fun getState(): State = current.value

  override fun loadState(state: State) {
    current.value = state
  }

  /**
   * Apply [transform] to a copy of the current state and publish it.
   *
   * [persistToDisk] is `true` by default and mirrors URL / API key
   * edits into the shared env file the server reads. Toggle-only edits
   * should pass `false` to avoid a synchronous disk write on the UI
   * thread.
   */
  fun update(
    transform: (State) -> Unit,
    persistToDisk: Boolean = true,
  ) {
    val next: State = current.value.copy()
    transform(next)
    current.value = next
    if (persistToDisk) syncConfigFile(state = next)
  }

  fun resetToDefaults() {
    current.value = State()
    syncConfigFile(state = current.value)
  }

  private fun syncConfigFile(state: State) {
    ProviderConfigFile.updateProviderConfig(configKey = "ollama", state.ollamaBaseUrl, state.ollamaApiKey)
    ProviderConfigFile.updateProviderConfig(configKey = "lmstudio", state.lmStudioBaseUrl, state.lmStudioApiKey)
    ProviderKind.cloudKinds.forEach { kind: ProviderKind ->
      val (url: String, key: String) = state.configFor(kind)
      if (state.isEnabled(kind)) {
        ProviderConfigFile.updateProviderConfig(kind.configKey, baseUrl = url, apiKey = key)
      } else {
        ProviderConfigFile.removeProviderConfig(kind.configKey)
      }
    }
  }

  companion object {
    fun getInstance(): ProviderSettings =
      ApplicationManager.getApplication().getService(ProviderSettings::class.java)
  }
}
