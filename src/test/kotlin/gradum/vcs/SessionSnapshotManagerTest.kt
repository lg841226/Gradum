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

    val statusOutput = runGit(projectDirectory, "status", "--porcelain").second
    assertEquals("", statusOutput, "worktree and index should be clean after revert")
  }

  @Test
  fun `snapshot on a non-git directory returns false`() {
    val plainDirectory = Files.createTempDirectory(prefix = "gradum-not-git").toFile()
    plainDirectory.deleteOnExit()
    assertFalse(SessionSnapshotManager.createSnapshot(plainDirectory.absolutePath, "session-no-git"))
  }

  @Test
  fun `revert without a snapshot ref is a no-op success`() {
    val projectDirectory = createGitProject()
    assertTrue(SessionSnapshotManager.revertSnapshot(projectDirectory.absolutePath, "session-never-snapshotted"))
  }

  @Test
  fun `blank session id is rejected`() {
    val projectDirectory = createGitProject()
    assertFalse(SessionSnapshotManager.createSnapshot(projectDirectory.absolutePath, "  "))
    assertTrue(SessionSnapshotManager.revertSnapshot(projectDirectory.absolutePath, ""))
  }

  private fun createGitProject(): File {
    val projectDirectory = Files.createTempDirectory(prefix = "gradum-snapshot").toFile()
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

  private fun runGit(projectDirectory: File, vararg gitArguments: String): Pair<Int, String> {
    val gitProcess = ProcessBuilder("git", *gitArguments)
      .directory(projectDirectory)
      .start()
    val standardOutput = gitProcess.inputStream.readBytes().toString(Charsets.UTF_8)
    val exitCode = gitProcess.waitFor()
    if (exitCode != 0) {
      val errorOutput = gitProcess.errorStream.readBytes().toString(Charsets.UTF_8)
      throw IllegalStateException("git ${gitArguments.joinToString(" ")} failed: $errorOutput")
    }
    return exitCode to standardOutput
  }
}
