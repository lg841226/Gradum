/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ReadFileSkillPrepareHistoryTest.kt  2026-08-25 14:01:24 Changed by gwy
 */

package gradum.skill

import gradum.Provider
import gradum.SkillResult
import gradum.ToolMode
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * Regression coverage for the read_file content-strip bug.
 *
 * ReadFileSkill previously declared
 *
 *     override val historyKeepCount: Int = 2
 *     override val historyVolatileKeys: List<String> = listOf("content")
 *
 * so [Skill.prepareHistoryResult] would strip the `content`
 * key from the 3rd result onward in a session. The LLM would
 * receive a result map containing only `path` and a few
 * metadata fields, with no file body, and was unable to plan
 * or verify any subsequent edit. Wasted tokens and confused
 * the agent into thinking the file was empty.
 *
 * The fix sets `historyKeepCount = Int.MAX_VALUE` and an empty
 * `historyVolatileKeys`, so the LLM-visible result always
 * carries the full file content. The test below pins that
 * behavior: it exercises the full `execute` →
 * `prepareHistoryResult` pipeline five times on the same skill
 * instance and asserts that `content` survives every call.
 *
 * The skill instance is treated as a singleton here — that
 * matches the production behavior in [SkillRegistry], which
 * reuses one ReadFileSkill across the whole session. The
 * counter is per-instance, not per-call, so a single instance
 * is the only honest way to reproduce the original bug.
 */
class ReadFileSkillPrepareHistoryTest {

  private lateinit var projectRoot: File
  private val fileBody: String = listOf(
    "line one",
    "line two with some content",
    "line three",
    "line four"
  ).joinToString("\n")

  @BeforeTest
  fun setUp() {
    projectRoot = Files.createTempDirectory("gradum_read_history_test_").toFile()
    File(projectRoot, "hello.txt").writeText(fileBody)
  }

  private fun readContext(): SkillContext = SkillContext(
    projectRoot = projectRoot.absolutePath,
    toolMode = ToolMode.READ_ONLY,
    provider = Provider.OLLAMA,
    modelName = "qwen2.5:7b"
  )

  @Test
  fun `read_file preserves content across five consecutive calls`() {
    val skill = ReadFileSkill()
    skill.resetHistoryCount()
    val context = readContext()

    val seenBodies: MutableList<String> = mutableListOf()

    for (i in 1..5) {
      val raw: SkillResult = skill.execute(
        mapOf("path" to "hello.txt"),
        context
      )

      val data: Map<String, Any> = when (raw) {
        is SkillResult.Success -> raw.data
        is SkillResult.Failure -> fail("call #$i: read_file failed: ${raw.code} ${raw.message}")
      }
      val history: Map<String, Any> = skill.prepareHistoryResult(data)

      assertTrue(
        history.containsKey("content"),
        "call #$i: `content` was stripped from the history result — keys present: ${history.keys}"
      )
      assertTrue(
        history.containsKey("path"),
        "call #$i: `path` was stripped from the history result — keys present: ${history.keys}"
      )

      val rendered: String = when (val content: Any = history["content"]!!) {
        is Map<*, *> -> content.values.joinToString("\n") { it.toString() }
        is String -> content
        else -> content.toString()
      }
      assertEquals(
        fileBody, rendered,
        "call #$i: content body did not match the file on disk"
      )
      seenBodies.add(rendered)
    }

    assertEquals(
      5,
      seenBodies.size
    )
    assertTrue(seenBodies.all { it == fileBody })
  }

  @Test
  fun `read_file with lineRange preserves content across many calls`() {
    val skill = ReadFileSkill()
    skill.resetHistoryCount()
    val context = readContext()

    for (i in 1..4) {
      val raw: SkillResult = skill.execute(
        mapOf("path" to "hello.txt", "lineRange" to "2-3"),
        context
      )
      val data: Map<String, Any> = when (raw) {
        is SkillResult.Success -> raw.data
        is SkillResult.Failure -> fail("call #$i: read_file failed: ${raw.code} ${raw.message}")
      }
      val history: Map<String, Any> = skill.prepareHistoryResult(data)

      assertTrue(
        history.containsKey("content"),
        "call #$i (lineRange=2-3): `content` was stripped — keys: ${history.keys}"
      )
      val rendered: String = when (val content: Any = history["content"]!!) {
        is Map<*, *> -> content.values.joinToString("\n") { it.toString() }
        is String -> content
        else -> content.toString()
      }

      assertTrue(
        rendered.contains("line two"),
        "call #$i: line 2 missing from content: $rendered"
      )
      assertTrue(
        rendered.contains("line three"),
        "call #$i: line 3 missing from content: $rendered"
      )
    }
  }
}
