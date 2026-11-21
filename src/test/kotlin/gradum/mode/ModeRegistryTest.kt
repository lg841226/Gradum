package gradum.mode

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ModeRegistryTest {

  private lateinit var configDirectory: File
  private var previousProperty: String? = null

  @BeforeTest
  fun isolateConfigDir() {
    previousProperty = System.getProperty("gradum.server.configDir")
    configDirectory = createTempDirectory("gradum-modes-test").toFile()
    System.setProperty("gradum.server.configDir", configDirectory.absolutePath)
    ModeRegistry.reload()
  }

  @AfterTest
  fun restoreConfigDir() {
    if (previousProperty == null) System.clearProperty("gradum.server.configDir")
    else System.setProperty("gradum.server.configDir", previousProperty!!)
    configDirectory.deleteRecursively()
    ModeRegistry.reload()
  }

  private fun writeMode(fileName: String, json: String) {
    val modesDirectory = File(configDirectory, "modes").apply { mkdirs() }
    File(modesDirectory, fileName).writeText(json, Charsets.UTF_8)
  }

  private fun modeById(id: String): AgentMode = ModeRegistry.list().first { it.id == id }

  @Test
  fun builtinsLoadInDisplayOrder() {
    assertEquals(listOf("agent", "edit", "read_only", "yolo"), ModeRegistry.list().map { it.id })
  }

  @Test
  fun resolveAcceptsIdsAliasesAndAnyCase() {
    assertEquals("agent", ModeRegistry.resolve("write").id)
    assertEquals("edit", ModeRegistry.resolve("single_step").id)
    assertEquals("read_only", ModeRegistry.resolve("READ_ONLY").id)
    assertEquals("agent", ModeRegistry.resolve("bogus").id)
    assertEquals("agent", ModeRegistry.resolve(null).id)
    assertEquals("agent", ModeRegistry.resolve("   ").id)
  }

  @Test
  fun builtinSurfacesMatchTheLegacyWhitelist() {
    val agent = modeById("agent")
    val edit = modeById("edit")
    val readOnly = modeById("read_only")
    val yolo = modeById("yolo")

    for (origin in ToolOrigin.entries) {
      assertTrue(agent.allowsTool("to_do", origin), "agent keeps to_do")
      assertTrue(agent.allowsTool("write_file", origin), "agent keeps write_file")
      assertTrue(agent.allowsTool("anything", origin), "agent keeps everything")
      assertTrue(yolo.allowsTool("to_do", origin), "yolo keeps everything")
      assertTrue(yolo.allowsTool("anything", origin), "yolo keeps everything")
    }

    assertFalse(edit.allowsTool("to_do"), "edit drops to_do")
    assertFalse(edit.allowsTool("finish_to_do_item"), "edit drops finish_to_do_item")
    assertTrue(edit.allowsTool("write_file"), "edit keeps write_file")
    assertTrue(edit.allowsTool("anything", ToolOrigin.MCP), "edit keeps mcp tools")
    assertTrue(edit.allowsTool("hello", ToolOrigin.EXTERNAL), "edit keeps external skills")

    assertFalse(readOnly.allowsTool("to_do"), "read_only drops to_do")
    assertFalse(readOnly.allowsTool("finish_to_do_item"), "read_only drops finish_to_do_item")
    assertFalse(readOnly.allowsTool("write_file"), "read_only drops write_file")
    assertFalse(readOnly.allowsTool("anything", ToolOrigin.MCP), "read_only drops mcp tools")
    assertTrue(readOnly.allowsTool("read_file"), "read_only keeps read_file")
    assertTrue(readOnly.allowsTool("run_cmd"), "read_only keeps run_cmd")
    assertTrue(readOnly.allowsTool("mcp_tools"), "read_only keeps the mcp listing skill")
    assertTrue(readOnly.allowsTool("hello", ToolOrigin.EXTERNAL), "read_only keeps external skills")
  }

  @Test
  fun yoloTurnsEveryGateOffAndOthersKeepThem() {
    assertEquals(ModeGates(approvals = false, commandFilter = false, protectedPaths = false), modeById("yolo").gates)
    for (id in listOf("agent", "edit", "read_only")) assertEquals(ModeGates(), modeById(id).gates, id)
  }

  @Test
  fun userFileReplacesBuiltinInPlace() {
    writeMode(
      "edit.json",
      """
      {
        "id": "edit",
        "label": "My Edit",
        "tools": { "allow": ["*"], "deny": ["read_file"] }
      }
      """.trimIndent()
    )
    ModeRegistry.reload()

    assertEquals(listOf("agent", "edit", "read_only", "yolo"), ModeRegistry.list().map { it.id })
    val edit = modeById("edit")
    assertEquals("My Edit", edit.label)
    assertFalse(edit.allowsTool("read_file"), "custom deny replaces the whole surface")
    assertTrue(edit.allowsTool("to_do"), "the builtin deny list is gone, not merged")
  }

  @Test
  fun userFileAddsNewMode() {
    writeMode(
      "review.json",
      $$"""
      {
        "id": "review",
        "label": "Code Review",
        "description": "Reads diffs, runs tests, never writes",
        "tools": { "deny": ["write_file", "$mcp"] },
        "gates": { "commandFilter": false }
      }
      """.trimIndent()
    )
    ModeRegistry.reload()

    assertEquals("review", ModeRegistry.list().last().id)
    assertEquals("review", ModeRegistry.resolve("review").id)
    val review = modeById("review")
    assertFalse(review.allowsTool("write_file"))
    assertFalse(review.allowsTool("anything", ToolOrigin.MCP))
    assertTrue(review.allowsTool("run_cmd"))
    assertEquals(ModeGates(approvals = true, commandFilter = false, protectedPaths = true), review.gates)
    assertEquals("review", review.prompt, "prompt defaults to the id")
  }

  @Test
  fun idMismatchWithFileNameIsSkipped() {
    writeMode("oops.json", """{ "id": "other", "label": "Other" }""")
    ModeRegistry.reload()

    assertEquals(listOf("agent", "edit", "read_only", "yolo"), ModeRegistry.list().map { it.id })
  }

  @Test
  fun malformedJsonNeverTakesTheRegistryDown() {
    writeMode("broken.json", "{ not json")
    ModeRegistry.reload()

    assertEquals(listOf("agent", "edit", "read_only", "yolo"), ModeRegistry.list().map { it.id })
  }

  @Test
  fun aliasOwnedByAnotherModeIsDropped() {
    writeMode("mine.json", """{ "id": "mine", "label": "Mine", "aliases": ["agent"] }""")
    ModeRegistry.reload()

    assertEquals("mine", ModeRegistry.resolve("mine").id)
    assertEquals("agent", ModeRegistry.resolve("agent").id, "the alias must not steal the built-in")
  }

  @Test
  fun deletingTheUserFileRestoresTheBuiltinOnReload() {
    writeMode("edit.json", """{ "id": "edit", "label": "My Edit" }""")
    ModeRegistry.reload()
    assertEquals("My Edit", modeById("edit").label)

    File(configDirectory, "modes/edit.json").delete()
    ModeRegistry.reload()
    assertEquals("Edit Permissions", modeById("edit").label)
  }

  @Test
  fun minimalFileGetsSensibleDefaults() {
    writeMode("plain.json", """{ "id": "plain" }""")
    ModeRegistry.reload()

    val plain = modeById("plain")
    assertEquals("plain", plain.label)
    assertEquals(listOf(AgentMode.SELECTOR_ALL), plain.allow)
    assertEquals(emptyList(), plain.deny)
    assertEquals(ModeGates(), plain.gates)
    assertTrue(plain.allowsTool("write_file"))
  }
}
