/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * LspServerSpec.kt  2026-06-21 07:53:44 Changed by gwy
 */

package gradum.util

import java.io.File
import java.nio.file.Path

/**
 * Declarative description of one LSP server. Adding a new language means
 * adding one entry to [LspServerRegistry.KNOWN_SERVERS] — no other code
 * changes required.
 */
data class LspServerSpec(
    val languageId: String,
    val command: List<String>,
    val extensions: Set<String>,
    val projectMarker: String? = null,
    val installHint: String
) {
    fun matches(path: Path): Boolean = extensions.any {
        ext: String -> path.fileName.toString().endsWith(ext)
    }
}

/**
 * Static registry of every LSP server Gradum knows about. The list is the
 * single source of truth for "which language maps to which server".
 */
object LspServerRegistry {
    val KNOWN_SERVERS: List<LspServerSpec> = listOf(
        LspServerSpec(languageId = "kotlin", command = listOf("kotlin-language-server"), extensions = setOf(".kt", ".kts"), projectMarker = "build.gradle.kts", installHint = "brew install kotlin-language-server"),
        LspServerSpec(languageId = "java", command = listOf("jdtls"), extensions = setOf(".java"), projectMarker = "build.gradle.kts", installHint = "brew install jdtls"),
        LspServerSpec(languageId = "typescript", command = listOf("typescript-language-server", "--stdio"), extensions = setOf(".ts", ".tsx"), projectMarker = "package.json", installHint = "npm i -g typescript-language-server typescript"),
        LspServerSpec(languageId = "javascript", command = listOf("typescript-language-server", "--stdio"), extensions = setOf(".js", ".jsx", ".mjs"), projectMarker = "package.json", installHint = "npm i -g typescript-language-server typescript"),
        LspServerSpec(languageId = "python", command = listOf("pyright-langserver", "--stdio"), extensions = setOf(".py"), projectMarker = "pyproject.toml", installHint = "npm i -g pyright"),
        LspServerSpec(languageId = "go", command = listOf("gopls"), extensions = setOf(".go"), projectMarker = "go.mod", installHint = "go install golang.org/x/tools/gopls@latest"),
        LspServerSpec(languageId = "rust", command = listOf("rust-analyzer"), extensions = setOf(".rs"), projectMarker = "Cargo.toml", installHint = "rustup component add rust-analyzer"),
        LspServerSpec(languageId = "cpp", command = listOf("clangd"), extensions = setOf(".c", ".cpp", ".cc", ".h", ".hpp"), projectMarker = "compile_commands.json", installHint = "brew install llvm"),
        LspServerSpec(languageId = "csharp", command = listOf("csharp-ls"), extensions = setOf(".cs"), projectMarker = null, installHint = "dotnet tool install -g csharp-ls"),
        LspServerSpec(languageId = "ruby", command = listOf("solargraph", "stdio"), extensions = setOf(".rb"), projectMarker = "Gemfile", installHint = "gem install solargraph"),
    )

    fun findByPath(path: Path): LspServerSpec? = KNOWN_SERVERS.firstOrNull { it.matches(path) }
}

/**
 * Walk up from [start] looking for [marker]. Returns the directory containing
 * the marker, or [start]'s parent if nothing is found. Bounded by [maxDepth]
 * to avoid traversing the entire filesystem on a stray path.
 */
fun findProjectRoot(start: Path, marker: String?, maxDepth: Int = 8): Path {
    var current: Path = start.toAbsolutePath().normalize()

    if (marker == null) return start.toAbsolutePath().normalize()
    repeat(maxDepth) {
        if (current.resolve(marker).toFile().exists())
            return current

        val parent: Path? = current.parent

        if (parent == null || parent == current)
            return start.toAbsolutePath().normalize()
        current = parent
    }
    return start.toAbsolutePath().normalize()
}

/**
 * Check whether the executable for [spec] is on PATH. Used to gracefully
 * degrade when a server is not installed.
 */
fun isLspServerInstalled(spec: LspServerSpec): Boolean {
    val binary: String = spec.command.first()
    val pathEnv: String = System.getenv("PATH") ?: return false
    val separator: Char = File.pathSeparatorChar

    return pathEnv.split(separator).any {
        dir: String -> File(dir, binary).canExecute()
    }
}
