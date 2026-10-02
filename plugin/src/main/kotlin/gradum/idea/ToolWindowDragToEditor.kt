package gradum.idea

import com.intellij.openapi.fileEditor.impl.EditorsSplitters
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import gradum.idea.chat.editor.openGradumChatInEditor
import java.awt.*
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Lets the user drag the Gradum tool window header (its title strip) onto
 * the editor area to move the chat into an editor tab: the drag equivalent
 * of the Open in Editor title action.
 *
 * A press on the tool window chrome, outside the chat content itself, arms
 * a potential drag. When the release is at least [DRAG_THRESHOLD_PX] away
 * and lands on an editor window, the chat moves via
 * [openGradumChatInEditor] (same move semantics as the title action).
 * Every other gesture: clicks, releases outside the editor area, drags
 * starting inside the chat: is ignored, so the platform's own
 * tool-window redocking keeps working unchanged.
 *
 * Mouse events do not bubble in Swing, so the gesture is observed with a
 * toolkit-level AWTEventListener filtered to this tool window's components;
 * the listener is removed when the tool window is disposed.
 */
internal fun installToolWindowDragToEditor(project: Project, toolWindow: ToolWindow) {
  if (project.isDisposed) return
  val contentComponent: JComponent = toolWindow.component
  val chromeRoot: Container = generateSequence<Container>(contentComponent) { container -> container.parent }
    .takeWhile { component -> component !is Window }
    .lastOrNull() ?: return

  var pressedAt: Point? = null
  val listener = AWTEventListener { event ->
    if (event !is MouseEvent) return@AWTEventListener
    val source = event.component ?: return@AWTEventListener
    when (event.id) {
      MouseEvent.MOUSE_PRESSED -> {
        pressedAt = null
        if (!SwingUtilities.isLeftMouseButton(event) || event.isPopupTrigger) return@AWTEventListener
        if (!SwingUtilities.isDescendingFrom(source, chromeRoot)) return@AWTEventListener
        if (SwingUtilities.isDescendingFrom(source, contentComponent)) return@AWTEventListener
        pressedAt = event.locationOnScreen
      }

      MouseEvent.MOUSE_RELEASED -> {
        val start: Point = pressedAt ?: return@AWTEventListener
        pressedAt = null
        if (event.isPopupTrigger) return@AWTEventListener
        val end: Point = event.locationOnScreen
        if (start.distance(end) < DRAG_THRESHOLD_PX) return@AWTEventListener
        if (!isOverEditorArea(end)) return@AWTEventListener
        if (project.isDisposed) return@AWTEventListener
        openGradumChatInEditor(project = project)
      }
    }
  }
  Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
  Disposer.register(toolWindow.disposable) {
    Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
  }
}

private const val DRAG_THRESHOLD_PX: Double = 8.0

private fun isOverEditorArea(screenPoint: Point): Boolean {
  for (window in Window.getWindows()) {
    if (!window.isShowing) continue
    val point = Point(screenPoint)
    try {
      SwingUtilities.convertPointFromScreen(point, window)
    } catch (_: IllegalComponentStateException) {
      continue
    }
    if (!window.contains(point)) continue
    val deepest = SwingUtilities.getDeepestComponentAt(window, point.x, point.y) ?: continue
    if (generateSequence(deepest) { component -> component.parent }
        .any { component -> component is EditorsSplitters }) return true
  }
  return false
}
