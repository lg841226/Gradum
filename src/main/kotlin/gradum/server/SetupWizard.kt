package gradum.server

import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.NonBlockingReader
import java.io.File

private const val ESC: String = "\u001B"
private const val ERASE_LINE: String = "$ESC[K"
private const val CURSOR_HOME: String = "$ESC[H"
private const val CLEAR_TO_END: String = "$ESC[J"
private const val ANSI_REVERSE: String = "$ESC[7m"
private const val HIDE_CURSOR: String = "$ESC[?25l"
private const val SHOW_CURSOR: String = "$ESC[?25h"
private const val ALT_SCREEN_ON: String = "$ESC[?1049h"
private const val ANSI_MUTED: String = "$ESC[38;5;245m"
private const val ALT_SCREEN_OFF: String = "$ESC[?1049l"
private const val ANSI_GREEN: String = "$ESC[38;2;74;222;128m"
private const val GLYPH_POINTER: String = "\u25B8"
private const val GLYPH_DONE: String = "\u2713"
private const val GLYPH_MASK: String = "\u2022"
private const val GLYPH_DOT: String = "\u00B7"
private const val GLYPH_RULE: Char = '\u2500'
private const val GLYPH_ACTIVE: String = "*"
private const val ARROW_LEFT: String = "\u2190"
private const val ARROW_RIGHT: String = "\u2192"
private const val ARROW_UP: String = "\u2191"
private const val ARROW_DOWN: String = "\u2193"
private const val ARROWS_LR: String = ARROW_LEFT + ARROW_RIGHT
private const val ARROWS_UD: String = ARROW_UP + ARROW_DOWN
private const val KEY_TAB: Int = 9
private const val KEY_ESC: Int = 27
private const val KEY_CTRL_C: Int = 3
private const val KEY_BACKSPACE: Int = 8
private const val KEY_ENTER_LF: Int = 10
private const val KEY_ENTER_CR: Int = 13
private const val KEY_BACKSPACE_DEL: Int = 127
private const val ESCAPE_SEQUENCE_TIMEOUT_MS: Long = 80L
private const val LINE_SEPARATOR: String = "\r\n"
private const val RULE_WIDTH: Int = 64
private const val LABEL_WIDTH: Int = 10
private const val PROVIDER_KIND_WIDTH: Int = 7
private const val PROVIDER_NAME_WIDTH: Int = 11
private const val INDENT: String = "  "
private const val TAB_GAP: String = "    "
private const val INNER_GAP: String = "   "
private const val EMPTY_GLYPH: String = " "
private const val ROW_INDENT: String = "   "
private const val LABEL_JOIN: String = " : "
private const val NOT_SET_TEXT: String = "(not set)"
private const val DOT_JOIN: String = "  $GLYPH_DOT  "
private const val AUTO_MODEL_TEXT: String = "auto (first available)"
private const val ACTIVE_SUFFIX: String = "  $GLYPH_ACTIVE"
private const val HINT_LOCAL_SKIP: String = "  (blank to skip for local)"
private val STEP_NAMES: List<String> = listOf("Provider", "Credentials", "Connection", "Review")
private const val STEP_REVIEW: Int = 3
private const val STEP_PROVIDER: Int = 0
private const val STEP_CONNECTION: Int = 2
private const val STEP_CREDENTIALS: Int = 1

private sealed interface Key {
  data object Up : Key
  data object Tab : Key
  data object Esc : Key
  data object Down : Key
  data object Left : Key
  data object Quit : Key
  data object Right : Key
  data object Enter : Key
  data object Ignore : Key
  data object ShiftTab : Key
  data object Backspace : Key
  data class Text(val value: Char) : Key
}

private enum class NavResult { CONTINUE, SAVED, QUIT }

/**
 * Full-screen, four-step provider wizard reached via `gradum setup` from a real
 * terminal. Drawn with JLine in raw mode on the alternate screen so the user's
 * scrollback is untouched; the terminal is always restored, including on crash
 * through a shutdown hook.
 *
 * The model is intentionally not configured here: the server auto-discovers it
 * at runtime (see `ModelIdentity.discoverModels`). Only the provider, its API
 * key, and the base URL are written, and only once at the end.
 *
 * Returns false when there is no attached console (piped output, ACP terminal
 * auth without a pty) so the caller can fall back to the line-based wizard.
 */
internal fun runTuiSetup(): Boolean {
  if (System.console() == null) return false

  val terminal: Terminal =
    try {
      TerminalBuilder.builder().system(true).build()
    } catch (_: Exception) {
      return false
    }

  val settingsFile = File(ServerSettingsStore.configDir(), "settings.json")
  val root: MutableMap<String, Any?> = readSettingsRoot(settingsFile)
    ?: run {
      println("Cannot parse $settingsFile; fix the JSON first (nothing was changed).")
      return true
    }
  val colored: Boolean = System.getenv("NO_COLOR").isNullOrEmpty()

  val shutdownHook = Thread { restoreTerminal(terminal) }
  Runtime.getRuntime().addShutdownHook(shutdownHook)
  try {
    terminal.enterRawMode()
    val writer = terminal.writer()
    writer.print(ALT_SCREEN_ON)
    writer.flush()

    val state = WizardState(root)
    val saved: Boolean = runEventLoop(terminal, state, colored)

    writer.print(SHOW_CURSOR)
    writer.print(ALT_SCREEN_OFF)
    writer.flush()

    if (saved) {
      val llm: MutableMap<String, Any?> = llmGroup(root)
      llm["baseUrl"] = state.baseUrl
      llm["think"] = state.thinking
      root["${state.provider.key}.baseUrl"] = state.baseUrl
      if (state.apiKey.isNotBlank()) root["${state.provider.key}.apiKey"] = state.apiKey
      dropLegacyFlatLlmKeys(root)
      writeSettingsRoot(settingsFile, root)
      printFooter(settingsFile, colored)
    }
    return true
  } finally {
    restoreTerminal(terminal)
    try {
      Runtime.getRuntime().removeShutdownHook(shutdownHook)
    } catch (_: IllegalStateException) {
      // Already shutting down; the hook is running or about to run.
    }
  }
}

private fun restoreTerminal(terminal: Terminal) {
  try {
    val writer = terminal.writer()
    writer.print(SHOW_CURSOR)
    writer.print(ALT_SCREEN_OFF)
    writer.flush()
    terminal.close()
  } catch (_: Exception) {
    // Best effort: never let cleanup mask the original failure.
  }
}

private fun runEventLoop(terminal: Terminal, state: WizardState, colored: Boolean): Boolean {
  val reader: NonBlockingReader = terminal.reader()
  val writer = terminal.writer()

  while (true) {
    writer.print(CURSOR_HOME)
    writer.print(HIDE_CURSOR)
    writer.print(buildFrame(state, colored))
    writer.print(CLEAR_TO_END)
    writer.flush()

    val pressedKey: Key = readKey(reader)
    if (pressedKey == Key.Quit) return false

    if (state.editing) {
      applyEditingKey(state, pressedKey)
      continue
    }

    when (handleNavigation(state, pressedKey)) {
      NavResult.SAVED -> return true
      NavResult.QUIT -> return false
      NavResult.CONTINUE -> Unit
    }
  }
}

private fun applyEditingKey(state: WizardState, pressedKey: Key) {
  when (pressedKey) {
    Key.Left -> state.moveCursor(-1)
    Key.Right -> state.moveCursor(1)
    Key.Esc -> state.cancelEdit()
    Key.Enter -> state.commitEdit()
    Key.Backspace -> state.deleteBackward()
    is Key.Text -> state.insertChar(pressedKey.value)
    else -> Unit
  }
}

private fun handleNavigation(state: WizardState, pressedKey: Key): NavResult {
  when (pressedKey) {
    Key.Up -> state.moveFocus(-1)
    Key.Down -> state.moveFocus(1)
    Key.Right, Key.Tab -> state.moveStep(1)
    Key.Left, Key.ShiftTab -> state.moveStep(-1)
    Key.Enter ->
      return if (handleEnter(state)) NavResult.SAVED
      else NavResult.CONTINUE

    Key.Esc -> return NavResult.QUIT
    else -> Unit
  }
  return NavResult.CONTINUE
}

/** Returns true when Review confirmed the save. */
private fun handleEnter(state: WizardState): Boolean {
  return when (state.step) {
    STEP_PROVIDER -> {
      state.moveStep(1)
      false
    }

    STEP_CREDENTIALS -> {
      if (state.focus == 1) state.toggleThinking() else state.startEdit()
      false
    }

    STEP_CONNECTION -> {
      state.startEdit()
      false
    }

    STEP_REVIEW -> true
    else -> false
  }
}

private fun readKey(reader: NonBlockingReader): Key {
  return when (val firstKey: Int = reader.read()) {
    KEY_TAB -> Key.Tab
    KEY_ESC -> readEscape(reader)
    KEY_ENTER_CR, KEY_ENTER_LF -> Key.Enter
    NonBlockingReader.EOF, KEY_CTRL_C -> Key.Quit
    KEY_BACKSPACE, KEY_BACKSPACE_DEL -> Key.Backspace
    else -> if (firstKey in 32..126) Key.Text(firstKey.toChar()) else Key.Ignore
  }
}

private fun readEscape(reader: NonBlockingReader): Key {
  val secondKey: Int = reader.read(ESCAPE_SEQUENCE_TIMEOUT_MS)

  if (secondKey == NonBlockingReader.READ_EXPIRED || secondKey == NonBlockingReader.EOF)
    return Key.Esc
  if (secondKey != '['.code && secondKey != 'O'.code)
    return Key.Esc

  return when (reader.read(ESCAPE_SEQUENCE_TIMEOUT_MS)) {
    'A'.code -> Key.Up
    'B'.code -> Key.Down
    'C'.code -> Key.Right
    'D'.code -> Key.Left
    'Z'.code -> Key.ShiftTab
    else -> Key.Ignore
  }
}

/** Mutable wizard state: chosen provider plus one focus index per step. */
private class WizardState(private val root: Map<String, Any?>) {

  val configuredProviderKey: String = PROVIDER_CHOICES
    .firstOrNull { root.containsKey("${it.key}.baseUrl") }
    ?.key
    .orEmpty()

  var providerIndex: Int = PROVIDER_CHOICES
    .indexOfFirst { it.key == configuredProviderKey }
    .takeIf { it >= 0 }
    ?: 0
  val provider: ProviderChoice get() = PROVIDER_CHOICES[providerIndex]
  val model: String = llmString(root, "model")
  var apiKey: String = existingString(root, "${provider.key}.apiKey")
  var baseUrl: String = existingString(root, "${provider.key}.baseUrl").ifBlank { provider.baseUrl }
  var thinking: Boolean = llmBoolean(root, "think")
  var focus: Int = 0
  var cursor: Int = 0
  var editing: Boolean = false
  var step: Int = STEP_PROVIDER
  private val editBuffer: StringBuilder = StringBuilder()

  fun fieldCount(): Int = when (step) {
    STEP_PROVIDER -> PROVIDER_CHOICES.size
    STEP_CREDENTIALS -> 2
    STEP_CONNECTION -> 1
    else -> 0
  }

  fun moveStep(delta: Int) {
    editing = false
    step = (step + delta).coerceIn(0, STEP_NAMES.size - 1)
    focus = if (step == STEP_PROVIDER) providerIndex else 0
  }

  fun moveFocus(delta: Int) {
    if (step == STEP_PROVIDER) {
      moveProvider(delta)
      focus = providerIndex
      return
    }
    val fieldTotal = fieldCount()
    if (fieldTotal == 0) return
    focus = ((focus + delta) % fieldTotal + fieldTotal) % fieldTotal
  }

  private fun moveProvider(delta: Int) {
    val providerTotal = PROVIDER_CHOICES.size
    val nextIndex = ((providerIndex + delta) % providerTotal + providerTotal) % providerTotal
    if (nextIndex == providerIndex) return
    providerIndex = nextIndex
    baseUrl = existingString(root, "${provider.key}.baseUrl").ifBlank { provider.baseUrl }
    apiKey = existingString(root, "${provider.key}.apiKey")
  }

  fun toggleThinking() {
    thinking = !thinking
  }

  fun startEdit() {
    val initialText =
      when (step) {
        STEP_CONNECTION -> baseUrl
        STEP_CREDENTIALS if focus == 0 -> apiKey
        else -> ""
      }
    editBuffer.setLength(0)
    editBuffer.append(initialText)
    cursor = editBuffer.length
    editing = true
  }

  fun commitEdit() {
    val editedText = editBuffer.toString()
    when (step) {
      STEP_CONNECTION -> baseUrl = editedText
      STEP_CREDENTIALS if focus == 0 -> apiKey = editedText
      else -> Unit
    }
    editing = false
  }

  fun cancelEdit() {
    editing = false
  }

  fun moveCursor(delta: Int) {
    cursor = (cursor + delta).coerceIn(0, editBuffer.length)
  }

  fun deleteBackward() {
    if (cursor == 0) return
    editBuffer.deleteCharAt(cursor - 1)
    cursor -= 1
  }

  fun insertChar(value: Char) {
    editBuffer.insert(cursor, value)
    cursor += 1
  }

  fun editingValue(): String = editBuffer.toString()
}

private fun buildFrame(state: WizardState, colored: Boolean): String {
  val frameLines: MutableList<String> = mutableListOf()
  frameLines += ""
  renderGradientBanner(BANNER_ART, colored).split("\n").forEach { frameLines += it }
  frameLines += ""
  frameLines += INDENT + paint("Gradum", gradientColor(0f), colored) +
    paint(DOT_JOIN + "setup", ANSI_MUTED, colored)
  frameLines += INDENT + headerDivider(colored)
  frameLines += ""
  frameLines += stepTabLine(state, colored)
  frameLines += ""
  renderStep(state, colored, frameLines)
  frameLines += ""
  frameLines += hintLine(state, colored)
  frameLines += ""
  return frameLines.joinToString(separator = ERASE_LINE + LINE_SEPARATOR) + ERASE_LINE
}

private fun stepTabLine(state: WizardState, colored: Boolean): String {
  val tabLabels = STEP_NAMES.mapIndexed { index, name ->
    val labelText = "${index + 1} $name"
    when {
      index == state.step -> paint(labelText, gradientColor(0f) + ANSI_BOLD, colored)
      index < state.step -> paint(GLYPH_DONE + EMPTY_GLYPH + labelText, ANSI_MUTED, colored)
      else -> labelText
    }
  }
  return INDENT + tabLabels.joinToString(separator = TAB_GAP)
}

private fun renderStep(state: WizardState, colored: Boolean, frameLines: MutableList<String>) {
  when (state.step) {
    STEP_PROVIDER -> renderProviders(state, colored, frameLines)

    STEP_CREDENTIALS -> {
      frameLines += fieldRow(body = apiKeyBody(state, colored), label = "API key", fieldIndex = 0, colored, state)
      frameLines += fieldRow(body = thinkingBody(state, colored), label = "Thinking", fieldIndex = 1, colored, state)
    }

    STEP_CONNECTION -> frameLines += fieldRow(
      body = baseUrlBody(state, colored), label = "Base URL", fieldIndex = 0, colored, state
    )

    STEP_REVIEW -> renderReview(state, colored, frameLines)
  }
}

private fun renderProviders(state: WizardState, colored: Boolean, frameLines: MutableList<String>) {
  PROVIDER_CHOICES.forEachIndexed { index, choice ->
    val focused = index == state.focus
    val kindText = paint(choice.kind.padEnd(PROVIDER_KIND_WIDTH), ANSI_MUTED, colored)
    val urlText = paint(choice.baseUrl, ANSI_MUTED, colored)

    val nameText = paint(
      choice.label.padEnd(PROVIDER_NAME_WIDTH),
      if (focused) ANSI_BOLD else ANSI_MUTED, colored
    )

    val activeText =
      if (choice.key == state.configuredProviderKey) paint(ACTIVE_SUFFIX, ANSI_GREEN, colored)
      else ""

    frameLines += ROW_INDENT + marker(focused, colored) + EMPTY_GLYPH + nameText + kindText + urlText + activeText
  }
}

private fun fieldRow(
  body: String,
  label: String,
  fieldIndex: Int,
  colored: Boolean,
  state: WizardState
): String {
  val focused = state.focus == fieldIndex
  return ROW_INDENT + marker(focused, colored) + EMPTY_GLYPH + labelColumn(label, focused, colored) + body
}

private fun apiKeyBody(state: WizardState, colored: Boolean): String {
  val editingHere = state.editing && state.focus == 0
  val bodyText =
    if (editingHere) editingBody(state, masked = true)
    else displayBody(state.apiKey, masked = true, colored)

  return if (state.provider.kind == "cloud") bodyText
  else bodyText + paint(HINT_LOCAL_SKIP, ANSI_MUTED, colored)
}

private fun baseUrlBody(state: WizardState, colored: Boolean): String {
  val editingHere = state.editing && state.focus == 0

  return if (editingHere) editingBody(state, masked = false)
  else displayBody(state.baseUrl, masked = false, colored)
}

private fun thinkingBody(state: WizardState, colored: Boolean): String {
  val boxText = if (state.thinking) "[x]" else "[ ]"
  return boxText + EMPTY_GLYPH + paint(state.thinking.toString(), ANSI_MUTED, colored)
}

private fun displayBody(value: String, masked: Boolean, colored: Boolean): String {
  val shownText = if (masked) GLYPH_MASK.repeat(value.length) else value
  return shownText.ifEmpty { paint(NOT_SET_TEXT, ANSI_MUTED, colored) }
}

private fun editingBody(state: WizardState, masked: Boolean): String {
  val rawValue = state.editingValue()
  val shownText =
    if (masked) GLYPH_MASK.repeat(rawValue.length)
    else rawValue

  val beforeText = shownText.take(state.cursor)
  val afterText = shownText.drop(state.cursor + 1)
  val cursorGlyph = shownText.getOrNull(state.cursor)?.toString() ?: EMPTY_GLYPH
  return beforeText + ANSI_REVERSE + cursorGlyph + ANSI_RESET + afterText
}

private fun renderReview(state: WizardState, colored: Boolean, frameLines: MutableList<String>) {
  val keyValue =
    if (state.apiKey.isBlank()) "not set"
    else GLYPH_MASK.repeat(state.apiKey.length)

  frameLines += reviewLine("Provider", "${state.provider.label}  (${state.provider.kind})", colored)
  frameLines += reviewLine("Base URL", state.baseUrl, colored)
  frameLines += reviewLine("Model", state.model.ifBlank { AUTO_MODEL_TEXT }, colored)
  frameLines += reviewLine("API key", keyValue, colored)
  frameLines += reviewLine("Thinking", state.thinking.toString(), colored)
}

private fun reviewLine(label: String, value: String, colored: Boolean): String {
  val muted = value.startsWith("auto") || value == "not set"
  val valueText = if (muted) paint(value, ANSI_MUTED, colored) else value
  val labelText = paint(label.padEnd(LABEL_WIDTH) + LABEL_JOIN, ANSI_MUTED, colored)

  return ROW_INDENT + labelText + valueText
}

private fun hintLine(state: WizardState, colored: Boolean): String {
  val hintText =
    when {
      state.editing -> "$ARROWS_LR cursor${INNER_GAP}Backspace delete${INNER_GAP}Enter confirm${INNER_GAP}Esc cancel"
      state.step == STEP_REVIEW -> "Enter save & exit${INNER_GAP}Esc exit without saving"
      state.step == STEP_CONNECTION -> "Tab/$ARROWS_LR steps${INNER_GAP}Enter edit${INNER_GAP}Esc quit"
      else -> "Tab/$ARROWS_LR steps${INNER_GAP}$ARROWS_UD move${INNER_GAP}Enter select${INNER_GAP}Esc quit"
    }
  return INDENT + paint(hintText, ANSI_MUTED, colored)
}

private fun marker(focused: Boolean, colored: Boolean): String =
  if (focused) paint(GLYPH_POINTER, gradientColor(0f), colored)
  else EMPTY_GLYPH

private fun labelColumn(label: String, focused: Boolean, colored: Boolean): String =
  paint(
    label.padEnd(LABEL_WIDTH) + LABEL_JOIN,
    if (focused) ANSI_BOLD else ANSI_MUTED, colored
  )

private fun paint(text: String, ansi: String, colored: Boolean): String =
  if (colored) ansi + text + ANSI_RESET
  else text

private fun headerDivider(colored: Boolean): String {
  if (!colored) return "-".repeat(RULE_WIDTH)

  return buildString {
    for (column in 0 until RULE_WIDTH) {
      append(gradientColor(column.toFloat() / (RULE_WIDTH - 1))).append(GLYPH_RULE)
    }
    append(ANSI_RESET)
  }
}
