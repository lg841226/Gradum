/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolCallScenarioParserTest.kt  2026-08-09 21:05:00 Changed by gwy
 */

package gradum.debug

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolCallScenarioParserTest {

  @Test
  fun `parses a scenario with path detailed attributes`(): Unit {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      """
      <tls nam="demo">
        <t nam="read_file" pth="src/main/kotlin/gradum/AgentConfiguration.kt"/>
        <t nam="grep" pth="src/main" ptr="TODO"/>
      </tls>
      """.trimIndent()
    )

    assertEquals("demo", scenario.scenarioName)
    assertEquals(2, scenario.toolCalls.size)

    assertEquals("read_file", scenario.toolCalls[0].functionName)
    assertEquals("src/main/kotlin/gradum/AgentConfiguration.kt", arg(scenario, 0, "path"))
    assertEquals(scenario.toolCalls[0].expectSuccess, true)

    assertEquals("grep", scenario.toolCalls[1].functionName)
    assertEquals("src/main", arg(scenario, 1, "path"))
    assertEquals("TODO", arg(scenario, 1, "pattern"))
  }

  @Test
  fun `tool calls are kept in document order`(): Unit {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      """
      <tls>
        <t nam="read_file" pth="a.txt"/>
        <t nam="grep" pth="src/main" ptr="TODO"/>
        <t nam="glob" pth="src" ptr="**/*.kt"/>
      </tls>
      """.trimIndent()
    )

    assertEquals(3, scenario.steps.size)
    assertEquals(3, scenario.toolCalls.size)

    assertIs<ParsedToolCall>(scenario.steps[0])
    assertEquals("read_file", (scenario.steps[0] as ParsedToolCall).functionName)
    assertIs<ParsedToolCall>(scenario.steps[1])
    assertEquals("grep", (scenario.steps[1] as ParsedToolCall).functionName)
    assertIs<ParsedToolCall>(scenario.steps[2])
    assertEquals("glob", (scenario.steps[2] as ParsedToolCall).functionName)
  }

  @Test
  fun `ai reply tt element is rejected`(): Unit {
    val exception: ToolCallScenarioParseException = assertFailsWith {
      ToolCallScenarioParser.parse(
        """
        <tls>
          <tt msg="AI narration is no longer authored here."/>
          <t nam="glob" pth="src" ptr="**/*.kt"/>
        </tls>
        """.trimIndent()
      )
    }
    assertTrue(exception.message.orEmpty().contains("<tt>"))
  }

  @Test
  fun `unknown attributes pass through verbatim`(): Unit {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      """
      <tls>
        <t nam="read_file" pth="a.txt" caseSensitive="true" limit="5"/>
      </tls>
      """.trimIndent()
    )

    assertEquals("true", arg(scenario, 0, "caseSensitive"))
    assertEquals("5", arg(scenario, 0, "limit"))
  }

  @Test
  fun `defaults to agent and blank scenario name`(): Unit {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      """
      <tls>
        <t nam="read_file" pth="a.txt"/>
      </tls>
      """.trimIndent()
    )

    assertEquals("", scenario.scenarioName)
    assertEquals(1, scenario.toolCalls.size)
  }

  @Test
  fun `exp attribute maps to expected success flag`(): Unit {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      """
      <tls>
        <t nam="read_file" pth="a.txt" exp="error"/>
        <t nam="save_file" pth="b.txt" ctl="hello" exp="success"/>
      </tls>
      """.trimIndent()
    )

    assertEquals(false, scenario.toolCalls[0].expectSuccess)
    assertEquals(true, scenario.toolCalls[1].expectSuccess)
  }

  @Test
  fun `invalid expect value throws`(): Unit {
    assertFailsWith<ToolCallScenarioParseException> {
      ToolCallScenarioParser.parse(
        """
        <tls>
          <t nam="read_file" pth="a.txt" exp="maybe"/>
        </tls>
        """.trimIndent()
      )
    }
  }

  @Test
  fun `invalid xml throws parse exception with root tag hint`(): Unit {
    val exception: ToolCallScenarioParseException = assertFailsWith {
      ToolCallScenarioParser.parse(
        """
        <scenario>
          <t nam="read_file" pth="a.txt"/>
        </scenario>
        """.trimIndent()
      )
    }
    assertEquals("Expected root element <tls> but found <scenario>", exception.message)
  }

  @Test
  fun `scenario with no tool entries is rejected`(): Unit {
    assertFailsWith<ToolCallScenarioParseException> {
      ToolCallScenarioParser.parse("<tls nam=\"empty\"/>")
    }
  }

  @Test
  fun `unknown child element inside tls throws`(): Unit {
    assertFailsWith<ToolCallScenarioParseException> {
      ToolCallScenarioParser.parse(
        """
        <tls>
          <x nam="read_file" pth="a.txt"/>
        </tls>
        """.trimIndent()
      )
    }
  }

  @Test
  fun `t element without name attribute is rejected`(): Unit {
    assertFailsWith<ToolCallScenarioParseException> {
      ToolCallScenarioParser.parse(
        """
        <tls>
          <t pth="a.txt"/>
        </tls>
        """.trimIndent()
      )
    }
  }

  @Test
  fun `raw garbage xml throws`(): Unit {
    assertFailsWith<ToolCallScenarioParseException> {
      ToolCallScenarioParser.parse("definitely not xml at all")
    }
  }

  @Test
  fun `numeric attribute stays an untyped json primitive string`(): Unit {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      """
      <tls>
        <t nam="explore_project" dep="2"/>
      </tls>
      """.trimIndent()
    )
    assertEquals("2", arg(scenario, 0, "depth"))
    assertEquals(
      JsonPrimitive("2"),
      (scenario.toolCalls[0].functionArguments["depth"] as? JsonPrimitive),
    )
    assertNull(scenario.toolCalls[0].functionArguments["dep"])
  }

  private fun arg(scenario: ToolCallScenario, index: Int, key: String): String? {
    @Suppress("UNCHECKED_CAST")
    val tool: ParsedToolCall = scenario.toolCalls[index]
    return (tool.functionArguments[key] as? JsonPrimitive)?.content
  }
}