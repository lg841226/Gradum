/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SkillContext.kt  2026-07-14 21:27:12 Changed by gwy
 */

package gradum.skill

import gradum.Provider
import gradum.ToolMode

/**
 * Per-session context handed to every [Skill.execute] call.
 *
 * Centralizes the session state a skill needs that is not part of the
 * LLM's tool-call arguments: the active [toolMode], the validated
 * [projectRoot], and the provider/model identity used by provider-aware
 * skills to pick schema variants.
 */
data class SkillContext(
  val toolMode: ToolMode,
  val projectRoot: String,
  val provider: Provider = Provider.OLLAMA,
  val modelName: String = "",
)
