/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderSettings.kt  2026-08-15 18:30:00 Changed by gwy
 */
package gradum.idea.provider

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

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
  )

  private val current: MutableState<State> = mutableStateOf(State())

  val snapshot: State
    get() = current.value

  override fun getState(): State = current.value

  override fun loadState(state: State) {
    current.value = state
  }

  fun update(transform: (State) -> Unit) {
    val old = current.value
    val copy = old.copy()
    transform(copy)
    current.value = copy
  }

  companion object {
    fun getInstance(): ProviderSettings =
      ApplicationManager.getApplication().getService(ProviderSettings::class.java)
  }
}
