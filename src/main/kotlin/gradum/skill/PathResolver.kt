/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PathResolver.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.skill

import gradum.utils.ProtectedPaths
import java.io.File
import java.io.IOException
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
 * @property rejectionReason Non-null when the path was
 *   rejected by the [resolveProjectPath]'s
 *   `requireWithinProject` check — i.e. the resolved path
 *   escapes the project root and is not on the safe-prefix
 *   list (`/tmp`). The tool should fail the call with
 *   `PERMISSION_DENIED` rather than operate on the path.
 */
data class ResolvedProjectPath(
  val resolved: Path,
  val original: String,
  val shifted: Boolean,
  val shiftedForm: String? = null,
  val rejectionReason: String? = null
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
 * ## Security: `requireWithinProject`
 *
 * The LLM is treated as untrusted input — a prompt-injected
 * search result or a malicious context file can tell it to
 * read `/etc/passwd`, `~/.ssh/id_rsa`, or the cloud metadata
 * service. With `requireWithinProject = true` (the default
 * for every read/write skill), this resolver refuses to
 * produce a path that escapes [projectRoot] and isn't on
 * [ProtectedPaths.safePathPrefixes]. The check is
 * segment-boundary aware (so `/home/u/proj-evil` is not
 * accepted as "inside" `/home/u/proj`) and normalized, so a
 * `..` traversal resolves to a path that's still checked
 * against the same boundary.
 *
 * The check is opt-out via the parameter rather than
 * enforced by the caller so the protection lives next to
 * the path math (single source of truth) instead of being
 * duplicated in every skill.
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
 *  - blank [filePath] or blank [projectRoot] — the resolved
 *    path is marked rejected (no project to constrain to)
 *  - absolute [filePath] (POSIX `/...` or Windows
 *    `C:\\...`); absolute paths must still be under
 *    [projectRoot] (or in a safe prefix) when
 *    `requireWithinProject` is `true`, otherwise they are
 *    rejected
 *  - projectRoot has no basename (unusual, but defensive)
 *  - first segment doesn't match the projectRoot basename
 *  - the shift would leave an empty remainder
 *  - the shifted candidate does not exist on disk
 */
fun resolveProjectPath(
  filePath: String, projectRoot: String, requireWithinProject: Boolean = true
): ResolvedProjectPath {
  val trimmed = filePath.trim()
  val normalizedRoot: Path? = projectRootOrNull(projectRoot)

  if (trimmed.isBlank()) {
    return rejectedPath(original = trimmed, reason = "file path is blank")
  }
  if (Paths.get(trimmed).isAbsolute) {
    val absolute = Paths.get(trimmed).toAbsolutePath().normalize()
    return evaluate(
      shifted = false,
      original = trimmed,
      resolved = absolute,
      originalWasAbsolute = true,
      normalizedRoot = normalizedRoot,
      requireWithinProject = requireWithinProject
    )
  }

  if (normalizedRoot == null) {
    return rejectedPath(original = trimmed, reason = "project root is not configured")
  }

  val direct = Paths.get(normalizedRoot.toString(), trimmed).toAbsolutePath().normalize()
  if (File(direct.toString()).exists()) {
    return evaluate(
      shifted = false,
      resolved = direct,
      original = trimmed,
      originalWasAbsolute = false,
      normalizedRoot = normalizedRoot,
      requireWithinProject = requireWithinProject
    )
  }

  val projectBasename = normalizedRoot.fileName?.toString()
  if (projectBasename.isNullOrBlank()) {
    return evaluate(
      shifted = false,
      resolved = direct,
      original = trimmed,
      originalWasAbsolute = false,
      normalizedRoot = normalizedRoot,
      requireWithinProject = requireWithinProject
    )
  }

  val segments = trimmed.split('/', '\\')
  if (segments.firstOrNull() != projectBasename) {
    return evaluate(
      shifted = false,
      original = trimmed,
      resolved = direct,
      originalWasAbsolute = false,
      normalizedRoot = normalizedRoot,
      requireWithinProject = requireWithinProject
    )
  }

  val shiftedTrimmed = segments.drop(n = 1).joinToString(separator = "/")
  if (shiftedTrimmed.isBlank()) {
    return evaluate(
      shifted = false,
      resolved = direct,
      original = trimmed,
      originalWasAbsolute = false,
      normalizedRoot = normalizedRoot,
      requireWithinProject = requireWithinProject
    )
  }

  val shifted = Paths.get(normalizedRoot.toString(), shiftedTrimmed).toAbsolutePath().normalize()
  return if (File(shifted.toString()).exists()) {
    evaluate(
      shifted = true,
      resolved = shifted,
      original = trimmed,
      originalWasAbsolute = false,
      shiftedForm = shiftedTrimmed,
      normalizedRoot = normalizedRoot,
      requireWithinProject = requireWithinProject
    )
  } else {
    evaluate(
      shifted = false,
      resolved = direct,
      original = trimmed,
      originalWasAbsolute = false,
      normalizedRoot = normalizedRoot,
      requireWithinProject = requireWithinProject
    )
  }
}

/**
 * Apply the within-project constraint to [resolved] when
 * [requireWithinProject] is `true`, and return either a
 * successful [ResolvedProjectPath] or a rejected one. Centralizes
 * the boundary check so every code path through [resolveProjectPath]
 * uses the same definition of "inside the project".
 *
 * [originalWasAbsolute] is the gate on the safe-prefix escape:
 * only an LLM that explicitly typed an absolute path can opt into
 * a `/tmp`-style carve-out. A relative path whose `..` happens to
 * land in `/tmp` is still rejected — that's the point of the
 * boundary check, and treating the two cases the same is what let
 * a project under `java.io.tmpdir` (the macOS default) silently
 * expose its siblings as "safe scratch space".
 */
private fun evaluate(
  resolved: Path,
  original: String,
  shifted: Boolean,
  normalizedRoot: Path?,
  originalWasAbsolute: Boolean,
  shiftedForm: String? = null,
  requireWithinProject: Boolean
): ResolvedProjectPath {
  if (!requireWithinProject)
    return ResolvedProjectPath(resolved, original, shifted, shiftedForm)

  val within: Boolean = isWithinProjectRoot(resolved, normalizedRoot) ||
    (originalWasAbsolute && isInSafePrefix(resolved))
  if (within) {
    if (isResolvedThroughSymlinkOutside(resolved, normalizedRoot, originalWasAbsolute)) {
      return rejectedPath(
        original,
        reason = "resolved path '$resolved' traverses a symlink that points outside the project root"
      )
    }
    return ResolvedProjectPath(resolved, original, shifted, shiftedForm)
  }
  return rejectedPath(
    original,
    reason = "resolved path '$resolved' is outside the project root " +
      "(${normalizedRoot ?: "<unset>"})"
  )
}

/** Reject the path with a single, machine-and-LLM-friendly reason. */
private fun rejectedPath(original: String, reason: String): ResolvedProjectPath =
  ResolvedProjectPath(
    shifted = false,
    original = original,
    resolved = Paths.get(""),
    rejectionReason = reason
  )

/**
 * True iff [resolved] is [normalizedRoot] itself, or a strict
 * descendant of it. Segment-boundary safe: `/home/u/proj-evil`
 * is NOT considered inside `/home/u/proj` even though the string
 * starts with it.
 */
private fun isWithinProjectRoot(resolved: Path, normalizedRoot: Path?): Boolean {
  if (normalizedRoot == null) return false
  val resolvedString: String = resolved.toString()
  val rootString: String = normalizedRoot.toString()
  return resolvedString == rootString || resolvedString.startsWith(prefix = "$rootString/")
}

/**
 * True when [resolved] exists on disk and its fully-resolved real target
 * (following symlinks) falls outside the project / safe-prefix boundary.
 * A string-level `startsWith` check is fooled by `proj/evil -> ~/.ssh`:
 * the path reads as inside, but every file operation follows the link
 * out of the project. Only checks paths that actually exist — a
 * not-yet-created target has no symlink chain to resolve yet.
 */
private fun isResolvedThroughSymlinkOutside(
  resolved: Path, normalizedRoot: Path?, originalWasAbsolute: Boolean
): Boolean {
  val resolvedFile: File = resolved.toFile()
  return resolvedFile.exists() && try {
    val realPath: Path = resolved.toRealPath()
    val realRoot: Path? = normalizedRoot?.takeIf { it.toFile().exists() }?.toRealPath()
    val realPrefixes: List<Path> = ProtectedPaths.safePathPrefixes.mapNotNull { prefix ->
      try {
        Paths.get(prefix).toRealPath()
      } catch (_: IOException) {
        null
      }
    }
    val within: Boolean = isWithinProjectRoot(resolved = realPath, normalizedRoot = realRoot) ||
      (originalWasAbsolute && realPrefixes.any { prefix ->
        isWithinProjectRoot(resolved = realPath, normalizedRoot = prefix)
      })
    !within
  } catch (_: IOException) {
    // Can't resolve the real path — be conservative and reject.
    true
  }
}

/** Allow `/tmp` etc. so test fixtures and IDE scratch files keep working. */
private fun isInSafePrefix(resolved: Path): Boolean {
  val resolvedString: String = resolved.toString()
  for (safePrefix in ProtectedPaths.safePathPrefixes) {
    if (resolvedString == safePrefix ||
      resolvedString.startsWith(prefix = "$safePrefix/")
    ) return true
  }
  return false
}

private fun projectRootOrNull(projectRoot: String): Path? {
  if (projectRoot.isBlank()) return null
  return try {
    Paths.get(projectRoot).toAbsolutePath().normalize()
  } catch (_: Exception) {
    null
  }
}
