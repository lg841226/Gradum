package gradum.vcs

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

private val logger: Logger = LoggerFactory.getLogger("SessionSnapshotManager")

private const val REF_PREFIX: String = "refs/gradum/session"
private const val SNAPSHOT_MESSAGE_PREFIX: String = "gradum snapshot"

/** Exit code, stdout and stderr of one finished `git` invocation. */
private data class GitRunResult(val exitCode: Int, val standardOutput: String)

private class GitCommandFailure(gitMessage: String) : Exception(gitMessage)

/**
 * Per-session worktree snapshots built on git plumbing commands.
 *
 * A snapshot never touches the user's HEAD or index: the tree is written
 * through a temporary index (`GIT_INDEX_FILE`) and anchored on a dedicated
 * ref (`refs/gradum/session/<sessionId>`), so the only visible side effect
 * in the user's repository is one extra ref. Deleting the session replays
 * the reverse patch onto the worktree, undoing every change the model made
 * during the session.
 *
 * Known limits: paths matched by .gitignore are not captured, and commits
 * the model made mid-session stay in history (only the worktree reverts).
 */
object SessionSnapshotManager {

  fun isGitRepository(projectRoot: String): Boolean =
    try {
      runGitCommand(projectRoot, emptyMap(), "rev-parse", "--is-inside-work-tree").exitCode == 0
    } catch (_: Exception) {
      false
    }

  /**
   * Anchors the current worktree state as the restore point of [sessionId].
   * The first call wins: later calls return true without re-snapshotting so
   * the restore point always means "session start". Returns false when the
   * directory is not a git repository or any git step fails; never throws.
   */
  fun createSnapshot(projectRoot: String, sessionId: String): Boolean {
    return sessionId.isNotBlank() && try {
      if (!isGitRepository(projectRoot)) return false
      val snapshotRef: String = snapshotRefName(sessionId)
      if (runGitCommand(projectRoot, emptyMap(), "rev-parse", "--verify", "--quiet", snapshotRef).exitCode == 0) {
        logger.debug("Snapshot ref {} already exists; keeping the original restore point", snapshotRef)
        return true
      }

      val tempIndexFile: Path = Files.createTempFile("gradum-index", ".idx")
      tempIndexFile.toFile().deleteOnExit()
      val tempIndexEnv: Map<String, String> = mapOf("GIT_INDEX_FILE" to tempIndexFile.toString())

      val headExists: Boolean = runGitCommand(
        projectRoot, emptyMap(), "rev-parse", "--verify", "--quiet", "HEAD"
      ).exitCode == 0
      if (headExists)
        runGitChecked(projectRoot, tempIndexEnv, "read-tree", "HEAD")

      runGitChecked(projectRoot, tempIndexEnv, "add", "-A", "--")
      val treeHash: String = runGitChecked(projectRoot, tempIndexEnv, "write-tree").standardOutput.trim()

      val commitHash: String = runGitChecked(
        projectRoot, authorEnvironment(),
        "commit-tree", treeHash, "-m", "$SNAPSHOT_MESSAGE_PREFIX $sessionId"
      ).standardOutput.trim()
      runGitChecked(projectRoot, emptyMap(), "update-ref", snapshotRef, commitHash)
      logger.info("Session snapshot {} anchored at commit {}", snapshotRef, commitHash)
      true
    } catch (snapshotException: Exception) {
      logger.warn(
        "Failed to snapshot session '{}': {}", sessionId, snapshotException.message
      )
      false
    }
  }

  /**
   * Replays the worktree back to the snapshot of [sessionId], then drops the
   * ref. A missing ref counts as success ("nothing to revert"). On failure
   * the ref is kept for forensics and false is returned; the caller should
   * continue its own flow either way.
   */
  fun revertSnapshot(projectRoot: String, sessionId: String): Boolean {
    return sessionId.isBlank() || try {
      if (!isGitRepository(projectRoot)) return true
      val snapshotRef: String = snapshotRefName(sessionId)
      val refResolveResult: GitRunResult =
        runGitCommand(projectRoot, emptyMap(), "rev-parse", "--verify", "--quiet", "$snapshotRef^{tree}")
      if (refResolveResult.exitCode != 0) return true
      val snapshotTreeHash: String = refResolveResult.standardOutput.trim()

      val tempIndexFile: Path = Files.createTempFile("gradum-index", ".idx")
      tempIndexFile.toFile().deleteOnExit()
      val tempIndexEnv: Map<String, String> = mapOf("GIT_INDEX_FILE" to tempIndexFile.toString())

      runGitChecked(projectRoot, tempIndexEnv, "add", "-A", "--")
      val currentTreeHash: String = runGitChecked(projectRoot, tempIndexEnv, "write-tree").standardOutput.trim()

      if (currentTreeHash != snapshotTreeHash) {
        val restorePatch: String = runGitChecked(
          projectRoot, emptyMap(), "diff-tree", "--binary", "-p", "-r", currentTreeHash, snapshotTreeHash
        ).standardOutput
        runGitPatchApply(projectRoot, restorePatch)
      }

      runGitChecked(projectRoot, emptyMap(), "update-ref", "-d", snapshotRef)
      logger.info("Session snapshot {} reverted; worktree restored", snapshotRef)
      true
    } catch (revertException: Exception) {
      logger.warn(
        "Failed to revert session '{}' (snapshot ref kept): {}", sessionId, revertException.message
      )
      false
    }
  }

  private fun snapshotRefName(sessionId: String): String = "$REF_PREFIX/$sessionId"

  /** Author/committer identity so commit-tree works without user-level git config. */
  private fun authorEnvironment(): Map<String, String> = mapOf(
    "GIT_AUTHOR_NAME" to "Gradum",
    "GIT_AUTHOR_EMAIL" to "gradum@local",
    "GIT_COMMITTER_NAME" to "Gradum",
    "GIT_COMMITTER_EMAIL" to "gradum@local",
  )

  private fun runGitChecked(
    projectRoot: String, extraEnvironment: Map<String, String>, vararg gitArguments: String
  ): GitRunResult {
    val runResult: GitRunResult = runGitCommand(projectRoot, extraEnvironment, *gitArguments)
    if (runResult.exitCode != 0)
      throw GitCommandFailure("git ${gitArguments.joinToString(separator = " ")} failed (exit ${runResult.exitCode})")
    return runResult
  }

  private fun runGitPatchApply(projectRoot: String, patchText: String) {
    val patchProcess: Process = ProcessBuilder("git", "apply", "--binary", "--whitespace=nowarn")
      .directory(java.io.File(projectRoot))
      .start()
    patchProcess.outputStream.use { processInput: java.io.OutputStream ->
      processInput.write(patchText.toByteArray(Charsets.UTF_8))
    }
    val applyExitCode: Int = patchProcess.waitFor()
    if (applyExitCode != 0)
      throw GitCommandFailure("git apply failed (exit $applyExitCode)")
  }

  private fun runGitCommand(
    projectRoot: String, extraEnvironment: Map<String, String>, vararg gitArguments: String
  ): GitRunResult {
    val gitProcess: Process = ProcessBuilder("git", *gitArguments)
      .directory(java.io.File(projectRoot))
      .apply { environment().putAll(extraEnvironment) }
      .start()
    val standardOutput: String = gitProcess.inputStream.readBytes().toString(Charsets.UTF_8)
    val exitCode: Int = gitProcess.waitFor()
    return GitRunResult(exitCode = exitCode, standardOutput = standardOutput)
  }
}
