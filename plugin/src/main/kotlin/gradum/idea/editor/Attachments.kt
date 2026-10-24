package gradum.idea.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.icons.Icon
import com.intellij.platform.icons.swing.toNewIcon
import gradum.idea.utils.GradumBundle.message

sealed class AttachedContext {
  abstract val icon: Icon
  abstract val displayName: String
}

data class AttachedFile(
  val file: VirtualFile, override val icon: Icon
) : AttachedContext() {
  override val displayName: String get() = file.name
}

/**
 * A function or method declaration attached as chat context.
 *
 * [lineRange] is 1-based and inclusive, matching the server's
 * `ReadFileSkill` line-range semantics. The icon is resolved from
 * the owning file exactly like [AttachedFile] does.
 */
data class AttachedSymbol(
  val file: VirtualFile,
  val symbolName: String,
  val lineRange: IntRange
) : AttachedContext() {
  override val displayName: String get() = "$symbolName @ ${file.name}"

  override val icon: Icon by lazy { fileIcon(file) }
}

data class AttachedText(
  val content: String,
  val preview: String
) : AttachedContext() {
  override val displayName: String get() = preview

  /**
   * Resolved on read rather than on construction: pasted text carries no file, and
   * looking the icon up eagerly would drag the platform in even where the model is
   * used without a running IDE (unit tests build [AttachedText] directly).
   */
  override val icon: Icon get() = TextFileIcon
}


data class AttachedImage(
  val id: String,
  val mime: String,
  val data: String,
  val file: VirtualFile,
  val originalName: String,
  val originalSizeBytes: Long,
  override val icon: Icon
) : AttachedContext() {
  override val displayName: String get() = originalName
}

data class ErrorEntry(
  val line: Int, val message: String
)

data class AttachedFileErrors(
  val file: VirtualFile, val errors: List<ErrorEntry>
) : AttachedContext() {
  override val displayName: String get() = message("gradum.problems.attachment.name", file.name, errors.size)
  override val icon: Icon get() = AllIcons.General.Error.toNewIcon()
}
