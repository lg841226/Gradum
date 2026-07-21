/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.editor.AttachedContext
import gradum.idea.editor.AttachedImage
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
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
      imageAttachments.forEach { image ->
        ImageThumbnailChip(
          imageAttachment = image,
          onClick = { onAttachmentClick(image.file) },
          modifier = Modifier.size(chipSizeDp)
        )
      }
    }
  }
}

/** Single thumbnail chip with rounded corners and border. */
@Composable
private fun ImageThumbnailChip(
  imageAttachment: AttachedImage, onClick: () -> Unit, modifier: Modifier = Modifier
) {
  val decodedBitmap: ImageBitmap? = remember(imageAttachment.file.path) {
    decodeImage(file = imageAttachment.file)
  }

  val clipShape = RoundedCornerShape(bubbleThumbnailCornerRadiusDp.dp)
  val chipModifier: Modifier = modifier
    .clip(clipShape)
    .border(
      width = 1.dp,
      shape = clipShape,
      color = JewelTheme.globalColors.borders.normal
    )
    .clickable(onClick = onClick)

  if (decodedBitmap != null) {
    Image(
      modifier = chipModifier,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      painter = BitmapPainter(
        image = decodedBitmap,
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
  SkiaImage.makeFromEncoded(file.contentsToByteArray()).toComposeImageBitmap()
}.getOrNull()
