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
import javax.swing.text.Element
import kotlin.system.exitProcess
import javax.swing.text.Document as SwingDocument

private const val MAX_LINES: Int = 5_000
private const val POLL_INTERVAL_MILLIS: Long = 200L
private const val MAX_READ_BYTES: Long = 8L * 1024L * 1024L
private const val REPLAY_TAIL_BYTES: Long = 512L * 1024L
private const val RENDER_BATCH_LINES: Int = 200
private const val RENDER_BATCH_BYTES: Int = 64 * 1024
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
  private val pendingLines: ArrayDeque<String> = ArrayDeque()
  private val renderScheduled: AtomicBoolean = AtomicBoolean(false)
  private val replayHint: JLabel = JLabel()
  private var replayLineCount: Int = 0

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

    replayHint.border = BorderFactory.createEmptyBorder(6, 12, 0, 12)
    replayHint.foreground = UIManager.getColor("Label.disabledForeground")
    val header = JPanel(BorderLayout()).apply {
      add(replayHint, BorderLayout.NORTH)
      add(buildToolbar(), BorderLayout.CENTER)
    }

    val eventStreamPane = JPanel(BorderLayout()).apply {
      add(header, BorderLayout.NORTH)
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
    primeReadPosition()
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
            if (appendedBlock != null) enqueueBlock(appendedBlock)
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

  private fun primeReadPosition() {
    val fileSize: Long =
      try {
        Files.size(logFile)
      } catch (_: Exception) {
        0L
      }
    replayLineCount = 0
    val startOffset: Long = if (fileSize <= REPLAY_TAIL_BYTES) 0L else tailStartOffset(logFile, fileSize)
    readPosition.set(startOffset)
    replayHint.text =
      if (startOffset > 0L) {
        "Showing only the last $replayLineCount lines (${REPLAY_TAIL_BYTES / 1024} KB) of ${logFile.fileName}; earlier events are skipped."
      } else {
        ""
      }
  }

  private fun tailStartOffset(filePath: Path, fileSize: Long): Long {
    val startOffset: Long = fileSize - REPLAY_TAIL_BYTES
    val replayBuffer: ByteBuffer = ByteBuffer.allocate(REPLAY_TAIL_BYTES.toInt())
    try {
      Files.newByteChannel(filePath).use { channel ->
        channel.position(startOffset)
        while (replayBuffer.hasRemaining() && channel.read(replayBuffer) >= 0) {
          // Fill the buffer or reach EOF.
        }
      }
    } catch (_: Exception) {
      return fileSize
    }
    val replayText = String(replayBuffer.array(), 0, replayBuffer.position(), Charsets.UTF_8)
    val firstNewlineIndex: Int = replayText.indexOf('\n')
    if (firstNewlineIndex < 0) {
      replayLineCount = 0
      return fileSize
    }
    replayLineCount = replayText.substring(firstNewlineIndex + 1).count { character -> character == '\n' }
    return startOffset + firstNewlineIndex + 1L
  }

  private fun enqueueBlock(appendedBlock: String) {
    enqueueLines(appendedBlock.lineSequence())
  }

  private fun enqueueLines(lines: Sequence<String>) {
    synchronized(pendingLines) {
      for (line: String in lines) {
        if (line.isBlank()) continue
        pendingLines.addLast(line)
        while (pendingLines.size > MAX_LINES) pendingLines.removeFirst()
      }
      if (pendingLines.isEmpty()) return
    }
    scheduleRender()
  }

  private fun scheduleRender() {
    if (!renderScheduled.compareAndSet(false, true)) return
    SwingUtilities.invokeLater { drainBatch() }
  }

  private fun drainBatch() {
    renderScheduled.set(false)
    val batch: MutableList<String> = ArrayList(RENDER_BATCH_LINES)
    var batchBytes = 0
    var hasMoreBatches = false
    synchronized(pendingLines) {
      while (pendingLines.isNotEmpty() && batch.size < RENDER_BATCH_LINES && batchBytes < RENDER_BATCH_BYTES) {
        val line: String = pendingLines.removeFirst()
        batch.add(line)
        batchBytes += line.length
      }
      hasMoreBatches = pendingLines.isNotEmpty()
    }
    if (batch.isNotEmpty()) renderBatch(batch)
    if (hasMoreBatches) scheduleRender()
  }

  private fun renderBatch(batch: List<String>) {
    val renderedText: StringBuilder = StringBuilder()
    val eventTypes: LinkedHashSet<String> = LinkedHashSet()
    for (rawLine: String in batch) {
      val eventType: String = extractEventType(rawLine)
      synchronized(eventLineBuffer) {
        eventLineBuffer.addLast(eventType to rawLine)
        while (eventLineBuffer.size > MAX_LINES) eventLineBuffer.removeFirst()
      }
      eventTypes.add(eventType)
      if (matchesEventFilter(eventType)) renderText(renderedText, rawLine)
    }
    if (renderedText.isNotEmpty()) insertRendered(renderedText.toString())
    rememberEventTypes(eventTypes)
    if (autoScrollToggle.isSelected) outputTextArea.caretPosition = outputTextArea.document.length
    updateStatus()
  }

  private fun renderText(target: StringBuilder, rawLine: String) {
    val renderedBlock: String = if (prettyToggle.isSelected) prettyJson(rawLine) else rawLine
    target.append(renderedBlock).append("\n\n")
  }

  private fun insertRendered(text: String) {
    val textDocument: SwingDocument = outputTextArea.document
    textDocument.insertString(textDocument.length, text, null)
    trimOldLines()
  }

  private fun rerenderEvents() {
    SwingUtilities.invokeLater {
      outputTextArea.text = ""
      synchronized(pendingLines) { pendingLines.clear() }
      val replayLines: List<String> = synchronized(eventLineBuffer) { eventLineBuffer.map { (_, rawLine) -> rawLine } }
      enqueueLines(replayLines.asSequence())
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

  private fun rememberEventTypes(eventTypes: Collection<String>) {
    for (eventType: String in eventTypes) {
      if (containsEventType(eventType)) continue
      eventTypeFilter.addItem(eventType)
    }
  }

  private fun containsEventType(eventType: String): Boolean {
    for (index in 0 until eventTypeFilter.itemCount) {
      if (eventTypeFilter.getItemAt(index) == eventType) return true
    }
    return false
  }

  private fun matchesEventFilter(eventType: String): Boolean {
    val selected: String = eventTypeFilter.selectedItem as? String ?: EVENT_FILTER_ALL
    return selected == EVENT_FILTER_ALL || selected == eventType
  }

  private fun trimOldLines() {
    val textDocument: SwingDocument = outputTextArea.document
    val rootElement: Element = textDocument.defaultRootElement
    if (rootElement.elementCount <= MAX_LINES) return

    val linesToRemove: Int = rootElement.elementCount - MAX_LINES
    val cutOffset: Int = rootElement.getElement(linesToRemove).startOffset
    textDocument.remove(0, cutOffset)
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
    logFile = resolvedPath
    synchronized(pendingLines) { pendingLines.clear() }
    synchronized(eventLineBuffer) { eventLineBuffer.clear() }
    outputTextArea.text = ""
    primeReadPosition()
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
    val lineCount: Int = outputTextArea.document.defaultRootElement.elementCount
    statusLabel.text = "Read ${readPosition.get()} bytes, $lineCount lines, $logFile$enabledHint"
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
