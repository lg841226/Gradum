package gradum.utils

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val logger: Logger = LoggerFactory.getLogger("CommandFilter")

// Security-decision audit trail: every non-Safe verdict recorded here so
// operators can route command-filter decisions to a dedicated log sink
private val auditLogger: Logger = LoggerFactory.getLogger("CommandFilter.Audit")

sealed class CommandVerdict {

  data object Safe : CommandVerdict()

  data class Blocked(val ruleName: String, val description: String) : CommandVerdict()

  /**
   * Potentially destructive but only touches paths outside project root and
   * outside always-protected set. Runs only after user approval: granted for
   * one execution or remembered for session by [category] (e.g. `rm:delete`,
   * `chmod:recursive`, `dd:device-write`).
   */
  data class NeedsApproval(val category: String, val description: String) : CommandVerdict()
}

data class ProtectedPathsConfig(
  /** System roots that destructive operations must never touch */
  val systemPrefixes: List<String>,
  /** Home subdirectories that destructive operations must never touch */
  val protectedHomeSubdirectories: List<String>,
  /** Directories LLM-driven file ops are always allowed to touch */
  val safePathPrefixes: List<String>,
  /** Bare paths that are always protected (e.g. `/dev`) */
  val exactProtectedPaths: List<String>
)

/**
 * Fully-resolved command filter settings.
 *
 * [DEFAULT] mirrors the historical hardcoded lists. Any field present under
 * the `commandFilter` key of `~/.gradum/settings.json` replaces the matching
 * DEFAULT field wholesale; absent fields keep the DEFAULT. Only the *data*
 * (command / path lists) is configurable: the filtering logic (risk scoring)
 * stays in code on purpose.
 */
data class CommandFilterConfig(
  val blockedExecutables: Set<String>,
  val protectedPaths: ProtectedPathsConfig
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

// Commands that only wrap another command: filter looks through them to the real executable
private val COMMAND_WRAPPERS: Set<String> = setOf(
  "sudo", "su", "doas", "pkexec",
  "env", "nohup", "command", "builtin", "exec",
  "nice", "ionice", "setsid", "time", "timeout", "stdbuf", "xargs", "busybox",
)

// Options that consume a following value, per wrapper. Without this list the
// unwrapper would mistake an option's value (`-u root`) for the wrapped
// command and stop one token too early.
private val WRAPPER_VALUE_OPTIONS: Map<String, Set<String>> = mapOf(
  "env" to setOf("-u", "--unset", "-C", "--chdir", "-S", "--split-string"),
  "nice" to setOf("-n", "--adjustment"),
  "ionice" to setOf("-c", "--class", "-n", "--classdata"),
  "timeout" to setOf("-s", "--signal", "-k", "--kill-after"),
  "stdbuf" to setOf("-i", "-o", "-e", "--input", "--output", "--error"),
  "xargs" to setOf("-n", "-I", "-i", "-P", "-s", "-a", "-d", "-E", "-e", "-L", "-l"),
  "sudo" to setOf("-u", "--user", "-g", "--group", "-p", "--prompt", "-C", "-h", "--host", "-r", "--role", "-t", "--type", "-U", "--other-user"),
  "doas" to setOf("-u", "-C"),
  "su" to setOf("-c", "--command", "-s", "--shell"),
  "time" to setOf("-o", "--output", "-f", "--format"),
)

private val PRIVILEGE_ESCALATORS: Set<String> = setOf("sudo", "su", "doas", "pkexec")

private val SHELL_INTERPRETERS: Set<String> = setOf("sh", "bash", "zsh", "dash", "ksh", "fish")

// Executables whose non-flag operands are all write targets (source and destination alike)
private val OPERAND_WRITE_EXECUTABLES: Set<String> = setOf(
  "rm", "shred", "truncate", "chmod", "chown", "chgrp", "ln", "touch", "mkdir", "rmdir", "mv", "tee",
)

// Executables that write only to their final non-flag operand (the destination)
private val DESTINATION_WRITE_EXECUTABLES: Set<String> = setOf("cp", "install")

private val FIND_EXEC_PREDICATES: Set<String> = setOf("-exec", "-execdir", "-ok", "-okdir")

// Redirection sinks safe to write even though they sit under a protected root
private val SAFE_REDIRECTION_TARGETS: Set<String> = setOf("/dev/null", "/dev/stdout", "/dev/stderr", "/dev/tty")

// Feature weights for the command risk score. Total risk maps to 0-100:
//
// `Risk = min(100, (target and action) * (1 + (privilege, composite and injection) / 100))`
//
// The multiplier makes privilege escalation and command composition amplify
// base destructiveness (`sudo rm` worse than `rm` plus `sudo`), and
// thresholds split score into silent-pass / log / approval / block.
private const val LOG_THRESHOLD: Int = 20
private const val MUTATING_WEIGHT: Int = 15
private const val BLOCK_THRESHOLD: Int = 80
private const val COMPOSITE_WEIGHT: Int = 10
private const val PRIVILEGE_WEIGHT: Int = 40
private const val INJECTION_WEIGHT: Int = 20
private const val APPROVAL_THRESHOLD: Int = 50
private const val DESTRUCTIVE_WEIGHT: Int = 25
private const val OUTSIDE_TARGET_WEIGHT: Int = 30
private const val PROTECTED_TARGET_WEIGHT: Int = 50

// Guard against `sh -c "sh -c …"` payload chain driving deep recursion
private const val MAX_SHELL_DEPTH: Int = 4

private val COMPOSITE_PATTERN: Regex = Regex("""&&|\|\||[;|]""")
private val SHELL_C_PATTERN: Regex = Regex("""\b(?:ba|z|da|k)?sh\s+-c\b""")
private val ASSIGNMENT_PATTERN: Regex = Regex("""[A-Za-z_][A-Za-z0-9_]*=.*""")
private val RECURSIVE_FLAG_PATTERN: Regex = Regex("""^(?:-[A-Za-z]*R[A-Za-z]*|--recursive)$""")

// Destructive executables matched anywhere in text, so payload hidden inside quote still counts
private val DESTRUCTIVE_EXECUTABLE_PATTERN: Regex =
  Regex("""\b(?:rm|shred|truncate|mkfs(?:\.\w+)?|mkswap|fdisk|sfdisk|parted|gdisk|dd)\b""")

private val MUTATING_EXECUTABLE_PATTERN: Regex =
  Regex("""\b(?:mv|chmod|chown|chgrp|cp|install|ln|tee|touch|mkdir|rmdir)\b""")

private val REDIRECTION_OPERATORS: List<String> = listOf("<<<", "&>>", ">>", ">|", "<>", "<<", "&>", ">", "<")

// A shell operand after quote/escape removal
private data class ShellWord(val value: String)

private sealed interface ShellToken

private data class ShellWordToken(val value: String) : ShellToken

private data class ShellOperatorToken(val value: String) : ShellToken

// A file redirection: [operator] is raw token (`>`, `2>>`, `&>`), [target] the file operand (null for fd duplication / heredoc)
private data class Redirection(val operator: String, val target: String?)

// One simple command: its words and the redirections attached to them, split from neighbors by control operators
private data class SimpleCommand(val words: List<ShellWord>, val redirections: List<Redirection>)

private data class RiskAssessment(val riskScore: Int, val approvalCategory: String)

/**
 * System and home paths that destructive operations must never touch.
 *
 * Single source of truth for protected-path check, shared by
 * [classifyCommand] and file-writing skills (e.g. WriteFileSkill).
 * If a path needs protecting, add it here: not in a per-skill copy.
 */
object ProtectedPaths {

  val systemPrefixes: List<String>
    get() = CommandFilterRuntime.config.protectedPaths.systemPrefixes

  val protectedHomeSubdirectories: List<String>
    get() = CommandFilterRuntime.config.protectedPaths.protectedHomeSubdirectories

  /**
   * Directories that LLM-driven file operations are *always* allowed
   * to touch, regardless of project root. Currently limited to
   * `/tmp`: the universal, cross-platform "scratch space" path
   * every Unix user knows.
   *
   * `java.io.tmpdir` is deliberately NOT in this list: on macOS
   * that resolves to `/var/folders/.../T/`, and a project that
   * happens to live there (common test-fixture pattern via
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
   *
   * Comparison runs on canonicalized real paths, so quoting, `..` traversal,
   * a symlink escaping a safe prefix, and (on case-insensitive volume) a
   * differently-cased prefix can no longer smuggle a protected path past the check.
   */
  fun isProtected(targetPath: String): Boolean {
    val expandedPath: String = expandHomeTokens(targetPath)
    val canonicalTarget: String = canonicalize(expandedPath)

    for (safePrefix in safePathPrefixes) {
      if (isWithin(canonicalTarget, canonicalize(safePrefix))) return false
    }

    for (systemPrefix in systemPrefixes) {
      if (isWithin(canonicalTarget, canonicalize(systemPrefix))) return true
    }

    val homeDirectory: String = System.getProperty("user.home") ?: return false
    val canonicalHome: String = canonicalize(homeDirectory)

    for (protectedSubdirectory in protectedHomeSubdirectories) {
      if (isWithin(canonicalTarget, "$canonicalHome/$protectedSubdirectory")) return true
    }

    return exactProtectedPaths.any { isWithin(canonicalTarget, canonicalize(it)) }
  }
}

/**
 * Classify a shell command by risk score. Hard rules block outright before
 * scoring: blacklisted executables, device writes, and write operations
 * against protected system paths. Everything else is scored
 * `min(100, (target and action) * (1 + (privilege, composite and injection) / 100))`
 * and mapped to a verdict: below [LOG_THRESHOLD] silent pass, below
 * [APPROVAL_THRESHOLD] pass with a log line, below [BLOCK_THRESHOLD]
 * [CommandVerdict.NeedsApproval], otherwise [CommandVerdict.Blocked].
 *
 * Unlike a naive whitespace split, the classifier unquotes its operands,
 * looks through command wrappers (`sudo`, `env`, `nohup`, `xargs`, …), follows
 * file redirections, and understands `find -delete` / `find -exec`. A write
 * target whose path cannot be resolved statically (`$VAR/x`, `$(cat cfg)`)
 * is surfaced for approval instead of being silently allowed.
 *
 * A blank [projectRoot] means no project boundary is defined, so every
 * non-protected target path counts as outside ([OUTSIDE_TARGET_WEIGHT]).
 */
fun classifyCommand(commandText: String, projectRoot: String = ""): CommandVerdict {
  val trimmedCommand: String = commandText.trim()
  if (trimmedCommand.isEmpty()) return CommandVerdict.Safe

  val verdict: CommandVerdict = analyzeCommand(trimmedCommand, projectRoot, depth = 0)
  when (verdict) {
    is CommandVerdict.Blocked ->
      auditLogger.warn("blocked rule={} command={}", verdict.ruleName, trimmedCommand)

    is CommandVerdict.NeedsApproval ->
      auditLogger.info("approval category={} command={}", verdict.category, trimmedCommand)

    CommandVerdict.Safe -> Unit
  }
  return verdict
}

private fun analyzeCommand(commandText: String, projectRoot: String, depth: Int): CommandVerdict {
  val tokens: List<ShellToken> = tokenizeShell(commandText)
  val commands: List<SimpleCommand> = buildSimpleCommands(tokens)
  if (commands.isEmpty()) return CommandVerdict.Safe

  val nestedVerdict: CommandVerdict? = analyzeNestedShell(commandText, commands, projectRoot, depth)
  if (nestedVerdict != null) return nestedVerdict

  val unwrappedCommands: List<Pair<SimpleCommand, List<ShellWord>>> =
    commands.map { it to unwrapCommand(it.words) }

  val executableHeads: List<String> = unwrappedCommands.flatMap { (_, unwrapped) ->
    val head: String? = unwrapped.firstOrNull()?.let { executableName(it.value) }
    buildList {
      if (head != null) add(head)
      addAll(embeddedFindHeads(unwrapped))
    }
  }

  for (head in executableHeads) {
    if (head in CommandFilterRuntime.config.blockedExecutables) {
      return CommandVerdict.Blocked(
        ruleName = "executable:$head",
        description = "'$head' is not allowed"
      )
    }
  }

  val redirectionTargets: List<String> = commands.flatMap { command ->
    command.redirections
      .filter { isFileWriteRedirection(it.operator) }
      .mapNotNull { it.target }
      .filterNot { stripWrapping(it) in SAFE_REDIRECTION_TARGETS }
  }

  val executableTargets: List<Pair<String, String>> = unwrappedCommands.flatMap { (_, unwrapped) ->
    val owner: String = unwrapped.firstOrNull()?.let { executableName(it.value) } ?: ""
    writeTargetsFor(unwrapped).filter { it.isNotBlank() }.map { target: String -> target to owner }
  }

  for ((targetPath, owner) in executableTargets) {
    val strippedTarget: String = stripWrapping(targetPath)
    if (owner == "dd" && strippedTarget.startsWith(prefix = "/dev/")) {
      return CommandVerdict.Blocked(
        ruleName = "dd:deviceOutput",
        description = "Writing to device '$strippedTarget' is not allowed",
      )
    }
    if (ProtectedPaths.isProtected(resolveTargetForCheck(strippedTarget, projectRoot))) {
      return CommandVerdict.Blocked(
        ruleName = protectedRuleName(owner),
        description = protectedBlockDescription(owner, strippedTarget),
      )
    }
  }

  for (targetPath in redirectionTargets) {
    val strippedTarget: String = stripWrapping(targetPath)
    if (ProtectedPaths.isProtected(resolveTargetForCheck(strippedTarget, projectRoot))) {
      return CommandVerdict.Blocked(
        ruleName = "redirect:criticalPath",
        description = "Writing to critical path '$strippedTarget' is not allowed",
      )
    }
  }

  val unresolvableTarget: String? =
    (executableTargets.map { it.first } + redirectionTargets).map { stripWrapping(it) }
      .firstOrNull { isUnresolvablePath(it) }

  val dominantHead: String = dominantExecutable(commands)
  if (unresolvableTarget != null) {
    return CommandVerdict.NeedsApproval(
      category = approvalCategory(dominantHead),
      description = "The write target '$unresolvableTarget' cannot be resolved statically",
    )
  }

  if (hasXargsInvocation(commands) && dominantHead in OPERAND_WRITE_EXECUTABLES + DESTINATION_WRITE_EXECUTABLES) {
    return CommandVerdict.NeedsApproval(
      category = approvalCategory(dominantHead),
      description = "xargs forwards paths from its input stream to '$dominantHead'",
    )
  }

  val assessment: RiskAssessment =
    assessRisk(commandText, commands, executableTargets.map { it.first } + redirectionTargets, projectRoot, dominantHead)

  return when {
    assessment.riskScore >= BLOCK_THRESHOLD -> CommandVerdict.Blocked(
      ruleName = "risk:highScore",
      description = "Risk score ${assessment.riskScore} for '$commandText'",
    )

    assessment.riskScore >= APPROVAL_THRESHOLD -> CommandVerdict.NeedsApproval(
      category = assessment.approvalCategory,
      description = "'${assessment.approvalCategory}' risk score ${assessment.riskScore} for '$commandText'",
    )

    else -> {
      if (assessment.riskScore >= LOG_THRESHOLD)
        logger.info("Command risk score {}: '{}'", assessment.riskScore, commandText)
      CommandVerdict.Safe
    }
  }
}

// Resolves a `sh -c "<payload>"` payload by classifying it as its own
// command. A nested block is reported as [risk:highScore] because the real
// target was not visible at the outer level; a nested approval propagates
// unchanged; a nested pass falls through to the outer analysis.
private fun analyzeNestedShell(
  commandText: String, commands: List<SimpleCommand>, projectRoot: String, depth: Int
): CommandVerdict? {
  val payload: String = commands.firstNotNullOfOrNull { command ->
    val unwrapped: List<ShellWord> = unwrapCommand(command.words)
    val head: String = unwrapped.firstOrNull()?.let { executableName(it.value) } ?: return@firstNotNullOfOrNull null
    if (head !in SHELL_INTERPRETERS) return@firstNotNullOfOrNull null
    val args: List<ShellWord> = unwrapped.drop(1)
    val flagIndex: Int = args.indexOfFirst { it.value == "-c" }
    if (flagIndex < 0) return@firstNotNullOfOrNull null
    args.getOrNull(flagIndex + 1)?.value
  } ?: return null

  if (payload.isBlank()) return null

  if (depth >= MAX_SHELL_DEPTH) {
    auditLogger.warn("shell depth limit reached: {}", commandText)
    return CommandVerdict.NeedsApproval(
      category = "shell:depthLimit",
      description = "Nested shell payload exceeds the analysis depth limit",
    )
  }

  return when (val payloadVerdict: CommandVerdict = analyzeCommand(payload, projectRoot, depth + 1)) {
    is CommandVerdict.Blocked -> CommandVerdict.Blocked(
      ruleName = "risk:highScore",
      description = "Nested shell payload is unsafe: ${payloadVerdict.description}",
    )

    is CommandVerdict.NeedsApproval -> payloadVerdict
    CommandVerdict.Safe -> null
  }
}

// Target paths the head executable operates on: `of=` values for `dd`, the
// operands of any write executable, `find` roots when a destructive predicate
// is present, and the payload of a `find -exec <cmd>`.
private fun writeTargetsFor(unwrapped: List<ShellWord>): List<String> {
  val head: String = unwrapped.firstOrNull()?.let { executableName(it.value) } ?: return emptyList()
  val args: List<ShellWord> = unwrapped.drop(1)

  return when (head) {
    "dd" -> args.mapNotNull { word -> ofValue(word.value) }
    "find" -> findWriteTargets(args)
    in OPERAND_WRITE_EXECUTABLES -> nonFlagOperands(args)
    in DESTINATION_WRITE_EXECUTABLES -> nonFlagOperands(args).lastOrNull()?.let { listOf(it) } ?: emptyList()
    else -> emptyList()
  }
}

private fun findWriteTargets(args: List<ShellWord>): List<String> {
  val embeddedTargets: List<String> = extractFindExecTargets(args)
  val hasDeletePredicate: Boolean = args.any { it.value == "-delete" }
  if (!hasDeletePredicate && embeddedTargets.isEmpty()) return emptyList()

  val roots: List<String> = args
    .takeWhile { !it.value.startsWith(prefix = "-") && it.value != "!" && it.value != "(" }
    .map { it.value }

  return roots + embeddedTargets
}

// Write targets of a `find -exec <cmd> …` predicate; empty when embedded command is read-only
private fun extractFindExecTargets(args: List<ShellWord>): List<String> {
  val predicateIndex: Int = args.indexOfFirst { it.value in FIND_EXEC_PREDICATES }
  if (predicateIndex < 0) return emptyList()

  val embeddedWords: List<ShellWord> = args.drop(predicateIndex + 1)
    .takeWhile { it.value != ";" && it.value != "+" }

  return writeTargetsFor(embeddedWords)
}

// Heads of the commands embedded in `find -exec`, so blacklisted executables are caught there too
private fun embeddedFindHeads(unwrapped: List<ShellWord>): List<String> {
  if (unwrapped.firstOrNull()?.let { executableName(it.value) } != "find") return emptyList()
  return extractEmbeddedFindCommands(unwrapped.drop(1))
    .mapNotNull { command -> command.firstOrNull()?.let { executableName(it.value) } }
}

private fun extractEmbeddedFindCommands(args: List<ShellWord>): List<List<ShellWord>> {
  val commands = mutableListOf<List<ShellWord>>()
  for ((position, word) in args.withIndex()) {
    if (word.value !in FIND_EXEC_PREDICATES) continue
    val embedded: List<ShellWord> = args.drop(position + 1).takeWhile { it.value != ";" && it.value != "+" }
    if (embedded.isNotEmpty()) commands += embedded
  }
  return commands
}

private fun nonFlagOperands(args: List<ShellWord>): List<String> = args
  .filter { !it.value.startsWith(prefix = "-") }
  .map { it.value }

private fun ofValue(token: String): String? =
  if (token.startsWith(prefix = "of=")) token.removePrefix(prefix = "of=") else null

private fun assessRisk(
  commandText: String, commands: List<SimpleCommand>, targets: List<String>, projectRoot: String, dominantName: String
): RiskAssessment {
  var targetWeight: Int = targets.map { stripWrapping(it) }
    .maxOfOrNull { targetPath: String -> targetWeightFor(targetPath, projectRoot) } ?: 0

  val privilegeWeight: Int =
    if (commands.any { command -> command.words.any { executableName(it.value) in PRIVILEGE_ESCALATORS } })
      PRIVILEGE_WEIGHT else 0

  val compositeWeight: Int =
    if (COMPOSITE_PATTERN.containsMatchIn(input = commandText)) COMPOSITE_WEIGHT else 0

  val injectionWeight: Int =
    if (hasCommandInjection(commandText, commands)) INJECTION_WEIGHT else 0

  val actionWeight: Int = actionWeight(commands, commandText)

  if (injectionWeight > 0 && actionWeight >= DESTRUCTIVE_WEIGHT && targetWeight < PROTECTED_TARGET_WEIGHT)
    targetWeight = PROTECTED_TARGET_WEIGHT

  val rawScore: Int = (targetWeight + actionWeight) *
    (100 + privilegeWeight + compositeWeight + injectionWeight) / 100
  return RiskAssessment(
    riskScore = minOf(a = 100, b = rawScore),
    approvalCategory = approvalCategory(dominantName),
  )
}

private fun targetWeightFor(targetPath: String, projectRoot: String): Int =
  when {
    ProtectedPaths.isProtected(resolveTargetForCheck(targetPath, projectRoot)) -> PROTECTED_TARGET_WEIGHT
    isAbsoluteUnderSafePrefix(targetPath) -> 0
    isOutsideProjectRoot(targetPath, projectRoot) -> OUTSIDE_TARGET_WEIGHT
    else -> 0
  }

// Action weight: 0 for read-only commands, [MUTATING_WEIGHT] for plain file
// mutations, [DESTRUCTIVE_WEIGHT] for irreversible deletes, raw writes,
// file redirections, recursive `chmod`, and `find -delete` / `find -exec`.
// Destructive executables are matched anywhere in the text so a payload
// hidden inside a composite or injected string still scores.
private fun actionWeight(commands: List<SimpleCommand>, commandText: String): Int {
  if (DESTRUCTIVE_EXECUTABLE_PATTERN.containsMatchIn(commandText)) return DESTRUCTIVE_WEIGHT
  if (commandText.contains(other = "of=")) return DESTRUCTIVE_WEIGHT
  if (commands.any { command -> command.redirections.any { isFileWriteRedirection(it.operator) } })
    return DESTRUCTIVE_WEIGHT
  if (commands.any { command -> command.words.any { it.value == "-delete" } }) return DESTRUCTIVE_WEIGHT
  if (commands.any { command -> command.words.any { it.value in FIND_EXEC_PREDICATES } })
    return DESTRUCTIVE_WEIGHT
  if (hasRecursiveChmod(commands)) return DESTRUCTIVE_WEIGHT
  if (MUTATING_EXECUTABLE_PATTERN.containsMatchIn(commandText)) return MUTATING_WEIGHT
  return 0
}

private fun hasRecursiveChmod(commands: List<SimpleCommand>): Boolean =
  commands.any { command ->
    command.words.any { executableName(it.value) == "chmod" } &&
      command.words.any { RECURSIVE_FLAG_PATTERN.matches(it.value) }
  }

private fun hasCommandInjection(commandText: String, commands: List<SimpleCommand>): Boolean {
  if (commandText.contains(other = "$(") || commandText.contains(other = "`")) return true
  if (SHELL_C_PATTERN.containsMatchIn(input = commandText)) return true

  val hasComposite: Boolean = COMPOSITE_PATTERN.containsMatchIn(input = commandText)
  return hasComposite && commands.any { command ->
    command.words.any { executableName(it.value) in SHELL_INTERPRETERS }
  }
}

private fun dominantExecutable(commands: List<SimpleCommand>): String {
  val heads: List<String> = commands.mapNotNull { command ->
    unwrapCommand(command.words).firstOrNull()?.let { executableName(it.value) }
  }
  val writeHeads: Set<String> = OPERAND_WRITE_EXECUTABLES + DESTINATION_WRITE_EXECUTABLES
  return heads.firstOrNull { it in writeHeads } ?: heads.firstOrNull() ?: ""
}

private fun protectedRuleName(executableName: String): String =
  when (executableName) {
    "rm" -> "rm:criticalPath"
    "chmod" -> "chmod:criticalPathRecursive"
    else -> "$executableName:criticalPath"
  }

private fun protectedBlockDescription(executableName: String, targetPath: String): String = when (executableName) {
  "rm" -> "Removing critical path '$targetPath' is not allowed"
  "chmod" -> "Recursive chmod on critical path '$targetPath' is not allowed"
  "dd" -> "Writing to '$targetPath' is not allowed"
  else -> "Modifying critical path '$targetPath' is not allowed"
}

private fun approvalCategory(executableName: String): String = when (executableName) {
  "rm" -> "rm:delete"
  "chmod" -> "chmod:recursive"
  "dd" -> "dd:device-write"
  else -> "$executableName:modify"
}

private fun hasXargsInvocation(commands: List<SimpleCommand>): Boolean =
  commands.any { command -> command.words.any { executableName(it.value) == "xargs" } }

private fun isOutsideProjectRoot(targetPath: String, projectRoot: String): Boolean {
  if (projectRoot.isBlank()) return true
  val rootString: String = canonicalize(projectRoot)
  val targetString: String = canonicalize(resolveTargetForCheck(targetPath, projectRoot))

  return targetString != rootString && !targetString.startsWith(prefix = "$rootString/")
}

// True when the path as written is absolute and lands under a safe scratch prefix after canonicalization
private fun isAbsoluteUnderSafePrefix(targetPath: String): Boolean {
  val expandedPath: String = expandHomeTokens(targetPath)
  val path: Path = try {
    Paths.get(expandedPath)
  } catch (_: Exception) {
    return false
  }
  if (!path.isAbsolute) return false

  val canonicalTarget: String = canonicalize(expandedPath)
  return ProtectedPaths.safePathPrefixes.any { isWithin(canonicalTarget, canonicalize(it)) }
}

// Resolve a target against [projectRoot] when relative, so a protected check never depends on the process CWD
private fun resolveTargetForCheck(targetPath: String, projectRoot: String): String {
  val expandedPath: String = expandHomeTokens(targetPath)
  if (projectRoot.isBlank()) return expandedPath
  return try {
    val path: Path = Paths.get(expandedPath)
    if (path.isAbsolute) expandedPath else Paths.get(projectRoot).resolve(path).toString()
  } catch (pathException: Exception) {
    logger.debug("Failed to resolve target '$targetPath': ${pathException.message}", pathException)
    expandedPath
  }
}

// Expands `~` and `$HOME` / `${HOME}`; other variables left for [isUnresolvablePath] to flag
private fun expandHomeTokens(targetPath: String): String {
  val homeDirectory: String = System.getProperty("user.home") ?: return targetPath
  var expanded: String = targetPath
  if (expanded == "~") expanded = homeDirectory
  else if (expanded.startsWith(prefix = "~/")) expanded = homeDirectory + expanded.substring(1)
  expanded = expanded.replace($$"${HOME}", homeDirectory).replace($$"$HOME", homeDirectory)
  return expanded
}

// True when [targetPath] still holds a variable, command substitution, or brace expression after home expansion
private fun isUnresolvablePath(targetPath: String): Boolean {
  val expandedPath: String = expandHomeTokens(targetPath)
  return expandedPath.contains(other = "$") ||
    expandedPath.contains(other = "`") ||
    expandedPath.contains(other = "{") ||
    expandedPath.contains(other = "}")
}

// Strips the wrapping a composite or injected payload leaves on an embedded
// operand (`$(/etc`, `/etc)`, `` `/etc` ``) so the same path is checked
// whether it was written bare, quoted, or substituted.
private fun stripWrapping(rawToken: String): String {
  var value: String = rawToken
  if (value.startsWith(prefix = "$(")) value = value.removePrefix(prefix = "$(")
  else if (value.startsWith(prefix = $$"${")) value = value.removePrefix(prefix = $$"${")
  return value
    .trimStart('`', '"', '\'', '(')
    .trimEnd(')', '}', '`', '"', '\'')
}

// The trailing path component of a token, with wrapper characters removed, for executable matching
private fun executableName(rawToken: String): String =
  rawToken.substringAfterLast('/')
    .trimStart('$', '(', '`', '"', '\'')
    .trimEnd(')', '}', '`', '"', '\'')

private fun unwrapCommand(words: List<ShellWord>): List<ShellWord> {
  var index = 0
  var guardCount = 0

  while (index < words.size && guardCount < 32) {
    guardCount += 1
    val value: String = words[index].value
    if (ASSIGNMENT_PATTERN.matches(value)) {
      index += 1
      continue
    }

    val name: String = executableName(value)
    if (name !in COMMAND_WRAPPERS) break

    index += 1

    if (name == "timeout" && index < words.size && !words[index].value.startsWith(prefix = "-"))
      index += 1

    val valueOptions: Set<String> = WRAPPER_VALUE_OPTIONS[name] ?: emptySet()
    while (index < words.size) {
      val option: String = words[index].value
      if (option == "--") {
        index += 1
        break
      }
      if (!option.startsWith(prefix = "-")) break
      index += 1
      if (option in valueOptions && index < words.size) index += 1
    }
  }

  return words.drop(index)
}

private fun buildSimpleCommands(tokens: List<ShellToken>): List<SimpleCommand> {
  val commands = mutableListOf<SimpleCommand>()
  var words = mutableListOf<ShellWord>()
  var redirections = mutableListOf<Redirection>()
  var index = 0

  while (index < tokens.size) {
    when (val token: ShellToken = tokens[index]) {
      is ShellOperatorToken -> {
        if (isRedirectionToken(token.value)) {
          val target: String? = (tokens.getOrNull(index + 1) as? ShellWordToken)?.value
          redirections += Redirection(token.value, target)
          index += if (target != null) 2 else 1
        } else {
          commands += SimpleCommand(words, redirections)
          words = mutableListOf()
          redirections = mutableListOf()
          index += 1
        }
      }

      is ShellWordToken -> {
        words += ShellWord(token.value)
        index += 1
      }
    }
  }

  commands += SimpleCommand(words, redirections)
  return commands.filter { it.words.isNotEmpty() || it.redirections.isNotEmpty() }
}

private fun isRedirectionToken(operator: String): Boolean =
  operator.contains(other = ">") || operator.contains(other = "<")

// A redirection that writes to a file (not fd duplication like `2>&1`, not a heredoc)
private fun isFileWriteRedirection(operator: String): Boolean {
  if (operator.contains(other = ">&")) return false
  if (operator.startsWith(prefix = "&>")) return true
  return operator.contains(other = ">")
}

private fun tokenizeShell(commandText: String): List<ShellToken> {
  val tokens = mutableListOf<ShellToken>()
  val current = StringBuilder()
  var hasWord = false
  var index = 0

  fun flushWord() {
    if (hasWord) tokens += ShellWordToken(current.toString())
    current.setLength(0)
    hasWord = false
  }

  while (index < commandText.length) {
    val character: Char = commandText[index]
    when {
      character == '\\' && index + 1 < commandText.length -> {
        current.append(commandText[index + 1])
        hasWord = true
        index += 2
      }

      character == '\'' -> {
        val end: Int = commandText.indexOf('\'', index + 1)
        val stop: Int = if (end < 0) commandText.length else end
        current.append(commandText, index + 1, stop)
        hasWord = true
        index = if (end < 0) commandText.length else end + 1
      }

      character == '"' -> {
        var cursor = index + 1
        while (cursor < commandText.length && commandText[cursor] != '"') {
          if (commandText[cursor] == '\\' && cursor + 1 < commandText.length) {
            current.append(commandText[cursor + 1])
            cursor += 2
          } else {
            current.append(commandText[cursor])
            cursor += 1
          }
        }
        hasWord = true
        index = if (cursor < commandText.length) cursor + 1 else cursor
      }

      character.isWhitespace() -> {
        flushWord()
        index += 1
      }

      character == '&' && commandText.getOrNull(index + 1) == '&' -> {
        flushWord()
        tokens += ShellOperatorToken("&&")
        index += 2
      }

      character == '|' && commandText.getOrNull(index + 1) == '|' -> {
        flushWord()
        tokens += ShellOperatorToken("||")
        index += 2
      }

      character == '&' && commandText.getOrNull(index + 1) == '>' -> {
        flushWord()
        val operator: String = matchRedirection(commandText, index)
        tokens += ShellOperatorToken(operator)
        index += operator.length
      }

      character == ';' || character == '|' || character == '&' -> {
        flushWord()
        tokens += ShellOperatorToken(character.toString())
        index += 1
      }

      character == '<' || character == '>' -> {
        flushWord()
        val operator: String = matchRedirection(commandText, index)
        tokens += ShellOperatorToken(operator)
        index += operator.length
      }

      character.isDigit() && !hasWord && isFdRedirectionAt(commandText, index) -> {
        flushWord()
        val operator: String = matchRedirection(commandText, index)
        tokens += ShellOperatorToken(operator)
        index += operator.length
      }

      else -> {
        current.append(character)
        hasWord = true
        index += 1
      }
    }
  }

  flushWord()
  return tokens
}

private fun matchRedirection(commandText: String, start: Int): String {
  var cursor = start
  while (cursor < commandText.length && commandText[cursor].isDigit()) cursor += 1

  val rest: String = commandText.substring(cursor)
  val operator: String? = REDIRECTION_OPERATORS.firstOrNull { rest.startsWith(prefix = it) }
  if (operator == null) return commandText.substring(start, start + 1)

  cursor += operator.length
  if (cursor < commandText.length && commandText[cursor] == '&') {
    cursor += 1
    while (cursor < commandText.length && (commandText[cursor].isDigit() || commandText[cursor] == '-')) cursor += 1
  }
  return commandText.substring(start, cursor)
}

private fun isFdRedirectionAt(commandText: String, start: Int): Boolean {
  var cursor = start
  while (cursor < commandText.length && commandText[cursor].isDigit()) cursor += 1
  return cursor > start && cursor < commandText.length && (commandText[cursor] == '<' || commandText[cursor] == '>')
}

// Canonicalizes a path to its real, absolute form. Non-existent trailing
// components are kept and appended to the nearest existing ancestor, which is
// itself resolved with [Path.toRealPath] so symlinks (`/tmp` -> `/private/tmp`)
// and a case-insensitive prefix cannot forge a path past the boundary checks.
private fun canonicalize(rawPath: String): String {
  if (rawPath.isBlank()) return rawPath

  val path: Path = try {
    Paths.get(rawPath)
  } catch (pathException: Exception) {
    logger.debug("Failed to parse path '$rawPath': ${pathException.message}", pathException)
    return rawPath
  }

  val absolutePath: Path = if (path.isAbsolute) path else path.toAbsolutePath()
  return try {
    var ancestor: Path? = absolutePath
    val missingTail = ArrayDeque<String>()
    while (ancestor != null && !Files.exists(ancestor)) {
      missingTail.addFirst(ancestor.fileName?.toString() ?: "")
      ancestor = ancestor.parent
    }

    if (ancestor == null) {
      absolutePath.normalize().toString()
    } else {
      var realPath: Path = ancestor.toRealPath()
      for (name in missingTail) realPath = realPath.resolve(name)
      realPath.normalize().toString()
    }
  } catch (ioException: IOException) {
    logger.debug("Failed to resolve real path '$rawPath': ${ioException.message}", ioException)
    absolutePath.normalize().toString()
  }
}

private fun isWithin(targetPath: String, prefixPath: String): Boolean =
  targetPath == prefixPath || targetPath.startsWith(prefix = "$prefixPath/")