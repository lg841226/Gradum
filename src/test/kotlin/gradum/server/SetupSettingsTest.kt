package gradum.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupSettingsTest {

  @Test
  fun `llm values land in the nested section the loader reads`() {
    val settingsRoot: MutableMap<String, Any?> = mutableMapOf("server" to mutableMapOf("port" to 8080))

    val llm: MutableMap<String, Any?> = llmGroup(settingsRoot)
    llm["baseUrl"] = "http://localhost:11434"
    llm["think"] = true

    assertEquals("http://localhost:11434", llmString(settingsRoot, "baseUrl"))
    assertEquals(true, llmBoolean(settingsRoot, "think"))
    assertEquals(8080, (settingsRoot["server"] as Map<*, *>)["port"])
    assertTrue("llm.baseUrl" !in settingsRoot)
  }

  @Test
  fun `flat llm keys left by older setup runs are dropped on save`() {
    val settingsRoot: MutableMap<String, Any?> = mutableMapOf(
      "llm.baseUrl" to "http://localhost:11434",
      "llm.model" to "qwen",
      "llm.think" to true,
      "ollama.baseUrl" to "http://localhost:11434",
    )

    dropLegacyFlatLlmKeys(settingsRoot)

    assertEquals(setOf("ollama.baseUrl"), settingsRoot.keys)
  }

  @Test
  fun `corrupt settings file reads as null instead of an empty map`() {
    val settingsFile: File = File.createTempFile("settings", ".json")
    try {
      settingsFile.writeText("{ not json")
      assertNull(readSettingsRoot(settingsFile))
    } finally {
      settingsFile.delete()
    }
  }
}
