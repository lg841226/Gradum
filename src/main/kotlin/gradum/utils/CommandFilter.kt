/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * CommandFilter.kt  2026-08-14 12:44:12 Changed by gwy
 */

package gradum.utils

import gradum.ToolMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Paths

private val logger: Logger = LoggerFactory.getLogger("CommandFilter")

sealed class CommandVerdict {

  data object Safe : CommandVerdict()

  data class Blocked(val ruleName: String, val description: String) : CommandVerdict()
}

private val blockedExecutables: Set<String> = setOf(
  "mkfs", "mkfs.ext2", "mkfs.ext3", "mkfs.ext4",
  "mkfs.xfs", "mkfs.btrfs", "mkfs.vfat", "mkfs.ntfs",
  "mkswap", "fdisk", "sfdisk", "parted", "gdisk",
  "shutdown", "reboot", "halt", "poweroff", "init",
  "sudo", "su", "doas", "pkexec"
)

/**
 * System and home paths that destructive operations must never touch.
 *
 * Single source of truth for the protected-path check, shared by
 * [classifyCommand] (rm / recursive chmod) and the file-writing
 * skills (e.g. SaveFileSkill). If a path needs protecting, add it
 * here — not in a per-skill copy.
 */
object ProtectedPaths {

  private val systemPrefixes: List<String> = listOf(
    "/etc", "/usr", "/var", "/boot", "/bin", "/sbin",
    "/lib", "/lib64", "/opt",
    "/System", "/Library", "/Applications", "/private"
  )

  private val protectedHomeSubdirectories: List<String> = listOf(
    ".ssh", ".gnupg", ".aws", ".kube", ".netrc",
    ".pypirc", ".npmrc", ".docker",
  )

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
   *
   * If a future feature needs an additional directory (a CI
   * `$RUNNER_TEMP`, a Gradle build cache, etc.), add it here and
   * make sure the consumers in PathResolver still gate the
   * safe-prefix opt-in on the LLM typing an absolute path
   * explicitly.
   */
  val safePathPrefixes: List<String> = listOf("/tmp")

  private val exactProtectedPaths: List<String> = listOf("/", "/dev", "/proc", "/sys")

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
 * Executables that are safe to invoke when the active [ToolMode] is
 * [ToolMode.READ_ONLY]. Any command whose head token resolves to a name
 * outside this whitelist is rejected before it reaches the shell.
 *
 * Anything that can mutate the filesystem, network, or process state
 * is deliberately absent. `git` is excluded too — its write subcommands
 * (commit, push, checkout, reset, clean, stash) are easy to reach and
 * hard to enumerate exhaustively, so a Read-only session skips it
 * entirely rather than trying to filter subcommands.
 */
private val readOnlyAllowedExecutables: Set<String> = setOf(
  // directory listing
  "ls", "tree", "pwd", "dir",
  // file content
  "cat", "head", "tail", "less", "more", "bat",
  // search / text
  "grep", "rg", "ag", "ack", "find", "wc",
  "sort", "uniq", "cut", "tr", "awk", "diff", "cmp", "xargs",
  // file metadata
  "file", "stat", "du", "df", "readlink", "realpath",
  // system info
  "uname", "whoami", "date", "which", "whereis", "type",
  "id", "groups", "ps", "top", "htop", "hostname", "uptime", "arch",
  "system_profiler",
  // pure output (no file write)
  "echo", "printf", "true", "false", "test", "yes",
)

/**
 * Classify a shell command. Returns [CommandVerdict.Safe] when the
 * command may run, or [CommandVerdict.Blocked] with the reason.
 *
 * Two layers of filtering run, in order:
 * 1. **Always-on danger filter** — blocklists dangerous executables
 *    (`sudo`, `mkfs`, etc.) and operations against protected paths.
 *    Independent of [toolMode] so it always applies.
 * 2. **Read-only whitelist** — when [toolMode] is [ToolMode.READ_ONLY],
 *    the head executable must appear in [readOnlyAllowedExecutables].
 *    Any other executable is blocked so the model cannot reach
 *    `rm`, `mv`, `touch`, `mkdir`, `git commit`, `>`, etc. through
 *    `run_cmd` even though `run_cmd` is in the tool list.
 *
 * Defaults to [ToolMode.AGENT] for callers that don't have a toolMode
 * in scope (the existing dangerous-only path inside RunCommandSkill).
 */
fun classifyCommand(commandText: String, toolMode: ToolMode = ToolMode.AGENT): CommandVerdict {
  if (commandText.isBlank()) return CommandVerdict.Safe

  val tokens: List<String> = commandText.trim().split(WHITESPACE_PATTERN)
  if (tokens.isEmpty()) return CommandVerdict.Safe

  val executableName: String = Paths.get(tokens[0]).fileName.toString()

  if (executableName in blockedExecutables) {
    return CommandVerdict.Blocked("executable:$executableName", "'$executableName' is not allowed")
  }

  val baseVerdict: CommandVerdict = when (executableName) {
    "dd" -> classifyDeviceWrite(tokens)
    "rm" -> classifyRemoveOperation(tokens)
    "chmod" -> classifyChmodOperation(tokens)
    else -> CommandVerdict.Safe
  }
  if (baseVerdict is CommandVerdict.Blocked) return baseVerdict

  if (toolMode == ToolMode.READ_ONLY) {
    // Walk every subcommand separated by `|`, `||`, `&&`, `;` so a
    // chained pipeline can't sneak a write past the head-only check
    // above (e.g. `cat file | tee out`, `ls && touch foo`).
    for (subcommand: String in commandText.split(SHELL_OPERATOR_PATTERN)) {
      val trimmed: String = subcommand.trim()
      if (trimmed.isEmpty()) continue
      val subTokens: List<String> = trimmed.split(WHITESPACE_PATTERN)
      val subcommandExecutable: String = Paths.get(subTokens[0]).fileName.toString()
      if (subcommandExecutable !in readOnlyAllowedExecutables) {
        return CommandVerdict.Blocked(
          "readonly:executable:$subcommandExecutable",
          "Read-only mode: '$subcommandExecutable' is not in the read-only command set"
        )
      }
    }
    // Catch shell-side write attempts that would slip past the
    // executable whitelist: `echo hi > out.txt`, `cat in | tee out`,
    // `ls > listing`. Only flag fd-to-file redirects; fd-to-fd
    // redirections like `2>&1` are still allowed since they don't
    // touch the filesystem.
    if (hasShellFileRedirect(commandText)) {
      return CommandVerdict.Blocked(
        "readonly:shell-redirect",
        "Read-only mode: output redirection is not allowed"
      )
    }

    // Command substitution / backticks let a whitelisted head token
    // (echo, cat, grep…) execute arbitrary nested commands whose head
    // is never checked — `echo $(rm -rf ./src)` is Safe by head-only
    // analysis. Same for `find ... -exec`, which runs a nested command,
    // and `xargs <cmd>` where the piped input becomes that command's
    // arguments (e.g. `find . | xargs rm`).
    if (COMMAND_SUBSTITUTION_PATTERN.containsMatchIn(commandText) ||
      FIND_EXEC_PATTERN.containsMatchIn(commandText) ||
      XARGS_HEAD_PATTERN.matches(commandText.trim())
    ) {
      return CommandVerdict.Blocked(
        "readonly:nested-execution",
        "Read-only mode: command substitution / nested execution is not allowed"
      )
    }
  }

  return CommandVerdict.Safe
}

/** Pipeline / chain operators that split a shell command into subcommands. */
private val SHELL_OPERATOR_PATTERN: Regex = Regex("""[|;&]""")

private val WHITESPACE_PATTERN: Regex = Regex("""\s+""")

/**
 * True when commandText contains an output redirect to a file:
 * `> file`, `>> file`, `>| file`, and fd-source forms like `1>file`,
 * `2>>file` (a bare fd number before `>` points the fd at a file, not
 * at another fd). Does NOT match fd-merge redirections `2>&1`, `>&2`,
 * `&>` where the `>` is followed by `&`, so read-only idioms like
 * `cmd 2>&1` still pass.
 */
private val SHELL_REDIRECT_PATTERN: Regex = Regex(""">>?(?!&)""")

private fun hasShellFileRedirect(commandText: String): Boolean {
  return SHELL_REDIRECT_PATTERN.containsMatchIn(commandText)
}

/** `$(...)` command substitution or backticks — nested executable whose head is unchecked. */
private val COMMAND_SUBSTITUTION_PATTERN: Regex = Regex("""\$\(|`\S+""")

/** `find ... -exec <cmd> ... \;` / `find ... -delete` — nested write. */
private val FIND_EXEC_PATTERN: Regex = Regex("""\bfind\b.*\b-exec\b|\bfind\b.*\b-delete\b""")

/** `xargs <executable>` — piped input becomes that executable's args. */
private val XARGS_HEAD_PATTERN: Regex = Regex("""^\s*xargs\b.*""")

private fun classifyDeviceWrite(commandTokens: List<String>): CommandVerdict {
  for (token in commandTokens) {
    if (token.startsWith("of=") && token.removePrefix("of=").startsWith("/dev/")) {
      return CommandVerdict.Blocked(
        "dd:deviceOutput",
        "Writing to device '${token.removePrefix("of=")}' is not allowed",
      )
    }
  }
  return CommandVerdict.Safe
}

private fun classifyRemoveOperation(commandTokens: List<String>): CommandVerdict {
  val pathArguments: List<String> = extractPathArguments(commandTokens)
  for (targetPath in pathArguments) {
    if (isCriticalPath(targetPath)) {
      return CommandVerdict.Blocked("rm:criticalPath", "Removing critical path '$targetPath' is not allowed")
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
  return commandTokens.drop(1).filter { token: String -> !token.startsWith("-") }
}

private fun isCriticalPath(targetPath: String): Boolean =
  ProtectedPaths.isProtected(targetPath)
