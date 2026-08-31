/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * MessageAttachmentPreview.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.idea.chat.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.editor.AttachedContext
import gradum.idea.editor.AttachedImage
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.HorizontallyScrollableContainer
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.skia.Image as SkiaImage

private const val bubbleThumbnailEdgeDp: Int = 100
private const val bubbleThumbnailCornerRadiusDp: Int = 6

/**
 * Image-only preview row above the user message bubble.
 *
 * Images are laid out as a right-aligned horizontal row inside
 * a [HorizontallyScrollableContainer] which provides scrolling
 * and a scrollbar when there are too many to fit. Clicking any
 * chip opens the file in the IDE.
 */
@Composable
fun MessageAttachmentPreview(
  attachments: List<AttachedContext>,
  onAttachmentClick: (VirtualFile) -> Unit = {},
  modifier: Modifier = Modifier
) {
  val imageAttachments: List<AttachedImage> = attachments.filterIsInstance<AttachedImage>()
  if (imageAttachments.isEmpty()) return

  val chipSizeDp = bubbleThumbnailEdgeDp.dp

  HorizontallyScrollableContainer(modifier = modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(GradumSpacing.md, Alignment.End)) {
      imageAttachments.forEach { image: AttachedImage ->
        ImageThumbnailChip(
          onClick = { onAttachmentClick(image.file) },
          modifier = Modifier.size(chipSizeDp),
          imageAttachment = image
        )
      }
    }
  }
}

/** Single thumbnail chip with rounded corners and border. */
@Composable
private fun ImageThumbnailChip(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  imageAttachment: AttachedImage
) {
  var decodedBitmap by remember { mutableStateOf<ImageBitmap?>(value = null) }
  LaunchedEffect(key1 = imageAttachment.file.path) {
    val bitmap: ImageBitmap? = withContext(Dispatchers.IO) {
      decodeImage(imageAttachment.file)
    }
    decodedBitmap = bitmap
  }

  val clipShape = RoundedCornerShape(size = bubbleThumbnailCornerRadiusDp.dp)
  val chipModifier: Modifier = modifier
    .clip(clipShape)
    .border(
      width = 1.dp,
      shape = clipShape,
      color = JewelTheme.globalColors.borders.normal
    )
    .clickable(onClick = onClick)

  val decoded: ImageBitmap? = decodedBitmap
  if (decoded != null) {
    Image(
      modifier = chipModifier,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      painter = BitmapPainter(
        image = decoded,
        filterQuality = FilterQuality.High
      )
    )
  } else {
    Icon(
      modifier = chipModifier,
      contentDescription = null,
      key = imageAttachment.iconKey
    )
  }
}

/** Decode `file` into a Compose `ImageBitmap`. Returns null on failure. */
private fun decodeImage(file: VirtualFile): ImageBitmap? = runCatching {
  SkiaImage.makeFromEncoded(bytes = file.contentsToByteArray()).toComposeImageBitmap()
}.getOrNull()
