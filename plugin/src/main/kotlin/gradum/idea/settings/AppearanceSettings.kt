/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AppearanceSettings.kt  2026-08-20 11:21:57 Changed by gwy
 */

package gradum.idea.settings

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Vertical spacing between assistant response paragraphs.
 */
enum class ParagraphDensity(val storageKey: String) {
  COMPACT("compact"),
  DEFAULT("default"),
  SPACIOUS("spacious");
}

/** Bounds for the configurable assistant body font size, in sp. */
const val MIN_PARAGRAPH_FONT_SIZE_SP: Float = 8f
const val MAX_PARAGRAPH_FONT_SIZE_SP: Float = 16f
const val DEFAULT_PARAGRAPH_FONT_SIZE_SP: Float = 14f

/** Bounds for the configurable code-block font size, in sp. */
const val MIN_CODE_BLOCK_FONT_SIZE_SP: Float = 8f
const val MAX_CODE_BLOCK_FONT_SIZE_SP: Float = 16f

/** Sentinel meaning "follow the IDE editor font size" for code blocks. */
const val CODE_BLOCK_FONT_SIZE_AUTO_SP: Float = 0f
const val DEFAULT_CODE_BLOCK_FONT_SIZE_SP: Float = CODE_BLOCK_FONT_SIZE_AUTO_SP

/**
 * Persisted, app-level appearance configuration.
 *
 * Holds the value in a Compose [MutableState] so the chat UI recomposes
 * immediately when the density setting changes. Persistence is wired
 * through [PersistentStateComponent]; the settings dialog applies edits
 * via [update].
 */
@State(
  name = "GradumAppearanceSettings",
  storages = [Storage("gradum.xml")]
)

@Service(Service.Level.APP)
class AppearanceSettings : PersistentStateComponent<AppearanceSettings.State> {

  data class State(
    var paragraphDensity: ParagraphDensity = ParagraphDensity.DEFAULT,
    var paragraphFontSizeSp: Float = DEFAULT_PARAGRAPH_FONT_SIZE_SP,
    var showTimestamp: Boolean = true,
    var collapseThinkingByDefault: Boolean = false,
    var showModelName: Boolean = true,
    var autoScrollToBottom: Boolean = true,
    var codeBlockFontSizeSp: Float = DEFAULT_CODE_BLOCK_FONT_SIZE_SP,
    var showCopyAction: Boolean = true,
    var showRetryAction: Boolean = true,
    var showLikeDislikeAction: Boolean = true,
  )

  private val current: MutableState<State> = mutableStateOf(State())

  val snapshot: State get() = current.value

  override fun getState(): State = current.value

  override fun loadState(state: State) {
    current.value = state
  }

  fun update(transform: (State) -> Unit) {
    val next: State = current.value.copy()
    transform(next)
    current.value = next
  }

  companion object {
    fun getInstance(): AppearanceSettings =
      ApplicationManager.getApplication().getService(AppearanceSettings::class.java)
  }
}
