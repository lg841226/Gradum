package gradum.idea.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.intellij.util.IconUtil
import org.jetbrains.jewel.ui.component.Icon

@Composable
fun SwingIcon(icon: javax.swing.Icon, contentDescription: String?, modifier: Modifier = Modifier) {
  val bitmap: ImageBitmap = remember(icon) { IconUtil.toBufferedImage(icon).toComposeImageBitmap() }
  Icon(bitmap, contentDescription = contentDescription, modifier = modifier)
}
