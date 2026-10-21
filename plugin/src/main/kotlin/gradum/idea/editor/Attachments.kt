package gradum.idea.editor

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.icons.Icon

sealed class AttachedContext {
  abstract val icon: Icon
  abstract val displayName: String
}

data class AttachedFile(
  val file: VirtualFile,
  override val icon: Icon
) : AttachedContext() {
  override val displayName: String get() = file.name
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
