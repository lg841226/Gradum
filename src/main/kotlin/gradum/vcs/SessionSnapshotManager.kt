package gradum.vcs

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private val logger: Logger = LoggerFactory.getLogger("SessionSnapshotManager")

private const val HASH_LENGTH: Int = 12
private const val TURN_REF_PREFIX: String = "refs/gradum/turn"
private const val SESSION_REF_PREFIX: String = "refs/gradum/session"
private const val SNAPSHOT_STORE_SEGMENT: String = "snapshots"
private const val SNAPSHOT_MESSAGE_PREFIX: String = "gradum snapshot"

private data class GitRunResult(val exitCode: Int, val standardOutput: String)

private class GitCommandFailure(gitMessage: String) : Exception(gitMessage)

/**
 * Per-session worktree snapshots built on git plumbing commands.
 *
 * Snapshots live in a dedicated git repository **outside** the project
 * (`~/.gradum/snapshots/<project>-<hash>`), driven with
 * `--git-dir`/`--work-tree` on every invocation — mirroring OpenCode's
 * design. The user's repository is never touched: no `git init`, no refs,
 * no object writes, so permission clashes with the user's own git tooling
 * are impossible and plain non-git directories work unchanged.
 *
 * Each snapshot is one tree object written through a temporary index
 * (`GIT_INDEX_FILE`), anchored on refs inside the snapshot store: one per
 * session (`refs/gradum/session/<sessionId>`, "session start") and one per
 * user message (`refs/gradum/turn/<sessionId>/<messageId>`, "right before
 * this message was sent"). Both are first-wins. Deleting a session reverts
 * to the session ref; rewinding a message reverts to its turn ref, undoing
 * that message's model changes along with everything after it. Reverting
 * diffs the current worktree tree against the snapshot tree and replays the
 * reverse patch with `git apply --binary`, which restores edits and deletions
 * and removes files the model created. Deleting a session also drops its refs
 * and garbage-collects the store, so storage tracks only live sessions.
 *
 * Known limits: paths matched by .gitignore are not captured, and commits
 * the model made mid-session stay in history (only the worktree reverts).
 * The `.gradum` runtime directory is excluded so a revert can never roll
 * session bookkeeping back.
 */
object SessionSnapshotManager {

  /** Runtime state excluded from snapshots so reverts never touch it. */
  private val SNAPSHOT_ADD_PATHSPECS: Array<out String> =
    arrayOf("add", "-A", "--", ".", ":(exclude).gradum")

  /**
   * Anchors the current worktree state as the restore point of [sessionId],
   * and when [turnMessageId] is given also as the per-turn restore point
   * (`refs/gradum/turn/<sessionId>/<messageId>`) that message rewinds replay.
   * Both refs are first-wins: the session ref always means "session start"
   * and a turn ref always means "right before this message was sent", even
   * when the same message id is sent again after a rewind. One tree write
   * serves both refs. Returns false when any git step fails; never throws.
   */
  fun createSnapshot(projectRoot: String, sessionId: String, turnMessageId: String? = null): Boolean {
    return sessionId.isNotBlank() && try {
      val snapshotGitDirectory: Path = snapshotStoreDirectory(projectRoot)
      ensureSnapshotRepository(projectRoot, snapshotGitDirectory)

      val sessionRef: String = sessionRefName(sessionId)
      val sessionRefExists: Boolean =
        runGitCommand(projectRoot, snapshotGitDirectory, emptyMap(), "rev-parse", "--verify", "--quiet", sessionRef)
          .exitCode == 0

      val tempIndexEnvironment: Map<String, String> = freshTempIndexEnvironment()

      runGitChecked(projectRoot, snapshotGitDirectory, tempIndexEnvironment, *SNAPSHOT_ADD_PATHSPECS)
      val treeHash: String =
        runGitChecked(projectRoot, snapshotGitDirectory, tempIndexEnvironment, "write-tree").standardOutput.trim()

      val commitHash: String = runGitChecked(
        projectRoot, snapshotGitDirectory, authorEnvironment(),
        "commit-tree", treeHash, "-m", "$SNAPSHOT_MESSAGE_PREFIX $sessionId"
      ).standardOutput.trim()

      if (sessionRefExists) {
        logger.debug("{} already exists; keeping the original restore point", sessionRef)
      } else {
        runGitChecked(projectRoot, snapshotGitDirectory, emptyMap(), "update-ref", sessionRef, commitHash)
        logger.info(
          "anchored session '{}' at commit {} (store '{}')",
          sessionId, commitHash, snapshotGitDirectory
        )
      }

      if (!turnMessageId.isNullOrBlank()) anchorTurnRef(projectRoot, snapshotGitDirectory, sessionId, turnMessageId, commitHash)
      true
    } catch (snapshotException: Exception) {
      logger.warn("create failed for session '{}': {}", sessionId, snapshotException.message)
      false
    }
  }

  private fun anchorTurnRef(
    projectRoot: String, snapshotGitDirectory: Path,
    sessionId: String, turnMessageId: String, commitHash: String
  ) {
    val turnRef: String = turnRefName(sessionId, turnMessageId)
    val turnRefExists: Boolean =
      runGitCommand(projectRoot, snapshotGitDirectory, emptyMap(), "rev-parse", "--verify", "--quiet", turnRef)
        .exitCode == 0
    if (turnRefExists) {
      logger.debug("turn ref {} already exists; keeping it", turnRef)
      return
    }
    runGitChecked(projectRoot, snapshotGitDirectory, emptyMap(), "update-ref", turnRef, commitHash)
    logger.info("anchored turn '{}' for session '{}'", turnMessageId, sessionId)
  }

  /**
   * Replays the worktree back to the session-start snapshot of [sessionId],
   * drops the session ref plus every turn ref of the session, and prunes the
   * store. A missing ref counts as success ("nothing to revert"). On failure
   * the refs are kept for forensics and false is returned; the caller should
   * continue its own flow either way.
   */
  fun revertSnapshot(projectRoot: String, sessionId: String): Boolean {
    return sessionId.isBlank() || try {
      val snapshotGitDirectory: Path = snapshotStoreDirectory(projectRoot)
      revertToRef(projectRoot, snapshotGitDirectory, sessionRefName(sessionId), "session '$sessionId'")
      dropTurnRefs(projectRoot, snapshotGitDirectory, sessionId)
      true
    } catch (revertException: Exception) {
      logger.warn("revert failed for session '{}' (refs kept): {}", sessionId, revertException.message)
      false
    }
  }

  /**
   * Replays the worktree back to the state captured right before user message
   * [messageId] was sent, undoing every model change made by that message and
   * the turns after it. The turn ref itself is kept: the user may rewind to
   * the same message again, and re-sending it re-anchors nothing (first-wins).
   * Stale refs of rewound-away turns are cleaned up on session delete.
   */
  fun revertTurnSnapshot(projectRoot: String, sessionId: String, messageId: String): Boolean {
    return sessionId.isBlank() || messageId.isBlank() || try {
      val snapshotGitDirectory: Path = snapshotStoreDirectory(projectRoot)
      revertToRef(projectRoot, snapshotGitDirectory, turnRefName(sessionId, messageId), "turn '$messageId'")
      true
    } catch (revertException: Exception) {
      logger.warn("turn revert failed for '{}' (ref kept): {}", messageId, revertException.message)
      false
    }
  }

  /** Shared reverse-patch replay behind [revertSnapshot] and [revertTurnSnapshot]. */
  private fun revertToRef(
    projectRoot: String, snapshotGitDirectory: Path, snapshotRef: String, snapshotLabel: String
  ) {
    val refResolveResult: GitRunResult = runGitCommand(
      projectRoot, snapshotGitDirectory, emptyMap(), "rev-parse", "--verify", "--quiet", "$snapshotRef^{tree}"
    )
    if (refResolveResult.exitCode != 0 || !Files.exists(snapshotGitDirectory)) {
      logger.info("revert skipped: no snapshot ref for {}", snapshotLabel)
      return
    }
    val snapshotTreeHash: String = refResolveResult.standardOutput.trim()

    val tempIndexEnvironment: Map<String, String> = freshTempIndexEnvironment()

    runGitChecked(projectRoot, snapshotGitDirectory, tempIndexEnvironment, *SNAPSHOT_ADD_PATHSPECS)
    val currentTreeHash: String =
      runGitChecked(projectRoot, snapshotGitDirectory, tempIndexEnvironment, "write-tree").standardOutput.trim()

    if (currentTreeHash != snapshotTreeHash) {
      val restorePatch: String = runGitChecked(
        projectRoot, snapshotGitDirectory, emptyMap(),
        "diff-tree", "--binary", "-p", "-r", currentTreeHash, snapshotTreeHash
      ).standardOutput
      runGitPatchApply(projectRoot, snapshotGitDirectory, restorePatch)
      logger.info("revert applied reverse patch for {}", snapshotLabel)
    } else {
      logger.info("worktree already matches the snapshot for {}", snapshotLabel)
    }
  }

  /**
   * `~/.gradum/snapshots/<projectDirName>-<pathHash>`: the readable project
   * name plus a hash of the absolute path, so same-named projects in
   * different locations never share a store.
   */
  private fun snapshotStoreDirectory(projectRoot: String): Path {
    val absoluteRoot: Path = Path.of(projectRoot).toAbsolutePath().normalize()
    val pathHash: String = MessageDigest.getInstance("SHA-256")
      .digest(absoluteRoot.toString().toByteArray(Charsets.UTF_8))
      .joinToString(separator = "") { byte: Byte -> "%02x".format(byte) }
      .take(n = HASH_LENGTH)
    return Path.of(System.getProperty("user.home"), ".gradum", SNAPSHOT_STORE_SEGMENT)
      .resolve("${absoluteRoot.fileName}-$pathHash")
  }

  // Creates the snapshot repository once; a plain `git init` with directed env.
  private fun ensureSnapshotRepository(projectRoot: String, snapshotGitDirectory: Path) {
    if (Files.exists(snapshotGitDirectory)) return
    Files.createDirectories(snapshotGitDirectory)
    val initEnvironment: Map<String, String> = mapOf(
      "GIT_DIR" to snapshotGitDirectory.toString(),
      "GIT_WORK_TREE" to Path.of(projectRoot).toAbsolutePath().normalize().toString(),
    )
    runGitChecked(projectRoot, snapshotGitDirectory, initEnvironment, "init")
    logger.info("initialized snapshot store at '{}'", snapshotGitDirectory)
  }

  // Best-effort garbage collection; unreachable snapshot objects linger otherwise.
  private fun pruneSnapshotStore(projectRoot: String, snapshotGitDirectory: Path) {
    try {
      runGitCommand(projectRoot, snapshotGitDirectory, emptyMap(), "gc", "--prune=14.days")
    } catch (pruneException: Exception) {
      logger.debug("store prune skipped: {}", pruneException.message)
    }
  }

  private fun sessionRefName(sessionId: String): String = "$SESSION_REF_PREFIX/$sessionId"

  private fun turnRefName(sessionId: String, messageId: String): String = "$TURN_REF_PREFIX/$sessionId/$messageId"

  // Drops every turn ref of [sessionId]; called when the session itself is deleted.
  private fun dropTurnRefs(projectRoot: String, snapshotGitDirectory: Path, sessionId: String) {
    val turnRefList: String = runGitCommand(
      projectRoot, snapshotGitDirectory, emptyMap(), "for-each-ref", "--format=%(refname)", "$TURN_REF_PREFIX/$sessionId/"
    ).standardOutput
    turnRefList.lines().filter { turnRef: String -> turnRef.isNotBlank() }.forEach { turnRef: String ->
      runGitChecked(projectRoot, snapshotGitDirectory, emptyMap(), "update-ref", "-d", turnRef)
    }
    pruneSnapshotStore(projectRoot, snapshotGitDirectory)
  }

  /**
   * Environment pointing `GIT_INDEX_FILE` at a fresh, non-existent temp path so
   * git builds a private index without touching any existing one. The temp file
   * is removed right away: git rejects a pre-existing zero-byte index with
   * "index file smaller than expected", so it must be allowed to create one.
   */
  private fun freshTempIndexEnvironment(): Map<String, String> {
    val tempIndexFile: Path = Files.createTempFile("gradum-index", ".idx")
    tempIndexFile.toFile().deleteOnExit()
    tempIndexFile.toFile().delete()
    return mapOf("GIT_INDEX_FILE" to tempIndexFile.toString())
  }

  /** Author/committer identity so commit-tree works without user-level git config. */
  private fun authorEnvironment(): Map<String, String> = mapOf(
    "GIT_AUTHOR_NAME" to "Gradum",
    "GIT_COMMITTER_NAME" to "Gradum",
    "GIT_AUTHOR_EMAIL" to "gradum@local",
    "GIT_COMMITTER_EMAIL" to "gradum@local"
  )

  private fun runGitChecked(
    projectRoot: String, snapshotGitDirectory: Path,
    extraEnvironment: Map<String, String>, vararg gitArguments: String
  ): GitRunResult {
    val runResult: GitRunResult = runGitCommand(projectRoot, snapshotGitDirectory, extraEnvironment, *gitArguments)
    if (runResult.exitCode != 0)
      throw GitCommandFailure("git ${gitArguments.joinToString(separator = " ")} failed (exit ${runResult.exitCode})")
    return runResult
  }

  private fun runGitPatchApply(projectRoot: String, snapshotGitDirectory: Path, patchText: String) {
    val patchProcess: Process = ProcessBuilder(
      "git", "--git-dir", snapshotGitDirectory.toString(),
      "--work-tree", projectRoot, "apply", "--binary", "--whitespace=nowarn"
    )
      .directory(File(projectRoot))
      .start()
    patchProcess.outputStream.use { processInput: OutputStream ->
      processInput.write(patchText.toByteArray(Charsets.UTF_8))
    }
    val applyExitCode: Int = patchProcess.waitFor()
    if (applyExitCode != 0)
      throw GitCommandFailure("git apply failed (exit $applyExitCode)")
  }

  private fun runGitCommand(
    projectRoot: String, snapshotGitDirectory: Path,
    extraEnvironment: Map<String, String>, vararg gitArguments: String
  ): GitRunResult {
    val gitProcess: Process = ProcessBuilder(
      "git", "--git-dir", snapshotGitDirectory.toString(), "--work-tree", projectRoot, *gitArguments
    )
      .directory(File(projectRoot))
      .apply { environment().putAll(extraEnvironment) }
      .start()
    val standardOutput: String = gitProcess.inputStream.readBytes().toString(Charsets.UTF_8)
    val exitCode: Int = gitProcess.waitFor()
    return GitRunResult(exitCode = exitCode, standardOutput = standardOutput)
  }
}
