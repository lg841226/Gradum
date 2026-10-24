package gradum.idea.editor

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Collects error-severity highlights for a file from the document-level
 * MarkupModel, the exact source the Problems "Current File" tab reads
 * (`ProblemsViewHighlightingWatcher` uses the same model and the same
 * `HighlightInfo.fromRangeHighlighter` extraction).
 *
 * Returns null when the file has no text editor or no error highlights.
 */
internal object FileErrorsCollector {

  fun collectFileErrors(project: Project, file: VirtualFile): AttachedFileErrors? =
    ReadAction.compute<AttachedFileErrors?, RuntimeException> {
      val textEditor: TextEditor =
        FileEditorManager.getInstance(project).getSelectedEditor(file) as? TextEditor
          ?: return@compute null
      val document: Document = textEditor.editor.document
      val markupModel: MarkupModel = DocumentMarkupModel.forDocument(document, project, true)
      val highlighters = markupModel.allHighlighters
      val infos = highlighters.mapNotNull { highlighter -> HighlightInfo.fromRangeHighlighter(highlighter) }
      val errorInfos = infos.filter { info -> info.severity.myVal >= HighlightSeverity.ERROR.myVal }
      val errorEntries: List<ErrorEntry> = errorInfos
        .map { info ->
          ErrorEntry(
            line = document.getLineNumber(info.startOffset) + 1,
            message = info.description.orEmpty()
          )
        }
        .distinct()
        .sortedBy { entry -> entry.line }
      if (errorEntries.isEmpty()) return@compute null
      AttachedFileErrors(file = file, errors = errorEntries)
    }
}
