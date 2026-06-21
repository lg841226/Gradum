/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * CommandFilter.kt  2026-06-21 07:53:44 Changed by gwy
 */

package gradum.util

import java.nio.file.Paths

sealed class CommandVerdict {

    data object Safe : CommandVerdict()

    data class Blocked(val ruleName: String, val description: String) : CommandVerdict()
}

private val blockedExecutables: Set<String> = setOf(
    "mkfs", "mkfs.ext2", "mkfs.ext3", "mkfs.ext4",
    "mkfs.xfs", "mkfs.btrfs", "mkfs.vfat", "mkfs.ntfs",
    "mkswap", "fdisk", "sfdisk", "parted", "gdisk",
    "shutdown", "reboot", "halt", "poweroff", "init",
    "sudo", "su", "doas", "pkexec",
)

private val protectedPrefixes: List<String> = listOf(
    "/etc", "/usr", "/var", "/boot", "/bin", "/sbin",
    "/lib", "/lib64", "/opt",
    "/System", "/Library", "/Applications", "/private",
)

private val protectedHomeSubdirectories: List<String> = listOf(
    ".ssh", ".gnupg", ".aws", ".kube", ".netrc",
    ".pypirc", ".npmrc", ".docker",
)

private val safePathPrefixes: List<String> = listOf("/tmp")

private val exactProtectedPaths: List<String> = listOf("/", "/dev", "/proc", "/sys")

fun classifyCommand(commandText: String): CommandVerdict {
    if (commandText.isBlank()) return CommandVerdict.Safe

    val tokens: List<String> = commandText.trim().split("\\s+".toRegex())
    if (tokens.isEmpty()) return CommandVerdict.Safe

    val executableName: String = Paths.get(tokens[0]).fileName.toString()

    if (executableName in blockedExecutables) {
        return CommandVerdict.Blocked("executable:$executableName", "'$executableName' is not allowed")
    }

    return when (executableName) {
        "dd" -> classifyDeviceWrite(tokens)
        "rm" -> classifyRemoveOperation(tokens)
        "chmod" -> classifyChmodOperation(tokens)
        else -> CommandVerdict.Safe
    }
}

private fun classifyDeviceWrite(commandTokens: List<String>): CommandVerdict {
    for (token in commandTokens) {
        if (token.startsWith("of=") && token.removePrefix("of=").startsWith("/dev/")) {
            return CommandVerdict.Blocked(
                "dd:deviceOutput",
                "Writing to device '${token.removePrefix("of=")}' is not allowed",
            )
        }
    }

    return CommandVerdict.Safe
}

private fun classifyRemoveOperation(commandTokens: List<String>): CommandVerdict {
    val pathArguments: List<String> = extractPathArguments(commandTokens)
    for (targetPath in pathArguments) {
        if (isCriticalPath(targetPath)) {
            return CommandVerdict.Blocked("rm:criticalPath", "Removing critical path '$targetPath' is not allowed")
        }
    }

    return CommandVerdict.Safe
}

private fun classifyChmodOperation(commandTokens: List<String>): CommandVerdict {
    val hasRecursiveFlag: Boolean = commandTokens.any { it == "-R" || it == "--recursive" }
    if (!hasRecursiveFlag) return CommandVerdict.Safe

    val pathArguments: List<String> = extractPathArguments(commandTokens)
    for (targetPath in pathArguments) {
        if (isCriticalPath(targetPath)) {
            return CommandVerdict.Blocked(
                ruleName = "chmod:criticalPathRecursive",
                description = "Recursive chmod on critical path '$targetPath' is not allowed",
            )
        }
    }

    return CommandVerdict.Safe
}

private fun extractPathArguments(commandTokens: List<String>): List<String> {
    return commandTokens.drop(1).filter { token: String -> !token.startsWith("-") }
}

private fun resolveAbsolutePath(pathString: String): String {
    return try {
        Paths.get(pathString).toAbsolutePath().normalize().toString()
    } catch (e: Exception) {
        pathString
    }
}

private fun isCriticalPath(targetPath: String): Boolean {
    val resolvedTarget: String = resolveAbsolutePath(targetPath)

    for (safePrefix in safePathPrefixes) {
        if (resolvedTarget == safePrefix || resolvedTarget.startsWith("$safePrefix/")) {
            return false
        }
    }

    for (systemPrefix in protectedPrefixes) {
        val resolvedPrefix: String = resolveAbsolutePath(systemPrefix)
        if (resolvedTarget == resolvedPrefix || resolvedTarget.startsWith("$resolvedPrefix/")) {
            return true
        }
    }

    val homeDirectory: String = System.getProperty("user.home")
    for (protectedSubdirectory in protectedHomeSubdirectories) {
        val protectedPath: String = "$homeDirectory/$protectedSubdirectory"
        if (resolvedTarget == protectedPath || resolvedTarget.startsWith("$protectedPath/")) {
            return true
        }
    }

    if (resolvedTarget in exactProtectedPaths) {
        return true
    }

    return false
}
