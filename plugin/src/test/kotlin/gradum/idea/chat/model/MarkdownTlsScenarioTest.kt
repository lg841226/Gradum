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
  fun `narration before and after a tls block becomes tt segments`() {
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
  fun `multiple tls blocks keep document order`() {
    val xml: String = MarkdownTlsScenario.compile(
      """
      <tls><t nam="glob" pth="src" ptr="**/*.kt"/></tls>
      between
      <tls><t nam="grep" pth="src" ptr="TODO"/></tls>
      """.trimIndent()
    )!!

    val globIndex = xml.indexOf("glob")
    val betweenIndex = xml.indexOf("between")
    val grepIndex = xml.indexOf("grep")
    assertTrue("glob tool should precede narration", globIndex in 0..<betweenIndex)
    assertTrue("narration should precede grep tool", betweenIndex in 0..<grepIndex)
  }

  @Test
  fun `empty narration segments are skipped`() {
    val xml: String = MarkdownTlsScenario.compile(
      "<tls><t nam=\"glob\" pth=\"src\" ptr=\"**\"/></tls>"
    )!!
    assertEquals("<tls>\n<t nam=\"glob\" pth=\"src\" ptr=\"**\"/>\n</tls>", xml)
  }

  @Test
  fun `literal cdata terminator inside narration is escaped`() {
    val xml: String = MarkdownTlsScenario.compile(
      "See a]]>b\n\n<tls><t nam=\"glob\"/></tls>"
    )!!
    assertTrue(xml.contains("<tt><![CDATA[See a]]]]><![CDATA[>b]]></tt>"))
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
    assertFalse(xml.contains("\n  <tt>"))
  }
}