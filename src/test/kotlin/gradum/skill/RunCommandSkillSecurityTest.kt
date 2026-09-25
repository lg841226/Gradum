/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * RunCommandSkillSecurityTest.kt  2026-09-25 Changed by gwy
 */

package gradum.skill

import gradum.ErrorCode
import gradum.SkillResult
import gradum.ToolMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.junit5.JUnit5Asserter.assertTrue

/**
 * Authorization-boundary tests for [RunCommandSkill].
 *
 * Verifies that destructive commands targeting paths *outside* the project
 * root (but not protected system paths) go through an [AskScope] decision
 * (once / always / no) before executing, that "always" is remembered by
 * category for the session, that hard-blocked commands are still blocked
 * without asking, and that an absent ask capability denies.
 */
class RunCommandSkillSecurityTest {

  private lateinit var projectRoot: File
  private lateinit var skill: RunCommandSkill

  @BeforeEach
  fun setUp() {
    // Under /tmp (ProtectedPaths.safePathPrefixes) so the project root itself
    // is not protected; java.io.tmpdir (/var/folders) is deliberately blocked.
    projectRoot = File("/tmp", "gradum-run-cmd-security-test-${System.nanoTime()}")
    projectRoot.mkdirs()
    skill = RunCommandSkill()
  }

  @AfterEach
  fun tearDown() {
    projectRoot.deleteRecursively()
  }

  private fun askContext(): Triple<SkillContext, PendingQuestions, MutableList<Pair<String, Map<String, Any>>>> {
    val pending = PendingQuestions()
    val emitted = mutableListOf<Pair<String, Map<String, Any>>>()
    val scope = AskScope("s", pending) { type, data -> emitted.add(type to data) }
    val ctx = SkillContext(
      toolMode = ToolMode.AGENT,
      projectRoot = projectRoot.absolutePath,
      modelName = "qwen2.5:7b",
      scope = scope
    )
    return Triple(ctx, pending, emitted)
  }

  /** Answers the single outstanding ask question and returns the executed result. */
  private fun runAskWith(
    ctx: SkillContext,
    pending: PendingQuestions,
    emitted: MutableList<Pair<String, Map<String, Any>>>,
    command: String,
    answer: String
  ): SkillResult {
    val holder = arrayOfNulls<SkillResult>(1)
    val executor = Thread { holder[0] = skill.execute(mapOf("command" to command), ctx) }
    executor.start()
    waitUntil { emitted.isNotEmpty() && pending.size() == 1 }
    val requestId: String = emitted.last().second["requestId"] as String
    pending.completeChoice("s", requestId, answer)
    executor.join(2000)
    return holder[0]!!
  }

  @Test
  fun `rm outside the project root runs once when the user allows one execution`() {
    val fileName = "gradum-run-cmd-once-${System.nanoTime()}.txt"
    val outside = File(projectRoot.parentFile, fileName)
    outside.writeText("to be removed\n")
    val (ctx, pending, emitted) = askContext()
    val result = runAskWith(ctx, pending, emitted, "rm ../$fileName", "once")
    assertTrue("one-time allow should succeed: $result", result is SkillResult.Success)
    assertTrue("target file should have been removed", !outside.exists())
    assertTrue("once must not remember the category", ctx.authorizedCommandCategories.isEmpty())
  }

  @Test
  fun `rm always whitelists the category for the session`() {
    val fileName = "gradum-run-cmd-always-${System.nanoTime()}.txt"
    val outside = File(projectRoot.parentFile, fileName)
    outside.writeText("first\n")
    val (ctx, pending, emitted) = askContext()

    val result = runAskWith(ctx, pending, emitted, "rm ../$fileName", "always")
    assertTrue("always allow should succeed: $result", result is SkillResult.Success)
    assertTrue("rm:delete" in ctx.authorizedCommandCategories)

    // A second destructive rm (different path, same category) must NOT re-ask.
    val secondFileName = "gradum-run-cmd-always2-${System.nanoTime()}.txt"
    val secondOutside = File(projectRoot.parentFile, secondFileName)
    secondOutside.writeText("second\n")
    val secondHolder = arrayOfNulls<SkillResult>(1)
    val second = Thread {
      secondHolder[0] = skill.execute(mapOf("command" to "rm ../$secondFileName"), ctx)
    }
    second.start()
    second.join(2000)
    assertTrue("whitelisted command should succeed: ${secondHolder[0]}", secondHolder[0] is SkillResult.Success)
    assertTrue("whitelisted command must not re-ask", !second.isAlive)
    assertTrue("second target file should have been removed", !secondOutside.exists())
    assertEquals(1, emitted.size, "whitelisted command must not emit another ask card")
  }

  @Test
  fun `rm outside the project root denies when the user rejects`() {
    val fileName = "gradum-run-cmd-no-${System.nanoTime()}.txt"
    val outside = File(projectRoot.parentFile, fileName)
    outside.writeText("keep me\n")
    val (ctx, pending, emitted) = askContext()
    val result = runAskWith(ctx, pending, emitted, "rm ../$fileName", "no")
    assertTrue(result is SkillResult.Failure)
    assertEquals(ErrorCode.PERMISSION_DENIED.code, (result as SkillResult.Failure).code)
    assertTrue("rejected command must not run", outside.exists())
  }

  @Test
  fun `rm outside the project root denies when no ask capability is wired`() {
    val fileName = "gradum-run-cmd-noscope-${System.nanoTime()}.txt"
    val outside = File(projectRoot.parentFile, fileName)
    outside.writeText("keep me\n")
    val ctx = SkillContext(
      toolMode = ToolMode.AGENT,
      projectRoot = projectRoot.absolutePath,
      modelName = "qwen2.5:7b"
    )
    val result = skill.execute(mapOf("command" to "rm ../$fileName"), ctx)
    assertTrue(result is SkillResult.Failure)
    assertEquals(ErrorCode.PERMISSION_DENIED.code, (result as SkillResult.Failure).code)
    assertTrue("un-approved command must not run", outside.exists())
  }

  @Test
  fun `hard-blocked command is blocked without asking`() {
    val (ctx, pending, emitted) = askContext()
    val result = skill.execute(mapOf("command" to "rm -rf /etc"), ctx)
    assertTrue(result is SkillResult.Failure)
    assertEquals(ErrorCode.COMMAND_BLOCKED.code, (result as SkillResult.Failure).code)
    assertEquals(0, emitted.size, "hard-blocked command must not emit an ask card")
  }

  private fun waitUntil(condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 2000
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return
      Thread.sleep(10)
    }
    error("condition not met within timeout")
  }
}
