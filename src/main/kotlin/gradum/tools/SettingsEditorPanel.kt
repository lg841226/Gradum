package gradum.tools

import gradum.logging.MONOSPACE_UI_FONT
import gradum.utils.JsonUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.swing.*

/**
 * Settings tab: opens `~/.gradum/settings.json` in an editable text area,
 * validates the JSON on save (backup + atomic replace) and reloads. Changes
 * reach the running server only after a restart.
 */
class SettingsEditorPanel : JPanel(BorderLayout()) {

  private val settingsFile: File =
    File(System.getProperty("user.home"), ".gradum/settings.json")

  private val editorTextArea: JTextArea = JTextArea().apply {
    isEditable = true
    font = MONOSPACE_UI_FONT
    lineWrap = false
  }

  init {
    add(JScrollPane(editorTextArea).apply {
      verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_ALWAYS
      horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
      border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
    }, BorderLayout.CENTER)

    val toolbar: JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
      border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
      add(JButton("Reload").apply {
        isFocusable = false
        addActionListener { load() }
      })
      add(JButton("Save").apply {
        isFocusable = false
        addActionListener { save() }
      })
      add(JLabel(" $settingsFile"))
    }

    add(toolbar, BorderLayout.NORTH)

    load()
  }

  private fun load() {
    val content: String =
      if (settingsFile.isFile) {
        try {
          Files.readString(settingsFile.toPath(), StandardCharsets.UTF_8)
        } catch (readException: Exception) {
          showError("Load failed: ${readException.message}")
          return
        }
      } else {
        ""
      }
    editorTextArea.text = content
    editorTextArea.caretPosition = 0
  }

  private fun save() {
    val content: String = editorTextArea.text
    try {
      JsonUtil.decodeMap(content)
    } catch (jsonException: Exception) {
      showError("Not valid JSON, not saved:\n${jsonException.message}")
      return
    }

    try {
      val settingsPath: Path = settingsFile.toPath()
      Files.createDirectories(settingsPath.parent)
      if (Files.isRegularFile(settingsPath)) {
        Files.copy(
          settingsPath,
          settingsPath.resolveSibling("settings.json.bak"),
          StandardCopyOption.REPLACE_EXISTING
        )
      }
      Files.writeString(settingsPath, content, StandardCharsets.UTF_8)
    } catch (writeException: Exception) {
      showError("Save failed: ${writeException.message}")
    }
  }

  private fun showError(text: String) {
    JOptionPane.showMessageDialog(this, text, "Gradum Settings", JOptionPane.ERROR_MESSAGE)
  }
}
