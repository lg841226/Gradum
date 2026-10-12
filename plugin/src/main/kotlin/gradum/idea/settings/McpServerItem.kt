package gradum.idea.settings

import gradum.idea.provider.ProviderConfigFile
import gradum.idea.provider.ProviderConfigFile.McpServerEntry

/**
 * In-session working copy of one `mcpServers[]` entry. The file stays
 * the single source of truth: every debounced field edit is validated
 * and written back through [ProviderConfigFile.writeMcpServers]; raw
 * text fields mirror the file until the user edits them. Env maps are
 * intentionally absent: they are edited in the IDE editor against
 * settings.json and re-read from disk on every write.
 */
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

/**
 * Validates the working copy and converts it to the file model, or
 * returns null when the entry must not be written (blank name or a
 * command that tokenizes to nothing). The returned entry carries an
 * empty env; persist splices the env from the file by name so
 * hand-edits in settings.json survive panel writes.
 */
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

/**
 * First free tab name for a registry import: the base name when it is not
 * taken yet, otherwise `<base>-2`, `<base>-3`, … until it fits.
 */
internal fun uniqueMcpTabName(base: String, existing: Set<String>): String {
  var candidate: String = base
  var suffix = 2
  while (candidate in existing) {
    candidate = "$base-$suffix"
    suffix++
  }
  return candidate
}

/**
 * Env splice used by persist: an entry keeps the env map stored under its
 * name in settings.json unless [providedEnvs] carries one for it (the
 * registry import pre-fills its hints this way).
 */
internal fun spliceMcpEnvs(
  entry: McpServerEntry,
  fileEnvs: Map<String, Map<String, String>>,
  providedEnvs: Map<String, Map<String, String>>
): McpServerEntry =
  entry.copy(env = providedEnvs[entry.name] ?: fileEnvs[entry.name].orEmpty())
