package gradum.idea.settings

import gradum.idea.provider.ProviderConfigFile
import gradum.idea.provider.ProviderConfigFile.McpServerEntry

internal data class McpServerItem(
  val id: Long,
  val name: String,
  val commandText: String,
  val workingDir: String
)

internal fun McpServerEntry.toItem(id: Long): McpServerItem = McpServerItem(
  id = id,
  name = name,
  commandText = ProviderConfigFile.formatCommandText(command),
  workingDir = workingDir.orEmpty()
)

internal fun McpServerItem.toEntryOrNull(): McpServerEntry? {
  val trimmedName: String = name.trim()
  if (trimmedName.isEmpty()) return null
  val tokens: List<String> = ProviderConfigFile.parseCommandText(commandText)
  if (tokens.isEmpty()) return null
  return McpServerEntry(
    name = trimmedName,
    command = tokens,
    workingDir = workingDir.trim().ifEmpty { null }
  )
}

internal fun uniqueMcpTabName(base: String, existing: Set<String>): String {
  var candidate: String = base
  var suffix = 2
  while (candidate in existing) {
    candidate = "$base-$suffix"
    suffix++
  }
  return candidate
}

internal fun spliceMcpEnvs(
  entry: McpServerEntry,
  fileEnvs: Map<String, Map<String, String>>,
  providedEnvs: Map<String, Map<String, String>>
): McpServerEntry =
  entry.copy(env = providedEnvs[entry.name] ?: fileEnvs[entry.name].orEmpty())
