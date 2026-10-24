package gradum.idea.editor

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Collects error-severity highlighters from the open editor of a file.
 *
 * Reads the live MarkupModel (the same data the Problems "Current File" tab
 * mirrors) so the chat-side entry point works without the Problems tool
 * window ever being opened. Returns null when the file has no text editor
 * or no error highlighters.
 */
internal object FileErrorsCollector {

  fun collectFileErrors(project: Project, file: VirtualFile): AttachedFileErrors? {
    val textEditor: TextEditor =
      FileEditorManager.getInstance(project).getSelectedEditor(file) as? TextEditor ?: return null
    val markupModel: MarkupModel = textEditor.editor.markupModel
    val document: Document = textEditor.editor.document
    val errorEntries: List<ErrorEntry> = markupModel.allHighlighters
      .asSequence()
      .mapNotNull { highlighter: RangeHighlighter -> HighlightInfo.fromRangeHighlighter(highlighter) }
      .filter { info -> info.severity == HighlightSeverity.ERROR }
      .map { info ->
        ErrorEntry(
          line = document.getLineNumber(info.startOffset) + 1,
          message = info.description.orEmpty()
        )
      }
      .distinct()
      .sortedBy { entry -> entry.line }
      .toList()
    if (errorEntries.isEmpty()) return null
    return AttachedFileErrors(file = file, errors = errorEntries)
  }
}
