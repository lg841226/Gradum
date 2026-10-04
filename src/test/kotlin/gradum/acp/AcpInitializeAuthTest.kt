package gradum.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AcpInitializeAuthTest {

  @Test
  fun `declares a terminal auth method the registry auth check accepts`() {
    val result = acpInitialize {
      protocolVersion = 1
      authMethod {
        id = "terminal"
        name = "Configure provider"
        description = "Run interactive setup in a terminal"
        type = "terminal"
        args = listOf("setup")
      }
    }

    val methods = result["authMethods"]!!.jsonArray
    assertEquals(1, methods.size)

    val method = methods[0].jsonObject
    assertEquals("terminal", method["type"]!!.jsonPrimitive.content)
    assertEquals("setup", method["args"]!!.jsonArray[0].jsonPrimitive.content)
  }

  @Test
  fun `omits declared methods when none are configured`() {
    val result = acpInitialize { protocolVersion = 1 }
    assertEquals(0, result["authMethods"]!!.jsonArray.size)
  }

  @Test
  fun `builds the terminal auth payload the setup entry needs`() {
    val method = buildTerminalAuthMethod()
    assertEquals("terminal", method["type"]!!.jsonPrimitive.content)
    assertEquals(TERMINAL_AUTH_METHOD_ID, method["id"]!!.jsonPrimitive.content)
    assertEquals("setup", method["args"]!!.jsonArray[0].jsonPrimitive.content)
  }

  @Test
  fun `gates terminal auth on the client capability`() {
    val specOptIn = Json.parseToJsonElement("""{"clientCapabilities":{"auth":{"terminal":true}}}""").jsonObject
    val specOptOut = Json.parseToJsonElement("""{"clientCapabilities":{"auth":{"terminal":false}}}""").jsonObject
    val legacyOptIn = Json.parseToJsonElement("""{"clientCapabilities":{"_meta":{"terminal-auth":true}}}""").jsonObject
    val registryValidator = Json.parseToJsonElement(
      """{"clientCapabilities":{"terminal":true,"fs":{"readTextFile":true},"_meta":{"terminal_output":true,"terminal-auth":true}}}"""
    ).jsonObject

    assertEquals(true, clientSupportsTerminalAuth(specOptIn))
    assertEquals(false, clientSupportsTerminalAuth(specOptOut))
    assertEquals(true, clientSupportsTerminalAuth(legacyOptIn))
    assertEquals(true, clientSupportsTerminalAuth(registryValidator))
    assertEquals(false, clientSupportsTerminalAuth(Json.parseToJsonElement("{}").jsonObject))
    assertEquals(false, clientSupportsTerminalAuth(null))
  }
}
