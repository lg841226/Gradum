/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * CommandFilter.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.utils

import kotlin.jvm.Volatile
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Paths

private val logger: Logger = LoggerFactory.getLogger("CommandFilter")

sealed class CommandVerdict {

  data object Safe : CommandVerdict()

  data class Blocked(val ruleName: String, val description: String) : CommandVerdict()

  /**
   * The command is potentially destructive but only touches paths outside
   * the project root and outside the always-protected set. It may run only
   * after the user approves — granted for one execution or remembered for
   * the session by [category] (e.g. `rm:delete`, `chmod:recursive`,
   * `dd:device-write`).
   */
  data class NeedsApproval(val category: String, val description: String) : CommandVerdict()
}

/** User-configurable lists of protected filesystem paths (see [CommandFilterConfig]). */
data class ProtectedPathsConfig(
  /** System roots that destructive operations must never touch. */
  val systemPrefixes: List<String>,
  /** Home subdirectories that destructive operations must never touch. */
  val protectedHomeSubdirectories: List<String>,
  /** Directories LLM-driven file ops are always allowed to touch. */
  val safePathPrefixes: List<String>,
  /** Bare paths that are always protected (e.g. `/dev`). */
  val exactProtectedPaths: List<String>,
)

/**
 * Fully-resolved command filter settings.
 *
 * [DEFAULT] mirrors the historical hardcoded lists. Any field present under
 * the `commandFilter` key of `~/.gradum/settings.json` replaces the matching
 * DEFAULT field wholesale; absent fields keep the DEFAULT. Only the *data*
 * (command / path lists) is configurable — the filtering logic (dd/rm/chmod
 * analysis) stays in code on purpose.
 */
data class CommandFilterConfig(
  /** Always-on dangerous executables blacklist (never configurable away). */
  val blockedExecutables: Set<String>,
  /** Paths that destructive operations must never touch. */
  val protectedPaths: ProtectedPathsConfig,
) {

  companion object {

    val DEFAULT: CommandFilterConfig = CommandFilterConfig(
      blockedExecutables = setOf(
        "mkfs", "mkfs.ext2", "mkfs.ext3", "mkfs.ext4",
        "mkfs.xfs", "mkfs.btrfs", "mkfs.vfat", "mkfs.ntfs",
        "mkswap", "fdisk", "sfdisk", "parted", "gdisk",
        "shutdown", "reboot", "poweroff",
      ),
      protectedPaths = ProtectedPathsConfig(
        systemPrefixes = listOf(
          "/etc", "/usr", "/var", "/boot", "/bin", "/sbin",
          "/lib", "/lib64", "/opt",
          "/System", "/Library", "/Applications", "/private"
        ),
        protectedHomeSubdirectories = listOf(
          ".ssh", ".gnupg", ".aws", ".kube", ".netrc",
          ".pypirc", ".npmrc", ".docker",
        ),
        safePathPrefixes = listOf("/tmp"),
        exactProtectedPaths = listOf("/", "/dev", "/proc", "/sys"),
      )
    )
  }
}

/**
 * Runtime holder so the server can swap in the user's `commandFilter` config
 * after parsing `~/.gradum/settings.json`. Defaults to [CommandFilterConfig.DEFAULT];
 * left untouched it preserves the current behavior exactly. Swap is atomic and
 * read by [classifyCommand] and [ProtectedPaths] on each invocation.
 */
object CommandFilterRuntime {

  @Volatile
  var config: CommandFilterConfig = CommandFilterConfig.DEFAULT
}

/** Prefixes that wrap a real command (e.g. `sudo reboot`). The filter skips past these. */
private val PRIVILEGE_ESCALATORS: Set<String> = setOf("sudo", "su", "doas", "pkexec")

/**
 * System and home paths that destructive operations must never touch.
 *
 * Single source of truth for the protected-path check, shared by
 * [classifyCommand] (rm / recursive chmod) and the file-writing
 * skills (e.g. WriteFileSkill). If a path needs protecting, add it
 * here — not in a per-skill copy.
 */
object ProtectedPaths {

  val systemPrefixes: List<String>
    get() = CommandFilterRuntime.config.protectedPaths.systemPrefixes

  val protectedHomeSubdirectories: List<String>
    get() = CommandFilterRuntime.config.protectedPaths.protectedHomeSubdirectories

  /**
   * Directories that LLM-driven file operations are *always* allowed
   * to touch, regardless of project root. Currently limited to
   * `/tmp` — the universal, cross-platform "scratch space" path
   * every Unix user knows.
   *
   * `java.io.tmpdir` is deliberately NOT in this list: on macOS
   * that resolves to `/var/folders/.../T/`, and a project that
   * happens to live there (a common test-fixture pattern via
   * `Files.createTempDirectory`) would then have its sibling
   * directories inside the safe prefix, letting `../escape`-style
   * paths pass the boundary check. Limiting the carve-out to
   * `/tmp` keeps the safe-prefix list narrow and the boundary
   * check honest.
   */
  val safePathPrefixes: List<String>
    get() = CommandFilterRuntime.config.protectedPaths.safePathPrefixes

  val exactProtectedPaths: List<String>
    get() = CommandFilterRuntime.config.protectedPaths.exactProtectedPaths

  /**
   * True when [targetPath] resolves to a system path, a protected home
   * subdirectory, or an exact protected path. Paths under [safePathPrefixes]
   * (e.g. `/tmp`) are always considered safe.
   */
  fun isProtected(targetPath: String): Boolean {
    val resolvedTarget: String = resolveAbsolutePath(targetPath)

    for (safePrefix in safePathPrefixes) {
      if (resolvedTarget == safePrefix || resolvedTarget.startsWith("$safePrefix/"))
        return false
    }

    for (systemPrefix in systemPrefixes) {
      val resolvedPrefix: String = resolveAbsolutePath(systemPrefix)
      if (resolvedTarget == resolvedPrefix || resolvedTarget.startsWith("$resolvedPrefix/"))
        return true
    }

    val homeDirectory: String = System.getProperty("user.home") ?: return false

    for (protectedSubdirectory in protectedHomeSubdirectories) {
      val protectedPath = "$homeDirectory/$protectedSubdirectory"
      if (resolvedTarget == protectedPath || resolvedTarget.startsWith("$protectedPath/"))
        return true
    }

    return resolvedTarget in exactProtectedPaths
  }

  private fun resolveAbsolutePath(pathString: String): String {
    return try {
      Paths.get(pathString).toAbsolutePath().normalize().toString()
    } catch (pathException: Exception) {
      logger.debug("Failed to resolve path '$pathString': ${pathException.message}", pathException)
      pathString
    }
  }
}

/**
 * Classify a shell command. Returns [CommandVerdict.Safe] when the command
 * may run, [CommandVerdict.Blocked] when a hard safety rule forbids it, or
 * [CommandVerdict.NeedsApproval] when a potentially destructive operation
 * targets a path outside [projectRoot] (and outside the always-protected set)
 * and therefore requires user authorization before it may run.
 *
 * The always-on danger filter runs for every caller: dangerous executables
 * (`sudo`, `mkfs`, ...) and operations against protected system paths are
 * blocked outright. Destructive operations (`rm`, recursive `chmod`, `dd`)
 * that only touch paths outside [projectRoot] are flagged for approval
 * instead of blocked, so the user can authorize them once or for the session.
 *
 * A blank [projectRoot] means no project boundary is defined, so any
 * non-protected target path is treated as outside and requires approval.
 */
fun classifyCommand(commandText: String, projectRoot: String = ""): CommandVerdict {
  if (commandText.isBlank()) return CommandVerdict.Safe

  val tokens: List<String> = commandText.trim().split(regex = WHITESPACE_PATTERN)
  if (tokens.isEmpty()) return CommandVerdict.Safe

  val executableName: String = Paths.get(tokens[0]).fileName.toString()
  val prefixOffset: Int = if (executableName in PRIVILEGE_ESCALATORS && tokens.size > 1) 1 else 0
  val effectiveName: String = Paths.get(tokens[prefixOffset]).fileName.toString()

  if (effectiveName in CommandFilterRuntime.config.blockedExecutables) {
    return CommandVerdict.Blocked(
      ruleName = "executable:$effectiveName",
      description = "'$effectiveName' is not allowed"
    )
  }

  return when (effectiveName) {
    "dd" -> classifyDeviceWrite(commandTokens = tokens.drop(n = prefixOffset), projectRoot = projectRoot)
    "rm" -> classifyRemoveOperation(commandTokens = tokens.drop(n = prefixOffset), projectRoot = projectRoot)
    "chmod" -> classifyChmodOperation(commandTokens = tokens.drop(n = prefixOffset), projectRoot = projectRoot)
    else -> CommandVerdict.Safe
  }
}

private val WHITESPACE_PATTERN: Regex = Regex(pattern = """\s+""")

/**
 * Combines per-target verdicts with a hard [CommandVerdict.Blocked] taking
 * priority over [CommandVerdict.NeedsApproval]; either wins over
 * [CommandVerdict.Safe]. Empty input resolves to [CommandVerdict.Safe].
 */
private fun combineVerdicts(verdicts: List<CommandVerdict>): CommandVerdict {
  for (verdict in verdicts) if (verdict is CommandVerdict.Blocked) return verdict
  for (verdict in verdicts) if (verdict is CommandVerdict.NeedsApproval) return verdict
  return CommandVerdict.Safe
}

/**
 * Verdict for a single destructive-operation target path: hard-blocked when it
 * is a protected system path, needs approval when it is outside [projectRoot],
 * otherwise safe.
 */
private fun classifyOperationTarget(
  targetPath: String, category: String, blockedRuleName: String, blockedDescription: (String) -> String, projectRoot: String
): CommandVerdict {
  if (ProtectedPaths.isProtected(targetPath))
    return CommandVerdict.Blocked(blockedRuleName, blockedDescription(targetPath))
  if (isOutsideProjectRoot(targetPath, projectRoot))
    return CommandVerdict.NeedsApproval(
      category = category,
      description = "'$category' targets '$targetPath' outside the project root",
    )
  return CommandVerdict.Safe
}

/** True when [targetPath] is not under [projectRoot]. A blank root means "outside". */
private fun isOutsideProjectRoot(targetPath: String, projectRoot: String): Boolean {
  if (projectRoot.isBlank()) return true
  val rootString: String = resolveAbsolute(projectRoot)
  val targetString: String = resolveRelativeTo(targetPath, projectRoot)
  return targetString != rootString && !targetString.startsWith("$rootString/")
}

private fun resolveAbsolute(pathString: String): String {
  return try {
    Paths.get(pathString).toAbsolutePath().normalize().toString()
  } catch (pathException: Exception) {
    logger.debug("Failed to resolve path '$pathString': ${pathException.message}", pathException)
    pathString
  }
}

/** Resolves [pathString] against [projectRoot] when it is relative, so an in-project relative path stays inside. */
private fun resolveRelativeTo(pathString: String, projectRoot: String): String {
  val path = try {
    Paths.get(pathString)
  } catch (pathException: Exception) {
    logger.debug("Failed to parse path '$pathString': ${pathException.message}", pathException)
    return pathString
  }
  return try {
    if (path.isAbsolute) path.normalize().toString()
    else Paths.get(projectRoot).resolve(path).normalize().toAbsolutePath().toString()
  } catch (pathException: Exception) {
    logger.debug("Failed to resolve path '$pathString': ${pathException.message}", pathException)
    pathString
  }
}

private fun classifyDeviceWrite(commandTokens: List<String>, projectRoot: String): CommandVerdict {
  val ofTargets: List<String> = commandTokens
    .filter { token: String -> token.startsWith(prefix = "of=") }
    .map { token: String -> token.removePrefix("of=") }
  if (ofTargets.isEmpty()) return CommandVerdict.Safe
  return combineVerdicts(
    ofTargets.map { target: String ->
      if (target.startsWith(prefix = "/dev/"))
        CommandVerdict.Blocked(
          ruleName = "dd:deviceOutput",
          description = "Writing to device '$target' is not allowed",
        )
      else
        classifyOperationTarget(
          targetPath = target, category = "dd:device-write",
          blockedRuleName = "dd:deviceOutput",
          blockedDescription = { "Writing to '$it' is not allowed" },
          projectRoot = projectRoot
        )
    }
  )
}

private fun classifyRemoveOperation(commandTokens: List<String>, projectRoot: String): CommandVerdict {
  val pathArguments: List<String> = extractPathArguments(commandTokens)
  return combineVerdicts(
    pathArguments.map { targetPath: String ->
      classifyOperationTarget(
        targetPath = targetPath, category = "rm:delete",
        blockedRuleName = "rm:criticalPath",
        blockedDescription = { "Removing critical path '$it' is not allowed" },
        projectRoot = projectRoot
      )
    }
  )
}

private fun classifyChmodOperation(commandTokens: List<String>, projectRoot: String): CommandVerdict {
  val hasRecursiveFlag: Boolean = commandTokens.any { it == "-R" || it == "--recursive" }
  if (!hasRecursiveFlag) return CommandVerdict.Safe

  val pathArguments: List<String> = extractPathArguments(commandTokens)
  return combineVerdicts(
    pathArguments.map { targetPath: String ->
      classifyOperationTarget(
        targetPath = targetPath, category = "chmod:recursive",
        blockedRuleName = "chmod:criticalPathRecursive",
        blockedDescription = { "Recursive chmod on critical path '$it' is not allowed" },
        projectRoot = projectRoot
      )
    }
  )
}

private fun extractPathArguments(commandTokens: List<String>): List<String> {
  return commandTokens.drop(n = 1).filter { token: String -> !token.startsWith(prefix = "-") }
}
