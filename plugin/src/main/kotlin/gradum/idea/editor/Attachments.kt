package gradum.idea.editor

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.icons.Icon

sealed class AttachedContext {
  /** Icon the IDE shows for this attachment, in the platform's icon model. */
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

/**
 * An image attached to the next outgoing message.
 *
 * Two independent paths share the original file:
 *  - **Wire path** ([data]): factory reads bytes verbatim, base64-encodes them.
 *    MIME follows the file extension. Server receives the exact file.
 *  - **Render path** ([file]): original [VirtualFile] kept on disk.
 *    Compose preview loads it via Skia and downscales at draw time.
 *    No re-encode, no base64 round trip, no in-memory copy.
 *
 * Splitting the paths keeps the wire format lossless and model-friendly while
 * the UI gets fast hardware-scaled previews.
 */
@Suppress(
  "SpellCheckingInspection", "SpellCheckingInspection",
  "SpellCheckingInspection", "SpellCheckingInspection", "SpellCheckingInspection", "SpellCheckingInspection",
  "SpellCheckingInspection", "SpellCheckingInspection"
)
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
