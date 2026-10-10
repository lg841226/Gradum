package gradum.server

import gradum.utils.JsonUtil
import java.io.File

private const val INDENT: String = "  "
private const val ROW_INDENT: String = "   "
private const val LABEL_JOIN: String = " : "
private const val GLYPH_ACTIVE: String = "*"
private const val ESC: String = "\u001B"
private const val GLYPH_DOT: String = "\u00B7"
private const val DOT_JOIN: String = "  $GLYPH_DOT  "
private const val ANSI_MUTED: String = "$ESC[38;5;245m"
private const val ANSI_GREEN: String = "$ESC[38;2;74;222;128m"
private const val GLYPH_RULE: Char = '\u2500'
private const val RULE_WIDTH: Int = 64
private const val LABEL_WIDTH: Int = 10
private const val PROVIDER_KIND_WIDTH: Int = 7
private const val PROVIDER_NAME_WIDTH: Int = 11
private const val API_KEY_LABEL: String = "API key"
private const val THINKING_LABEL: String = "Thinking"

/**
 * One selectable provider. [key] is the lowercase config key used for the
 * `<key>.baseUrl` / `<key>.apiKey` settings keys (see `ProviderConfigStore`);
 * [baseUrl] is the value pre-filled when nothing is configured yet.
 */
internal class ProviderChoice(
  val key: String,
  val kind: String,
  val label: String,
  val baseUrl: String
)

internal val PROVIDER_CHOICES: List<ProviderChoice> = listOf(
  ProviderChoice("ollama", "local", "Ollama", "http://localhost:11434"),
  ProviderChoice("lmstudio", "local", "LM Studio", "http://localhost:1234"),
  ProviderChoice("minimax", "cloud", "MiniMax", "https://api.minimaxi.com/v1"),
  ProviderChoice("deepseek", "cloud", "DeepSeek", "https://api.deepseek.com/v1"),
  ProviderChoice("zhipu", "cloud", "Zhipu", "https://open.bigmodel.cn/api/paas/v4")
)

/**
 * Line-based fallback provider wizard, used when there is no attached console
 * (piped output, ACP terminal auth without a pty) so [runTuiSetup] cannot draw.
 *
 * Reads the same `../.gradum/settings.json` the server loads and writes back only
 * the keys it touches, so hand-edited `server` / `commandFilter` / `mcpServers`
 * sections survive. The write happens once at the very end, so interrupting the
 * wizard with Ctrl-C leaves the file untouched.
 */
internal fun runSetup() {
  val useColor: Boolean = supportsAnsiColor()
  val settingsFile = File(ServerSettingsStore.configDir(), "settings.json")
  val settingsRoot: MutableMap<String, Any?> = readSettingsRoot(settingsFile)
    ?: run {
      printHeader(useColor)
      println(INDENT + paint("$settingsFile is not valid JSON; fix it first (nothing was changed).", ANSI_MUTED, useColor))
      println()
      return
    }

  printHeader(useColor)

  val selectedProvider: ProviderChoice = chooseProvider(settingsRoot, useColor)
  val baseUrl: String = promptValue(
    label = "Base URL",
    defaultValue = existingString(settingsRoot, "${selectedProvider.key}.baseUrl")
      .ifBlank { selectedProvider.baseUrl },
    useColor = useColor
  )
  val model: String = promptValue(
    label = "Model",
    defaultValue = llmString(settingsRoot, "model"),
    useColor = useColor
  )
  val apiKey: String = promptApiKey(
    skippable = selectedProvider.kind != "cloud",
    useColor = useColor
  )
  val thinking: Boolean = promptThinking(
    defaultValue = llmBoolean(settingsRoot, "think"),
    useColor = useColor
  )

  val llm: MutableMap<String, Any?> = llmGroup(settingsRoot)
  llm["baseUrl"] = baseUrl
  llm["model"] = model
  llm["think"] = thinking
  settingsRoot["${selectedProvider.key}.baseUrl"] = baseUrl

  if (apiKey.isNotBlank())
    settingsRoot["${selectedProvider.key}.apiKey"] = apiKey

  dropLegacyFlatLlmKeys(settingsRoot)
  settingsRoot[ServerSettingsStore.CONFIGURED_KEY] = true
  writeSettingsRoot(settingsFile, settingsRoot)
  printFooter(settingsFile, useColor)
}

private fun printHeader(useColor: Boolean) {
  println()
  println(renderGradientBanner(BANNER_ART, colored = useColor))
  println()
  println(INDENT + paint("Gradum", gradientColor(0f), useColor) + paint(DOT_JOIN + "setup guide", ANSI_MUTED, useColor))
  println(INDENT + divider(useColor))
  println()
}

private fun chooseProvider(settingsRoot: Map<String, Any?>, useColor: Boolean): ProviderChoice {
  val configuredKey: String = PROVIDER_CHOICES
    .firstOrNull { choice -> settingsRoot.containsKey("${choice.key}.baseUrl") }
    ?.key
    .orEmpty()

  println(INDENT + paint("Provider", ANSI_BOLD, useColor))
  PROVIDER_CHOICES.forEachIndexed { index, choice ->
    val activeGlyph =
      if (choice.key == configuredKey) paint(GLYPH_ACTIVE, ANSI_GREEN, useColor)
      else " "

    val nameText = choice.label.padEnd(PROVIDER_NAME_WIDTH)
    val urlText = paint(choice.baseUrl, ANSI_MUTED, useColor)
    val kindText = paint(choice.kind.padEnd(PROVIDER_KIND_WIDTH), ANSI_MUTED, useColor)
    println(ROW_INDENT + activeGlyph + " ${index + 1}  " + nameText + kindText + urlText)
  }
  println()

  while (true) {
    val rawSelection: String = readAnswer(
      prompt = INDENT + paint("Select", ANSI_BOLD, useColor) + " [1-${PROVIDER_CHOICES.size}] "
    )
    val selectionIndex: Int? = rawSelection.toIntOrNull()

    if (rawSelection.isBlank()) {
      val fallbackIndex: Int = PROVIDER_CHOICES.indexOfFirst { it.key == configuredKey }
      return PROVIDER_CHOICES[if (fallbackIndex >= 0) fallbackIndex else 0]
    }
    if (selectionIndex != null && selectionIndex in 1..PROVIDER_CHOICES.size) {
      return PROVIDER_CHOICES[selectionIndex - 1]
    }
    println(INDENT + paint("Enter a number between 1 and ${PROVIDER_CHOICES.size}.", ANSI_MUTED, useColor))
  }
}

private fun promptValue(label: String, defaultValue: String, useColor: Boolean): String {
  val defaultText: String =
    if (defaultValue.isNotBlank()) paint(" ($defaultValue)", ANSI_MUTED, useColor)
    else ""

  val answerText: String = readAnswer(
    prompt = INDENT + fieldLabel(label, useColor) + defaultText + LABEL_JOIN
  )
  return answerText.ifBlank { defaultValue }
}

private fun promptApiKey(skippable: Boolean, useColor: Boolean): String {
  val hintText: String =
    if (skippable) paint(" (blank to skip)", ANSI_MUTED, useColor)
    else ""

  print(INDENT + fieldLabel(API_KEY_LABEL, useColor) + hintText + LABEL_JOIN)

  val systemConsole = System.console()

  return if (systemConsole != null) {
    val secretValue = String(systemConsole.readPassword()).trim()
    println()
    secretValue
  } else {
    readlnOrNull()?.trim().orEmpty()
  }
}

private fun promptThinking(defaultValue: Boolean, useColor: Boolean): Boolean {
  val hintText: String =
    if (defaultValue) "[Y/n]" else "[y/N]"

  val answerText: String = readAnswer(
    prompt = INDENT + fieldLabel(THINKING_LABEL, useColor) + " $hintText" + LABEL_JOIN
  )

  return when (answerText.lowercase()) {
    "" -> defaultValue
    "y", "yes" -> true
    "n", "no" -> false
    else -> defaultValue
  }
}

internal fun printFooter(settingsFile: File, useColor: Boolean) {
  println()
  println(INDENT + divider(useColor))
  println(INDENT + paint("Saved", ANSI_GREEN, useColor) + INDENT + abbreviateHome(settingsFile.path))
  println(INDENT + paint("Start Gradum with `gradum`, or pick it from your IDE's ACP agent list.", ANSI_MUTED, useColor))
  println()
}

private fun readAnswer(prompt: String): String {
  print(prompt)
  return readlnOrNull()?.trim().orEmpty()
}

/** Parses the whole settings file; null when it exists but is not valid JSON. */
internal fun readSettingsRoot(file: File): MutableMap<String, Any?>? =
  try {
    if (file.isFile) JsonUtil.decodeMap(file.readText(Charsets.UTF_8)).toMutableMap()
    else mutableMapOf()
  } catch (_: Exception) {
    null
  }

/**
 * The `llm` section `ServerSettings.load` reads (nested object, not flat
 * `llm.*` keys): created when absent, or handed back when already present.
 */
internal fun llmGroup(settingsRoot: MutableMap<String, Any?>): MutableMap<String, Any?> {
  val current: Any? = settingsRoot["llm"]
  if (current is MutableMap<*, *>) {
    @Suppress("UNCHECKED_CAST")
    return current as MutableMap<String, Any?>
  }
  val group: MutableMap<String, Any?> =
    (current as? Map<*, *>)
      ?.entries
      ?.associateTo(mutableMapOf()) { (key, value) -> key.toString() to value }
      ?: mutableMapOf()
  settingsRoot["llm"] = group
  return group
}

internal fun llmString(settingsRoot: Map<String, Any?>, key: String): String =
  (settingsRoot["llm"] as? Map<*, *>)?.get(key)?.toString().orEmpty()

internal fun llmBoolean(settingsRoot: Map<String, Any?>, key: String): Boolean =
  (settingsRoot["llm"] as? Map<*, *>)?.get(key) as? Boolean ?: false

/**
 * Drops the flat `llm.baseUrl` / `llm.model` / `llm.think` keys older setup
 * runs scattered at the top level: the loader only honors the nested `llm`
 * section, and `TOP_LEVEL_KEYS` flags the flat ones as unknown.
 */
internal fun dropLegacyFlatLlmKeys(settingsRoot: MutableMap<String, Any?>) {
  settingsRoot.remove("llm.baseUrl")
  settingsRoot.remove("llm.model")
  settingsRoot.remove("llm.think")
}

internal fun writeSettingsRoot(file: File, settingsRoot: MutableMap<String, Any?>) {
  file.parentFile?.mkdirs()
  file.writeText(JsonUtil.encodeMap(settingsRoot, prettyPrint = true))
}

internal fun existingString(settingsRoot: Map<String, Any?>, key: String): String =
  settingsRoot[key]?.toString().orEmpty()

private fun fieldLabel(label: String, useColor: Boolean): String =
  paint(label.padEnd(LABEL_WIDTH), ANSI_BOLD, useColor)

private fun paint(text: String, ansi: String, useColor: Boolean): String =
  if (useColor) ansi + text + ANSI_RESET else text

private fun divider(useColor: Boolean): String {
  if (!useColor) return "-".repeat(RULE_WIDTH)
  return buildString {
    for (columnIndex in 0 until RULE_WIDTH) {
      append(gradientColor(columnIndex.toFloat() / (RULE_WIDTH - 1))).append(GLYPH_RULE)
    }
    append(ANSI_RESET)
  }
}

private fun abbreviateHome(fullPath: String): String {
  val homeDirectory: String = System.getProperty("user.home") ?: return fullPath
  return if (fullPath.startsWith(homeDirectory)) "~" + fullPath.removePrefix(homeDirectory) else fullPath
}
