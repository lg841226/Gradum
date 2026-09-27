/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SkillStore.kt  2026-09-28 00:05:47 Changed by gwy
 */

/**
 * Abstraction over the central skill registry that the external-skill
 * pipeline needs to mutate. The scanner reloads against [SkillStore] instead
 * of the concrete [SkillRegistry] singleton so its reconcile logic can be
 * unit-tested against an in-memory fake without polluting the global registry.
 *
 * The pieces:
 *
 * - [gradum.skill.SkillRegistry.register]: binds a skill under its [Skill.skillName], replacing any
 *   entry that is already stored under that name.
 * - [gradum.skill.SkillRegistry.unregister]: removes the skill stored under the requested name and
 *   returns it, or null when nothing was registered there.
 * - [gradum.skill.SkillRegistry.getSkill]: returns the skill stored under the requested name, or null.
 */

package gradum.skill

interface SkillStore {

  fun register(skill: Skill)

  fun unregister(skillName: String): Skill?

  fun getSkill(skillName: String): Skill?
}
