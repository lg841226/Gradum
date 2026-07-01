/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * CommandFilterTest.kt  2026-06-30 23:35:47 Changed by gwy
 */

package gradum.utils

import gradum.ToolMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommandFilterTest {

    @Test
    fun `write mode allows all commands that are not on the danger blocklist`() {
        // Anything not in the always-on blocklist should pass under WRITE.
        listOf("ls", "cat foo.txt", "rm /tmp/junk", "git status", "echo hi").forEach { command ->
            val verdict = classifyCommand(command, ToolMode.WRITE)
            assertEquals(CommandVerdict.Safe, verdict, "WRITE should allow '$command'")
        }
    }

    @Test
    fun `read-only mode permits the read-only whitelist`() {
        listOf(
            "ls -la",
            "cat src/main.kt",
            "grep -r TODO src/",
            "find . -name '*.kt'",
            "wc -l README.md",
            "head -n 5 file.txt",
            "tail -n 20 file.txt",
            "tree -L 2",
            "pwd",
            "echo hello",
        ).forEach { command ->
            val verdict = classifyCommand(command, ToolMode.READ_ONLY)
            assertEquals(CommandVerdict.Safe, verdict, "READ_ONLY should allow '$command'")
        }
    }

    @Test
    fun `read-only mode blocks every common write command`() {
        // Each of these would either mutate the filesystem, the network, or
        // process state. All must be rejected under READ_ONLY regardless of
        // how the LLM phrases them.
        listOf(
            "rm foo.txt",
            "mv a b",
            "cp a b",
            "touch newfile",
            "mkdir newdir",
            "chmod 777 secret",
            "chown root secret",
            "git commit -m 'oops'",
            "git push origin main",
            "git checkout main",
            "npm install lodash",
            "pip install requests",
            "brew install wget",
            "curl https://example.com",
            "ssh user@host",
            "kill 1234",
            "shutdown -h now",
            "dd if=/dev/zero of=/dev/sda",
        ).forEach { command ->
            val verdict = classifyCommand(command, ToolMode.READ_ONLY)
            // Some entries (rm / dd / chmod) are caught by the always-on
            // danger filter; others by the READ_ONLY whitelist. Either way
            // the command must be rejected.
            assertIs<CommandVerdict.Blocked>(verdict, "READ_ONLY should block '$command'")
        }
    }

    @Test
    fun `read-only mode blocks shell redirect via a permissive executable name`() {
        // `tee` and `sed` aren't on the read-only whitelist, so even a
        // hand-crafted "cat file | tee out" should be rejected before the
        // shell ever sees the redirect.
        listOf(
            "echo hi > out.txt",
            "cat file.txt | tee out.txt",
            "ls > listing.txt",
        ).forEach { command ->
            val verdict = classifyCommand(command, ToolMode.READ_ONLY)
            assertIs<CommandVerdict.Blocked>(verdict, "READ_ONLY should block redirect '$command'")
        }
    }

    @Test
    fun `always-on danger filter still applies in write mode`() {
        // Sanity check that READ_ONLY didn't accidentally weaken the
        // existing danger rules — sudo / mkfs / rm of /etc must still be
        // rejected under WRITE.
        listOf("sudo reboot", "mkfs.ext4 /dev/sdb", "rm -rf /etc").forEach { command ->
            val verdict = classifyCommand(command, ToolMode.WRITE)
            assertIs<CommandVerdict.Blocked>(verdict, "WRITE danger filter should block '$command'")
        }
    }

    @Test
    fun `default toolMode parameter is write so existing callers stay safe`() {
        // RunCommandSkill and any other caller that doesn't pass a toolMode
        // must keep the old behavior: only the always-on blocklist applies.
        val verdict = classifyCommand("rm junk.txt")
        assertEquals(CommandVerdict.Safe, verdict, "Default toolMode should be WRITE")
    }
}
