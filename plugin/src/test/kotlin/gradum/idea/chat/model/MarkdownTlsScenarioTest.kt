/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * MarkdownTlsScenarioTest.kt  2026-08-09 22:25:00 Changed by gwy
 */

package gradum.idea.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTlsScenarioTest {

  @Test
  fun `markdown without tls returns null`() {
    assertNull(MarkdownTlsScenario.compile("# Just a heading\n\nNo scenario here."))
  }

  @Test
  fun `narration is dropped and only tool blocks are carried over`() {
    val xml: String = MarkdownTlsScenario.compile(
      """
      Let me inspect the config.

      <tls>
        <t nam="read_file" pth="a.txt" lin="1-10"/>
      </tls>

      Found something; searching for more.
      """.trimIndent(),
      scenarioName = "intro"
    )!!

    assertTrue(xml.startsWith("<tls nam=\"intro\">"))
    assertTrue(xml.contains("<t nam=\"read_file\" pth=\"a.txt\" lin=\"1-10\"/>"))
    assertTrue(xml.endsWith("</tls>"))
    assertFalse(xml.contains("Let me inspect the config"))
    assertFalse(xml.contains("Found something"))
    assertFalse(xml.contains("<tt>"))
  }

  @Test
  fun `multiple tls blocks keep document order`() {
    val xml: String = MarkdownTlsScenario.compile(
      """
      <tls><t nam="glob" pth="src" ptr="**/*.kt"/></tls>
      between
      <tls><t nam="grep" pth="src" ptr="TODO"/></tls>
      """.trimIndent()
    )!!

    val globIndex = xml.indexOf("glob")
    val grepIndex = xml.indexOf("grep")
    assertTrue("glob tool should come first", globIndex in 0..<grepIndex)
    assertFalse("narration must not be embedded", xml.contains("between"))
  }

  @Test
  fun `a single tls block compiles intact`() {
    val xml: String = MarkdownTlsScenario.compile(
      "<tls><t nam=\"glob\" pth=\"src\" ptr=\"**\"/></tls>"
    )!!
    assertTrue(xml.contains("<t nam=\"glob\" pth=\"src\" ptr=\"**\"/>"))
    assertTrue(xml.startsWith("<tls"))
    assertTrue(xml.endsWith("</tls>"))
  }

  @Test
  fun `scenario name is xml escaped`() {
    val xml: String = MarkdownTlsScenario.compile(
      "<tls><t nam=\"glob\" pth=\"src\"/></tls>",
      scenarioName = "a \"weird\" & <name>"
    )!!
    assertTrue(xml.contains("nam=\"a &quot;weird&quot; &amp; &lt;name&gt;\""))
    assertTrue(xml.startsWith("<tls"))
    assertTrue(xml.endsWith("</tls>"))
  }
}