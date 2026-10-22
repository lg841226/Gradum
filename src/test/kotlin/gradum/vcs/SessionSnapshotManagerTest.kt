package gradum.vcs

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionSnapshotManagerTest {

  @Test
  fun `revert restores modified deleted and created files to the session start`() {
    val projectDirectory = createGitProject()
    val sessionId = "session-revert-test"

    File(projectDirectory, "kept.txt").writeText("original kept")
    assertTrue(SessionSnapshotManager.createSnapshot(projectDirectory.absolutePath, sessionId))

    // Simulate the model's session: edit one file, delete another, create a third.
    File(projectDirectory, "kept.txt").writeText("edited by model")
    File(projectDirectory, "tracked.txt").delete()
    File(projectDirectory, "created.txt").writeText("new file by model")

    assertTrue(SessionSnapshotManager.revertSnapshot(projectDirectory.absolutePath, sessionId))

    assertEquals("original kept", File(projectDirectory, "kept.txt").readText())
    assertEquals("tracked baseline", File(projectDirectory, "tracked.txt").readText())
    assertFalse(File(projectDirectory, "created.txt").exists())
  }

  @Test
  fun `second snapshot keeps the first restore point`() {
    val projectDirectory = createGitProject()
    val sessionId = "session-first-wins"

    File(projectDirectory, "kept.txt").writeText("first state")
    SessionSnapshotManager.createSnapshot(projectDirectory.absolutePath, sessionId)

    File(projectDirectory, "kept.txt").writeText("second state")
    SessionSnapshotManager.createSnapshot(projectDirectory.absolutePath, sessionId)

    SessionSnapshotManager.revertSnapshot(projectDirectory.absolutePath, sessionId)
    assertEquals("first state", File(projectDirectory, "kept.txt").readText())
  }

  @Test
  fun `revert leaves the user index and status clean`() {
    val projectDirectory = createGitProject()
    val sessionId = "session-clean-index"

    SessionSnapshotManager.createSnapshot(projectDirectory.absolutePath, sessionId)
    File(projectDirectory, "created.txt").writeText("junk")
    SessionSnapshotManager.revertSnapshot(projectDirectory.absolutePath, sessionId)

    val statusOutput = runGit(projectDirectory, "status", "--porcelain")
    assertEquals("", statusOutput, "worktree and index should be clean after revert")
  }

  @Test
  fun `snapshot on a plain directory works without touching the project`() {
    val plainDirectory = Files.createTempDirectory("gradum-not-git").toFile()
    plainDirectory.deleteOnExit()
    File(plainDirectory, "notes.txt").writeText("before")

    assertTrue(SessionSnapshotManager.createSnapshot(plainDirectory.absolutePath, "session-no-git"))
    assertFalse(
      File(plainDirectory, ".git").exists(),
      "the project directory must stay free of snapshot git state",
    )

    File(plainDirectory, "notes.txt").writeText("after")
    File(plainDirectory, "extra.txt").writeText("extra")
    assertTrue(SessionSnapshotManager.revertSnapshot(plainDirectory.absolutePath, "session-no-git"))
    assertEquals("before", File(plainDirectory, "notes.txt").readText())
    assertFalse(File(plainDirectory, "extra.txt").exists())
  }

  @Test
  fun `snapshot works on an initialized repository without any commit`() {
    val initOnlyDirectory = Files.createTempDirectory("gradum-init-only").toFile()
    initOnlyDirectory.deleteOnExit()
    runGit(initOnlyDirectory, "init")
    File(initOnlyDirectory, "draft.txt").writeText("draft")

    assertTrue(SessionSnapshotManager.createSnapshot(initOnlyDirectory.absolutePath, "session-no-head"))

    File(initOnlyDirectory, "draft.txt").writeText("edited")
    File(initOnlyDirectory, "extra.txt").writeText("extra")
    assertTrue(SessionSnapshotManager.revertSnapshot(initOnlyDirectory.absolutePath, "session-no-head"))
    assertEquals("draft", File(initOnlyDirectory, "draft.txt").readText())
    assertFalse(File(initOnlyDirectory, "extra.txt").exists())
  }

  @Test
  fun `gradum runtime directory is excluded from snapshots`() {
    val projectDirectory = createGitProject()
    val sessionId = "session-exclude-gradum"

    val gradumDirectory = File(projectDirectory, ".gradum")
    gradumDirectory.mkdirs()
    File(gradumDirectory, "runtime-state.txt").writeText("runtime before")
    SessionSnapshotManager.createSnapshot(projectDirectory.absolutePath, sessionId)

    File(gradumDirectory, "runtime-state.txt").writeText("runtime after")
    File(gradumDirectory, "session-file.md").writeText("transcript")
    SessionSnapshotManager.revertSnapshot(projectDirectory.absolutePath, sessionId)

    assertEquals(
      "runtime after",
      File(gradumDirectory, "runtime-state.txt").readText(),
      "runtime state should survive the revert",
    )
    assertTrue(File(gradumDirectory, "session-file.md").exists(), "new runtime files should survive the revert")
  }

  @Test
  fun `revert without a snapshot ref is a no-op success`() {
    val projectDirectory = createGitProject()
    assertTrue(SessionSnapshotManager.revertSnapshot(projectDirectory.absolutePath, "session-never-snapshotted"))
  }

  @Test
  fun `turn snapshot rewind restores the worktree to the pre-message state`() {
    val projectDirectory = createGitProject()
    val sessionId = "session-turn-test"

    File(projectDirectory, "kept.txt").writeText("turn one state")
    SessionSnapshotManager.createSnapshot(projectDirectory.absolutePath, sessionId, turnMessageId = "msg-1")

    // Model's changes during turn one.
    File(projectDirectory, "kept.txt").writeText("turn one edit")
    File(projectDirectory, "turn-one.txt").writeText("created in turn one")

    // Turn two anchors its own restore point after the model changed files.
    SessionSnapshotManager.createSnapshot(projectDirectory.absolutePath, sessionId, turnMessageId = "msg-2")
    File(projectDirectory, "turn-two.txt").writeText("created in turn two")

    // Rewinding message one undoes both turns' changes.
    assertTrue(SessionSnapshotManager.revertTurnSnapshot(projectDirectory.absolutePath, sessionId, "msg-1"))
    assertEquals("turn one state", File(projectDirectory, "kept.txt").readText())
    assertFalse(File(projectDirectory, "turn-one.txt").exists())
    assertFalse(File(projectDirectory, "turn-two.txt").exists())
  }

  @Test
  fun `session delete reverts to session start even with turn snapshots present`() {
    val projectDirectory = createGitProject()
    val sessionId = "session-delete-with-turns"

    File(projectDirectory, "kept.txt").writeText("start")
    SessionSnapshotManager.createSnapshot(projectDirectory.absolutePath, sessionId, turnMessageId = "msg-1")
    File(projectDirectory, "kept.txt").writeText("turn edit")
    SessionSnapshotManager.createSnapshot(projectDirectory.absolutePath, sessionId, turnMessageId = "msg-2")

    assertTrue(SessionSnapshotManager.revertSnapshot(projectDirectory.absolutePath, sessionId))
    assertEquals("start", File(projectDirectory, "kept.txt").readText())

    val turnRefs: String = runGit(
      projectDirectory, "--git-dir", turnRefsGitDirectory(projectDirectory),
      "for-each-ref", "--format=%(refname)", "refs/gradum/turn/$sessionId/"
    )
    assertEquals("", turnRefs.trim(), "turn refs should be dropped when the session is deleted")
  }

  @Test
  fun `rewind without a turn ref is a no-op success`() {
    val projectDirectory = createGitProject()
    assertTrue(SessionSnapshotManager.revertTurnSnapshot(projectDirectory.absolutePath, "session-no-turn", "msg-x"))
  }

  @Test
  fun `blank session id is rejected`() {
    val projectDirectory = createGitProject()
    assertFalse(SessionSnapshotManager.createSnapshot(projectDirectory.absolutePath, "  "))
    assertTrue(SessionSnapshotManager.revertSnapshot(projectDirectory.absolutePath, ""))
  }

  private fun createGitProject(): File {
    val projectDirectory = Files.createTempDirectory("gradum-snapshot").toFile()
    projectDirectory.deleteOnExit()
    runGit(projectDirectory, "init")
    File(projectDirectory, "tracked.txt").writeText("tracked baseline")
    runGit(projectDirectory, "add", ".")
    runGit(
      projectDirectory, "-c", "user.name=gradum-test", "-c", "user.email=gradum@test.local",
      "commit", "-m", "baseline",
    )
    return projectDirectory
  }

  // Locates the snapshot store of [projectDirectory] by its unique temp dir name.
  private fun turnRefsGitDirectory(projectDirectory: File): String =
    File(System.getProperty("user.home"), ".gradum/snapshots")
      .listFiles()!!
      .first { storeDirectory: File -> storeDirectory.name.startsWith(projectDirectory.name) }
      .absolutePath

  private fun runGit(projectDirectory: File, vararg gitArguments: String): String {
    val gitProcess = ProcessBuilder("git", *gitArguments)
      .directory(projectDirectory)
      .start()
    val standardOutput = gitProcess.inputStream.readBytes().toString(Charsets.UTF_8)
    val exitCode = gitProcess.waitFor()
    if (exitCode != 0) {
      val errorOutput = gitProcess.errorStream.readBytes().toString(Charsets.UTF_8)
      throw IllegalStateException("git ${gitArguments.joinToString(" ")} failed: $errorOutput")
    }
    return standardOutput
  }
}
