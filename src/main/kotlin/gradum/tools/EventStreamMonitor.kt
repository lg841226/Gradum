package gradum.tools

import gradum.logging.EventStreamDump
import gradum.server.setup.ServerSettingsStore
import gradum.utils.JsonUtil
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*

private const val MAX_LINES: Int = 5_000
private const val POLL_INTERVAL_MILLIS: Long = 200L
private const val MAX_READ_BYTES: Long = 8L * 1024L * 1024L
private val logger = LoggerFactory.getLogger(EventStreamMonitor::class.java)


/**
 * Swing tail for the debug dump written by [EventStreamDump]: follows the
 * file, splits it on newlines and re-renders each JSON object indented, so a
 * one-line NDJSON event reads as a multi-line block.
 *
 * Run with `./gradlew eventMonitor`. The server must have `debug.eventDump`
 * turned on in `~/.gradum/settings.json` for the file to receive anything.
 */
class EventStreamMonitor : JFrame("Gradum Event Stream Monitor") {
  private val isDumpEnabled: Boolean = ServerSettingsStore.load().debugEventDump
  private val pathField: JTextField = JTextField(EventStreamDump.file.toString(), 40)
  private val outputTextArea: JTextArea = JTextArea()
  private val pauseToggle: JCheckBox = JCheckBox("Pause")
  private val prettyToggle: JCheckBox = JCheckBox("Pretty JSON", true)
  private val autoScrollToggle: JCheckBox = JCheckBox("Auto-scroll", true)
  private val statusLabel: JLabel = JLabel()
  private val isPaused: AtomicBoolean = AtomicBoolean(false)

  @Volatile
  private var logFile: Path = EventStreamDump.file
  private val readPosition: AtomicLong = AtomicLong(0L)

  init {
    size = Dimension(960, 640)
    minimumSize = Dimension(720, 420)
    defaultCloseOperation = EXIT_ON_CLOSE

    outputTextArea.lineWrap = false
    outputTextArea.isEditable = false
    outputTextArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)

    contentPane.layout = BorderLayout()
    contentPane.add(statusLabel, BorderLayout.SOUTH)
    contentPane.add(buildToolbar(), BorderLayout.NORTH)
    contentPane.add(JScrollPane(outputTextArea), BorderLayout.CENTER)

    setLocationRelativeTo(null)
    startTailing()
    updateStatus()
  }

  private fun buildToolbar(): JToolBar =
    JToolBar().apply {
      isFloatable = false
      add(JLabel("File: "))
      add(pathField)
      add(pauseToggle)
      add(prettyToggle)
      add(autoScrollToggle)
      add(Box.createHorizontalStrut(8))
      add(JButton("Open").apply { addActionListener { onOpenClicked() } })
      add(JButton("Clear").apply { addActionListener { clearOutput() } })

      pauseToggle.addActionListener { isPaused.set(pauseToggle.isSelected) }
    }

  private fun startTailing() {
    val tailer = Tailer()
    val tailThread: Thread = Thread(tailer, "EventStreamMonitor-tail").apply { isDaemon = true }
    tailThread.start()
  }

  // Background loop: polls for appended bytes, pushes complete lines to the EDT. Never touches Swing components directly.
  private inner class Tailer : Runnable {
    override fun run() {
      while (!Thread.currentThread().isInterrupted) {
        try {
          if (!isPaused.get()) {
            val appendedBlock: String? = readAppendedText()
            if (appendedBlock != null) SwingUtilities.invokeLater { render(appendedBlock) }
          }
          Thread.sleep(POLL_INTERVAL_MILLIS)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          break
        } catch (ioException: Exception) {
          logger.error("Tail loop failed for file: {}", logFile, ioException)
          SwingUtilities.invokeLater { statusLabel.text = " Tail error: ${ioException.message}" }
          try {
            Thread.sleep(POLL_INTERVAL_MILLIS)
          } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            break
          }
        }
      }
    }
  }

  // Returns every complete line appended since the last poll, or null when
  // nothing new is readable. A trailing partial line is left unconsumed: the
  // position stops at the last newline, so partial bytes (possibly half of a
  // multibyte character) are re-read whole on the next poll.
  private fun readAppendedText(): String? {
    val filePath: Path = logFile
    if (!Files.isRegularFile(filePath)) return null

    val fileEnd: Long = Files.size(filePath)
    val currentPosition: Long = readPosition.get()

    if (currentPosition > fileEnd) {
      readPosition.set(0L)
      SwingUtilities.invokeLater { outputTextArea.text = "" }
      return null
    }
    if (currentPosition >= fileEnd) return null

    val readSize: Int = minOf(fileEnd - currentPosition, MAX_READ_BYTES).toInt()
    val readBuffer: ByteBuffer = ByteBuffer.allocate(readSize)

    Files.newByteChannel(filePath).use { channel ->
      channel.position(currentPosition)
      while (readBuffer.hasRemaining() && channel.read(readBuffer) >= 0) {
        // Fill the buffer or reach EOF.
      }
    }

    val readText = String(readBuffer.array(), 0, readBuffer.position(), Charsets.UTF_8)
    val lastNewlineIndex: Int = readText.lastIndexOf('\n')
    if (lastNewlineIndex < 0) return null

    readPosition.addAndGet(lastNewlineIndex + 1L)
    return readText.substring(0, lastNewlineIndex)
  }

  private fun render(appendedBlock: String) {
    val shouldPrettyPrint: Boolean = prettyToggle.isSelected
    val renderedBlock: String = appendedBlock.lineSequence()
      .filter { line -> line.isNotBlank() }
      .joinToString(separator = "\n\n") { line -> if (shouldPrettyPrint) prettyJson(line) else line }

    outputTextArea.append(renderedBlock)
    outputTextArea.append("\n\n")

    trimOldLines()

    if (autoScrollToggle.isSelected) outputTextArea.caretPosition = outputTextArea.document.length

    updateStatus()
  }

  // Keeps the text area bounded so long-running sessions cannot exhaust memory.
  private fun trimOldLines() {
    val textDocument: javax.swing.text.Document = outputTextArea.document
    val totalLineCount: Int = outputTextArea.lineCount
    if (totalLineCount <= MAX_LINES) return

    val lineCountToRemove: Int = totalLineCount - MAX_LINES
    var removedLineCount = 0
    var currentOffset = 0

    while (removedLineCount < lineCountToRemove && currentOffset < textDocument.length) {
      val character: String = textDocument.getText(currentOffset, 1)
      if (character == "\n") removedLineCount++
      currentOffset++
    }

    textDocument.remove(0, currentOffset)
  }

  private fun prettyJson(logLine: String): String =
    try {
      JsonUtil.encodeMap(JsonUtil.decodeMap(logLine.trim()), prettyPrint = true)
    } catch (jsonException: Exception) {
      logger.debug("Failed to pretty-print JSON line", jsonException)
      statusLabel.text = " JSON parse error: ${jsonException.message}"
      logLine
    }

  private fun onOpenClicked() {
    val rawPathText: String = pathField.text.trim()
    if (rawPathText.isEmpty()) return

    switchTo(Path.of(rawPathText))
  }

  private fun switchTo(resolvedPath: Path) {
    readPosition.set(0L)
    logFile = resolvedPath
    outputTextArea.text = ""
    updateStatus()
  }

  private fun clearOutput() {
    updateStatus()
    outputTextArea.text = ""
  }

  private fun updateStatus() {
    val enabledHint: String =
      if (isDumpEnabled) "" else "debug.eventDump is off in settings.json"
    statusLabel.text = "Read ${readPosition.get()} bytes, ${outputTextArea.lineCount} lines, $logFile$enabledHint"
  }
}


fun main() {
  SwingUtilities.invokeLater { EventStreamMonitor().isVisible = true }
}
