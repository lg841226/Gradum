package gradum.server

/**
 * Shared ANSI styling for Gradum's CLI surfaces: the startup banner and the
 * `setup` wizard. Keeping the palette and the logo here means both surfaces
 * fade across the same blue-to-violet gradient.
 *
 * Colors are 24-bit truecolor and degrade to plain text when the `NO_COLOR`
 * environment variable is set or the process has no console (piped output),
 * so redirected logs stay clean.
 */
internal const val ANSI_BOLD: String = "\u001B[1m"
internal const val ANSI_RESET: String = "\u001B[0m"
internal val BANNER_GRADIENT_TO: IntArray = intArrayOf(168, 85, 247)
internal val BANNER_GRADIENT_FROM: IntArray = intArrayOf(59, 130, 246)

/** Block-glyph logo; [renderGradientBanner] paints the colors. */
internal val BANNER_ART: List<String> = listOf(
  "   ██████  ██████    ██████  ██████   ██    ██ ██      ██",
  "  ██       ██   ██  ██    ██ ██   ██  ██    ██ ███    ███",
  "  ██       ██   ██  ██    ██ ██    ██ ██    ██ ██ ████ ██",
  "  ██   ███ ██████   ████████ ██    ██ ██    ██ ██  ██  ██",
  "  ██    ██ ██  ██   ██    ██ ██   ██  ██    ██ ██      ██",
  "   ██████  ██   ██  ██    ██ ██████    ██████  ██      ██"
)

/** True when stdout is a real terminal and the user has not opted out of color. */
internal fun supportsAnsiColor(): Boolean =
  System.getenv("NO_COLOR").isNullOrEmpty() && System.console() != null

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

internal fun gradientColor(ratio: Float): String {
  val red: Int = interpolateChannel(BANNER_GRADIENT_FROM[0], BANNER_GRADIENT_TO[0], ratio)
  val blue: Int = interpolateChannel(BANNER_GRADIENT_FROM[2], BANNER_GRADIENT_TO[2], ratio)
  val green: Int = interpolateChannel(BANNER_GRADIENT_FROM[1], BANNER_GRADIENT_TO[1], ratio)
  return "\u001B[38;2;$red;$green;${blue}m"
}

private fun interpolateChannel(from: Int, to: Int, ratio: Float): Int =
  (from + (to - from) * ratio).toInt().coerceIn(0, 255)
