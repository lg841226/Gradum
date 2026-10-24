package gradum.idea.editor

import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Resolves the function declaration a caret or code vision lens points at.
 *
 * Only Java and Kotlin files participate; every other language returns
 * `null` so callers can treat unsupported files exactly like a caret that
 * is not on a function.
 *
 * Uses reflection for `PsiMethod` to avoid `NoClassDefFoundError` when the
 * Java plugin is not present in the runtime classpath.
 */
internal object FunctionSymbols {

  private val supportedLanguageIds: Set<String> = setOf("JAVA", "kotlin")

  private val psiMethodClass: Class<*>? =
    try {
      Class.forName("com.intellij.psi.PsiMethod", false, PsiElement::class.java.classLoader)
    } catch (_: ClassNotFoundException) {
      null
    }

  private val psiMethodIsConstructorMethod = psiMethodClass?.getMethod("isConstructor")

  private val psiMethodDocCommentMethod = psiMethodClass?.getMethod("getDocComment")

  fun isSupportedFile(file: PsiFile): Boolean =
    file.language.id in supportedLanguageIds

  fun isFunction(element: PsiElement): Boolean =
    element is KtNamedFunction || psiMethodClass?.isInstance(element) == true
      && psiMethodIsConstructorMethod?.let { method ->
      try {
        !(method.invoke(element) as Boolean)
      } catch (_: Exception) {
        true
      }
    } ?: false

  /**
   * Checks whether the given function element has a documentation comment.
   *
   * For Java: [PsiMethod.getDocComment()] returns a [com.intellij.psi.javadoc.PsiDocComment] or null.
   * For Kotlin: [KtNamedFunction.docComment] returns a [org.jetbrains.kotlin.kdoc.psi.api.KDoc] or null.
   */
  fun hasDocComment(element: PsiElement): Boolean =
    if (element is KtNamedFunction) {
      element.docComment != null
    } else {
      psiMethodClass?.isInstance(element) == true
        && psiMethodDocCommentMethod?.let { method ->
        try {
          method.invoke(element) != null
        } catch (_: Exception) {
          false
        }
      } ?: false
    }

  /**
   * Walks up from the leaf at [offset] to the nearest enclosing function.
   *
   * The walk keeps local functions and methods inside anonymous classes
   * independent of their file-level container, and deliberately skips
   * lambdas so a caret inside one resolves to the enclosing named function.
   */
  fun findAt(file: PsiFile, offset: Int): PsiElement? {
    if (!isSupportedFile(file) || file.textLength == 0) return null

    val clampedOffset: Int = offset.coerceIn(0, file.textLength - 1)
    val leaf: PsiElement = file.findElementAt(clampedOffset) ?: return null
    var current: PsiElement? = leaf
    while (current != null && current !== file) {
      if (isFunction(current)) return current
      current = current.parent
    }
    return null
  }

  fun nameOf(function: PsiElement): String =
    (function as? PsiNamedElement)?.name?.takeIf { it.isNotBlank() } ?: "function"

  /**
   * Converts the declaration's text range to the 1-based, inclusive line
   * range used by the chat wire format and by the server's `ReadFileSkill`.
   */
  fun lineRange(document: Document, function: PsiElement): IntRange {
    val startLine: Int =
      document.getLineNumber(function.textRange.startOffset.coerceIn(0, document.textLength)) + 1
    val endLine: Int =
      document.getLineNumber(function.textRange.endOffset.coerceIn(0, document.textLength)) + 1
    return startLine..maxOf(startLine, endLine)
  }
}
