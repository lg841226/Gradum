/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * MarkdownTlsScenarioTest.kt  2026-08-25 14:40:47 Changed by gwy
 */

package gradum.idea.chat.model

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTlsScenarioTest {

  @Test
  fun `markdown without tls returns null`() {
    assertNull(MarkdownTlsScenario.compile("# Just a heading\n\nNo scenario here."))
  }

  @Test
  fun `narration becomes tt segments interleaved with tool blocks`() {
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
    assertTrue(xml.contains("<tt><![CDATA[Let me inspect the config.]]></tt>"))
    assertTrue(xml.contains("<t nam=\"read_file\" pth=\"a.txt\" lin=\"1-10\"/>"))
    assertTrue(xml.contains("<tt><![CDATA[Found something; searching for more.]]></tt>"))
    assertTrue(xml.endsWith("</tls>"))
  }

  @Test
  fun `multiple tls blocks keep document order with narration between`() {
    val xml: String = MarkdownTlsScenario.compile(
      markdown = """
      <tls><t nam="glob" pth="src" ptr="**/*.kt"/></tls>
      between
      <tls><t nam="grep" pth="src" ptr="TODO"/></tls>
      """.trimIndent()
    )!!

    val globIndex = xml.indexOf(string = "glob")
    val grepIndex = xml.indexOf(string = "grep")
    assertTrue("glob tool should come first", globIndex in 0..<grepIndex)
    assertTrue("narration must be embedded between tool blocks", xml.contains("between"))
  }

  @Test
  fun `a single tls block compiles intact`() {
    val xml: String = MarkdownTlsScenario.compile(
      markdown = "<tls><t nam=\"glob\" pth=\"src\" ptr=\"**\"/></tls>"
    )!!
    assertTrue(xml.contains("<t nam=\"glob\" pth=\"src\" ptr=\"**\"/>"))
    assertTrue(xml.startsWith("<tls"))
    assertTrue(xml.endsWith("</tls>"))
  }

  @Test
  fun `scenario name is xml escaped`() {
    val xml: String = MarkdownTlsScenario.compile(
      markdown = "<tls><t nam=\"glob\" pth=\"src\"/></tls>",
      scenarioName = "a \"weird\" & <name>"
    )!!
    assertTrue(xml.contains(other = "nam=\"a &quot;weird&quot; &amp; &lt;name&gt;\""))
    assertTrue(xml.startsWith(prefix = "<tls"))
    assertTrue(xml.endsWith(suffix = "</tls>"))
  }

  @Test
  fun `narration before and after the last tool block is both kept`() {
    val xml: String = MarkdownTlsScenario.compile(
      markdown = """
      Leading thoughts before any tool.

      <tls>
        <t nam="glob" pth="src" ptr="*"/>
      </tls>

      Trailing summary after the last tool.
      """.trimIndent()
    )!!

    assertTrue(xml.contains(other = "<tt><![CDATA[Leading thoughts before any tool.]]></tt>"))
    assertTrue(xml.contains(other = "<t nam=\"glob\" pth=\"src\" ptr=\"*\"/>"))
    assertTrue(xml.contains(other = "<tt><![CDATA[Trailing summary after the last tool.]]></tt>"))
    assertTrue(xml.endsWith(suffix = "</tls>"))
  }

  @Test
  fun `cdata escaping keeps well-formed xml when narration contains cd terminator`() {
    val xml: String = MarkdownTlsScenario.compile(
      markdown = """
      Code ends with ]]> here.

      <tls>
        <t nam="glob" pth="src" ptr="*"/>
      </tls>
      """.trimIndent()
    )!!

    // `]]>` cannot appear inside CDATA; the compiler must split and re-open it.
    assertTrue("CDATA terminator should be escaped", xml.contains("]]]]><![CDATA[>"))
  }

  @Test
  fun `adjacent tool blocks without narration compile back to back`() {
    val xml: String = MarkdownTlsScenario.compile(
      markdown = """
      <tls><t nam="glob" pth="src" ptr="*.kt"/></tls>
      <tls><t nam="grep" pth="src" ptr="TODO"/></tls>
      """.trimIndent()
    )!!

    val globIndex = xml.indexOf(string = "glob")
    val grepIndex = xml.indexOf(string = "grep")
    assertTrue(
      "glob should come first",
      globIndex in 0..<grepIndex
    )
    assertTrue(xml.startsWith(prefix = "<tls"))
    assertTrue(xml.endsWith(suffix = "</tls>"))
  }
}
