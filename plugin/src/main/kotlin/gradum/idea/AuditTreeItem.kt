/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AuditTreeItem.kt  2026-08-07 22:57:30 Changed by gwy
 */

package gradum.idea

import org.jetbrains.jewel.ui.icon.IntelliJIconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** A row in the audit list: a fixed group, a severity group, or a finding child. */
internal sealed interface AuditTreeItem {
  data class Group(val group: AuditGroup, val count: Int) : AuditTreeItem
  data class SeverityGroup(val level: String, val count: Int) : AuditTreeItem
  data class Finding(val finding: AuditFinding) : AuditTreeItem
}

/** Status icon shown next to a finding, matching its severity. */
internal fun severityIcon(level: String): IntelliJIconKey = when (level) {
  "critical" -> AllIconsKeys.General.Error
  "alert" -> AllIconsKeys.General.Warning
  else -> AllIconsKeys.General.Information
}

/** Outline version of the severity icon, used for reviewed findings. */
internal fun severityOutlineIcon(level: String): IntelliJIconKey = when (level) {
  "critical" -> AllIconsKeys.Ide.FatalErrorRead
  "alert" -> AllIconsKeys.General.ShowWarning
  else -> AllIconsKeys.General.Note
}

/** Severity levels in display order, most severe first. */
internal val SEVERITY_ORDER = listOf("critical", "alert", "watch", "normal")
