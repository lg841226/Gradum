/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * UserChatBubble.kt  2026-06-26 23:55:00 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.model.ChatMessage
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Right-aligned user message bubble with copy and reset buttons.
 *
 * Renders inside a rounded rectangle with a border color background.
 */
@Composable
fun UserChatBubble(message: ChatMessage, onDeleteMessage: () -> Unit = {}, onCopyAsContext: (String) -> Unit = {}) {
    var isCopied by remember { mutableStateOf(false) }
    var isAttachmentsExpanded by remember { mutableStateOf(true) }
    var showResetPopup by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            Box(
                modifier = Modifier
                    .background(
                        color = JewelTheme.globalColors.borders.normal,
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 0.dp)
                    )
                    .padding(10.dp)
            ) {
                Text(text = message.content)
            }
            if (message.attachments.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .clickable { isAttachmentsExpanded = !isAttachmentsExpanded }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        key = if (isAttachmentsExpanded) AllIconsKeys.General.ChevronDown
                        else AllIconsKeys.General.ChevronRight,
                        contentDescription = null
                    )
                    Text(
                        text = message("gradum.attachments"),
                        color = JewelTheme.globalColors.text.normal,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(4.dp))
                AnimatedVisibility(visible = isAttachmentsExpanded) {
                    MessageAttachmentList(attachments = message.attachments, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                MessageCopyButton(
                    message = message,
                    isCopied = isCopied,
                    onCopy = { isCopied = true },
                    onReset = { isCopied = false },
                    onCopyAsContext = onCopyAsContext
                )
                Spacer(Modifier.width(4.dp))
                Tooltip(tooltip = { Text(text = message("gradum.reset.tooltip")) }) {
                    IconButton(onClick = { showResetPopup = true }) {
                        Icon(key = AllIconsKeys.General.Reset, contentDescription = message("gradum.reset"))
                    }
                }
                if (showResetPopup) {
                    PopupMenu(
                        onDismissRequest = { showResetPopup = false; true },
                        horizontalAlignment = Alignment.End
                    ) {
                        passiveItem {
                            Column(modifier = Modifier.padding(horizontal = 6.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        key = GradumIcons.Warning,
                                        contentDescription = message("gradum.delete.confirm"),
                                        modifier = Modifier.padding(end = 6.dp)
                                    )
                                    Text(text = message("gradum.delete.confirm"))
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = message("gradum.delete.revert.warning"),
                                        color = JewelTheme.globalColors.text.info
                                    )
                                }
                            }
                        }
                        separator()
                        selectableItem(
                            selected = false,
                            onClick = {
                                showResetPopup = false
                                onDeleteMessage()
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    key = AllIconsKeys.General.Reset,
                                    contentDescription = message("gradum.delete.action"),
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                Text(text = message("gradum.delete.action"))
                            }
                        }
                    }
                }
            }
        }
    }
}
