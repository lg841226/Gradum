package gradum.utils

private val ANSI_ESCAPE: Regex = Regex("\u001B\\[[0-9;]*[A-Za-z]")

/**
 * Cleans raw command output for structured rendering: strips ANSI escape
 * sequences (colors, cursor moves from progress bars) and normalizes
 * CR/CRLF line endings to LF.
 */
fun normalizeCommandOutput(text: String): String =
  text.replace(ANSI_ESCAPE, "").replace("\r\n", "\n").replace("\r", "\n")

/**
 * Splits [text] into lines after normalization, dropping blank lines at the
 * start and end while preserving blank lines in the middle.
 */
fun trimmedOutputLines(text: String): List<String> {
  val lines: List<String> = normalizeCommandOutput(text).split("\n")
  var start: Int = 0
  var end: Int = lines.size
  while (start < end && lines[start].isBlank()) start++
  while (end > start && lines[end - 1].isBlank()) end--
  return lines.subList(start, end)
}
