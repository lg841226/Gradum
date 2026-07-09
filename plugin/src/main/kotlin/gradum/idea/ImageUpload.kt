/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ImageUpload.kt  2026-07-04 11:46:56 Changed by gwy
 */

package gradum.idea

import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.editor.AttachedImage
import java.util.*

/**
 * Per-extension MIME type for the wire payload. The key is the
 * lower-cased extension with no leading dot; the value is the
 * IANA media type sent to the model. Extensions not in this map
 * are rejected outright — we'd rather fail loud than ship a
 * `application/octet-stream` blob the vision model can't decode.
 */
private val imageMimeByExtension: Map<String, String> = mapOf(
  "jpg" to "image/jpeg",
  "jpeg" to "image/jpeg",
  "png" to "image/png",
  "gif" to "image/gif",
  "webp" to "image/webp",
  "bmp" to "image/bmp",
  "tiff" to "image/tiff",
  "tif" to "image/tiff",
  "heic" to "image/heic",
  "heif" to "image/heif"
)

/**
 * Resolve the wire MIME type from [file]'s extension. Returns `null`
 * for unknown / missing extensions — the caller treats that as a
 * "not an image we support" failure and bails out.
 */
private fun getImageMimeType(file: VirtualFile): String? {
  val extension: String = file.extension?.lowercase() ?: return null
  return imageMimeByExtension[extension]
}

/**
 * Read [file] verbatim and return an [AttachedImage] carrying the
 * original bytes on the wire. Returns `null` if the file extension
 * is not a recognized image format — the 5 MB cap is enforced by
 * the caller in
 * [gradum.idea.GradumToolWindowFactory.attachImages] before we get
 * here, so this function does no size check of its own.
 *
 * The bytes are base64-encoded for the wire because the plugin
 * surfaces the payload as a UTF-8 string in the request body.
 * Original file content is preserved byte-for-byte — vision
 * models that need lossless input (HEIC from iPhone screenshots,
 * WebP animations, large PNGs with subtle gradients) get exactly
 * what the user picked, not a re-encoded JPEG.
 *
 * Note that the *render* path is independent: the original
 * [VirtualFile] is still passed to [AttachedImage.file] and the
 * Compose preview chip decodes it on demand. The wire path and
 * the render path share the on-disk file but never re-encode.
 */
internal fun encodeImageToAttachment(file: VirtualFile): AttachedImage? {
  val mime: String = getImageMimeType(file) ?: return null
  val bytes: ByteArray = file.contentsToByteArray()

  return AttachedImage(
    file = file,
    mime = mime,
    originalName = file.name,
    originalSizeBytes = file.length,
    id = UUID.randomUUID().toString(),
    data = Base64.getEncoder().encodeToString(bytes)
  )
}
