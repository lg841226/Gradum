/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ErrorsPanelParseTest.kt
 */

package gradum.idea.chat.ui.chat.skill

import gradum.idea.chat.ui.chat.skill.internal.capitalizeErrorMessage
import gradum.idea.chat.ui.chat.skill.internal.parseSyntaxErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for [parseSyntaxErrors] — the parser that
 * turns the raw `Map<String, Any?>` result map of an `edit_file` tool
 * call into a typed [gradum.idea.chat.ui.chat.skill.internal.SyntaxErrorEntry]
 * list used by [EditedRenderer]'s errors panel.
 *
 * Parsing is the riskiest part of the feature: it reads map keys
 * whose values may be present, null, or the wrong type, and the
 * panel must fall back gracefully so a malformed payload never
 * makes the whole chat row disappear.
 */
class ErrorsPanelParseTest {

  // ---- success path with syntax errors (the most common case) ----

  @Test
  fun `parses syntax errors with full field set`() {
    val raw: Map<String, Any?> = mapOf(
      "success" to true,
      "syntaxErrors" to listOf(
        mapOf(
          "message" to "Redundant SAM constructor",
          "severity" to "ERROR",
          "line" to 42,
          "column" to 13,
          "errorCode" to "RedundantSamConstructor"
        )
      )
    )

    val errors = parseSyntaxErrors(raw)
    assertEquals(1, errors.size)
    val entry = errors[0]
    assertEquals("Redundant SAM constructor", entry.message)
    assertEquals(42, entry.line)
    assertEquals(13, entry.column)
    assertEquals("RedundantSamConstructor", entry.errorCode)
  }

  @Test
  fun `parses multiple syntax errors in order`() {
    val raw: Map<String, Any?> = mapOf(
      "success" to true,
      "syntaxErrors" to listOf(
        mapOf("message" to "msg 1", "line" to 10),
        mapOf("message" to "msg 2", "line" to 20),
        mapOf("message" to "msg 3", "line" to 30)
      )
    )

    val errors = parseSyntaxErrors(raw)
    assertEquals(listOf(10, 20, 30), errors.map { it.line })
    assertEquals(listOf("msg 1", "msg 2", "msg 3"), errors.map { it.message })
  }

  // ---- null / missing line and column handling ----

  @Test
  fun `null line and column are preserved as nulls, not crashed on`() {
    val raw: Map<String, Any?> = mapOf(
      "success" to true,
      "syntaxErrors" to listOf(
        mapOf("message" to "Type mismatch", "line" to null, "column" to null)
      )
    )

    val errors = parseSyntaxErrors(raw)
    assertEquals(1, errors.size)
    assertNull("line should be null", errors[0].line)
    assertNull("column should be null", errors[0].column)
    assertEquals("Type mismatch", errors[0].message)
  }

  @Test
  fun `missing line and column keys default to null`() {
    val raw: Map<String, Any?> = mapOf(
      "success" to true,
      "syntaxErrors" to listOf(
        mapOf("message" to "vague issue")
      )
    )

    val errors = parseSyntaxErrors(raw)
    assertEquals(1, errors.size)
    assertNull(errors[0].line)
    assertNull(errors[0].column)
    assertNull(errors[0].errorCode)
  }

  // ---- blank messages are filtered out ----

  @Test
  fun `blank messages are dropped so the panel never renders empty rows`() {
    val raw: Map<String, Any?> = mapOf(
      "success" to true,
      "syntaxErrors" to listOf(
        mapOf("message" to "", "line" to 1),
        mapOf("message" to "   ", "line" to 2),
        mapOf("message" to "real issue", "line" to 3)
      )
    )

    val errors = parseSyntaxErrors(raw)
    assertEquals(1, errors.size)
    assertEquals("real issue", errors[0].message)
  }

  @Test
  fun `missing message key drops the entire entry`() {
    val raw: Map<String, Any?> = mapOf(
      "success" to true,
      "syntaxErrors" to listOf(
        mapOf("line" to 5),  // no message key
        mapOf("message" to "kept", "line" to 10)
      )
    )

    val errors = parseSyntaxErrors(raw)
    assertEquals(1, errors.size)
    assertEquals("kept", errors[0].message)
  }

  // ---- Number coercion ----

  @Test
  fun `line as Long is accepted and coerced to Int`() {
    val raw: Map<String, Any?> = mapOf(
      "success" to true,
      "syntaxErrors" to listOf(
        mapOf("message" to "x", "line" to 42L)
      )
    )

    val errors = parseSyntaxErrors(raw)
    assertNotNull(errors[0].line)
    assertEquals(42, errors[0].line)
  }

  @Test
  fun `line as Double is accepted and coerced to Int`() {
    val raw: Map<String, Any?> = mapOf(
      "success" to true,
      "syntaxErrors" to listOf(
        mapOf("message" to "x", "line" to 42.0)
      )
    )

    val errors = parseSyntaxErrors(raw)
    assertNotNull(errors[0].line)
    assertEquals(42, errors[0].line)
  }

  // ---- failure path: error.message + error.code ----

  @Test
  fun `failed tool call yields a synthetic error entry from error_message`() {
    val raw: Map<String, Any?> = mapOf(
      "success" to false,
      "error" to mapOf(
        "code" to "CODE_NOT_FOUND",
        "message" to "The target string was not found in the file"
      )
    )

    val errors = parseSyntaxErrors(raw)
    assertEquals(1, errors.size)
    assertEquals("The target string was not found in the file", errors[0].message)
    assertEquals("CODE_NOT_FOUND", errors[0].errorCode)
    assertNull("tool-level error has no associated line", errors[0].line)
  }

  @Test
  fun `failed tool call with blank error message is dropped`() {
    val raw: Map<String, Any?> = mapOf(
      "success" to false,
      "error" to mapOf("code" to "X", "message" to "   ")
    )

    val errors = parseSyntaxErrors(raw)
    assertTrue("expected empty list when error message is blank", errors.isEmpty())
  }

  @Test
  fun `failed tool call with no error map yields no entry`() {
    val raw: Map<String, Any?> = mapOf("success" to false)
    val errors = parseSyntaxErrors(raw)
    assertTrue("expected empty list when no error payload", errors.isEmpty())
  }

  // ---- merged cases ----

  @Test
  fun `failed tool call with both syntaxErrors and error yields both entries`() {
    val raw: Map<String, Any?> = mapOf(
      "success" to false,
      "syntaxErrors" to listOf(
        mapOf("message" to "compile error", "line" to 7)
      ),
      "error" to mapOf("code" to "MULTIPLE_MATCHES", "message" to "pattern matches 2 lines")
    )

    val errors = parseSyntaxErrors(raw)
    assertEquals(2, errors.size)
    // syntaxErrors are appended first, then the synthetic error entry
    assertEquals("compile error", errors[0].message)
    assertEquals(7, errors[0].line)
    assertEquals("pattern matches 2 lines", errors[1].message)
    assertEquals("MULTIPLE_MATCHES", errors[1].errorCode)
  }

  @Test
  fun `successful tool call without syntaxErrors yields empty list`() {
    val raw: Map<String, Any?> = mapOf("success" to true)
    val errors = parseSyntaxErrors(raw)
    assertTrue(errors.isEmpty())
  }

  // ---- malformed payload does not throw ----

  @Test
  fun `syntaxErrors not a list yields empty list, not crash`() {
    val raw: Map<String, Any?> = mapOf(
      "success" to true,
      "syntaxErrors" to "garbage"
    )
    val errors = parseSyntaxErrors(raw)
    assertTrue(errors.isEmpty())
  }

  @Test
  fun `syntaxErrors entries that are not maps are silently skipped`() {
    val raw: Map<String, Any?> = mapOf(
      "success" to true,
      "syntaxErrors" to listOf(
        "string entry",
        42,
        mapOf("message" to "real", "line" to 3)
      )
    )
    val errors = parseSyntaxErrors(raw)
    assertEquals(1, errors.size)
    assertEquals("real", errors[0].message)
  }

  // ---- capitalizeErrorMessage coverage ----

  @Test
  fun `capitalizeErrorMessage lowercases the first char of lowercase input`() {
    assertEquals(
      "Redundant SAM constructor",
      capitalizeErrorMessage("redundant SAM constructor")
    )
  }

  @Test
  fun `capitalizeErrorMessage leaves already-capitalized input unchanged`() {
    assertEquals(
      "Unresolved reference: foo",
      capitalizeErrorMessage("Unresolved reference: foo")
    )
  }

  @Test
  fun `capitalizeErrorMessage returns empty for empty input`() {
    assertEquals("", capitalizeErrorMessage(""))
  }

  @Test
  fun `capitalizeErrorMessage capitalizes first char after leading whitespace`() {
    assertEquals(
      "  Bad indent",
      capitalizeErrorMessage("  bad indent")
    )
  }

  @Test
  fun `capitalizeErrorMessage works on the typical unresolved reference message`() {
    assertEquals(
      "Unresolved reference: com.example.Foo",
      capitalizeErrorMessage("unresolved reference: com.example.Foo")
    )
  }
}
