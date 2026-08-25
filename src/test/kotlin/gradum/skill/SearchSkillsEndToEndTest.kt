/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SearchSkillsEndToEndTest.kt  2026-08-16 16:52:39 Changed by gwy
 */

package gradum.skill

import gradum.Provider
import gradum.SkillResult
import gradum.ToolMode
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * End-to-end coverage for the [GlobSkill] and [GrepSkill] on-disk
 * behavior. The `compileGlobToRegex` unit tests pin the translation
 * itself; these tests exercise the whole pipeline:
 *
 *   1. Build a small project tree in a JUnit temp dir.
 *   2. Construct a [SkillContext] pointing at that tree.
 *   3. Invoke the skill's `execute` with a real `arguments` map.
 *   4. Assert the result's `files` / `matches` reflect what the user
 *      would actually see in the chat panel.
 *
 * The model name is set to `qwen2.5:7b` so the SIMPLE schema path
 * runs (`isSmall(modelName) = true`). The SIMPLE path shares its
 * glob engine with the FULL path, so the bug under test affects
 * both, and one test path is enough to prove the regression.
 */
class SearchSkillsEndToEndTest {

    private lateinit var projectRoot: File

    @BeforeTest
    fun setUp() {
        projectRoot = Files.createTempDirectory("gradum_search_test_").toFile()

        fun write(relative: String, body: String) {
            val file = File(projectRoot, relative)
            file.parentFile?.mkdirs()
            file.writeText(body)
        }

        write("gradum_kotlin.kt", """logger.info("Opening Local AI Assistant tool window")""")
        write(
            "src/main/kotlin/com/wangyang/localaiassistant/ui/CustomBorders.kt",
            "package com.wangyang.localaiassistant.ui\nclass CustomBorders\n",
        )
        write(
            "src/main/kotlin/com/wangyang/localaiassistant/ui/DebugBorder.kt",
            "package com.wangyang.localaiassistant.ui\nclass DebugBorder\n",
        )
        write(
            "src/main/kotlin/com/wangyang/localaiassistant/ui/BubbleUtils.kt",
            "package com.wangyang.localaiassistant.ui\nclass BubbleUtils\n// class BubbleHelper\n",
        )
        write(
            "src/main/java/com/example/Legacy.java",
            "package com.example;\npublic class Legacy {}\n",
        )
        write(
            "src/main/kotlin/com/wangyang/localaiassistant/MainToolWindow.kt",
            "package com.wangyang.localaiassistant\nclass MainToolWindow\n",
        )
        write("README.md", "# readme\n")

        write("build/classes/Foo.class/CustomBorders.class", "nope")
        write("bin/main/CustomBorders.kt/CustomBorders.kt", "package should_be_skipped\n")
        write(".gradle/cache/Foo.kt/Foo.kt", "package should_be_skipped\n")
        write(".idea/workspace.xml", "<idea-project/>")
    }

    @AfterTest
    fun tearDown() {
        projectRoot.deleteRecursively()
    }

    private fun simpleContext(): SkillContext = SkillContext(
        toolMode = ToolMode.READ_ONLY,
        projectRoot = projectRoot.absolutePath,
        provider = Provider.OLLAMA,
        modelName = "qwen2.5:7b",
    )

    /**
     * The SIMPLE schema path (local 7B models) is hard-wired to
     * case-insensitive matching — small models can't reliably use
     * `caseSensitive`. The CLOUD/FULL path is what actually honors the
     * `caseSensitive` argument, so the case-sensitivity tests must run
     * with a model that resolves to `SchemaVariant.FULL`.
     */
    private fun cloudContext(): SkillContext = SkillContext(
        toolMode = ToolMode.READ_ONLY,
        projectRoot = projectRoot.absolutePath,
        provider = Provider.OPENAI,
        modelName = "gpt-4o",
    )

    @Test
    fun `Glob finds a file by exact relative glob (regression for the original report)`() {
        val skill = GlobSkill()
        val result = skill.execute(
            arguments = mapOf("pattern" to "**/CustomBorders.kt"),
            context = simpleContext(),
        )

        val files = result.fields("files") as List<*>
        assertEquals(
            listOf("src/main/kotlin/com/wangyang/localaiassistant/ui/CustomBorders.kt"),
            files,
            "Glob must return the file's path relative to the project root"
        )
        assertEquals(
              1,
              result.fields("total_files")
        )
    }

    @Test
    fun `Glob does not match files under skipped directories`() {
        val skill = GlobSkill()
        val result = skill.execute(
            arguments = mapOf("pattern" to "**/CustomBorders.kt"),
            context = simpleContext(),
        )
        val files = result.fields("files") as List<*>
        assertTrue(
            files.none { (it as String).startsWith("bin/") },
            "bin/ is in SKIPPED_DIRECTORY_NAMES and must be walked past"
        )
        assertTrue(
            files.none { (it as String).startsWith("build/") },
            "build/ is in SKIPPED_DIRECTORY_NAMES and must be walked past"
        )
        assertTrue(
            files.none { (it as String).startsWith(".gradle/") },
            ".gradle/ starts with '.' and must be walked past"
        )
        assertTrue(
            files.none { (it as String).startsWith(".idea/") },
            ".idea/ starts with '.' and must be walked past"
        )
    }

    @Test
    fun `Glob single-star is anchored to a single path segment`() {
        val skill = GlobSkill()
        val result = skill.execute(
            arguments = mapOf("pattern" to "*.kt"),
            context = simpleContext(),
        )
        val files = result.fields("files") as List<*>
        assertEquals(
              listOf("gradum_kotlin.kt"),
              files
        )
    }

    @Test
    fun `Glob double-star matches files at any depth`() {
        val skill = GlobSkill()
        val result = skill.execute(
            arguments = mapOf("pattern" to "**/*.kt"),
            context = simpleContext(),
        )
        val files = (result.fields("files") as List<*>).map { it as String }
        assertEquals(
            setOf(
                "gradum_kotlin.kt",
                "src/main/kotlin/com/wangyang/localaiassistant/ui/CustomBorders.kt",
                "src/main/kotlin/com/wangyang/localaiassistant/ui/DebugBorder.kt",
                "src/main/kotlin/com/wangyang/localaiassistant/ui/BubbleUtils.kt",
                "src/main/kotlin/com/wangyang/localaiassistant/MainToolWindow.kt",
            ),
            files.toSet(),
            "**/*.kt must reach every .kt file in the tree"
        )
        assertTrue(files.none { it.startsWith("bin/") })
        assertTrue(files.none { it.startsWith("build/") })
        assertTrue(files.none { it.startsWith(".gradle/") })
    }

    @Test
    fun `Glob does not match across extensions`() {
        val skill = GlobSkill()
        val result = skill.execute(
            arguments = mapOf("pattern" to "**/*.java"),
            context = simpleContext(),
        )
        val files = (result.fields("files") as List<*>).map { it as String }
        assertEquals(
            listOf("src/main/java/com/example/Legacy.java"),
            files,
        )
    }

    @Test
    fun `Glob with question mark matches the right names`() {
        val skill = GlobSkill()
        val result = skill.execute(
            arguments = mapOf("pattern" to "RE????.md"),
            context = simpleContext(),
        )
        val files = (result.fields("files") as List<*>).map { it as String }
        assertEquals(
              listOf("README.md"),
              files
        )
    }

    @Test
    fun `Glob returns zero files (and zero error) for a pattern that matches nothing`() {
        val skill = GlobSkill()
        val result = skill.execute(
            arguments = mapOf("pattern" to "**/DoesNotExist.kt"),
            context = simpleContext(),
        )
        assertEquals(
              0,
              result.fields("total_files")
        )
        assertEquals(
              emptyList<String>(),
              result.fields("files")
        )
    }

    @Test
    fun `Glob rejects an empty pattern with a structured error`() {
        val skill = GlobSkill()
        val result = skill.execute(
            arguments = mapOf("pattern" to "   "),
            context = simpleContext(),
        )
        assertTrue(
            result.isError(),
            "An empty pattern is a usage error, not a zero-match result; " +
                "the LLM needs the error to know it called the tool wrong"
        )
    }

    @Test
    fun `Glob with a path argument scopes the walk to that subdirectory`() {
        val skill = GlobSkill()
        val result = skill.execute(
            arguments = mapOf(
                "pattern" to "**/*.kt",
                "path" to "src/main/kotlin/com/wangyang/localaiassistant/ui",
            ),
            context = simpleContext(),
        )
        val files = (result.fields("files") as List<*>).map { it as String }.toSet()
        assertEquals(
            setOf(
                "src/main/kotlin/com/wangyang/localaiassistant/ui/CustomBorders.kt",
                "src/main/kotlin/com/wangyang/localaiassistant/ui/DebugBorder.kt",
                "src/main/kotlin/com/wangyang/localaiassistant/ui/BubbleUtils.kt",
            ),
            files,
        )
    }

    @Test
    fun `Grep include filter for kotlin sources actually finds them (regression)`() {
        val skill = GrepSkill()
        val result = skill.execute(
            arguments = mapOf(
                "pattern" to "CustomBorders",
                "include" to "*.kt",
            ),
            context = simpleContext(),
        )
        val totalMatches = result.fields("total_matches") as Int
        val matches = result.fields("matches") as List<Map<*, *>>
        assertTrue(
            totalMatches >= 1,
            "Grep with include=*.kt must find at least one Kotlin file containing CustomBorders; " +
                "before the fix, the include pattern was a literal substring and matched nothing"
        )
        assertTrue(matches.any { (it["content"] as String).contains("CustomBorders") })
    }

    @Test
    fun `Grep with class Border pattern and kt include finds DebugBorder and CustomBorders`() {
        val skill = GrepSkill()
        val result = skill.execute(
            arguments = mapOf(
                "pattern" to "class.*Border",
                "include" to "*.kt",
            ),
            context = simpleContext(),
        )
        val matches = (result.fields("matches") as List<*>)
            .map { (it as Map<*, *>)["content"] as String }
        assertTrue(matches.any { it.contains("class CustomBorders") })
        assertTrue(matches.any { it.contains("class DebugBorder") })
    }

    @Test
    fun `Grep does not search skipped directories`() {
        val skill = GrepSkill()
        val result = skill.execute(
            arguments = mapOf(
                "pattern" to "should_be_skipped",
                "include" to "*.kt",
            ),
            context = simpleContext(),
        )
        assertEquals(
              0,
              result.fields("total_matches")
        )
    }

    @Test
    fun `Grep reports which files were searched`() {
        val skill = GrepSkill()
        val result = skill.execute(
            arguments = mapOf(
                "pattern" to "class",
                "include" to "*.kt",
            ),
            context = simpleContext(),
        )
        val filesSearched = result.fields("files_searched") as Int
        assertTrue(
            filesSearched in 4..6,
            "Expected roughly 5 .kt files searched, got $filesSearched"
        )
    }

    @Test
    fun `Glob brace alternation matches multiple extensions`() {
        val skill = GlobSkill()
        val result = skill.execute(
            arguments = mapOf("pattern" to "**/*.{kt,java}"),
            context = simpleContext(),
        )
        val files = (result.fields("files") as List<*>).map { it as String }.toSet()
        assertEquals(
            setOf(
                "gradum_kotlin.kt",
                "src/main/kotlin/com/wangyang/localaiassistant/ui/CustomBorders.kt",
                "src/main/kotlin/com/wangyang/localaiassistant/ui/DebugBorder.kt",
                "src/main/kotlin/com/wangyang/localaiassistant/ui/BubbleUtils.kt",
                "src/main/kotlin/com/wangyang/localaiassistant/MainToolWindow.kt",
                "src/main/java/com/example/Legacy.java",
            ),
            files,
        )
    }

    @Test
    fun `Glob with character class matches the right letters`() {
        val skill = GlobSkill()
        val result = skill.execute(
            arguments = mapOf("pattern" to "**/[CD]*.kt"),
            context = simpleContext(),
        )
        val files = (result.fields("files") as List<*>).map { it as String }.toSet()
        assertEquals(
            setOf(
                "src/main/kotlin/com/wangyang/localaiassistant/ui/CustomBorders.kt",
                "src/main/kotlin/com/wangyang/localaiassistant/ui/DebugBorder.kt",
            ),
            files,
        )
    }

    @Test
    fun `Glob with absolute path argument scopes the walk to that subdirectory`() {
        val skill = GlobSkill()
        val absoluteUiDir =
            File(projectRoot, "src/main/kotlin/com/wangyang/localaiassistant/ui").absolutePath
        val result = skill.execute(
            arguments = mapOf(
                "pattern" to "**/*.kt",
                "path" to absoluteUiDir,
            ),
            context = simpleContext(),
        )
        val files = (result.fields("files") as List<*>).map { it as String }.toSet()
        assertEquals(
            setOf(
                "src/main/kotlin/com/wangyang/localaiassistant/ui/CustomBorders.kt",
                "src/main/kotlin/com/wangyang/localaiassistant/ui/DebugBorder.kt",
                "src/main/kotlin/com/wangyang/localaiassistant/ui/BubbleUtils.kt",
            ),
            files,
            "Output paths must stay relative to the project root, even when path is absolute"
        )
    }

    @Test
    fun `Glob with a non-existent path returns a structured error`() {
        val skill = GlobSkill()
        val result = skill.execute(
            arguments = mapOf(
                "pattern" to "**/*.kt",
                "path" to "this/dir/does/not/exist",
            ),
            context = simpleContext(),
        )
        assertTrue(result.isError(), "Non-existent path must surface as an error, not a zero-result")
    }

    @Test
    fun `Glob with a malformed pattern returns a structured error`() {
        val skill = GlobSkill()
        val result = skill.execute(
            arguments = mapOf("pattern" to "**/[unclosed"),
            context = simpleContext(),
        )
        assertTrue(result.isError(), "Malformed glob must surface as an error")
    }

    @Test
    fun `Glob respects the limit argument`() {
        val skill = GlobSkill()
        val result = skill.execute(
            arguments = mapOf(
                "pattern" to "**/*.kt",
                "limit" to 2,
            ),
            context = simpleContext(),
        )
        val total = result.fields("total_files") as Int
        val limitApplied = result.fields("limit_applied") as Boolean
        val files = result.fields("files") as List<*>
        assertEquals(
              2,
              files.size
        )
        assertEquals(
              2,
              total
        )
        assertTrue(limitApplied, "limit_applied must be true when result hit the cap")
    }

    @Test
    fun `Glob limit argument is coerced into the allowed range`() {
        val skill = GlobSkill()
        val result = skill.execute(
            arguments = mapOf(
                "pattern" to "gradum_kotlin.kt",
                "limit" to 0,
            ),
            context = simpleContext(),
        )
        val files = result.fields("files") as List<*>
        assertEquals(
              1,
              files.size,
              "limit=0 should be coerced up to 1, not silently drop results"
        )
    }

    @Test
    fun `Glob single-star does not cross path separators`() {
        val skill = GlobSkill()
        val result = skill.execute(
            context = simpleContext(),
            arguments = mapOf("pattern" to "src/*/kotlin")
        )
        val files = (result.fields("files") as List<*>).map { it as String }
        assertEquals(
              emptyList(),
              files
        )
    }

    @Test
    fun `Glob double-star in the middle matches at any depth`() {
        val skill = GlobSkill()
        val result = skill.execute(
            arguments = mapOf("pattern" to "src/**/*.kt"),
            context = simpleContext(),
        )
        val files = (result.fields("files") as List<*>).map { it as String }.toSet()
        assertTrue(
            files.contains("src/main/kotlin/com/wangyang/localaiassistant/ui/CustomBorders.kt"),
            "src/**/*.kt must reach files nested arbitrarily deep under src/"
        )
        assertTrue(
            files.contains("src/main/kotlin/com/wangyang/localaiassistant/MainToolWindow.kt"),
            "src/**/*.kt must also reach files at sibling package roots"
        )
        assertTrue(files.none { it.startsWith("bin/") })
    }

    @Test
    fun `Grep rejects an empty pattern with a structured error`() {
        val skill = GrepSkill()
        val result = skill.execute(
            arguments = mapOf("pattern" to "  "),
            context = simpleContext(),
        )
        assertTrue(
            result.isError(),
            "An empty pattern is a usage error, not a zero-match result; " +
                "the LLM needs the error to know it called the tool wrong"
        )
    }

    @Test
    fun `Grep rejects a malformed regex with a structured error`() {
        val skill = GrepSkill()
        val result = skill.execute(
            arguments = mapOf("pattern" to "(unclosed"),
            context = simpleContext(),
        )
        assertTrue(
            result.isError(),
            "An invalid regex must surface as an error so the LLM can retry with valid syntax"
        )
    }

    @Test
    fun `Grep with caseSensitive true is case sensitive`() {
        val skill = GrepSkill()
        val result = skill.execute(
            arguments = mapOf(
                "pattern" to "customborders",
                "include" to "*.kt",
                "caseSensitive" to true,
            ),
            context = cloudContext(),
        )
        assertEquals(
            0, result.fields("total_matches"),
            "caseSensitive=true on lowercase pattern must not match CustomBorders"
        )

        val upperResult = skill.execute(
            arguments = mapOf(
                "pattern" to "CustomBorders",
                "include" to "*.kt",
                "caseSensitive" to true,
            ),
            context = cloudContext(),
        )
        assertTrue(
            (upperResult.fields("total_matches") as Int) >= 1,
            "caseSensitive=true on the right case must still match"
        )
    }

    @Test
    fun `Grep with caseSensitive false is case insensitive by default`() {
        val skill = GrepSkill()
        val result = skill.execute(
            arguments = mapOf(
                "pattern" to "CUSTOMBORDERS",
                "include" to "*.kt",
                "caseSensitive" to false,
            ),
            context = cloudContext(),
        )
        assertTrue(
            (result.fields("total_matches") as Int) >= 1,
            "caseSensitive=false on uppercase pattern must still match CustomBorders"
        )
    }

    @Test
    fun `Grep limit argument is respected`() {
        val skill = GrepSkill()
        val result = skill.execute(
            arguments = mapOf(
                "pattern" to "class",
                "include" to "*.kt",
                "limit" to 2,
            ),
            context = simpleContext(),
        )
        val matches = result.fields("matches") as List<*>
        val limitApplied = result.fields("limit_applied") as Boolean
        assertEquals(
              2,
              matches.size
        )
        assertTrue(limitApplied, "limit_applied must be true when matches hit the cap")
    }

    @Test
    fun `Grep with empty include matches every text file`() {
        val skill = GrepSkill()
        val result = skill.execute(
            arguments = mapOf(
                "pattern" to "readme",
                "include" to "",
            ),
            context = simpleContext(),
        )
        val matches = (result.fields("matches") as List<*>)
            .map { (it as Map<*, *>)["content"] as String }
        assertTrue(
            matches.any { it.contains("# readme") },
            "Empty include must let the search walk every text file; the README.md hit must show up"
        )
    }

    @Test
    fun `Grep with brace alternation include picks up both extensions`() {
        val skill = GrepSkill()
        val result = skill.execute(
            arguments = mapOf(
                "pattern" to "public class",
                "include" to "*.{kt,java}",
            ),
            context = simpleContext(),
        )
        val matches = (result.fields("matches") as List<*>)
            .map { (it as Map<*, *>)["content"] as String }
        assertTrue(
            matches.any { it.contains("public class Legacy") },
            "Brace alternation include must include .java files"
        )
    }

    @Test
    fun `Grep with a non-existent path returns a structured error`() {
        val skill = GrepSkill()
        val result = skill.execute(
            arguments = mapOf(
                "pattern" to "anything",
                "path" to "this/dir/does/not/exist",
            ),
            context = simpleContext(),
        )
        assertTrue(result.isError(), "Non-existent path must surface as an error, not a zero-result")
    }

    @Test
    fun `Grep with a file (not directory) path returns a structured error`() {
        val skill = GrepSkill()
        val result = skill.execute(
            arguments = mapOf(
                "pattern" to "anything",
                "path" to "gradum_kotlin.kt",
            ),
            context = simpleContext(),
        )
        assertTrue(result.isError(), "A file path (not directory) must be rejected, not silently walked")
    }

    @Test
    fun `Glob pattern with a literal regex metacharacter still works`() {
        val skill = GlobSkill()
        val trickyDir = File(projectRoot, "src/main/kotlin/weird (name)")
        File(trickyDir, "weird.kt").apply { parentFile?.mkdirs() }
            .writeText("package weird\nclass Weird\n")
        val result = skill.execute(
            arguments = mapOf("pattern" to "**/weird (name)/weird.kt"),
            context = simpleContext(),
        )
        val files = (result.fields("files") as List<*>).map { it as String }
        assertEquals(
            listOf("src/main/kotlin/weird (name)/weird.kt"),
            files,
            "Glob must treat regex metacharacters in filenames as literals"
        )
    }

    @Test
    fun `Glob matches a file whose name contains a glob metacharacter literally`() {
        val skill = GlobSkill()
        val trickyFile = File(projectRoot, "weird?name.kt").apply {
            parentFile?.mkdirs()
        }
        trickyFile.writeText("package weird\nclass Weird\n")
        val result = skill.execute(
            arguments = mapOf("pattern" to "weird?name.kt"),
            context = simpleContext(),
        )
        val files = (result.fields("files") as List<*>).map { it as String }
        assertEquals(
            listOf("weird?name.kt"),
            files,
            "A literal `?` in the filename must not be confused with the glob quantifier"
        )
    }

    private fun SkillResult.fields(key: String): Any? =
        (this as SkillResult.Success).data[key]

    private fun SkillResult.isError(): Boolean = this is SkillResult.Failure
}
