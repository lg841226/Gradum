package gradum.idea.editor

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.InlayHintsUtils
import com.intellij.codeInsight.hints.codeVision.CodeVisionProviderBase
import com.intellij.codeInsight.hints.settings.language.isInlaySettingsEditor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SyntaxTraverser
import gradum.idea.settings.AppearanceSettings
import gradum.idea.utils.GradumBundle.message
import java.awt.event.MouseEvent

/**
 * Code vision entry that offers a single "Explain" action above Java and
 * Kotlin function declarations.
 *
 * The provider is daemon-bound, so PSI invalidation and recomputation are
 * owned by the platform. [acceptsFile] reads the in-memory settings snapshot
 * only (never the settings file) because the platform calls it while a file
 * is being opened, before its editor exists.
 */
class GradumCodeVisionProvider : CodeVisionProviderBase() {

  companion object {
    const val ID: String = "gradum.explainFunction"
  }

  private val hint: String by lazy { message("gradum.codevision.explain") }

  override fun acceptsFile(file: PsiFile): Boolean =
    AppearanceSettings.getInstance().snapshot.codeVisionExplainEnabled &&
      FunctionSymbols.isSupportedFile(file)

  override fun acceptsElement(element: PsiElement): Boolean =
    FunctionSymbols.isFunction(element)

  override fun getHint(element: PsiElement, file: PsiFile): String = hint

  override fun handleClick(editor: Editor, element: PsiElement, event: MouseEvent?) {
    explainFunctionAt(editor = editor, offset = element.textRange.startOffset)
  }

  override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
    if (!acceptsFile(file)) return emptyList()

    val lenses = ArrayList<Pair<TextRange, CodeVisionEntry>>()
    val traverser = SyntaxTraverser.psiTraverser(file)
    for (element in traverser) {
      if (!acceptsElement(element)) continue
      if (!InlayHintsUtils.isFirstInLine(element)) continue
      val range = InlayHintsUtils.getTextRangeWithoutLeadingCommentsAndWhitespaces(element)
      val handler = ClickHandler(element)
      lenses.add(range to ClickableTextCodeVisionEntry(hint, id, handler, AllIcons.Actions.Attach))
    }
    return lenses
  }

  private inner class ClickHandler(element: PsiElement) : (MouseEvent?, Editor) -> Unit {
    private val elementPointer = SmartPointerManager.createPointer(element)

    override fun invoke(event: MouseEvent?, editor: Editor) {
      if (isInlaySettingsEditor(editor)) return
      val element = elementPointer.element ?: return
      handleClick(editor, element, event)
    }
  }

  override val name: String
    get() = message("gradum.codevision.name")

  override val relativeOrderings: List<CodeVisionRelativeOrdering>
    get() = listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingFirst)

  override val defaultAnchor: CodeVisionAnchorKind
    get() = CodeVisionAnchorKind.Top

  override val id: String
    get() = ID
}
