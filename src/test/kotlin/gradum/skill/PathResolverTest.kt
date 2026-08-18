/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PathResolverTest.kt  2026-08-18 12:45:23 Changed by gwy
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
    assertNull(result.rejectionReason, "in-project relative path must not be rejected")
    assertEquals(
      Paths.get(rootPath(), "src/demo.js").toAbsolutePath().normalize(),
      result.resolved
    )
  }

  @Test
  fun `redundant project basename prefix is stripped to existing file`() {
    val result = resolveProjectPath(
      "${tempRoot.name}/src/demo.js",
      rootPath()
    )
    assertTrue(result.shifted, "expected shift for first segment = projectRoot basename")
    assertEquals("src/demo.js", result.shiftedForm)
    assertNull(result.rejectionReason)
    assertEquals(
      Paths.get(rootPath(), "src/demo.js").toAbsolutePath().normalize(),
      result.resolved
    )
  }

  @Test
  fun `shift falls back to direct path when corrected form does not exist`() {
    val result = resolveProjectPath(
      "${tempRoot.name}/missing.js",
      rootPath()
    )
    assertFalse(result.shifted)
    assertNull(result.rejectionReason, "missing file is not a security rejection")
    assertEquals(
      Paths.get(rootPath(), "${tempRoot.name}/missing.js").toAbsolutePath().normalize(),
      result.resolved
    )
  }

  @Test
  fun `first segment that does not match project basename is not shifted`() {
    val result = resolveProjectPath("src/missing-but-other-js-exists.js", rootPath())
    assertFalse(result.shifted)
    assertEquals("src/missing-but-other-js-exists.js", result.original)
    assertNull(result.rejectionReason)
  }

  @Test
  fun `path equal to projectRoot basename only is not shifted`() {
    val result = resolveProjectPath(tempRoot.name, rootPath())
    assertFalse(result.shifted)
    assertEquals(tempRoot.name, result.original)
    assertNull(result.rejectionReason)
  }

  @Test
  fun `absolute path inside project root is accepted`() {
    val absolute = Paths.get(rootPath(), "src/demo.js").toAbsolutePath().normalize()
    val result = resolveProjectPath(absolute.toString(), rootPath())
    assertFalse(result.shifted)
    assertNull(result.rejectionReason, "absolute path inside project root must be accepted")
    assertEquals(absolute, result.resolved)
  }

  @Test
  fun `absolute path outside project root is rejected with PERMISSION_DENIED reason`() {
    val result = resolveProjectPath("/etc/passwd", rootPath())
    assertNotNull(result.rejectionReason, "absolute system path must be rejected")
    assertTrue(
      result.rejectionReason.contains("outside the project root"),
      "rejection reason should explain the boundary violation: ${result.rejectionReason}"
    )
  }

  @Test
  fun `absolute path in home dot ssh is rejected`() {
    val result = resolveProjectPath(
      "${System.getProperty("user.home")}/.ssh/id_rsa",
      rootPath()
    )
    assertNotNull(result.rejectionReason)
  }

  @Test
  fun `prefix collision is rejected - proj-evil is not inside proj`() {
    val trapRoot: File = Files.createTempDirectory("gradum").toFile()
    try {
      val evilRoot: File = Files.createTempDirectory(trapRoot.name + "-evil").toFile()
      val result = resolveProjectPath(
        evilRoot.absolutePath,
        trapRoot.absolutePath
      )
      assertNotNull(
        result.rejectionReason,
        "sibling directory sharing a prefix must be rejected, " +
          "not silently accepted as inside the project"
      )
    } finally {
      trapRoot.deleteRecursively()
    }
  }

  @Test
  fun `parent traversal is rejected`() {
    val result = resolveProjectPath("../escaped.txt", rootPath())
    assertNotNull(result.rejectionReason, "../ traversal must not escape the project root")
  }

  @Test
  fun `safe prefix tmp is allowed even outside project root`() {
    val result = resolveProjectPath("/tmp/scratch.json", rootPath())
    assertNull(
      result.rejectionReason,
      "/tmp scratch paths should be allowed regardless of project root"
    )
  }

  @Test
  fun `blank filePath is rejected when project root is required`() {
    val result = resolveProjectPath("", rootPath())
    assertNotNull(result.rejectionReason, "blank filePath must be rejected")
  }

  @Test
  fun `blank projectRoot rejects every path`() {
    val result = resolveProjectPath("src/demo.js", "")
    assertNotNull(result.rejectionReason)
  }

  @Test
  fun `requireWithinProject false opts out of the boundary check`() {
    val result = resolveProjectPath("/etc/passwd", rootPath(), requireWithinProject = false)
    assertNull(result.rejectionReason)
  }

  @Test
  fun `whitespace-only projectRoot is rejected`() {
    val result = resolveProjectPath("src/demo.js", "   ")
    assertNotNull(result.rejectionReason, "whitespace-only projectRoot must be treated as unset")
  }

  @Test
  fun `trailing slash in input is tolerated`() {
    val result = resolveProjectPath(
      "${tempRoot.name}/",
      rootPath()
    )
    assertFalse(result.shifted)
    assertNull(result.rejectionReason)
  }

  @Test
  fun `nested shift candidate resolves to existing file`() {
    val result = resolveProjectPath(
      "${tempRoot.name}/deep/nested/file.txt",
      rootPath()
    )
    assertTrue(result.shifted)
    assertEquals("deep/nested/file.txt", result.shiftedForm)
    assertNull(result.rejectionReason)
    assertEquals(
      Paths.get(rootPath(), "deep/nested/file.txt").toAbsolutePath().normalize(),
      result.resolved
    )
  }

  @Test
  fun `back-slash separator is recognised on input`() {
    val result = resolveProjectPath(
      "${tempRoot.name}\\src\\demo.js",
      rootPath()
    )
    assertTrue(result.shifted)
    assertEquals("src/demo.js", result.shiftedForm)
    assertNull(result.rejectionReason)
  }

  @Test
  fun `symlink escaping the project root is rejected`() {
    // Create a real file OUTSIDE the project, then a symlink INSIDE the
    // project pointing at it. The string-level boundary check passes
    // (`proj/evil` starts with rootPath), but the on-disk target escapes.
    val outsideRoot: File = Files.createTempDirectory("gradum-path-outside").toFile()
    try {
      val secretFile = File(outsideRoot, "secret.txt")
      secretFile.writeText("sensitive")
      val link = File(tempRoot, "evil-link")
      Files.createSymbolicLink(link.toPath(), secretFile.toPath())

      val result = resolveProjectPath("evil-link", rootPath())
      assertNotNull(
        result.rejectionReason,
        "project-internal symlink to an external file must be rejected"
      )
    } finally {
      outsideRoot.deleteRecursively()
    }
  }
}
