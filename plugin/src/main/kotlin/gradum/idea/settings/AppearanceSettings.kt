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
import gradum.idea.chat.ui.input.PermissionMode

/**
 * Vertical spacing between assistant response paragraphs.
 */
enum class ParagraphDensity(val storageKey: String) {
  COMPACT("compact"),
  DEFAULT("default"),
  SPACIOUS("spacious");
}

/**
 * Welcome screen layout: how many quick-start suggestions vs recent chats.
 */
enum class WelcomeLayout(val storageKey: String, val quickStartCount: Int, val recentCount: Int) {
  QS0_RC6("qs0_rc6", 0, 6),
  QS1_RC5("qs1_rc5", 1, 5),
  QS2_RC4("qs2_rc4", 2, 4),
  QS3_RC3("qs3_rc3", 3, 3),
  QS4_RC2("qs4_rc2", 4, 2),
  QS5_RC1("qs5_rc1", 5, 1);
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

/** Bounds for the auto-cleanup session age, in days. */
const val MIN_AUTO_CLEANUP_DAYS: Int = 30
const val MAX_AUTO_CLEANUP_DAYS: Int = 365
const val DEFAULT_AUTO_CLEANUP_DAYS: Int = 30

/** Bounds for the message load count per session. */
const val MIN_MESSAGE_LOAD_COUNT: Int = 20
const val MAX_MESSAGE_LOAD_COUNT: Int = 300
const val DEFAULT_MESSAGE_LOAD_COUNT: Int = 100

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
    var collapseThinkingByDefault: Boolean = true,
    var showModelName: Boolean = true,
    var autoScrollToBottom: Boolean = true,
    var codeBlockFontSizeSp: Float = DEFAULT_CODE_BLOCK_FONT_SIZE_SP,
    var showCopyAction: Boolean = true,
    var showRetryAction: Boolean = true,
    var showLikeDislikeAction: Boolean = true,
    var enableStickySections: Boolean = true,
    var welcomeLayout: WelcomeLayout = WelcomeLayout.QS4_RC2,
    var autoCleanupSessions: Boolean = false,
    var autoCleanupDays: Int = 30,
    var messageLoadCount: Int = 100,
    var messageLoadEnabled: Boolean = true,
    var rememberPermission: Boolean = false,
    var rememberContext: Boolean = false,
    var agentEnabled: Boolean = true,
    var gitEnabled: Boolean = true,
    var lastPermission: String = PermissionMode.READONLY,
    var lastContextEnabled: Boolean = false,
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

  fun resetToDefaults() {
    current.value = State()
  }

  companion object {
    fun getInstance(): AppearanceSettings =
      ApplicationManager.getApplication().getService(AppearanceSettings::class.java)
  }
}
