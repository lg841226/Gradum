package gradum.runtime

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import gradum.logging.EventStreamDump
import gradum.server.setup.ServerSettingsStore
import gradum.utils.JsonUtil
import org.slf4j.LoggerFactory
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import kotlin.system.exitProcess
import javax.swing.text.Document as SwingDocument

private const val MAX_LINES: Int = 5_000
private const val POLL_INTERVAL_MILLIS: Long = 200L
private const val MAX_READ_BYTES: Long = 8L * 1024L * 1024L
private const val EVENT_FILTER_ALL: String = "All"
private val logger = LoggerFactory.getLogger(EventStreamMonitor::class.java)

class EventStreamMonitor : JFrame("Gradum Runtime Window") {
  private val isDumpEnabled: Boolean = ServerSettingsStore.load().debugEventDump
  private val pathField: JTextField = JTextField(EventStreamDump.file.toString(), 40)
  private val pauseToggle: JToggleButton = JToggleButton("Pause")
  private val prettyToggle: JCheckBox = JCheckBox("Pretty JSON", true)
  private val autoScrollToggle: JCheckBox = JCheckBox("Auto scroll", true)
  private val eventTypeFilter: JComboBox<String> = JComboBox(arrayOf(EVENT_FILTER_ALL)).apply {
    isFocusable = false
  }
  private val statusLabel: JLabel = JLabel()
  private val outputTextArea: JTextArea = JTextArea()
  private val tabbedPane: JTabbedPane = JTabbedPane()
  private val serverLogPanel: ServerLogPanel = ServerLogPanel()
  private val settingsEditorPanel: SettingsEditorPanel = SettingsEditorPanel()

  private val eventLineBuffer: ArrayDeque<Pair<String, String>> = ArrayDeque()

  val serverLogSink: (String, String) -> Unit get() = serverLogPanel::appendLogLine
  private val isPaused: AtomicBoolean = AtomicBoolean(false)

  @Volatile
  private var logFile: Path = EventStreamDump.file
  private val readPosition: AtomicLong = AtomicLong(0L)

  init {
    size = Dimension(960, 640)
    minimumSize = Dimension(720, 420)
    defaultCloseOperation = DO_NOTHING_ON_CLOSE
    addWindowListener(object : WindowAdapter() {
      override fun windowClosing(windowEvent: WindowEvent) {
        val answer: Int = JOptionPane.showConfirmDialog(
          this@EventStreamMonitor,
          "Do you want to exit Gradum Runtime?",
          title,
          JOptionPane.YES_NO_OPTION
        )
        if (answer == JOptionPane.YES_OPTION) {
          dispose()
          exitProcess(0)
        }
      }
    })

    outputTextArea.lineWrap = false
    outputTextArea.isEditable = false
    outputTextArea.font = MONOSPACE_UI_FONT

    val eventStreamPane = JPanel(BorderLayout()).apply {
      add(buildToolbar(), BorderLayout.NORTH)
      add(JScrollPane(outputTextArea).apply {
        border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
      }, BorderLayout.CENTER)
      add(statusLabel.apply {
        border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
      }, BorderLayout.SOUTH)
    }

    tabbedPane.addTab("Event Stream", eventStreamPane)
    tabbedPane.addTab("Server Log", serverLogPanel)
    tabbedPane.addTab("Settings", settingsEditorPanel)
    contentPane.layout = BorderLayout()
    contentPane.add(tabbedPane, BorderLayout.CENTER)

    setLocationRelativeTo(null)
    startTailing()
    updateStatus()
  }

  private fun buildToolbar(): JPanel =
    JPanel(BorderLayout()).apply {
      border = BorderFactory.createEmptyBorder(8, 10, 8, 10)

      val leftGroup: JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
        add(JLabel("File: "))
        add(pathField)
        add(prettyToggle)
        add(autoScrollToggle)
        add(JLabel("Type: "))
        add(eventTypeFilter)
        for (toggle in listOf(prettyToggle, autoScrollToggle)) {
          toggle.isFocusable = false
        }
      }
      val rightGroup: JPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
        add(pauseToggle.apply { isFocusable = false })
        add(JButton("Open").apply { addActionListener { onOpenClicked() } })
        add(JButton("Clear").apply { addActionListener { clearOutput() } })
      }

      add(leftGroup, BorderLayout.CENTER)
      add(rightGroup, BorderLayout.EAST)

      pauseToggle.addActionListener { isPaused.set(pauseToggle.isSelected) }

      eventTypeFilter.addActionListener { rerenderEvents() }
      prettyToggle.addActionListener { rerenderEvents() }
    }

  private fun startTailing() {
    val tailer = Tailer()
    val tailThread: Thread = Thread(tailer, "EventStreamMonitor-tail").apply { isDaemon = true }
    tailThread.start()
  }

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
    for (rawLine: String in appendedBlock.lineSequence().filter { line -> line.isNotBlank() }) {
      val eventType: String = extractEventType(rawLine)
      synchronized(eventLineBuffer) {
        eventLineBuffer.addLast(eventType to rawLine)
        while (eventLineBuffer.size > MAX_LINES) eventLineBuffer.removeFirst()
      }
      rememberEventType(eventType)
      if (matchesEventFilter(eventType)) {
        appendRenderedLine(rawLine)
      }
    }
    if (autoScrollToggle.isSelected) outputTextArea.caretPosition = outputTextArea.document.length
    updateStatus()
  }

  private fun appendRenderedLine(rawLine: String) {
    val renderedBlock: String =
      if (prettyToggle.isSelected) prettyJson(rawLine) else rawLine
    outputTextArea.append(renderedBlock)
    outputTextArea.append("\n\n")
    trimOldLines()
  }

  private fun rerenderEvents() {
    SwingUtilities.invokeLater {
      outputTextArea.text = ""
      synchronized(eventLineBuffer) {
        eventLineBuffer.forEach { (eventType, rawLine) ->
          if (matchesEventFilter(eventType)) appendRenderedLine(rawLine)
        }
      }
      if (autoScrollToggle.isSelected) outputTextArea.caretPosition = outputTextArea.document.length
      updateStatus()
    }
  }

  private fun extractEventType(rawLine: String): String =
    try {
      val jsonMap: Map<String, Any?> = JsonUtil.decodeMap(rawLine.trim())
      val eventType: Any? = jsonMap["event"] ?: jsonMap["type"]
      eventType?.toString()?.takeIf { value -> value.isNotBlank() } ?: "other"
    } catch (_: Exception) {
      "raw"
    }

  private fun rememberEventType(eventType: String) {
    SwingUtilities.invokeLater {
      for (index in 0 until eventTypeFilter.itemCount) {
        if (eventTypeFilter.getItemAt(index) == eventType) return@invokeLater
      }
      eventTypeFilter.addItem(eventType)
    }
  }

  private fun matchesEventFilter(eventType: String): Boolean {
    val selected: String = eventTypeFilter.selectedItem as? String ?: EVENT_FILTER_ALL
    return selected == EVENT_FILTER_ALL || selected == eventType
  }

  private fun trimOldLines() {
    val textDocument: SwingDocument = outputTextArea.document
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
    synchronized(eventLineBuffer) { eventLineBuffer.clear() }
    outputTextArea.text = ""
    updateStatus()
  }

  private fun clearOutput() {
    updateStatus()
    outputTextArea.text = ""
  }

  private fun updateStatus() {
    val enabledHint: String =
      if (isDumpEnabled) ""
      else "debug.eventDump is off in settings.json"
    statusLabel.text = "Read ${readPosition.get()} bytes, ${outputTextArea.lineCount} lines, $logFile$enabledHint"
  }

  companion object {

    fun openForServer(): EventStreamMonitor? {
      if (GraphicsEnvironment.isHeadless()) return null
      installFlatLaf()

      val monitor = EventStreamMonitor()
      val logRouted: Boolean =
        try {
          routeServerLogsTo(target = monitor.serverLogSink)
        } catch (_: Exception) {
          false
        }
      SwingUtilities.invokeLater {
        if (logRouted) monitor.selectServerLogTab()
        monitor.isVisible = true
      }
      return monitor
    }
  }

  private fun selectServerLogTab() {
    tabbedPane.selectedIndex = tabbedPane.indexOfTab("Server Log")
  }
}


/** Must run before any component is created. */
private fun installFlatLaf() {
  try {
    val laf = if (isMacDarkModeEnabled()) FlatDarkLaf() else FlatLightLaf()
    UIManager.setLookAndFeel(laf)
    UIManager.put("defaultFont", MONOSPACE_UI_FONT)
  } catch (_: Exception) {
  }
}

private fun isMacDarkModeEnabled(): Boolean =
  try {
    val appearance: String? =
      Toolkit.getDefaultToolkit().getDesktopProperty("apple.awt.application.appearance") as? String
    if (appearance != null) {
      appearance.contains("dark", ignoreCase = true)
    } else {
      val process = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle").start()
      val output: String = process.inputStream.bufferedReader().readText().trim()
      process.waitFor()
      output.equals("Dark", ignoreCase = true)
    }
  } catch (_: Exception) {
    false
  }

fun main() {
  installFlatLaf()
  SwingUtilities.invokeLater { EventStreamMonitor().isVisible = true }
}
