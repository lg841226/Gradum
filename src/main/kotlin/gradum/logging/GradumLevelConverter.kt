/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumLevelConverter.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * Renders a single-letter log level with ANSI background coloring.
 *
 * Follows the Logcat style with background highlighting:
 * - TRACE: dim gray
 * - DEBUG: blue background, white text
 * - INFO:  green background, black text
 * - WARN:  yellow background, black text
 * - ERROR: red background, white text
 */
class GradumLevelConverter : ClassicConverter() {

  override fun convert(event: ILoggingEvent?): String {
    val level: Level = event?.level ?: Level.DEBUG

    return when (level.levelInt) {
      Level.ERROR_INT -> ansi(BG_RED, FG_WHITE, letter = "E")
      Level.TRACE_INT -> ansi(BG_GRAY, FG_WHITE, letter = "T")
      Level.DEBUG_INT -> ansi(BG_BLUE, FG_WHITE, letter = "D")
      Level.INFO_INT -> ansi(BG_GREEN, FG_BLACK, letter = "I")
      Level.WARN_INT -> ansi(BG_YELLOW, FG_BLACK, letter = "W")
      else -> "?"
    } + ANSI_RESET
  }

  private fun ansi(bg: String, fg: String, letter: String): String =
    "$fg$bg $letter $ANSI_RESET"

  companion object {
    private const val ANSI_RESET = "\u001B[0m"
    private const val FG_BLACK = "\u001B[30m"
    private const val FG_WHITE = "\u001B[37m"
    private const val BG_GRAY = "\u001B[100m"
    private const val BG_BLUE = "\u001B[104m"
    private const val BG_GREEN = "\u001B[102m"
    private const val BG_YELLOW = "\u001B[103m"
    private const val BG_RED = "\u001B[101m"
  }
}
