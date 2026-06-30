/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SkillContext.kt  2026-06-30 22:06:58 Changed by gwy
 */

package gradum.skill

import gradum.ToolMode

/**
 * Per-session context handed to every [Skill.execute] call.
 *
 * Centralises the two pieces of session state a Skill needs that are
 * not part of the LLM's tool-call arguments:
 *
 * - [toolMode] — the active [ToolMode] the agent loop is running in.
 *   Skills that need to branch behavior by mode (e.g. `RunCommandSkill`
 *   choosing a different log directory in READ_ONLY) can read it
 *   here. The agent still owns the gate; this is informational.
 * - [projectRoot] — the absolute, validated path to the project the
 *   current session is operating on. The plugin is the single source
 *   of truth for this value (`Project.basePath` over HTTP). The agent
 *   constructs a [SkillContext] from `AgentConfiguration.projectRoot`
 *   exactly once per session and every Skill sees the same string.
 *
 * Why this is a constructor-style parameter on [Skill.execute] rather
 * than a process-global or thread-local: the server runs multiple
 * sessions concurrently (one per `/events` request), so global state
 * would let one session's projectRoot leak into another. Passing it
 * explicitly also makes the dependency visible in unit tests — a Skill
 * can be exercised in isolation with a deterministic project root.
 *
 * @param toolMode The active permission tier for this session.
 * @param projectRoot Absolute, normalized, validated path to the
 *   project the IDE has open. Always non-empty in production (the
 *   `/events` route rejects requests that omit it).
 */
data class SkillContext(
    val toolMode: ToolMode,
    val projectRoot: String,
)
