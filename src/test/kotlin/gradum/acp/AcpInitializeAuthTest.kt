package gradum.acp

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
}
