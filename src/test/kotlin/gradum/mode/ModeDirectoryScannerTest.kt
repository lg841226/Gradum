package gradum.mode

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ModeDirectoryScannerTest {

  private var previousConfigDir: String? = null
  private lateinit var configDir: java.io.File

  @BeforeTest
  fun isolateConfigDir() {
    previousConfigDir = System.getProperty("gradum.server.configDir")
    configDir = Files.createTempDirectory("gradum-mode-scan-").toFile()
    System.setProperty("gradum.server.configDir", configDir.absolutePath)
    ModeRegistry.reload()
  }

  @AfterTest
  fun restoreConfigDir() {
    if (previousConfigDir == null) System.clearProperty("gradum.server.configDir")
    else System.setProperty("gradum.server.configDir", previousConfigDir!!)
    ModeRegistry.reload()
    configDir.deleteRecursively()
  }

  @Test
  fun `first scan creates the directory and drops the starter example`() {
    val modesDir = java.io.File(configDir, "modes")
    assertFalse(modesDir.exists(), "the test starts with no modes directory")

    ModeDirectoryScanner(modesDir).scan()

    assertTrue(modesDir.isDirectory)
    assertTrue(java.io.File(modesDir, "review.json").isFile, "starter review.json must be written")
    assertTrue(java.io.File(modesDir, "review.xml").isFile, "starter review.xml prompt must be written")
  }

  @Test
  fun `the starter mode resolves with its declared surface`() {
    val scanner = ModeDirectoryScanner(java.io.File(configDir, "modes"))
    scanner.scan()
    scanner.reconcile()

    val review: AgentMode = ModeRegistry.resolve("review")
    assertEquals("review", review.id, "the starter must be reachable by id")
    assertEquals("review", ModeRegistry.resolveId("cr"), "the starter alias must resolve to its canonical id")
    assertEquals("review", review.prompt)
    assertFalse(review.allowsTool("write_file"), "the review mode must reject write_file")
    assertFalse(review.allowsTool("some_mcp_tool", ToolOrigin.MCP), "the review mode must reject MCP tools")
    assertTrue(review.allowsTool("read_file"), "the review mode must keep read_file")
    assertTrue(review.gates.approvals, "approvals stay on in the starter")
    assertFalse(review.gates.commandFilter, "the starter shows commandFilter off")
  }

  @Test
  fun `rescan never overwrites user edits to the starter`() {
    val modesDir = java.io.File(configDir, "modes")
    ModeDirectoryScanner(modesDir).scan()
    val reviewFile = java.io.File(modesDir, "review.json")
    reviewFile.writeText(
      reviewFile.readText().replace("\"label\": \"Code Review\"", "\"label\": \"My Review\"")
    )

    ModeDirectoryScanner(modesDir).scan()

    assertTrue(reviewFile.readText().contains("My Review"), "a later scan must keep the user's text")
  }

  @Test
  fun `watcher reloads the registry and fires onChanged on a new file`() {
    val modesDir = java.io.File(configDir, "modes")
    modesDir.mkdirs()
    val scanner = ModeDirectoryScanner(modesDir)
    val latch = CountDownLatch(1)
    val watcher = ModeDirectoryWatcher(modeScanner = scanner, pollIntervalMs = 50L)
    watcher.onChanged = { latch.countDown() }
    watcher.start()
    try {
      java.io.File(modesDir, "quiet.json").writeText(
        """{"id": "quiet", "tools": {"deny": ["run_cmd"]}}"""
      )
      assertTrue(
        latch.await(5, TimeUnit.SECONDS),
        "the watcher must reload and notify after a new mode file"
      )
      assertEquals("quiet", ModeRegistry.resolveId("quiet"), "the new mode must be live after the reload")
    } finally {
      watcher.close()
    }
  }
}
