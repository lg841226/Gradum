package gradum.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

private const val ACP_LOG_FILE_NAME: String = "acp.log"
private const val ACP_LOG_RETENTION_DAYS: Int = 7
private const val ACP_LOG_PATTERN: String =
  "%d{yyyy-MM-dd HH:mm:ss.SSS} %gradumPort %gradumLevel %logger{0} %msg%n"

/**
 * Routes every log record to `<home>/.gradum/logs/acp.log` and drops the
 * stderr appender.
 *
 * ACP keeps stdout for JSON-RPC frames, so the launcher console has to stay
 * free of human output. Writing logs to a file keeps the ACP window silent and
 * still leaves the operator something to read in a separate window.
 *
 * Returns the log file path, or null when logback is not the logging backend.
 */
fun routeAcpLogsToFile(): Path? {
  val logbackContext: LoggerContext =
    LoggerFactory.getILoggerFactory() as? LoggerContext ?: return null

  val logDirectory: Path =
    Path.of(System.getProperty("user.home") ?: ".", ".gradum", "logs")
  Files.createDirectories(logDirectory)
  val logFile: Path = logDirectory.resolve(ACP_LOG_FILE_NAME)

  val layoutEncoder = PatternLayoutEncoder().apply {
    this.context = logbackContext
    pattern = ACP_LOG_PATTERN
    charset = StandardCharsets.UTF_8
    start()
  }

  val timeRollingPolicy = TimeBasedRollingPolicy<ILoggingEvent>().apply {
    this.context = logbackContext
    fileNamePattern = logDirectory.resolve("acp.%d{yyyy-MM-dd}.log").toString()
    maxHistory = ACP_LOG_RETENTION_DAYS
  }

  val fileAppender = RollingFileAppender<ILoggingEvent>().apply {
    this.context = logbackContext
    file = logFile.toString()
    encoder = layoutEncoder
    this.rollingPolicy = timeRollingPolicy
  }
  // logback wires the two together in this order when it reads the same
  // appender from XML: the policy needs a parent before it starts, and the
  // appender refuses to start until its triggering policy has.
  timeRollingPolicy.setParent(fileAppender)
  timeRollingPolicy.start()
  fileAppender.start()

  val rootLogger = logbackContext.getLogger(Logger.ROOT_LOGGER_NAME)
  rootLogger.detachAndStopAllAppenders()
  rootLogger.addAppender(fileAppender)
  return logFile
}
