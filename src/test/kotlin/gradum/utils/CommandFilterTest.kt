/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * CommandFilterTest.kt  2026-09-25 Changed by gwy
 */

package gradum.utils

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommandFilterTest {

  /** Project root under /tmp so in-project relative targets stay outside the protected set. */
  private val projectRoot: String = "/tmp/gradum-command-filter-test"

  @Test
  fun `allows commands that are not on the danger blocklist`() {
    listOf("ls", "cat foo.txt", "git status", "echo hi", "pwd").forEach { command ->
      val verdict = classifyCommand(commandText = command, projectRoot = projectRoot)
      assertEquals(CommandVerdict.Safe, verdict, "should allow '$command'")
    }
  }

  @Test
  fun `danger filter still blocks dangerous executables`() {
    listOf("sudo reboot", "mkfs.ext4 /dev/sdb", "shutdown -h now").forEach { command ->
      val verdict = classifyCommand(commandText = command, projectRoot = projectRoot)
      assertIs<CommandVerdict.Blocked>(value = verdict, message = "danger filter should block '$command'")
    }
  }

  @Test
  fun `rm on a protected system path is hard-blocked`() {
    listOf("rm -rf /etc", "rm /usr/local/bin/app", "rm -f ${System.getProperty("user.home")}/.ssh/id_rsa")
      .forEach { command ->
        val verdict = classifyCommand(commandText = command, projectRoot = projectRoot)
        assertIs<CommandVerdict.Blocked>(value = verdict, message = "rm on protected path should block '$command'")
        assertEquals("rm:criticalPath", verdict.ruleName)
      }
  }

  @Test
  fun `rm on a path inside the project root is safe`() {
    val verdict = classifyCommand(commandText = "rm build/junk.txt", projectRoot = projectRoot)
    assertEquals(CommandVerdict.Safe, verdict)
  }

  @Test
  fun `rm on a path outside the project root requires approval`() {
    val verdict = classifyCommand(commandText = "rm ../outside.txt", projectRoot = projectRoot)
    assertIs<CommandVerdict.NeedsApproval>(value = verdict)
    assertEquals("rm:delete", verdict.category)
  }

  @Test
  fun `recursive chmod on a protected system path is hard-blocked`() {
    val verdict = classifyCommand(commandText = "chmod -R 777 /etc", projectRoot = projectRoot)
    assertIs<CommandVerdict.Blocked>(value = verdict)
    assertEquals("chmod:criticalPathRecursive", verdict.ruleName)
  }

  @Test
  fun `recursive chmod on a path outside the project root requires approval`() {
    val verdict = classifyCommand(commandText = "chmod -R 777 ../shared", projectRoot = projectRoot)
    assertIs<CommandVerdict.NeedsApproval>(value = verdict)
    assertEquals("chmod:recursive", verdict.category)
  }

  @Test
  fun `non-recursive chmod is safe even on an outside path`() {
    val verdict = classifyCommand(commandText = "chmod 755 ../file.txt", projectRoot = projectRoot)
    assertEquals(CommandVerdict.Safe, verdict)
  }

  @Test
  fun `dd writing to a device is hard-blocked`() {
    val verdict = classifyCommand(commandText = "dd if=/dev/zero of=/dev/sda", projectRoot = projectRoot)
    assertIs<CommandVerdict.Blocked>(value = verdict)
    assertEquals("dd:deviceOutput", verdict.ruleName)
  }

  @Test
  fun `dd writing to a path outside the project root requires approval`() {
    val verdict = classifyCommand(commandText = "dd if=/dev/zero of=../backup.img", projectRoot = projectRoot)
    assertIs<CommandVerdict.NeedsApproval>(value = verdict)
    assertEquals("dd:device-write", verdict.category)
  }

  @Test
  fun `blank project root makes every non-protected destructive target require approval`() {
    val verdict = classifyCommand(commandText = "rm build/junk.txt", projectRoot = "")
    assertIs<CommandVerdict.NeedsApproval>(value = verdict)
  }

  @Test
  fun `in-project absolute path inside the project root is safe`() {
    val absProjectRoot = Paths.get(projectRoot).toAbsolutePath().normalize().toString()
    val verdict = classifyCommand(commandText = "rm $absProjectRoot/file.txt", projectRoot = projectRoot)
    assertEquals(CommandVerdict.Safe, verdict)
  }
}
