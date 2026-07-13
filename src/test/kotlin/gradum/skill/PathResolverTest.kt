/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PathResolverTest.kt  2026-07-13  Changed by gwy
 */

package gradum.skill

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.*

class PathResolverTest {

  private lateinit var tempRoot: File

  @BeforeTest
  fun setUp() {
    tempRoot = Files.createTempDirectory("gradum-path-resolver-test").toFile()
    // Layout:
    //   <root>/
    //     src/demo.js
    //     deep/nested/file.txt
    //     other.js
    File(tempRoot, "src/demo.js").apply {
      parentFile.mkdirs()
      writeText("// demo")
    }
    File(tempRoot, "deep/nested/file.txt").apply {
      parentFile.mkdirs()
      writeText("nested")
    }
    File(tempRoot, "other.js").writeText("// other")
  }

  @AfterTest
  fun tearDown() {
    tempRoot.deleteRecursively()
  }

  private fun rootPath(): String = tempRoot.absolutePath

  @Test
  fun `direct relative path resolves as-is`() {
    val result = resolveProjectPath("src/demo.js", rootPath())
    assertFalse(result.shifted)
    assertEquals("src/demo.js", result.original)
    assertEquals(
      Paths.get(rootPath(), "src/demo.js").toAbsolutePath().normalize(),
      result.resolved
    )
  }

  @Test
  fun `redundant project basename prefix is stripped to existing file`() {
    // Mirrors the LLM trace in the feature request:
    //   projectRoot = .../gradum-playground
    //   LLM call:   Read(path = "gradum-playground/src/demo.js")
    val result = resolveProjectPath(
      "${tempRoot.name}/src/demo.js",
      rootPath()
    )
    assertTrue(result.shifted, "expected shift for first segment = projectRoot basename")
    assertEquals("src/demo.js", result.shiftedForm)
    assertEquals(
      Paths.get(rootPath(), "src/demo.js").toAbsolutePath().normalize(),
      result.resolved
    )
  }

  @Test
  fun `shift falls back to direct path when corrected form does not exist`() {
    // projectRoot basename prefix present, but the suffix is
    // bogus — must NOT silently substitute a wrong file.
    val result = resolveProjectPath(
      "${tempRoot.name}/missing.js",
      rootPath()
    )
    assertFalse(result.shifted)
    assertEquals(
      Paths.get(rootPath(), "${tempRoot.name}/missing.js").toAbsolutePath().normalize(),
      result.resolved
    )
  }

  @Test
  fun `first segment that does not match project basename is not shifted`() {
    // Defensive: a correct path that *happens* to have a
    // sibling with a shallower name must not be silently
    // shortened.
    val result = resolveProjectPath("src/missing-but-other-js-exists.js", rootPath())
    assertFalse(result.shifted)
    assertEquals("src/missing-but-other-js-exists.js", result.original)
  }

  @Test
  fun `path equal to projectRoot basename only is not shifted`() {
    // Stripping the prefix would leave an empty remainder.
    val result = resolveProjectPath(tempRoot.name, rootPath())
    assertFalse(result.shifted)
    assertEquals(tempRoot.name, result.original)
  }

  @Test
  fun `absolute path is returned as-is without shift attempt`() {
    val absolute = Paths.get(rootPath(), "src/demo.js").toAbsolutePath().normalize()
    val result = resolveProjectPath(absolute.toString(), rootPath())
    assertFalse(result.shifted)
    assertEquals(absolute, result.resolved)
  }

  @Test
  fun `blank filePath yields trivial resolution`() {
    val result = resolveProjectPath("", rootPath())
    assertFalse(result.shifted)
    assertEquals("", result.original)
  }

  @Test
  fun `blank projectRoot yields trivial resolution`() {
    val result = resolveProjectPath("src/demo.js", "")
    assertFalse(result.shifted)
  }

  @Test
  fun `whitespace-only projectRoot is treated as blank`() {
    val result = resolveProjectPath("src/demo.js", "   ")
    assertFalse(result.shifted)
  }

  @Test
  fun `trailing slash in input is tolerated`() {
    val result = resolveProjectPath(
      "${tempRoot.name}/",
      rootPath()
    )
    assertFalse(result.shifted)
  }

  @Test
  fun `nested shift candidate resolves to existing file`() {
    // Make sure the strip logic still works when there are
    // multiple `/`-segments after the basename prefix.
    val result = resolveProjectPath(
      "${tempRoot.name}/deep/nested/file.txt",
      rootPath()
    )
    assertTrue(result.shifted)
    assertEquals("deep/nested/file.txt", result.shiftedForm)
    assertEquals(
      Paths.get(rootPath(), "deep/nested/file.txt").toAbsolutePath().normalize(),
      result.resolved
    )
  }

  @Test
  fun `back-slash separator is recognised on input`() {
    // On Windows the LLM may emit back-slashes; the strip
    // must recognise the prefix either way.
    val result = resolveProjectPath(
      "${tempRoot.name}\\src\\demo.js",
      rootPath()
    )
    assertTrue(result.shifted)
    assertEquals("src/demo.js", result.shiftedForm)
  }
}
