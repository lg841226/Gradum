/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Attachments.kt  2026-06-30 23:35:47 Changed by gwy
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
