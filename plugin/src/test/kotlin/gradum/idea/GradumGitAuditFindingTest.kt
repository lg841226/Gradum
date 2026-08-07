/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumGitAuditFindingTest.kt  2026-08-01 Changed by gwy
 */

package gradum.idea

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [AuditFinding.formatMessage].
 *
 * The script only reports an SXXXX code plus the raw params; the plugin is
 * responsible for rendering the localized message. These tests pin the
 * parameter order and numeric precision (matching the Python format spec
 * that used to produce the text) so a template/param-order regression in the
 * bundle or the [AuditFinding] mapping shows up as a failing case.
 */
class GradumGitAuditFindingTest {

  private fun finding(
    code: String,
    level: String = "alert",
    params: Map<String, Any?>,
    hash: String = "-",
  ): AuditFinding =
    AuditFinding(
      code = code,
      level = level,
      type = "T",
      params = params,
      hash = hash,
      index = -1,
      date = "",
      days = "-",
    )

  @Test
  fun `formatMessage renders critical finding in MSVC diagnostic style`() {
    val f = finding("S1001", level = "critical", hash = "a1b2c3d4", params = mapOf("add" to 500L, "dels" to 200L, "threshold" to 500L))
    assertEquals(
      "critical S1001: Commit a1b2c3d4 changes +500/-200 lines, exceeding the threshold of 500 lines.",
      f.formatMessage()
    )
  }

  @Test
  fun `formatMessage orders params per bundle placeholders not map order`() {
    // Map order is jumbled on purpose: formatMessage must pick by name.
    val f = finding("S2005", params = mapOf("threshold" to 500L, "avg" to 495.11538461538464))
    assertEquals("alert S2005: The project averages 495 lines per commit, higher than typical LLM generation patterns.", f.formatMessage())
  }

  @Test
  fun `formatMessage rounds float params to the template precision`() {
    val f = finding("S2007", params = mapOf("cv" to 0.123456789, "threshold" to 0.5))
    assertEquals("alert S2007: Commit sizes are too uniform at 0.123, not matching typical developer habits.", f.formatMessage())
  }

  @Test
  fun `formatMessage rounds ratio to one decimal`() {
    val f = finding("S4001", params = mapOf("ratio" to 7.844))
    assertEquals("alert S4001: Deletions only 7.8%, codebase expanding, redundant code may remain.", f.formatMessage())
  }

  @Test
  fun `formatMessage handles string params verbatim`() {
    val f = finding("S3003", hash = "e5f6a7b8", params = mapOf("author" to "gwy[bot]", "pattern" to "bot"))
    assertEquals("alert S3003: Author \"gwy[bot]\" of commit e5f6a7b8 appears to be an automated account matching pattern \"bot\".", f.formatMessage())
  }

  @Test
  fun `formatMessage renders agent development tools for S2013`() {
    val f = finding("S2013", params = mapOf("count" to 2L, "agents" to "Claude Code, OpenCode"))
    assertEquals("alert S2013: 2 AI agent development tool(s) detected in use: Claude Code, OpenCode.", f.formatMessage())
  }

  @Test
  fun `formatMessage handles multiline position range`() {
    val f = finding("S2001", params = mapOf("count" to 3L, "lines" to 1987L, "start_idx" to 153L, "end_idx" to 53L))
    assertEquals(
      "alert S2001: 3 consecutive commits removed 1987 lines, concentrated between 153 and 53.",
      f.formatMessage()
    )
  }

  @Test
  fun `formatMessage weaves hash into S2003`() {
    val f = finding("S2003", hash = "cafe1234", params = mapOf("lines" to 42L))
    assertEquals(
      "alert S2003: Commit cafe1234 removed 42 lines from core source files, potentially deleting important logic.",
      f.formatMessage()
    )
  }

  @Test
  fun `formatMessage weaves hash into S2006`() {
    val f = finding("S2006", hash = "0badc0de", params = mapOf("n" to 5L, "ratio" to 4.0))
    assertEquals(
      "alert S2006: Commit 0badc0de heads the first 5 commits with an add/delete ratio of 4.0x, showing signs of LLM-generated code.",
      f.formatMessage()
    )
  }

  @Test
  fun `formatMessage weaves hash into S3001`() {
    val f = finding("S3001", hash = "deadbeef", params = mapOf("lines" to 120L, "core" to 3L, "files" to 5L))
    assertEquals(
      "alert S3001: Commit deadbeef deleted 120 lines with 3 out of 5 changed files being source code.",
      f.formatMessage()
    )
  }

  @Test
  fun `formatMessage weaves hash into S2012`() {
    val f = finding("S2012", hash = "faceb00c", params = mapOf("days" to 30L, "half" to 45L))
    assertEquals(
      "alert S2012: Latest commit faceb00c is 30 days old, half-life 45 days, project may be abandoned.",
      f.formatMessage()
    )
  }

  @Test
  fun `hasRealCommitHash is true for hash-weaved codes with a real hash`() {
    assertTrue(finding("S1001", hash = "a1b2c3d4", params = emptyMap()).hasRealCommitHash())
    assertTrue(finding("S3003", hash = "e5f6a7b8", params = emptyMap()).hasRealCommitHash())
  }

  @Test
  fun `hasRealCommitHash is false for placeholder or non-weaved hashes`() {
    assertFalse(finding("S1001", hash = "-", params = emptyMap()).hasRealCommitHash())
    assertFalse(finding("S1001", hash = "", params = emptyMap()).hasRealCommitHash())
    assertFalse(finding("S2001", hash = "Cluster #3", params = emptyMap()).hasRealCommitHash())
    assertFalse(finding("S2002", hash = "Recent Spike", params = emptyMap()).hasRealCommitHash())
    assertFalse(finding("S2005", hash = "a1b2c3d4", params = emptyMap()).hasRealCommitHash())
  }

  @Test
  fun `subject and author default to empty when absent`() {
    val f = finding("S1001", hash = "a1b2c3d4", params = emptyMap())
    assertEquals("", f.subject)
    assertEquals("", f.author)
    assertEquals("", f.body)
  }
}
