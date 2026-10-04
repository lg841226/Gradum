package gradum.acp

import gradum.ModelEntry
import gradum.ModelIdentity
import gradum.ToolMode
import gradum.mcp.jsonrpc.RpcError
import gradum.mcp.jsonrpc.RpcResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Session config-option builders plus the `session/set_config_option`
 * handler. Config options are stateless JSON shapes derived from the
 * session's current selections, so the builders are plain top-level
 * functions; only the handler needs the AcpServer receiver.
 */


/**
 * The complete session config, in display-priority order. Both `session/new`
 * and `session/set_config_option` must return every option: the spec requires
 * the full configuration state on each change.
 */
internal fun buildSessionConfigOptions(sessionState: AcpSessionState): List<JsonObject> =
  listOf(
    buildModelConfigOption(sessionState.selectedModelName),
    buildModeConfigOption(sessionState.selectedToolMode)
  )

private fun buildModelConfigOption(selectedModel: String): JsonObject = acpConfigOption {
  id = "model"
  name = "Model"
  category = "model"
  type = "select"
  currentValue = selectedModel
  options = buildModelOptionValues()
}

private fun buildModeConfigOption(selectedMode: ToolMode): JsonObject = acpConfigOption {
  id = "mode"
  name = "Select permissions"
  category = "mode"
  type = "select"
  currentValue = modeId(selectedMode)
  options = ToolMode.entries.map { mode -> acpOptionValue(modeId(mode), modeLabel(mode), modeDescription(mode)) }
}

private fun buildModelOptionValues(): List<JsonObject> {
  val models: List<ModelEntry> = ModelIdentity.discoverModels().filter { it.available }
  return models.map { entry ->
    buildOptionValue(entry.modelName, entry.serverName, entry.providerType)
  }
}

private fun buildOptionValue(modelName: String, serverName: String, providerType: String): JsonObject {
  val description: String? = serverName.takeIf { it.isNotBlank() }?.let { "$providerType @ $it" }
  return acpOptionValue(modelName, modelName, description)
}

/**
 * Wire id for a [ToolMode], derived from the enum name so it stays in sync
 * with [ToolMode.fromStringOrDefault], which parses those same names back.
 */
private fun modeId(mode: ToolMode): String = mode.name.lowercase()

/**
 * Labels and descriptions mirror the plugin's GradumBundle.properties
 * (gradum.select.permissions, gradum.*.mode, gradum.*.info) so the ACP
 * selector reads identically to the plugin's own permission selector.
 */
private fun modeLabel(mode: ToolMode): String = when (mode) {
  ToolMode.AGENT -> "Agent Mode"
  ToolMode.EDIT -> "Edit Permissions"
  ToolMode.READ_ONLY -> "Read-only Permissions"
}

private fun modeDescription(mode: ToolMode): String = when (mode) {
  ToolMode.AGENT -> "Reads, writes, executes complex tasks"
  ToolMode.EDIT -> "Edit files and code by hand"
  ToolMode.READ_ONLY -> "Read, explore and run diagnostics"
}

internal fun AcpServer.handleSetConfigOption(requestId: Long, params: JsonObject?): RpcResponse {
  val sessionId: String = params?.get("sessionId")?.jsonPrimitive?.contentOrNull
    ?: return RpcResponse(
      id = requestId,
      error = RpcError(
        code = -32602, message = "Missing required parameter: sessionId"
      )
    )

  val configId: String = params["configId"]?.jsonPrimitive?.contentOrNull
    ?: return RpcResponse(
      id = requestId,
      error = RpcError(
        code = -32602, message = "Missing required parameter: configId"
      )
    )

  val value: String = params["value"]?.jsonPrimitive?.contentOrNull
    ?: return RpcResponse(
      id = requestId,
      error = RpcError(
        code = -32602, message = "Missing required parameter: value"
      )
    )

  val sessionState: AcpSessionState = activeSessions[sessionId]
    ?: return RpcResponse(
      id = requestId,
      error = RpcError(
        code = -32602, message = "Unknown sessionId: $sessionId"
      )
    )

  when (configId) {
    "model" -> {
      sessionState.selectedModelName = value
      logger.info("ACP config changed: sessionId=$sessionId model=$value")
    }

    "mode" -> {
      sessionState.selectedToolMode = ToolMode.fromStringOrDefault(value, sessionState.selectedToolMode)
      logger.info("ACP config changed: sessionId=$sessionId mode=$value")
    }

    else -> logger.warn("Unknown config option: $configId")
  }

  return RpcResponse(
    id = requestId,
    result = acpConfigOptions(buildSessionConfigOptions(sessionState))
  )
}
