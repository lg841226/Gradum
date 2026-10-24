package gradum.utils

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommandFilterTest {

  // Project root under /tmp so in-project relative targets stay outside the protected set.
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

  @Test
  fun `recursive delete inside tmp or project stays safe but destructive`() {
    listOf("rm -rf /tmp/build", "rm -rf ./build").forEach { command ->
      val verdict = classifyCommand(commandText = command, projectRoot = projectRoot)
      assertEquals(CommandVerdict.Safe, verdict, "should allow '$command'")
    }
  }

  @Test
  fun `privilege escalation amplifies the score into approval territory`() {
    val verdict = classifyCommand(
      commandText = "sudo rm -rf ${System.getProperty("user.home")}/Documents/notes",
      projectRoot = projectRoot
    )
    assertIs<CommandVerdict.NeedsApproval>(value = verdict, message = "sudo rm outside project should need approval")
  }

  @Test
  fun `injected destructive payload is blocked`() {
    val verdict = classifyCommand(
      commandText = "echo \"rm -rf /etc\" | bash",
      projectRoot = projectRoot
    )
    assertIs<CommandVerdict.Blocked>(value = verdict, message = "injected payload should block")
    assertEquals("risk:highScore", verdict.ruleName)
  }

  @Test
  fun `composite destructive command outside the project requires approval`() {
    val verdict = classifyCommand(
      commandText = "rm ../a.txt && rm ../b.txt",
      projectRoot = projectRoot
    )
    assertIs<CommandVerdict.NeedsApproval>(value = verdict)
  }

  @Test
  fun `blank and whitespace commands are safe`() {
    listOf("", "   ", "\t\n").forEach { command ->
      val verdict = classifyCommand(commandText = command, projectRoot = projectRoot)
      assertEquals(CommandVerdict.Safe, verdict)
    }
  }

  @Test
  fun `privilege escalation alone never escalates the verdict`() {
    listOf("sudo ls /etc", "pkexec git status", "doas cat /etc/hosts").forEach { command ->
      val verdict = classifyCommand(commandText = command, projectRoot = projectRoot)
      assertEquals(CommandVerdict.Safe, verdict, "read-only under sudo should stay safe: '$command'")
    }
  }

  @Test
  fun `composite operators alone never escalate the verdict`() {
    listOf("echo hi | grep hi", "git add . && git commit", "ls; pwd").forEach { command ->
      val verdict = classifyCommand(commandText = command, projectRoot = projectRoot)
      assertEquals(CommandVerdict.Safe, verdict, "read-only composite should stay safe: '$command'")
    }
  }

  @Test
  fun `command substitution payload is blocked`() {
    val verdict = classifyCommand(
      commandText = "echo \$(rm -rf /etc) done",
      projectRoot = projectRoot
    )
    assertIs<CommandVerdict.Blocked>(value = verdict)
    assertEquals("risk:highScore", verdict.ruleName)
  }

  @Test
  fun `backtick payload is blocked`() {
    val verdict = classifyCommand(
      commandText = "echo `rm -rf /usr`",
      projectRoot = projectRoot
    )
    assertIs<CommandVerdict.Blocked>(value = verdict)
  }

  @Test
  fun `shell -c payload is blocked`() {
    val verdict = classifyCommand(
      commandText = "sh -c \"rm -rf /var/log\"",
      projectRoot = projectRoot
    )
    assertIs<CommandVerdict.Blocked>(value = verdict)
  }

  @Test
  fun `sudo multiplier pushes a mutating command into approval`() {
    val verdict = classifyCommand(
      commandText = "sudo chmod 755 ../file.txt",
      projectRoot = projectRoot
    )
    assertIs<CommandVerdict.NeedsApproval>(value = verdict)
  }

  @Test
  fun `escalation stack is capped at the block threshold`() {
    val verdict = classifyCommand(
      commandText = "sudo sh -c \"rm -rf /etc\"",
      projectRoot = projectRoot
    )
    assertIs<CommandVerdict.Blocked>(value = verdict)
    assertEquals("risk:highScore", verdict.ruleName)
  }

  @Test
  fun `dd into project scratch space is safe`() {
    val verdict = classifyCommand(
      commandText = "dd if=/dev/zero of=./scratch.img bs=1m count=8",
      projectRoot = projectRoot
    )
    assertEquals(CommandVerdict.Safe, verdict)
  }

  @Test
  fun `dd onto a protected file path is blocked`() {
    val verdict = classifyCommand(
      commandText = "dd if=./boot.img of=/etc/fstab",
      projectRoot = projectRoot
    )
    assertIs<CommandVerdict.Blocked>(value = verdict)
    assertEquals("dd:criticalPath", verdict.ruleName)
  }

  @Test
  fun `file redirection inside the project is safe`() {
    val verdict = classifyCommand(
      commandText = "echo hi > out.txt",
      projectRoot = projectRoot
    )
    assertEquals(CommandVerdict.Safe, verdict)
  }

  @Test
  fun `mv inside the project is safe`() {
    val verdict = classifyCommand(
      commandText = "mv build/a.txt build/b.txt",
      projectRoot = projectRoot
    )
    assertEquals(CommandVerdict.Safe, verdict)
  }

  @Test
  fun `unknown mutating executable gets its own approval category`() {
    val verdict = classifyCommand(
      commandText = "sudo shred ../secret.txt",
      projectRoot = projectRoot
    )
    assertIs<CommandVerdict.NeedsApproval>(value = verdict)
    assertEquals("shred:modify", verdict.category)
  }

  @Test
  fun `quoted protected paths cannot slip past the check`() {
    listOf("rm -rf \"/etc\"", "rm -rf '/etc'", "rm -rf \"/usr/local\"").forEach { command ->
      val verdict = classifyCommand(commandText = command, projectRoot = projectRoot)
      assertIs<CommandVerdict.Blocked>(value = verdict, message = "quoted protected path should block '$command'")
      assertEquals("rm:criticalPath", verdict.ruleName)
    }
  }

  @Test
  fun `relative traversal into a protected path is blocked`() {
    val verdict = classifyCommand(commandText = "rm -rf ../../etc/passwd", projectRoot = projectRoot)
    assertIs<CommandVerdict.Blocked>(value = verdict)
    assertEquals("rm:criticalPath", verdict.ruleName)
  }

  @Test
  fun `redirection into a protected path is blocked`() {
    listOf(
      "echo x > /etc/passwd",
      "echo x >> /etc/hosts",
      "echo x > \$HOME/.ssh/authorized_keys",
    ).forEach { command ->
      val verdict = classifyCommand(commandText = command, projectRoot = projectRoot)
      assertIs<CommandVerdict.Blocked>(
        value = verdict, message = "redirection to a protected path should block '$command'"
      )
      assertEquals("redirect:criticalPath", verdict.ruleName)
    }
  }

  @Test
  fun `redirection to a device sink is safe`() {
    listOf("echo hi > /dev/null", "ls /nonexistent 2>/dev/null", "echo hi 2>&1").forEach { command ->
      val verdict = classifyCommand(commandText = command, projectRoot = projectRoot)
      assertEquals(CommandVerdict.Safe, verdict, "device sink should stay safe: '$command'")
    }
  }

  @Test
  fun `reading protected paths stays safe`() {
    listOf("cat /etc/hosts", "grep root /etc/passwd", "ls /etc", "sudo cat /etc/shadow").forEach { command ->
      val verdict = classifyCommand(commandText = command, projectRoot = projectRoot)
      assertEquals(CommandVerdict.Safe, verdict, "reading a protected path should stay safe: '$command'")
    }
  }

  @Test
  fun `command wrappers are looked through to the real executable`() {
    listOf(
      "env rm -rf /etc",
      "env FOO=bar rm -rf /etc",
      "nohup rm -rf /etc",
      "command rm -rf /etc",
      "sudo -u root rm -rf /etc",
    ).forEach { command ->
      val verdict = classifyCommand(commandText = command, projectRoot = projectRoot)
      assertIs<CommandVerdict.Blocked>(value = verdict, message = "wrapped destructive command should block '$command'")
    }
  }

  @Test
  fun `find with a destructive predicate on a protected root is blocked`() {
    listOf(
      "find /etc -delete",
      "find / -name '*.log' -exec rm {} +",
    ).forEach { command ->
      val verdict = classifyCommand(commandText = command, projectRoot = projectRoot)
      assertIs<CommandVerdict.Blocked>(value = verdict, message = "destructive find should block '$command'")
    }
  }

  @Test
  fun `find with a destructive predicate in a safe prefix stays safe`() {
    val verdict = classifyCommand(commandText = "find /tmp -name '*.log' -delete", projectRoot = projectRoot)
    assertEquals(CommandVerdict.Safe, verdict)
  }

  @Test
  fun `unresolvable write target requires approval`() {
    listOf("rm -rf \$SOMEVAR/x", "echo x > \$SOMEVAR/out.txt").forEach { command ->
      val verdict = classifyCommand(commandText = command, projectRoot = projectRoot)
      assertIs<CommandVerdict.NeedsApproval>(
        value = verdict, message = "an unresolvable write target should need approval: '$command'"
      )
    }
  }

  @Test
  fun `xargs forwarding paths into a destructive command requires approval`() {
    val verdict = classifyCommand(commandText = "xargs rm -rf", projectRoot = projectRoot)
    assertIs<CommandVerdict.NeedsApproval>(value = verdict)
    assertEquals("rm:delete", verdict.category)
  }

  @Test
  fun `shell -c payload containing a file write is blocked`() {
    val verdict = classifyCommand(
      commandText = "sh -c \"echo x > /etc/passwd\"",
      projectRoot = projectRoot
    )
    assertIs<CommandVerdict.Blocked>(value = verdict)
  }

  @Test
  fun `differently cased protected prefix is blocked on a case-insensitive volume`() {
    if (!isCaseInsensitiveFileSystem()) return
    val verdict = classifyCommand(commandText = "rm -rf /ETC", projectRoot = projectRoot)
    assertIs<CommandVerdict.Blocked>(value = verdict)
  }

  // Creates a probe in the temp dir and checks whether its upper-cased sibling resolves.
  private fun isCaseInsensitiveFileSystem(): Boolean {
    return try {
      val directory: Path = Files.createTempDirectory("gradum-case-probe")
      try {
        Files.createFile(directory.resolve("gradum-probe"))
        Files.exists(directory.resolve("GRADUM-PROBE"))
      } finally {
        directory.toFile().deleteRecursively()
      }
    } catch (_: IOException) {
      false
    }
  }
}
