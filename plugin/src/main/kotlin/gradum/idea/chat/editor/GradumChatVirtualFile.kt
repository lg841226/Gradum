package gradum.idea.chat.editor

import com.intellij.testFramework.LightVirtualFile
import gradum.idea.utils.GradumBundle.message

/**
 * Pseudo-file backing the Gradum chat when it is opened as an editor tab.
 * [openGradumChatInEditor] reuses a single instance per project so the
 * platform keeps one focused editor tab instead of stacking duplicates.
 * Only this file type is accepted by [GradumChatEditorProvider], so real
 * project files never route through the chat editor.
 */
class GradumChatVirtualFile : LightVirtualFile(message("gradum.editor.tabname"))
