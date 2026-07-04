/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SkillContext.kt  2026-07-01 21:53:11 Changed by gwy
 */

package gradum.skill

import gradum.Provider
import gradum.ToolMode

/**
 * Per-session context handed to every [Skill.execute] call.
 *
 * Centralizes the session state a Skill needs that are not part of
 * the LLM's tool-call arguments:
 *
 * - [toolMode] — the active [ToolMode] the agent loop is running in.
 * - [projectRoot] — the absolute, validated path to the project.
 * - [provider] — which LLM backend is driving this session.
 * - [modelName] — the model name string (e.g. "qwen2.5:14b"). Used
 *   by provider-aware skills to infer model capability and choose
 *   appropriate schema variants.
 *
 * @param toolMode The active permission tier for this session.
 * @param projectRoot Absolute, normalized, validated path to the
 *   project the IDE has open.
 * @param provider Which LLM backend is in use.
 * @param modelName Model name for capability inference.
 */
data class SkillContext(
    val toolMode: ToolMode,
    val projectRoot: String,
    val provider: Provider = Provider.OLLAMA,
    val modelName: String = "",
)
