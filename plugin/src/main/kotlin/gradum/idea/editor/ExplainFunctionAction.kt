package gradum.idea.editor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import gradum.idea.chat.editor.GradumChatVirtualFile
import gradum.idea.chat.state.GradumChatSession

/**
 * Adds the function around the caret as a chat symbol attachment, prefills
 * the "Explain this function." prompt and sends through the open chat.
 */
class ExplainFunctionAction : AnAction() {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(event: AnActionEvent) {
    val project: Project = event.project ?: return
    val editor: Editor = event.getData(CommonDataKeys.EDITOR) ?: return
    val target = resolveTarget(project, editor, editor.caretModel.offset)
    event.presentation.isEnabled = target != null
  }

  override fun actionPerformed(event: AnActionEvent) {
    val editor: Editor = event.getData(CommonDataKeys.EDITOR) ?: return
    explainFunctionAt(editor, editor.caretModel.offset)
  }
}

private const val PROMPT: String = "Explain this function."
private const val UPDATE_COMMENTS_PROMPT: String = "Update the comments of this function."
private const val ADD_COMMENTS_PROMPT: String = "Generate doc comments for this function."
private val logger: Logger = Logger.getInstance(ExplainFunctionAction::class.java)

private fun resolveTarget(project: Project, editor: Editor, offset: Int): ResolvedTarget? {
  return try {
    ReadAction.computeBlocking<ResolvedTarget?, RuntimeException> {
      val file: PsiFile =
        PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@computeBlocking null
      val function: PsiElement = FunctionSymbols.findAt(file, offset) ?: return@computeBlocking null
      ResolvedTarget(file = file, function = function)
    }
  } catch (exception: Exception) {
    logger.warn("Could not resolve the function at offset $offset", exception)
    null
  }
}

private fun attachFunctionAndSend(editor: Editor, offset: Int, prompt: String) {
  val project: Project = editor.project ?: return
  val target = resolveTarget(project, editor, offset) ?: return
  val virtualFile = target.file.virtualFile ?: return
  val session = project.getService(GradumChatSession::class.java) ?: return
  val document = editor.document

  val symbol = AttachedSymbol(
    file = virtualFile,
    symbolName = FunctionSymbols.nameOf(target.function),
    lineRange = FunctionSymbols.lineRange(document, target.function)
  )
  ensureChatVisible(project)
  session.attachSymbolAndSend(symbol = symbol, prompt = prompt)
}

fun explainFunctionAt(editor: Editor, offset: Int) = attachFunctionAndSend(editor, offset, PROMPT)
fun updateCommentsAt(editor: Editor, offset: Int) = attachFunctionAndSend(editor, offset, UPDATE_COMMENTS_PROMPT)
fun addCommentsAt(editor: Editor, offset: Int) = attachFunctionAndSend(editor, offset, ADD_COMMENTS_PROMPT)

/**
 * Focuses a chat that already lives in an editor tab; otherwise reveals
 * the tool window. The prompt and attachment land in the session even if
 * the UI is still being composed, because the session replays the queued
 * send when its handler registers.
 */
private fun ensureChatVisible(project: Project) {
  val editorManager = FileEditorManager.getInstance(project)
  val chatFile = editorManager.openFiles.firstOrNull { it is GradumChatVirtualFile }
  if (chatFile != null) {
    editorManager.openFile(chatFile, true)
    return
  }
  val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Gradum") ?: return
  if (!toolWindow.isVisible) toolWindow.show()
  toolWindow.activate(null)
}

private data class ResolvedTarget(
  val file: PsiFile, val function: PsiElement
)
