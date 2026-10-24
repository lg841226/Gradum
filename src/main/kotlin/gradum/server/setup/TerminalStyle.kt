package gradum.server.setup

// Shared ANSI styling for Gradum's CLI surfaces: the startup banner and the
// `setup` wizard. Keeping the palette and the logo here means both surfaces
// fade across the same blue-to-violet gradient.
//
// Colors are 24-bit truecolor and degrade to plain text only when the
// `NO_COLOR` environment variable is set. Escape codes are still emitted when
// stdout is not a TTY (e.g. the IntelliJ run console, where System.console()
// is null but ANSI renders fine).
internal const val ANSI_BOLD: String = "\u001B[1m"
internal const val ANSI_RESET: String = "\u001B[0m"
internal val BANNER_GRADIENT_TO: IntArray = intArrayOf(168, 85, 247)
internal val BANNER_GRADIENT_FROM: IntArray = intArrayOf(59, 130, 246)

internal val BANNER_ART: List<String> = listOf(
  "   ██████  ██████    ██████  ██████   ██    ██ ██      ██",
  "  ██       ██   ██  ██    ██ ██   ██  ██    ██ ███    ███",
  "  ██       ██   ██  ██    ██ ██    ██ ██    ██ ██ ████ ██",
  "  ██   ███ ██████   ████████ ██    ██ ██    ██ ██  ██  ██",
  "  ██    ██ ██  ██   ██    ██ ██   ██  ██    ██ ██      ██",
  "   ██████  ██   ██  ██    ██ ██████    ██████  ██      ██"
)

// True unless the user opted out of color with NO_COLOR.
internal fun supportsAnsiColor(): Boolean = System.getenv("NO_COLOR").isNullOrEmpty()

internal fun renderGradientBanner(art: List<String>, colored: Boolean = true): String {
  val lastColumn: Int = (art.maxOf { line -> line.length } - 1).coerceAtLeast(1)

  if (!colored) return art.joinToString(separator = "\n")

  return art.joinToString(separator = "\n") { line ->
    buildString {
      line.forEachIndexed { column, glyph ->
        if (glyph == ' ') append(' ')
        else append(gradientColor(column.toFloat() / lastColumn)).append(glyph)
      }
      append(ANSI_RESET)
    }
  }
}

private const val G_GLYPH_WIDTH: Int = 10

internal fun renderStartupLogo(colored: Boolean, width: Int): List<String> {
  val glyph: List<String> = BANNER_ART.map { line -> line.take(G_GLYPH_WIDTH) }
  val lastColumn: Int = (glyph.maxOf { line -> line.length } - 1).coerceAtLeast(1)

  return glyph.map { line ->
    val padLeft: Int = ((width - line.length) / 2).coerceAtLeast(0)
    val padRight: Int = (width - line.length - padLeft).coerceAtLeast(0)
    if (!colored) return@map " ".repeat(padLeft) + line + " ".repeat(padRight)

    buildString {
      append(" ".repeat(padLeft))
      line.forEachIndexed { column, glyph ->
        if (glyph == ' ') append(' ')
        else append(gradientColor(column.toFloat() / lastColumn)).append(glyph)
      }
      append(ANSI_RESET)
      append(" ".repeat(padRight))
    }
  }
}

internal fun gradientColor(ratio: Float): String {
  val red: Int = interpolateChannel(BANNER_GRADIENT_FROM[0], BANNER_GRADIENT_TO[0], ratio)
  val blue: Int = interpolateChannel(BANNER_GRADIENT_FROM[2], BANNER_GRADIENT_TO[2], ratio)
  val green: Int = interpolateChannel(BANNER_GRADIENT_FROM[1], BANNER_GRADIENT_TO[1], ratio)
  return "\u001B[38;2;$red;$green;${blue}m"
}

private fun interpolateChannel(from: Int, to: Int, ratio: Float): Int =
  (from + (to - from) * ratio).toInt().coerceIn(0, 255)
