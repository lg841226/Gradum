/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumPortConverter.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.logging

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import java.lang.management.ManagementFactory.getRuntimeMXBean

/**
 * Renders the process PID and the server port as `PID-PORT`.
 *
 * PID is obtained from the JVM runtime bean; PORT is read from the
 * system property `gradum.server.port` (set by [gradum.server.main]).
 * Falls back to `????` when the property is absent (e.g. during tests).
 */
class GradumPortConverter : ClassicConverter() {
  private val pid: String =
    try {
      val jvmName: String = getRuntimeMXBean().name
      jvmName.substringBefore(delimiter = "@")
    } catch (_: Exception) {
      "????"
    }

  override fun convert(event: ILoggingEvent?): String {
    val port: String = System.getProperty("gradum.server.port", "????")
    return "$pid-$port"
  }
}
