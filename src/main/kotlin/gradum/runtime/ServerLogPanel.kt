package gradum.runtime

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.*
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

const val SERVER_LOG_PATTERN: String = "%d{HH:mm:ss.SSS} %gradumLevel [%logger{0}] %msg%n"

private const val MAX_PANEL_LINES: Int = 5000
internal val MONOSPACE_UI_FONT: Font = Font(Font.MONOSPACED, Font.PLAIN, 12)
private val ANSI_PATTERN: Regex = Regex("\u001B\\[[0-9;]*[A-Za-z]|\u001B][^\u0007]*\u0007|\r")

internal enum class LogLevel(val letter: String, val color: Color?) {
  INFO("I", null),
  TRACE("T", Color(128, 128, 128)),
  DEBUG("D", Color(128, 128, 128)),
  WARN("W", Color(216, 152, 0)),
  ERROR("E", Color(219, 79, 71));

  companion object {
    fun fromLetter(letter: String): LogLevel =
      entries.firstOrNull { it.letter == letter.trim() } ?: INFO
  }
}

private data class LogLine(val level: LogLevel, val text: String)

/**
 * Live log view for the HTTP server: a bounded, colored text pane with level
 * filtering, keyword search and auto-scroll. Fed by whatever source owns it
 * (the logback appender on the server, nothing when the debug monitor runs
 * standalone).
 */
class ServerLogPanel : JPanel(BorderLayout()) {

  private val outputPane: JTextPane = object : JTextPane() {
    override fun getScrollableTracksViewportWidth(): Boolean = false
  }.apply {
    isEditable = false
    font = MONOSPACE_UI_FONT
  }

  private val lineBuffer: ArrayDeque<LogLine> = ArrayDeque()

  private val levelFilter: JComboBox<String> =
    JComboBox(arrayOf("All", "Info+", "Warn+", "Error")).apply {
      isFocusable = false
      addActionListener { rerender() }
    }

  private val autoScrollToggle: JToggleButton =
    JToggleButton("Auto scroll", true).apply {
      isFocusable = false
    }

  private val searchField: JTextField = JTextField(16)

  init {
    add(JScrollPane(outputPane).apply {
      verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_ALWAYS
      horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
      preferredSize = Dimension(900, 560)
      border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
    }, BorderLayout.CENTER)

    val leftControls: JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
      add(JLabel("Level: "))
      add(levelFilter)
      add(autoScrollToggle)
      add(JLabel("Find: "))
      add(searchField)
      add(JButton("Next").apply {
        isFocusable = false
        addActionListener { findNext() }
      })
      isFocusable = false
    }
    val rightControls: JPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
      add(JButton("Clear").apply {
        isFocusable = false
        addActionListener { outputPane.text = "" }
      })
      add(JButton("Copy All").apply {
        isFocusable = false
        addActionListener {
          val selection = StringSelection(outputPane.text)
          Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        }
      })
    }

    val toolbar: JPanel = JPanel(BorderLayout()).apply {
      border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
      add(leftControls, BorderLayout.CENTER)
      add(rightControls, BorderLayout.EAST)
    }
    add(toolbar, BorderLayout.NORTH)

    val searchKey = KeyStroke.getKeyStroke('F', Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
    outputPane.inputMap.put(searchKey, "focusSearch")
    outputPane.actionMap.put("focusSearch", object : AbstractAction() {
      override fun actionPerformed(actionEvent: ActionEvent?) {
        searchField.requestFocusInWindow()
      }
    })
  }

  fun appendLogLine(levelLetter: String, text: String) {
    val level: LogLevel = LogLevel.fromLetter(levelLetter)
    val plainText: String = text.replace(ANSI_PATTERN, "")
    val lineText: String = if (plainText.endsWith('\n')) plainText else "$plainText\n"

    synchronized(lineBuffer) {
      lineBuffer.addLast(LogLine(level, lineText))
      while (lineBuffer.size > MAX_PANEL_LINES) lineBuffer.removeFirst()
    }
    if (passesFilter(level)) {
      SwingUtilities.invokeLater { appendStyled(level, lineText) }
    }
  }

  private fun passesFilter(level: LogLevel): Boolean =
    when (levelFilter.selectedItem) {
      "Info+" -> level >= LogLevel.INFO
      "Warn+" -> level >= LogLevel.WARN
      "Error" -> level == LogLevel.ERROR
      else -> true
    }

  private fun rerender() {
    SwingUtilities.invokeLater {
      outputPane.text = ""
      synchronized(lineBuffer) {
        lineBuffer.forEach { line ->
          if (passesFilter(line.level)) appendStyled(line.level, line.text)
        }
      }
    }
  }

  private fun appendStyled(level: LogLevel, text: String) {
    val document: StyledDocument = outputPane.styledDocument
    val lineColor: Color? =
      if (level == LogLevel.DEBUG || level == LogLevel.TRACE) {
        UIManager.getColor("Label.disabledForeground") ?: level.color
      } else {
        level.color
      }
    val attributes: SimpleAttributeSet = SimpleAttributeSet().apply {
      lineColor?.let { StyleConstants.setForeground(this, it) }
    }
    document.insertString(document.length, text, attributes)

    if (autoScrollToggle.isSelected) outputPane.caretPosition = document.length
  }

  private fun findNext() {
    val needle: String = searchField.text
    if (needle.isEmpty()) return

    val documentText: String = outputPane.text
    val caretPosition: Int = outputPane.caretPosition
    val matches: MutableList<Int> = mutableListOf()
    var index: Int = documentText.indexOf(needle)
    while (index >= 0) {
      matches.add(index)
      index = documentText.indexOf(needle, index + needle.length)
    }
    if (matches.isEmpty()) return

    val highlighter = outputPane.highlighter
    highlighter.removeAllHighlights()
    for (matchStart in matches) {
      highlighter.addHighlight(
        matchStart,
        matchStart + needle.length,
        javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(Color(255, 226, 110))
      )
    }

    val nextMatch: Int = matches.firstOrNull { it >= caretPosition } ?: matches.first()
    outputPane.setCaretPosition(nextMatch)
    outputPane.moveCaretPosition(nextMatch + needle.length)
  }

  fun isEmpty(): Boolean = outputPane.text.isEmpty()
}

private class ServerLogPanelAppender(
  private val target: (String, String) -> Unit,
  private val layout: ch.qos.logback.core.Layout<ILoggingEvent>
) : AppenderBase<ILoggingEvent>() {

  init {
    name = "SERVER_LOG_PANEL"
  }

  override fun append(eventObject: ILoggingEvent) {
    val levelLetter: String = eventObject.level.toString().take(1)
    target(levelLetter, layout.doLayout(eventObject))
  }
}

/**
 * Routes every log record into [target] and drops the console appender, so
 * the terminal stays silent while the HTTP server runs.
 *
 * Returns false (and leaves logging untouched) when logback is not the
 * logging backend.
 */
fun routeServerLogsTo(target: (String, String) -> Unit): Boolean {
  val logbackContext: LoggerContext =
    LoggerFactory.getILoggerFactory() as? LoggerContext ?: return false

  val encoder = PatternLayoutEncoder().apply {
    this.context = logbackContext
    pattern = SERVER_LOG_PATTERN
    start()
  }
  val layout = encoder.layout

  val panelAppender = ServerLogPanelAppender(target, layout).apply {
    this.context = logbackContext
    start()
  }

  val rootLogger = logbackContext.getLogger(Logger.ROOT_LOGGER_NAME)
  rootLogger.detachAndStopAllAppenders()
  rootLogger.addAppender(panelAppender)
  return true
}
