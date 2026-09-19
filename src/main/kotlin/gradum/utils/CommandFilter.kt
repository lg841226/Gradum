/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * CommandFilter.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.utils

import gradum.ToolMode
import kotlin.jvm.Volatile
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Paths

private val logger: Logger = LoggerFactory.getLogger("CommandFilter")

sealed class CommandVerdict {

  data object Safe : CommandVerdict()

  data class Blocked(val ruleName: String, val description: String) : CommandVerdict()
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
 * (command / path lists) is configurable — the filtering logic (shell-operator
 * regexes, dd/rm/chmod analysis) stays in code on purpose.
 */
data class CommandFilterConfig(
  /** Always-on dangerous executables blacklist (independent of [ToolMode]). */
  val blockedExecutables: Set<String>,
  /**
   * Whitelist of executables allowed while [ToolMode.READ_ONLY] is active.
   * Anything mutating the filesystem / network / process state is deliberately
   * absent; `git` is excluded too since its write subcommands are easy to reach
   * and hard to enumerate exhaustively.
   */
  val readOnlyAllowedExecutables: Set<String>,
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
      readOnlyAllowedExecutables = setOf(
        // directory listing
        "ls", "tree", "pwd", "dir",
        // file content
        "cat", "head", "tail", "less", "more", "bat",
        "nl", "od", "hexdump", "xxd", "strings",
        // search / text
        "grep", "rg", "ag", "ack", "find", "wc",
        "sort", "uniq", "cut", "tr", "awk", "diff", "cmp", "xargs",
        "comm", "join", "paste",
        // file metadata
        "file", "stat", "du", "df", "readlink", "realpath",
        "basename", "dirname",
        // system info
        "uname", "whoami", "date", "which", "whereis", "type",
        "id", "groups", "ps", "top", "htop", "hostname", "uptime", "arch",
        "system_profiler", "sw_vers", "free", "nproc", "getconf", "lsof",
        // network info (read-only)
        "ping", "nslookup", "dig", "host",
        // interpreters (read-only execution)
        "python3", "python",
        // path / env
        "env", "printenv",
        // pure output (no file write)
        "echo", "printf", "true", "false", "test", "yes",
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
 * skills (e.g. SaveFileSkill). If a path needs protecting, add it
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
 * Classify a shell command. Returns [CommandVerdict.Safe] when the
 * command may run, or [CommandVerdict.Blocked] with the reason.
 *
 * Two layers of filtering run, in order:
 * 1. **Always-on danger filter** — blocklists dangerous executables
 *    (`sudo`, `mkfs`, etc.) and operations against protected paths.
 *    Independent of [toolMode] so it always applies.
 * 2. **Read-only whitelist** — when [toolMode] is [ToolMode.READ_ONLY],
 *    the head executable must appear in the configured read-only
 *    whitelist ([CommandFilterRuntime] -> [CommandFilterConfig.readOnlyAllowedExecutables]).
 *    Any other executable is blocked so the model cannot reach
 *    `rm`, `mv`, `touch`, `mkdir`, `git commit`, `>`, etc. through
 *    `run_cmd` even though `run_cmd` is in the tool list.
 *
 * Defaults to [ToolMode.AGENT] for callers that don't have a toolMode
 * in scope (the existing dangerous-only path inside RunCommandSkill).
 */
fun classifyCommand(commandText: String, toolMode: ToolMode = ToolMode.AGENT): CommandVerdict {
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

  val baseVerdict: CommandVerdict = when (effectiveName) {
    "dd" -> classifyDeviceWrite(commandTokens = tokens.drop(n = prefixOffset))
    "rm" -> classifyRemoveOperation(commandTokens = tokens.drop(n = prefixOffset))
    "chmod" -> classifyChmodOperation(commandTokens = tokens.drop(n = prefixOffset))
    else -> CommandVerdict.Safe
  }
  if (baseVerdict is CommandVerdict.Blocked) return baseVerdict

  if (toolMode == ToolMode.READ_ONLY) {
    for (subcommand: String in commandText.split(regex = SHELL_OPERATOR_PATTERN)) {
      val trimmed: String = subcommand.trim()
      if (trimmed.isEmpty()) continue
      val subTokens: List<String> = trimmed.split(regex = WHITESPACE_PATTERN)
      val subPrefixOffset: Int = if (subTokens[0] in PRIVILEGE_ESCALATORS && subTokens.size > 1) 1 else 0
      val subcommandExecutable: String = Paths.get(subTokens[subPrefixOffset]).fileName.toString()
      if (subcommandExecutable !in CommandFilterRuntime.config.readOnlyAllowedExecutables) {
        return CommandVerdict.Blocked(
          ruleName = "readonly:executable:$subcommandExecutable",
          description = "Read-only mode: '$subcommandExecutable' is not in the read-only command set"
        )
      }
    }
    if (hasShellFileRedirect(commandText)) {
      return CommandVerdict.Blocked(
        ruleName = "readonly:shell-redirect",
        description = "Read-only mode: output redirection is not allowed"
      )
    }

    if (COMMAND_SUBSTITUTION_PATTERN.containsMatchIn(input = commandText) ||
      FIND_EXEC_PATTERN.containsMatchIn(input = commandText) ||
      XARGS_HEAD_PATTERN.matches(input = commandText.trim())
    ) {
      return CommandVerdict.Blocked(
        ruleName = "readonly:nested-execution",
        description = "Read-only mode: command substitution / nested execution is not allowed"
      )
    }
  }

  return CommandVerdict.Safe
}

/** Pipeline / chain operators that split a shell command into subcommands. */
private val SHELL_OPERATOR_PATTERN: Regex = Regex(pattern = """[|;&]""")

private val WHITESPACE_PATTERN: Regex = Regex(pattern = """\s+""")

/**
 * True when commandText contains an output redirect to a file:
 * `> file`, `>> file`, `>| file`, and fd-source forms like `1>file`,
 * `2>>file` (a bare fd number before `>` points the fd at a file, not
 * at another fd). Does NOT match fd-merge redirections `2>&1`, `>&2`,
 * `&>` where the `>` is followed by `&`, so read-only idioms like
 * `cmd 2>&1` still pass.
 */
private val SHELL_REDIRECT_PATTERN: Regex = Regex(pattern = """>>?(?!&)""")

private fun hasShellFileRedirect(commandText: String): Boolean {
  return SHELL_REDIRECT_PATTERN.containsMatchIn(input = commandText)
}

/** `$(...)` command substitution or backticks — nested executable whose head is unchecked. */
private val COMMAND_SUBSTITUTION_PATTERN: Regex = Regex(pattern = """\$\(|`\S+""")

/** `find ... -exec <cmd> ... \;` / `find ... -delete` — nested write. */
private val FIND_EXEC_PATTERN: Regex = Regex(pattern = """\bfind\b.*\b-exec\b|\bfind\b.*\b-delete\b""")

/** `xargs <executable>` — piped input becomes that executable's args. */
private val XARGS_HEAD_PATTERN: Regex = Regex(pattern = """^\s*xargs\b.*""")

private fun classifyDeviceWrite(commandTokens: List<String>): CommandVerdict {
  for (token in commandTokens) {
    if (token.startsWith(prefix = "of=") && token.removePrefix("of=").startsWith(prefix = "/dev/")) {
      return CommandVerdict.Blocked(
        ruleName = "dd:deviceOutput",
        description = "Writing to device '${token.removePrefix("of=")}' is not allowed",
      )
    }
  }
  return CommandVerdict.Safe
}

private fun classifyRemoveOperation(commandTokens: List<String>): CommandVerdict {
  val pathArguments: List<String> = extractPathArguments(commandTokens)
  for (targetPath in pathArguments) {
    if (isCriticalPath(targetPath)) {
      return CommandVerdict.Blocked(
        ruleName = "rm:criticalPath",
        description = "Removing critical path '$targetPath' is not allowed"
      )
    }
  }

  return CommandVerdict.Safe
}

private fun classifyChmodOperation(commandTokens: List<String>): CommandVerdict {
  val hasRecursiveFlag: Boolean = commandTokens.any { it == "-R" || it == "--recursive" }
  if (!hasRecursiveFlag) return CommandVerdict.Safe

  val pathArguments: List<String> = extractPathArguments(commandTokens)
  for (targetPath in pathArguments) {
    if (isCriticalPath(targetPath)) {
      return CommandVerdict.Blocked(
        ruleName = "chmod:criticalPathRecursive",
        description = "Recursive chmod on critical path '$targetPath' is not allowed",
      )
    }
  }

  return CommandVerdict.Safe
}

private fun extractPathArguments(commandTokens: List<String>): List<String> {
  return commandTokens.drop(n = 1).filter { token: String -> !token.startsWith(prefix = "-") }
}

private fun isCriticalPath(targetPath: String): Boolean =
  ProtectedPaths.isProtected(targetPath)
