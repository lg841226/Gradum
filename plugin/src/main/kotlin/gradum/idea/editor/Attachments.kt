/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 */

package gradum.idea.editor

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

sealed class AttachedContext {
  abstract val iconKey: IconKey
  abstract val displayName: String
}

data class AttachedFile(
  val file: VirtualFile,
  override val iconKey: IconKey
) : AttachedContext() {
  override val displayName: String get() = file.name
}

data class AttachedText(
  val content: String,
  val preview: String,
  override val iconKey: IconKey = AllIconsKeys.FileTypes.Text
) : AttachedContext() {
  override val displayName: String get() = preview
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
  override val iconKey: IconKey = AllIconsKeys.FileTypes.Image
) : AttachedContext() {
  override val displayName: String get() = originalName
}
