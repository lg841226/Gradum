/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PathResolver.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.skill

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Result of resolving a tool-path argument against the project
 * root.
 *
 * @property resolved The final on-disk path the tool should
 *   operate on. Always non-null, always normalized. When
 *   [shifted] is `true` this is the corrected form, not the
 *   path the LLM originally passed.
 * @property original The exact string the LLM passed in
 *   (relative, after `.trim()`). Surface this in error
 *   messages so the LLM sees what it asked for.
 * @property shifted `true` if the resolver autocorrected
 *   [original] by stripping a redundant project-basename
 *   segment; `false` if the path resolved as-is (including
 *   the case where the original path was a hit and the
 *   shifted candidate was not).
 * @property shiftedForm The corrected form — only set when
 *   [shifted] is `true`. Useful for the `original` →
 *   `shiftedForm` mapping the LLM can observe in the
 *   success response.
 */
data class ResolvedProjectPath(
  val resolved: Path,
  val original: String,
  val shifted: Boolean,
  val shiftedForm: String? = null
)

/**
 * Resolve a tool-path argument against [projectRoot] with
 * autocorrection for the LLM's "redundant project-basename"
 * mistake.
 *
 * ## Why
 *
 * LLMs routinely include the project root's basename as the
 * first segment of a relative path:
 *
 * ```
 *   projectRoot = /Users/gwy/.../gradum-playground
 *   LLM call:   Read(path = "gradum-playground/src/demo.js")
 *   expected:   src/demo.js resolved against projectRoot
 * ```
 *
 * Without correction, the resolved path becomes
 * `/.../gradum-playground/gradum-playground/src/demo.js` and
 * the tool returns "file not found" — forcing the LLM to
 * re-explore the project and try again. The correction saves
 * one round-trip per occurrence. See the LLM trace in the
 * feature request: an `Explored` + a self-corrected `Read`
 * immediately after a single failed `Read`.
 *
 * ## What it does
 *
 * Given a non-blank, non-absolute [filePath]:
 *  1. Resolve [filePath] against [projectRoot] and check
 *     existence. If it exists, return as-is.
 *  2. Compute the basename of [projectRoot].
 *  3. If the **first** `/`- or `\\`-separated segment of
 *     [filePath] equals that basename, drop the first
 *     segment and resolve the remainder. If that exists,
 *     return it with [ResolvedProjectPath.shifted] = `true`.
 *  4. Otherwise, return the original (unshifted) resolution.
 *     The caller will handle "not found" as usual.
 *
 * The check is **scoped to "first segment matches project
 * basename"** deliberately. We do not try every possible
 * prefix strip — that would risk substituting a wrong file
 * that happens to exist at a shallower depth (e.g., shifting
 * a correct `src/demo.js` to a top-level `demo.js` that
 * exists by coincidence). The single-shift rule only fires
 * for the specific pattern of the LLM's mistake, and only
 * when the shift candidate is verified to exist.
 *
 * Edge cases (all return the trivial resolution with
 * [ResolvedProjectPath.shifted] = `false`):
 *  - blank [filePath] or blank [projectRoot]
 *  - absolute [filePath] (POSIX `/...` or Windows
 *    `C:\\...`); absolute paths bypass projectRoot entirely
 *  - projectRoot has no basename (unusual, but defensive)
 *  - first segment doesn't match the projectRoot basename
 *  - the shift would leave an empty remainder
 *  - the shifted candidate does not exist on disk
 */
fun resolveProjectPath(filePath: String, projectRoot: String): ResolvedProjectPath {
  val trimmed = filePath.trim()

  if (trimmed.isBlank() || projectRoot.isBlank()) {
    val fallback = if (trimmed.isBlank()) Paths.get("")
    else Paths.get(trimmed).toAbsolutePath().normalize()
    return ResolvedProjectPath(fallback, trimmed, shifted = false)
  }
  if (Paths.get(trimmed).isAbsolute) {
    return ResolvedProjectPath(
      Paths.get(trimmed).toAbsolutePath().normalize(),
      trimmed,
      shifted = false
    )
  }

  val direct = Paths.get(projectRoot, trimmed).toAbsolutePath().normalize()
  if (File(direct.toString()).exists()) {
    return ResolvedProjectPath(direct, trimmed, shifted = false)
  }

  val projectBasename = Paths.get(projectRoot).fileName?.toString()
  if (projectBasename.isNullOrBlank()) {
    return ResolvedProjectPath(direct, trimmed, shifted = false)
  }

  val segments = trimmed.split('/', '\\')
  if (segments.firstOrNull() != projectBasename) {
    return ResolvedProjectPath(direct, trimmed, shifted = false)
  }

  val shiftedTrimmed = segments.drop(1).joinToString("/")
  if (shiftedTrimmed.isBlank()) {
    return ResolvedProjectPath(direct, trimmed, shifted = false)
  }

  val shifted = Paths.get(projectRoot, shiftedTrimmed).toAbsolutePath().normalize()
  return if (File(shifted.toString()).exists()) {
    ResolvedProjectPath(shifted, trimmed, shifted = true, shiftedForm = shiftedTrimmed)
  } else {
    ResolvedProjectPath(direct, trimmed, shifted = false)
  }
}
