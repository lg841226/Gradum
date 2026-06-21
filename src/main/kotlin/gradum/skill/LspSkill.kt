/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * LspSkill.kt  2026-06-21 07:53:44 Changed by gwy
 */

package gradum.skill

import gradum.SkillResult
import gradum.makeFailure
import gradum.makeSuccess
import gradum.util.LspClient
import gradum.util.LspConnection
import gradum.util.LspServerRegistry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import java.nio.file.Path

/**
 * Language-aware code intelligence via the Language Server Protocol. Exposes
 * diagnostics, hover, definition, references, symbols, and formatting to the
 * agent as discrete tool calls. One instance is shared for the whole session;
 * the underlying [LspClient] keeps a per-language connection pool.
 */
class LspSkill : Skill() {

    override val skillName: String = "lsp"
    override val alias: String = "LSP"
    override val description: String = "Language-aware code analysis via LSP (diagnostics, hover, definition, references, symbols, format)"

    override fun getSchema(): Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to skillName,
            "description" to description,
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "Absolute or workspace-relative file path"),
                    "action" to mapOf("type" to "string", "enum" to listOf("diagnostics", "hover", "definition", "references", "symbols", "format")),
                    "line" to mapOf("type" to "integer", "description" to "1-based line number (required for hover/definition/references)"),
                    "col" to mapOf("type" to "integer", "description" to "1-based column number (required for hover/definition/references)"),
                ),
                "required" to listOf("path", "action"),
            ),
        ),
    )

    override fun execute(arguments: Map<String, Any>): SkillResult {
        val filePath: String = arguments["path"] as? String ?: return makeFailure("INVALID_PARAMETER", "Missing 'path'")
        val action: String = arguments["action"] as? String ?: return makeFailure("INVALID_PARAMETER", "Missing 'action'")
        val resolved: Path = Path.of(filePath).toAbsolutePath().normalize()

        val spec = LspServerRegistry.findByPath(resolved) ?: return makeFailure("UNSUPPORTED_LANGUAGE", "No LSP server registered for $filePath")
        val conn: LspConnection = LspClient.connectionFor(resolved) ?: return makeFailure("LSP_NOT_INSTALLED", "LSP server for ${spec.languageId} not on PATH. Install: ${spec.installHint}")

        return when (action) {
            "diagnostics" -> runDiagnostics(conn, resolved, spec.languageId)
            "hover" -> runPositionQuery(PositionQuery(conn, resolved, spec.languageId, "textDocument/hover", requirePosition(arguments)))
            "definition" -> runPositionQuery(PositionQuery(conn, resolved, spec.languageId, "textDocument/definition", requirePosition(arguments)))
            "references" -> runPositionQuery(PositionQuery(conn, resolved, spec.languageId, "textDocument/references", requirePosition(arguments)))
            "symbols" -> runSymbols(conn, resolved, spec.languageId)
            "format" -> runFormat(conn, resolved, spec.languageId)
            else -> makeFailure("INVALID_PARAMETER", "Unknown action '$action'")
        }
    }

    private data class PositionQuery(val conn: LspConnection, val path: Path, val languageId: String, val method: String, val position: Pair<Int, Int>)

    private fun requirePosition(arguments: Map<String, Any>): Pair<Int, Int> {
        val line: Int = (arguments["line"] as? Number)?.toInt() ?: throw IllegalArgumentException("Missing 'line'")
        val col: Int = (arguments["col"] as? Number)?.toInt() ?: throw IllegalArgumentException("Missing 'col'")
        return line to col
    }

    private fun runDiagnostics(conn: LspConnection, path: Path, languageId: String): SkillResult {
        val content: String = path.toFile().readText(Charsets.UTF_8)
        conn.didOpen(path, content, version = 1)

        val params: JsonObject = buildJsonObject { put("textDocument", buildJsonObject { put("uri", path.toUri().toString()) }) }
        val raw: JsonElement = conn.sendRequest("textDocument/diagnostic", params)
        val items: JsonArray = (raw as? JsonObject)?.get("items") as? JsonArray ?: JsonArray(emptyList())
        val parsed: List<Map<String, Any?>> = items.map { element: JsonElement -> parseDiagnostic(element) }

        val errors: Int = parsed.count { it["severity"] == "error" }
        val warnings: Int = parsed.count { it["severity"] == "warning" }
        val info: Int = parsed.count { it["severity"] == "info" }
        val hints: Int = parsed.count { it["severity"] == "hint" }
        val summary: Map<String, Int> = mapOf("total" to parsed.size, "errors" to errors, "warnings" to warnings, "info" to info, "hints" to hints)

        val fileName: String = path.fileName.toString()
        val parts: List<String> = listOfNotNull(
            if (errors > 0) "$errors error${if (errors == 1) "" else "s"}" else null,
            if (warnings > 0) "$warnings warning${if (warnings == 1) "" else "s"}" else null,
            if (info > 0) "$info info" else null,
            if (hints > 0) "$hints hint" else null,
        )
        val summaryText: String = parts.joinToString(", ").ifEmpty { "clean" }.let { "$it in $fileName" }

        return makeSuccess(mapOf("language" to languageId, "path" to path.toString(), "summary" to summary, "summaryText" to summaryText, "diagnostics" to parsed))
    }

    private fun startEndOrNull(obj: JsonObject, rangeKey: String = "range"): Pair<JsonObject, JsonObject>? {
        val range: JsonObject = obj[rangeKey] as? JsonObject ?: return null
        val start: JsonObject = range["start"] as? JsonObject ?: return null
        val end: JsonObject = range["end"] as? JsonObject ?: return null
        return start to end
    }

    private fun lineOf(pos: JsonObject): Int =
        ((pos["line"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0) + 1

    private fun colOf(pos: JsonObject): Int =
        ((pos["character"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0) + 1

    private fun parseDiagnostic(element: JsonElement): Map<String, Any?> {
        val obj: JsonObject = element as? JsonObject ?: return emptyMap()
        val pair: Pair<JsonObject, JsonObject> = startEndOrNull(obj) ?: return emptyMap()
        val (start: JsonObject, end: JsonObject) = pair
        return mapOf(
            "severity" to severityName((obj["severity"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1),
            "line" to lineOf(start),
            "column" to colOf(start),
            "endLine" to lineOf(end),
            "endColumn" to colOf(end),
            "message" to ((obj["message"] as? JsonPrimitive)?.content ?: ""),
            "source" to ((obj["source"] as? JsonPrimitive)?.content),
            "code" to ((obj["code"] as? JsonPrimitive)?.content),
        )
    }

    private fun runPositionQuery(q: PositionQuery): SkillResult {
        val content: String = q.path.toFile().readText(Charsets.UTF_8)
        q.conn.didOpen(q.path, content, version = 1)

        val params: JsonObject = buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", q.path.toUri().toString()) })
            put("position", buildJsonObject { put("line", q.position.first - 1); put("character", q.position.second - 1) })
        }
        val raw: JsonElement = q.conn.sendRequest(q.method, params)
        val locations: List<Map<String, Any?>> = parseQueryResult(raw, q.method)

        return makeSuccess(mapOf("language" to q.languageId, "path" to q.path.toString(), "action" to q.method.removePrefix("textDocument/"), "count" to locations.size, "locations" to locations))
    }

    private fun parseQueryResult(raw: JsonElement, method: String): List<Map<String, Any?>> {
        return when {
            method == "textDocument/hover" && raw is JsonObject -> listOf(parseHover(raw))
            raw is JsonArray -> raw.map { element: JsonElement -> parseLocation(element) }
            raw is JsonObject -> listOf(parseLocation(raw))
            else -> emptyList()
        }
    }

    private fun parseHover(obj: JsonObject): Map<String, Any?> {
        val contents: JsonObject = (obj["contents"] as? JsonObject) ?: return emptyMap()
        val value: String = (contents["value"] as? JsonPrimitive)?.content ?: ""
        val language: String? = (contents["language"] as? JsonPrimitive)?.content
        val pair: Pair<JsonObject, JsonObject>? = startEndOrNull(obj)
        val start: JsonObject? = pair?.first
        val end: JsonObject? = pair?.second
        return mapOf(
            "value" to value,
            "language" to language,
            "line" to (start?.let { lineOf(it) } ?: 0),
            "column" to (start?.let { colOf(it) } ?: 0),
            "endLine" to (end?.let { lineOf(it) } ?: 0),
            "endColumn" to (end?.let { colOf(it) } ?: 0),
        )
    }

    private fun parseLocation(element: JsonElement): Map<String, Any?> {
        val obj: JsonObject = element as? JsonObject ?: return emptyMap()
        val uriString: String = (obj["targetUri"] as? JsonPrimitive)?.content ?: (obj["uri"] as? JsonPrimitive)?.content ?: ""
        val pair: Pair<JsonObject, JsonObject> = startEndOrNull(obj) ?: startEndOrNull(obj, "selectionRange") ?: return emptyMap()
        val (start: JsonObject, end: JsonObject) = pair
        return mapOf(
            "path" to uriString,
            "line" to lineOf(start),
            "column" to colOf(start),
            "endLine" to lineOf(end),
            "endColumn" to colOf(end),
        )
    }

    private fun runSymbols(conn: LspConnection, path: Path, languageId: String): SkillResult {
        val content: String = path.toFile().readText(Charsets.UTF_8)
        conn.didOpen(path, content, version = 1)

        val params: JsonObject = buildJsonObject { put("textDocument", buildJsonObject { put("uri", path.toUri().toString()) }) }
        val raw: JsonElement = conn.sendRequest("textDocument/documentSymbol", params)
        val symbols: List<Map<String, Any?>> = parseSymbols(raw)

        return makeSuccess(mapOf("language" to languageId, "path" to path.toString(), "count" to symbols.size, "symbols" to symbols))
    }

    private fun parseSymbols(raw: JsonElement): List<Map<String, Any?>> {
        val jsonElements: JsonArray = raw as? JsonArray ?: return emptyList()
        return jsonElements.map { element: JsonElement -> parseSymbol(element) }
    }

    private fun parseSymbol(element: JsonElement): Map<String, Any?> {
        val obj: JsonObject = element as? JsonObject ?: return emptyMap()
        val name: String = (obj["name"] as? JsonPrimitive)?.content ?: ""
        val kind: Int = (obj["kind"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val range: JsonObject? = (obj["location"] as? JsonObject)?.get("range") as? JsonObject ?: obj["range"] as? JsonObject
        val start: JsonObject? = range?.get("start") as? JsonObject
        val end: JsonObject? = range?.get("end") as? JsonObject
        val childrenRaw: JsonArray? = obj["children"] as? JsonArray
        val children: List<Map<String, Any?>> = childrenRaw?.map { child: JsonElement -> parseSymbol(child) } ?: emptyList()
        return mapOf(
            "name" to name,
            "kind" to symbolKindName(kind),
            "line" to (start?.let { lineOf(it) } ?: 0),
            "column" to (start?.let { colOf(it) } ?: 0),
            "endLine" to (end?.let { lineOf(it) } ?: 0),
            "endColumn" to (end?.let { colOf(it) } ?: 0),
            "children" to children,
        )
    }

    private fun runFormat(conn: LspConnection, path: Path, languageId: String): SkillResult {
        val content: String = path.toFile().readText(Charsets.UTF_8)
        conn.didOpen(path, content, version = 1)

        val params: JsonObject = buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", path.toUri().toString()) })
            put("options", buildJsonObject { put("tabSize", 4); put("insertSpaces", true) })
        }
        val raw: JsonElement = conn.sendRequest("textDocument/formatting", params)
        val edits: JsonArray = (raw as? JsonArray) ?: JsonArray(emptyList())

        val parsed: List<Map<String, Any?>> = edits.map { element: JsonElement -> parseFormatEdit(element) }

        return makeSuccess(mapOf("language" to languageId, "path" to path.toString(), "editCount" to edits.size, "edits" to parsed))
    }

    private fun parseFormatEdit(element: JsonElement): Map<String, Any?> {
        val obj: JsonObject = element as? JsonObject ?: return emptyMap()
        val pair: Pair<JsonObject, JsonObject> = startEndOrNull(obj) ?: return emptyMap()
        val (start: JsonObject, end: JsonObject) = pair
        return mapOf(
            "line" to lineOf(start),
            "column" to colOf(start),
            "endLine" to lineOf(end),
            "endColumn" to colOf(end),
            "newText" to ((obj["newText"] as? JsonPrimitive)?.content ?: ""),
        )
    }

    private fun severityName(severity: Int): String = when (severity) {
        1 -> "error"
        2 -> "warning"
        3 -> "info"
        4 -> "hint"
        else -> "unknown"
    }

    private fun symbolKindName(kind: Int): String = SYMBOL_KIND_NAMES[kind] ?: "unknown"

    private companion object {
        private val SYMBOL_KIND_NAMES: Map<Int, String> = mapOf(
            1 to "file", 2 to "module", 3 to "namespace", 4 to "package", 5 to "class", 6 to "method",
            7 to "property", 8 to "field", 9 to "constructor", 10 to "enum", 11 to "interface",
            12 to "function", 13 to "variable", 14 to "constant", 15 to "string", 16 to "number",
            17 to "boolean", 18 to "array", 19 to "object", 20 to "key", 21 to "null",
            22 to "enumMember", 23 to "struct", 24 to "event", 25 to "operator", 26 to "typeParameter",
        )
    }
}
