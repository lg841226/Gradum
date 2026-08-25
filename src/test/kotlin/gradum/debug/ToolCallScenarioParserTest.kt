/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolCallScenarioParserTest.kt  2026-08-25 14:41:48 Changed by gwy
 */

package gradum.debug

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class ToolCallScenarioParserTest {

  @Test
  fun `parses a scenario with path detailed attributes`() {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      """
      <tls nam="demo">
        <t nam="read_file" pth="src/main/kotlin/gradum/AgentConfiguration.kt"/>
        <t nam="grep" pth="src/main" ptr="TODO"/>
      </tls>
      """.trimIndent()
    )

    assertEquals(
      "demo",
      scenario.scenarioName
    )
    assertEquals(
      2,
      scenario.toolCalls.size
    )

    assertEquals(
      "read_file",
      scenario.toolCalls[0].functionName
    )
    assertEquals(
      "src/main/kotlin/gradum/AgentConfiguration.kt",
      arg(scenario, 0, "path")
    )
    assertEquals(
      true,
      scenario.toolCalls[0].expectSuccess
    )

    assertEquals(
      "grep",
      scenario.toolCalls[1].functionName
    )
    assertEquals(
      "src/main",
      arg(scenario, index = 1, key = "path")
    )
    assertEquals(
      "TODO",
      arg(scenario, index = 1, key = "pattern")
    )
  }

  @Test
  fun `tool calls are kept in document order`() {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      """
      <tls>
        <t nam="read_file" pth="a.txt"/>
        <t nam="grep" pth="src/main" ptr="TODO"/>
        <t nam="glob" pth="src" ptr="**/*.kt"/>
      </tls>
      """.trimIndent()
    )

    assertEquals(
      3,
      scenario.steps.size
    )
    assertEquals(
      3,
      scenario.toolCalls.size
    )

    assertIs<ParsedToolCall>(value = scenario.steps[0])
    assertEquals(
      "read_file",
      scenario.toolCalls[0].functionName
    )
    assertIs<ParsedToolCall>(value = scenario.steps[1])
    assertEquals(
      "grep",
      scenario.toolCalls[1].functionName
    )
    assertIs<ParsedToolCall>(value = scenario.steps[2])
    assertEquals(
      "glob",
      scenario.toolCalls[2].functionName
    )
  }

  @Test
  fun `parses ai reply tt segments interleaved with tool calls`() {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      rawXml = """
      <tls>
        <tt msg="Let me inspect the config first."/>
        <t nam="read_file" pth="a.txt"/>
        <tt msg="Found it; searching for more."/>
        <t nam="grep" pth="src/main" ptr="TODO"/>
      </tls>
      """.trimIndent()
    )

    assertEquals(
      4,
      scenario.steps.size
    )
    assertEquals(
      2,
      scenario.toolCalls.size
    )

    val narration = scenario.steps[0] as ScenarioStep.AiReply
    val secondReply = scenario.steps[2] as ScenarioStep.AiReply
    assertEquals(
      "Let me inspect the config first.",
      narration.content
    )
    assertEquals(
      "Found it; searching for more.",
      secondReply.content
    )
    assertIs<ParsedToolCall>(value = scenario.steps[1])
    assertIs<ParsedToolCall>(value = scenario.steps[3])
  }

  @Test
  fun `tt falls back to element text when msg attribute is absent`() {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      rawXml = """
      <tls>
        <tt>Hello from a text node.</tt>
        <t nam="glob" pth="src" ptr="**/*.kt"/>
      </tls>
      """.trimIndent()
    )
    val narration = scenario.steps[0] as ScenarioStep.AiReply
    assertEquals(
      "Hello from a text node.",
      narration.content
    )
  }

  @Test
  fun `unknown attributes pass through verbatim`() {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      rawXml = """
      <tls>
        <t nam="read_file" pth="a.txt" caseSensitive="true" limit="5"/>
      </tls>
      """.trimIndent()
    )

    assertEquals(
      "true",
      arg(scenario, index = 0, key = "caseSensitive")
    )
    assertEquals(
      "5",
      arg(scenario, index = 0, key = "limit")
    )
  }

  @Test
  fun `defaults to agent and blank scenario name`() {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      rawXml = """
      <tls>
        <t nam="read_file" pth="a.txt"/>
      </tls>
      """.trimIndent()
    )

    assertEquals(
      "",
      scenario.scenarioName
    )
    assertEquals(
      1,
      scenario.toolCalls.size
    )
  }

  @Test
  fun `exp attribute maps to expected success flag`() {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      rawXml = """
      <tls>
        <t nam="read_file" pth="a.txt" exp="error"/>
        <t nam="save_file" pth="b.txt" ctl="hello" exp="success"/>
      </tls>
      """.trimIndent()
    )

    assertEquals(
      false,
      scenario.toolCalls[0].expectSuccess
    )
    assertEquals(
      true,
      scenario.toolCalls[1].expectSuccess
    )
  }

  @Test
  fun `invalid expect value throws`() {
    assertFailsWith<ToolCallScenarioParseException> {
      ToolCallScenarioParser.parse(
        rawXml = """
        <tls>
          <t nam="read_file" pth="a.txt" exp="maybe"/>
        </tls>
        """.trimIndent()
      )
    }
  }

  @Test
  fun `invalid xml throws parse exception with root tag hint`() {
    val exception: ToolCallScenarioParseException = assertFailsWith {
      ToolCallScenarioParser.parse(
        rawXml = """
        <scenario>
          <t nam="read_file" pth="a.txt"/>
        </scenario>
        """.trimIndent()
      )
    }
    assertEquals(
      "Expected root element <tls> but found <scenario>",
      exception.message
    )
  }

  @Test
  fun `scenario with no tool entries is rejected`() {
    assertFailsWith<ToolCallScenarioParseException> {
      ToolCallScenarioParser.parse(rawXml = "<tls nam=\"empty\"/>")
    }
  }

  @Test
  fun `unknown child element inside tls throws`() {
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
  fun `t element without name attribute is rejected`() {
    assertFailsWith<ToolCallScenarioParseException> {
      ToolCallScenarioParser.parse(
        rawXml = """
        <tls>
          <t pth="a.txt"/>
        </tls>
        """.trimIndent()
      )
    }
  }

  @Test
  fun `raw garbage xml throws`() {
    assertFailsWith<ToolCallScenarioParseException> {
      ToolCallScenarioParser.parse(rawXml = "definitely not xml at all")
    }
  }

  @Test
  fun `numeric attribute stays an untyped json primitive string`() {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      rawXml = """
      <tls>
        <t nam="explore_project" dep="2"/>
      </tls>
      """.trimIndent()
    )
    assertEquals(
      "2",
      arg(scenario, index = 0, key = "depth")
    )
    assertEquals(
      JsonPrimitive(value = "2"),
      scenario.toolCalls[0].functionArguments["depth"] as? JsonPrimitive
    )
    assertNull(scenario.toolCalls[0].functionArguments["dep"])
  }

  @Test
  fun `json array and object arguments are probed for array parameters`() {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      rawXml = """
      <tls>
        <t nam="to_do" tasks='["read config", "scan skills"]'/>
        <t nam="edit_file" pth="a.txt" edits='[{"oldString":"x","newString":"y"}]'/>
      </tls>
      """.trimIndent()
    )

    fun argJson(index: Int, key: String): JsonElement =
      scenario.toolCalls[index].functionArguments[key] ?: error("missing $key")

    val tasks: JsonArray = argJson(index = 0, key = "tasks") as JsonArray
    assertEquals(
      listOf("read config", "scan skills"),
      tasks.map { (it as JsonPrimitive).content }
    )

    val edits: JsonElement = argJson(index = 1, key = "edits")
    assertIs<JsonArray>(value = edits)
  }

  @Test
  fun `braced non-json value is not mistaken for json`() {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      rawXml = """
      <tls>
        <t nam="run_cmd" cmd='{name} --dry-run'/>
      </tls>
      """.trimIndent()
    )

    assertEquals(
      "{name} --dry-run",
      arg(scenario, index = 0, key = "command")
    )
  }

  @Test
  fun `scenario name can come from the name attribute`() {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      rawXml = """
      <tls name="storyboard">
        <t nam="glob" pth="src" ptr="*"/>
      </tls>
      """.trimIndent()
    )
    assertEquals(
      "storyboard",
      scenario.scenarioName
    )
  }

  @Test
  fun `nam attribute takes precedence over name`() {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      rawXml = """
      <tls name="both" nam="prefers-short">
        <t nam="glob" pth="src" ptr="*"/>
      </tls>
      """.trimIndent()
    )
    // Documented precedence: name= is checked first, nam= is the fallback.
    assertEquals(
      "both",
      scenario.scenarioName
    )
  }

  @Test
  fun `blank msgs and text both produce empty-free reply`() {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      rawXml = """
      <tls>
        <tt msg="  trimmed attribute  "/>
        <t nam="glob" pth="src" ptr="*"/>
      </tls>
      """.trimIndent()
    )
    val narration = scenario.steps[0] as ScenarioStep.AiReply
    assertEquals(
      "trimmed attribute",
      narration.content
    )
  }

  @Test
  fun `tt with only whitespace throws`() {
    assertFailsWith<ToolCallScenarioParseException> {
      ToolCallScenarioParser.parse(
        rawXml = """
        <tls>
          <tt msg="   "/>
        </tls>
        """.trimIndent()
      )
    }
  }

  @Test
  fun `root and tool names are trimmed but argument values stay verbatim`() {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      rawXml = """
      <tls nam="  padded  ">
        <t nam="  read_file  " pth="  a.txt  "/>
      </tls>
      """.trimIndent()
    )

    assertEquals(
      "padded",
      scenario.scenarioName
    )
    assertEquals(
      "read_file",
      scenario.toolCalls[0].functionName
    )
    assertEquals(
      "  a.txt  ",
      arg(scenario, 0, "path")
    )
  }

  @Test
  fun `nested object argument is preserved as json object`() {
    val scenario: ToolCallScenario = ToolCallScenarioParser.parse(
      rawXml = """
      <tls>
        <t nam="edit_file" pth="a.txt" edits='[{"oldString":"x","newString":"y"}]'/>
      </tls>
      """.trimIndent()
    )
    val edits = scenario.toolCalls[0].functionArguments["edits"] as JsonArray
    val firstEdit = edits.first() as kotlinx.serialization.json.JsonObject
    assertEquals(
      "x",
      (firstEdit["oldString"] as JsonPrimitive).content
    )
    assertEquals(
      "y",
      (firstEdit["newString"] as JsonPrimitive).content
    )
  }

  private fun arg(scenario: ToolCallScenario, index: Int, key: String): String? {
    return (scenario.toolCalls[index].functionArguments[key] as? JsonPrimitive)?.content
  }
}
