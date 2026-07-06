/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Attachments.kt  2026-07-05 16:42:55 Changed by gwy
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
 * The plugin pipeline for an image is split into two completely
 * independent paths that share the user's original file but
 * otherwise never touch each other:
 *
 *  - **Wire path** ([data], original → model): the factory reads
 *    the file's bytes verbatim and base64-encodes them so the
 *    server receives the exact image the user picked — no JPEG
 *    re-encoding, no quality loss, no colorspace surprises. The
 *    MIME type follows the file extension (`.png` → `image/png`,
 *    `.heic` → `image/heic`, etc.) so the vision model gets a
 *    payload it can decode natively.
 *  - **Render path** ([file], local → UI): the original
 *    [VirtualFile] is kept untouched on disk. The Compose preview
 *    chip loads the file directly through Skia's
 *    `Image.makeFromEncoded` and Compose downscales the bitmap to
 *    the chip size at draw time — no re-encode, no base64 round
 *    trip, no copy in memory. The file path is never sent to the
 *    server.
 *
 * Splitting the two paths lets the wire format stay lossless and
 * model-friendly (whatever the user picked) while the UI still
 * gets fast, hardware-scaled previews. The two are joined at
 * construction time only — after [gradum.idea.encodeImageToAttachment]
 * hands back the value, the wire payload is a one-way trip to
 * the server and the file path is a one-way trip to the UI.
 *
 * The factory caller enforces `originalSizeBytes <= 5 MB` per
 * image. Images share the same per-message attachment cap
 * (`MAX_ATTACHMENTS`) with file and text attachments.
 *
 * @property id A short random id used to dedupe identical uploads
 *   (e.g. user picks the same file twice in the same session).
 * @property mime The image's IANA media type as derived from the
 *   file extension (`image/png`, `image/jpeg`, `image/webp`, …).
 *   This is what the server sees — there is no normalization to
 *   a single type on the wire.
 * @property data Base64-encoded original file bytes (wire path only).
 * @property file The original on-disk file (render path only).
 *   The IDE's `VirtualFile` API gives the UI thread a fast
 *   read-only handle; `MessageAttachmentPreview` decodes it on
 *   demand and Compose downscales the bitmap to the chip size
 *   at draw time.
 * @property originalName The file name as the user selected it,
 *   surfaced in the chip and the LLM prompt as a hint.
 * @property originalSizeBytes Size of the *original* file in bytes,
 *   surfaced in tooltips and the error UI when an upload is
 *   rejected as too large.
 */
@Suppress(
  "SpellCheckingInspection", "SpellCheckingInspection", "SpellCheckingInspection",
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
