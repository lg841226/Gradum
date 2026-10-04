package gradum.acp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Type-safe builder DSL for the ACP v1 initialize response objects.
 *
 * Produces JsonObject directly (no intermediate data class and conversion
 * step), and eliminates every `put("key", JsonPrimitive(value))` call site
 * so the initialize handler reads as a plain declarative block.
 *
 * Field names mirror the ACP v1 schema: `loadSession`, `promptCapabilities`,
 * `mcpCapabilities` (`http`/`sse`). There is no agent-side permission
 * capability — `session/request_permission` is a core method.
 *
 * Usage:
 *
 *     val result = acpInitialize {
 *       protocolVersion = 1
 *       agentInfo {
 *         name = "Gradum"
 *         version = "1.0.2"
 *       }
 *       authMethods = emptyList()
 *       agentCapabilities {
 *         loadSession = false
 *         promptCapabilities { image = true }
 *         mcpCapabilities { /* stdio only: http/sse stay false */ }
 *       }
 *     }
 */
internal fun acpInitialize(block: AcpInitializeScope.() -> Unit): JsonObject {
  val scope = AcpInitializeScope().apply(block)
  return scope.toJsonObject()
}

internal class AcpInitializeScope {
  var protocolVersion: Int = 1
  private var agentInfoBuilder: AcpAgentInfoScope? = null
  var authMethods: List<String> = emptyList()
  private var agentCapabilitiesBuilder: AcpAgentCapabilitiesScope? = null

  fun agentInfo(block: AcpAgentInfoScope.() -> Unit) {
    agentInfoBuilder = AcpAgentInfoScope().apply(block)
  }

  fun agentCapabilities(block: AcpAgentCapabilitiesScope.() -> Unit) {
    agentCapabilitiesBuilder = AcpAgentCapabilitiesScope().apply(block)
  }

  internal fun toJsonObject(): JsonObject =
    buildJsonObject {
      put("protocolVersion", JsonPrimitive(protocolVersion))
      agentInfoBuilder?.let { put("agentInfo", it.toJsonObject()) }
      put("authMethods", buildJsonArray { authMethods.forEach { add(JsonPrimitive(it)) } })
      agentCapabilitiesBuilder?.let { put("agentCapabilities", it.toJsonObject()) }
    }
}

internal class AcpAgentInfoScope {
  var name: String = ""
  var version: String = ""

  internal fun toJsonObject(): JsonObject =
    buildJsonObject {
      put("name", JsonPrimitive(name))
      put("version", JsonPrimitive(version))
    }
}

internal class AcpAgentCapabilitiesScope {
  var loadSession: Boolean = false
  private var promptBuilder: AcpPromptScope? = null
  private var mcpCapabilitiesBuilder: AcpMcpCapabilitiesScope? = null

  fun promptCapabilities(block: AcpPromptScope.() -> Unit) {
    promptBuilder = AcpPromptScope().apply(block)
  }

  fun mcpCapabilities(block: AcpMcpCapabilitiesScope.() -> Unit) {
    mcpCapabilitiesBuilder = AcpMcpCapabilitiesScope().apply(block)
  }

  internal fun toJsonObject(): JsonObject =
    buildJsonObject {
      put("loadSession", JsonPrimitive(loadSession))
      promptBuilder?.let { put("promptCapabilities", it.toJsonObject()) }
      mcpCapabilitiesBuilder?.let { put("mcpCapabilities", it.toJsonObject()) }
    }
}

internal class AcpPromptScope {
  var image: Boolean = false
  var audio: Boolean = false
  var embeddedContext: Boolean = false

  internal fun toJsonObject(): JsonObject =
    buildJsonObject {
      put("image", JsonPrimitive(image))
      put("audio", JsonPrimitive(audio))
      put("embeddedContext", JsonPrimitive(embeddedContext))
    }
}

/**
 * The agent's MCP transport support. Gradum only connects client-provided
 * MCP servers over stdio, so http/sse are false and not advertised.
 */
internal class AcpMcpCapabilitiesScope {
  var http: Boolean = false
  var sse: Boolean = false

  internal fun toJsonObject(): JsonObject =
    buildJsonObject {
      put("http", JsonPrimitive(http))
      put("sse", JsonPrimitive(sse))
    }
}
