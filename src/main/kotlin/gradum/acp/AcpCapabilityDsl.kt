package gradum.acp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Type-safe builder DSL for ACP v2 draft initialize response objects.
 *
 * Produces JsonObject directly (no intermediate data class and conversion
 * step), and eliminates every `put("key", JsonPrimitive(value))` call site
 * so the initialize handler reads as a plain declarative block.
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
 *         prompt { image = true }
 *         tools { requestPermission = true }
 *         mcpCapabilities { /* none */ }
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
  var modes: List<String> = emptyList()
  private var toolsBuilder: AcpToolsScope? = null
  private var promptBuilder: AcpPromptScope? = null
  private var mcpCapabilitiesBuilder: AcpMcpCapabilitiesScope? = null

  fun prompt(block: AcpPromptScope.() -> Unit) {
    promptBuilder = AcpPromptScope().apply(block)
  }

  fun tools(block: AcpToolsScope.() -> Unit) {
    toolsBuilder = AcpToolsScope().apply(block)
  }

  fun mcpCapabilities(block: AcpMcpCapabilitiesScope.() -> Unit) {
    mcpCapabilitiesBuilder = AcpMcpCapabilitiesScope().apply(block)
  }

  internal fun toJsonObject(): JsonObject =
    buildJsonObject {
      put("loadSession", JsonPrimitive(loadSession))
      promptBuilder?.let { put("prompt", it.toJsonObject()) }
      put("modes", buildJsonArray { modes.forEach { add(JsonPrimitive(it)) } })
      toolsBuilder?.let { put("tools", it.toJsonObject()) }
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

internal class AcpToolsScope {
  var terminal: Boolean = false
  var requestPermission: Boolean = false
  var preview: List<String> = emptyList()
  private var fsBuilder: AcpFsScope? = null

  fun fs(block: AcpFsScope.() -> Unit) {
    fsBuilder = AcpFsScope().apply(block)
  }

  internal fun toJsonObject(): JsonObject =
    buildJsonObject {
      put("requestPermission", JsonPrimitive(requestPermission))
      fsBuilder?.let { put("fs", it.toJsonObject()) }
      put("terminal", JsonPrimitive(terminal))
      put("preview", buildJsonArray { preview.forEach { add(JsonPrimitive(it)) } })
    }
}

internal class AcpFsScope {
  var readTextFile: Boolean = false
  var writeTextFile: Boolean = false

  internal fun toJsonObject(): JsonObject =
    buildJsonObject {
      put("readTextFile", JsonPrimitive(readTextFile))
      put("writeTextFile", JsonPrimitive(writeTextFile))
    }
}

internal class AcpMcpCapabilitiesScope {
  var enabled: Boolean = true

  internal fun toJsonObject(): JsonObject =
    buildJsonObject {
      put("enabled", JsonPrimitive(enabled))
    }
}
