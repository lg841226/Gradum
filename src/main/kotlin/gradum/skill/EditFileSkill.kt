/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * EditFileSkill.kt  2026-07-15 22:41:33 Changed by gwy
 */

package gradum.skill

import gradum.*
import gradum.utils.SyntaxChecker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Search-and-replace file editing. Local models edit one file at a time with
 * a single oldString/newString pair. Cloud models can batch multiple edits.
 */
class EditFileSkill : Skill() {

  override val skillName: String = "edit_file"
  override val alias: String = "Edited"
  override val description: String =
    "Replace text in a file. Provide the exact text to find (oldString) and the replacement (newString)."

  override val allowedToolModes: Set<ToolMode> = setOf(ToolMode.AGENT, ToolMode.EDIT)

  /**
   * Keep the last [historyKeepCount] edits' full metadata in
   * history. Strip [historyVolatileKeys] (the diff payloads)
   * from older edits. The diff payloads are too large to
   * retain across long sessions and the LLM can always
   * re-read the file at `path` if it needs the pre/post
   * content of an old edit.
   */
  override val historyKeepCount: Int = 5
  override val historyVolatileKeys: List<String> =
    listOf("originalContent", "modifiedContent")

  /**
   * Strip the diff payloads from the CURRENT call's result
   * as well — they are too large for the LLM's view of this
   * turn even when the call is the most recent. The LLM only
   * needs `path`, `linesAdded`, `linesRemoved`, `totalEdits`,
   * and any `syntaxErrors` to understand "an edit happened at
   * path X with N added M removed"; the pre/post text would
   * just bloat the response.
   */
  override fun prepareHistoryResult(result: Map<String, Any>): Map<String, Any> {
    return result.filterKeys { it != "originalContent" && it != "modifiedContent" }
  }

  override fun getSchema(context: SkillContext?): Map<String, Any> {
    val useSimpleSchema = context != null && SchemaVariant.resolve(context.modelName) == SchemaVariant.SIMPLE
    return mapOf(
      "type" to "function",
      "function" to mapOf(
        "name" to skillName,
        "description" to if (useSimpleSchema) localDescription() else description,
        "parameters" to mapOf(
          "type" to "object",
          "properties" to if (useSimpleSchema) localProperties() else cloudProperties(),
          "required" to listOf("path") + if (useSimpleSchema) listOf(
            "oldString",
            "newString"
          ) else listOf("edits"),
        ),
      ),
    )
  }

  private fun localDescription(): String =
    "Replace text in a file. First read the file with read_file, then pass the exact text as oldString and the new text as newString. oldString must match exactly. You can only edit one location per call."

  private fun localProperties(): Map<String, Any> = mapOf(
    "path" to mapOf(
      "type" to "string",
      "description" to "File path relative to project root, e.g. 'src/main.py'."
    ),
    "oldString" to mapOf(
      "type" to "string",
      "description" to "Exact text to find. Include 2-3 lines of context for uniqueness. Must match file content including whitespace and indentation."
    ),
    "newString" to mapOf(
      "type" to "string",
      "description" to "Replacement text. Can be longer, shorter, or empty to delete."
    ),
  )

  private fun cloudProperties(): Map<String, Any> = mapOf(
    "path" to mapOf(
      "type" to "string",
      "description" to "Replace text in a file. Provide the exact text to find (oldString) and the replacement (newString).",
    ),
    "edits" to mapOf(
      "type" to "array",
      "description" to "List of edits to apply. Each edit has oldString (text to find) and newString (replacement). Edits are applied in order. You can batch multiple edits to the same file in one call.",
      "items" to mapOf(
        "type" to "object",
        "properties" to mapOf(
          "oldString" to mapOf(
            "type" to "string",
            "description" to "Exact text to find. Must include 2-3 lines of code context. Copy from read_file tool output exactly."
          ),
          "newString" to mapOf(
            "type" to "string",
            "description" to "You want replacement text. Can be empty to delete lines."
          ),
        ),
        "required" to listOf("oldString", "newString"),
      ),
    ),
  )

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val useSimpleSchema = SchemaVariant.resolve(context.modelName) == SchemaVariant.SIMPLE
    return if (useSimpleSchema) executeLocal(arguments, context) else executeCloud(arguments, context)
  }

  /**
   * Local model execution: single search/replace edit.
   * Simplified schema with flat parameters for small models.
   */
  private fun executeLocal(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val filePath: String = arguments["path"] as? String ?: ""
    val oldString: String = arguments["oldString"] as? String ?: ""
    val newString: String = arguments["newString"] as? String ?: ""
    val projectRoot: String = context.projectRoot

    if (filePath.isBlank())
      return makeFailure(
        ErrorCode.INVALID_PARAMETER,
        buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Missing 'path' parameter in edit_file call.",
          fixHint = "The 'path' parameter is required. Example: 'path': 'src/main.kt'"
        )
      )

    if (oldString.isBlank())
      return makeFailure(
        ErrorCode.INVALID_PARAMETER, buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Missing 'oldString' parameter.",
          fixHint = "Provide the text to find in the 'oldString' parameter."
        )
      )

    val resolved: ResolvedProjectPath = resolveProjectPath(filePath, projectRoot)
    val resolvedPath: Path = resolved.resolved
    val targetFile: File = resolvedPath.toFile()

    return try {
      val originalContent: String = targetFile.readText(Charsets.UTF_8)
      val singleEdit = listOf(EditOperation(oldString, newString, 0))
      applySequentialEdits(resolvedPath, originalContent, singleEdit)
    } catch (_: FileNotFoundException) {
      makeFailure(
        ErrorCode.FILE_NOT_FOUND, buildXmlError(
          code = "FILE_NOT_FOUND",
          message = "Edit File not found: $filePath",
          fixHint = "Check the current file path. you could run_cmd to find the correct path."
        ), mapOf("path" to resolvedPath.toString())
      )
    } catch (exception: Exception) {
      makeFailure(
        ErrorCode.IO_ERROR, buildXmlError(
          code = "IO_ERROR",
          message = exception.message ?: "Unknown error during edit.",
          fixHint = "Check file permissions and disk space. Stop editing."
        ), mapOf("path" to resolvedPath.toString())
      )
    }
  }

  /**
   * Cloud model execution: multiple search/replace edits via array.
   */
  private fun executeCloud(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val filePath: String = arguments["path"] as? String ?: ""
    val rawEdits: List<Map<String, Any>> = try {
      parseEdits(arguments["edits"])
    } catch (parseException: Exception) {
      return makeFailure(
        ErrorCode.INVALID_PARAMETER, buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Failed to parse 'edits' parameter: ${parseException.message ?: parseException::class.simpleName ?: "unknown parse error"}",
          fixHint = "Provide 'edits' as a JSON array of {oldString, newString} objects, or as a JSON-encoded string of the same shape."
        ), mapOf("path" to filePath)
      )
    }
    val projectRoot: String = context.projectRoot

    if (filePath.isBlank())
      return makeFailure(
        ErrorCode.INVALID_PARAMETER, buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Missing 'path' parameter.",
          fixHint = "Provide a valid file path in the 'path' parameter."
        )
      )

    if (rawEdits.isEmpty())
      return makeFailure(
        ErrorCode.INVALID_PARAMETER, buildXmlError(
          code = "INVALID_PARAMETER",
          message = "No edits provided.",
          fixHint = "Provide at least one edit object with 'oldString' and 'newString' fields."
        )
      )

    val resolved: ResolvedProjectPath = resolveProjectPath(filePath, projectRoot)
    val resolvedPath: Path = resolved.resolved
    val targetFile: File = resolvedPath.toFile()

    return try {
      val originalContent: String = targetFile.readText(Charsets.UTF_8)
      val parsedEdits: List<EditOperation> = rawEdits.mapIndexed { editIndex: Int, editEntry: Map<String, Any> ->
        EditOperation(
          searchText = editEntry["oldString"] as? String ?: "",
          replaceText = editEntry["newString"] as? String ?: "",
          editIndex = editIndex
        )
      }

      val invalidEdit: EditOperation? = parsedEdits.firstOrNull { it.searchText.isBlank() }
      if (invalidEdit != null) {
        return makeFailure(
          ErrorCode.INVALID_PARAMETER, buildXmlError(
            code = "INVALID_PARAMETER",
            message = "Edit ${invalidEdit.editIndex + 1} has empty 'search' text.",
            fixHint = "Provide non-empty oldString text that matches the target code block in the file."
          )
        )
      }
      applySequentialEdits(resolvedPath, originalContent, parsedEdits)
    } catch (_: FileNotFoundException) {
      makeFailure(
        ErrorCode.FILE_NOT_FOUND, buildXmlError(
          code = "FILE_NOT_FOUND",
          message = "File not found: $filePath",
          fixHint = "Check the file path. Use run_cmd tool to find the correct path."
        ), mapOf("path" to resolvedPath.toString())
      )
    } catch (exception: Exception) {
      makeFailure(
        ErrorCode.IO_ERROR, buildXmlError(
          code = "IO_ERROR",
          message = exception.message ?: "Unknown error during edit.",
          fixHint = "Check file permissions and disk space. Stop editing."
        ), mapOf("path" to resolvedPath.toString())
      )
    }
  }

  private fun applySequentialEdits(
    resolvedPath: Path, originalContent: String, editOperations: List<EditOperation>
  ): SkillResult {
    var currentContent: String = originalContent
    val appliedEdits: MutableList<Int> = mutableListOf()
    var linesAdded = 0
    var linesRemoved = 0

    val fileMutation = FileMutation()

    for (editOperation in editOperations) {
      val fileLines = currentContent.lines()
      val searchLines = editOperation.searchText.lines()

      when (val matchResult = findMatchesWithFallback(fileLines, searchLines)) {
        is FindResult.Found -> {
          if (matchResult.matches.size > 1) {
            writeWithLock(fileMutation, resolvedPath, currentContent, originalContent, appliedEdits.size)
              ?.let { return it }

            return makeFailure(
              ErrorCode.MULTIPLE_MATCHES,
              buildXmlError(
                code = "MULTIPLE_MATCHES",
                message = "Edit ${editOperation.editIndex + 1} found ${matchResult.matches.size} matches. Cannot determine which to replace.",
                fixHint = "Add more surrounding context (function name, class declaration, comments) to make the match unique.",
                appliedCount = appliedEdits.size
              ),
              mapOf("path" to resolvedPath.toString(), "appliedCount" to appliedEdits.size)
            )
          }

          val bestMatch = matchResult.matches.first()
          val replaceLines =
            if (editOperation.replaceText.isBlank()) emptyList() else editOperation.replaceText.lines()

          linesRemoved += searchLines.size
          linesAdded += replaceLines.size

          val newFileLines = fileLines.subList(0, bestMatch.startIndex) +
            replaceLines +
            fileLines.subList(bestMatch.endIndex, fileLines.size)
          currentContent = newFileLines.joinToString("\n")
          appliedEdits.add(editOperation.editIndex)
        }

        is FindResult.NotFound -> {
          writeWithLock(fileMutation, resolvedPath, currentContent, originalContent, appliedEdits.size)
            ?.let { return it }

          val failureStep = when (matchResult.failedAtStep) {
            MatchStrategy.EXACT -> "exact match"
            MatchStrategy.NORMALIZED -> "normalized match"
            MatchStrategy.STRIPPED -> "stripped match"
            MatchStrategy.NONE -> "matching"
          }
          return makeFailure(
            ErrorCode.CODE_NOT_FOUND,
            buildXmlError(
              code = "CODE_NOT_FOUND",
              message = "Edit ${editOperation.editIndex + 1} failed at step: $failureStep. oldString text not found in file.",
              fixHint = "Check for whitespace differences or add more surrounding context (function name, class declaration, comments) to make the match unique. If this fails after multiple attempts, inform the user and suggest manual editing.",
              searchPreview = matchResult.searchPreview,
              appliedCount = appliedEdits.size
            ),
            mapOf(
              "path" to resolvedPath.toString(),
              "appliedCount" to appliedEdits.size,
              "failedAtStep" to matchResult.failedAtStep.name,
              "searchPreview" to matchResult.searchPreview
            )
          )
        }
      }
    }

    if (currentContent.isBlank() && originalContent.isNotBlank()) {
      writeWithLock(fileMutation, resolvedPath, originalContent, originalContent)
        ?.let { return it }

      return makeFailure(
        ErrorCode.EMPTY_RESULT,
        buildXmlError(
          code = "EMPTY_RESULT",
          message = "Edit resulted in empty content.",
          fixHint = "The oldString block matched the entire file content. Add more context or check the file."
        ),
        mapOf("path" to resolvedPath.toString())
      )
    }

    val writeResult = runBlocking {
      fileMutation.writeTextPreservingBom(resolvedPath, currentContent)
    }

    if (writeResult.isFailure) {
      val error = writeResult.exceptionOrNull()
      return if (error is StaleContentError) {
        makeFailure(
          ErrorCode.CONCURRENT_MODIFICATION,
          buildXmlError(
            code = "CONCURRENT_MODIFICATION",
            message = "File was modified externally. Please read it again.",
            fixHint = "The file changed since you read it. Use read_file to get the latest content."
          )
        )
      } else {
        makeFailure(
          ErrorCode.IO_ERROR,
          buildXmlError(
            code = "IO_ERROR",
            message = error?.message ?: "Unknown error during write.",
            fixHint = "Check file permissions and disk space."
          )
        )
      }
    }

    return makeSuccess(
      mapOf(
        "path" to resolvedPath.toString(),
        "editsApplied" to appliedEdits.size,
        "totalEdits" to editOperations.size,
        "linesAdded" to linesAdded,
        "linesRemoved" to linesRemoved,
        "syntaxErrors" to SyntaxChecker.checkSyntax(resolvedPath),
        "originalContent" to originalContent,
        "modifiedContent" to currentContent,
      ),
    )
  }

  private data class EditOperation(val searchText: String, val replaceText: String, val editIndex: Int)

  companion object {
    private val jsonParser = Json { ignoreUnknownKeys = true }

    @Suppress("UNCHECKED_CAST")
    fun parseEdits(rawInput: Any?): List<Map<String, Any>> {
      if (rawInput is List<*>) return rawInput.filterIsInstance<Map<String, Any>>()

      if (rawInput is String && rawInput.isNotBlank()) {
        try {
          val jsonElement = jsonParser.parseToJsonElement(rawInput)
          if (jsonElement is JsonArray) {
            return jsonElement.mapNotNull { jsonItem ->
              if (jsonItem is JsonObject) {
                val oldStringContent = (jsonItem["oldString"] as? JsonPrimitive)?.content ?: ""
                val newStringContent = (jsonItem["newString"] as? JsonPrimitive)?.content ?: ""
                mapOf("oldString" to oldStringContent, "newString" to newStringContent)
              } else null
            }
          }
        } catch (_: Exception) { /* Fall through to empty */
        }
      }
      return emptyList()
    }
  }
}

/**
 * Matching strategy used by the matching process.
 */
private enum class MatchStrategy { EXACT, NORMALIZED, STRIPPED, NONE }

/**
 * Result of a single match.
 */
private data class MatchResult(
  val startIndex: Int, val endIndex: Int, val strategy: MatchStrategy
)

/**
 * Result of the matching process.
 */
private sealed class FindResult {
  data class Found(val matches: List<MatchResult>) : FindResult()
  data class NotFound(
    val failedAtStep: MatchStrategy, val searchPreview: List<String>
  ) : FindResult()
}

/**
 * Matching for search blocks.
 *
 * Step 1: EXACT - trimEnd() comparison (handles \r\n vs \n)
 * Step 2: NORMALIZED - trim() comparison (ignore leading/trailing whitespace)
 * Step 3: STRIPPED - remove all whitespace (handles different indentation)
 * Step 4: NONE - return detailed error
 */
private fun findMatchesWithFallback(
  fileLines: List<String>, searchLines: List<String>
): FindResult {
  if (searchLines.isEmpty()) return FindResult.NotFound(
    MatchStrategy.NONE, emptyList()
  )

  val searchNonBlank = searchLines.filter { it.isNotBlank() }
  if (searchNonBlank.isEmpty()) return FindResult.NotFound(
    MatchStrategy.NONE, searchLines.take(3)
  )

  val fileNonBlankCount = fileLines.count { it.isNotBlank() }
  if (searchNonBlank.size > fileNonBlankCount) return FindResult.NotFound(
    MatchStrategy.NONE, searchNonBlank.take(3)
  )

  val exactMatches = findMatchesByStrategy(fileLines, searchNonBlank) { fileLine, searchLine ->
    fileLine.trimEnd('\r', ' ') == searchLine.trimEnd('\r', ' ')
  }
  if (exactMatches.isNotEmpty()) return FindResult.Found(exactMatches)

  val normalizedMatches = findMatchesByStrategy(fileLines, searchNonBlank) { fileLine, searchLine ->
    fileLine.trim() == searchLine.trim()
  }
  if (normalizedMatches.isNotEmpty()) return FindResult.Found(normalizedMatches)

  val strippedMatches = findMatchesByStrategy(fileLines, searchNonBlank) { fileLine, searchLine ->
    fileLine.replace(Regex("\\s"), "") == searchLine.replace(Regex("\\s"), "")
  }
  if (strippedMatches.isNotEmpty()) return FindResult.Found(strippedMatches)

  return FindResult.NotFound(
    failedAtStep = MatchStrategy.STRIPPED,
    searchPreview = searchNonBlank.take(3)
  )
}

/**
 * Find matches using a custom comparison function.
 *
 * Skips blank lines during matching so that different blank-line counts
 * between the file and search text do not cause false negatives.
 */
private fun findMatchesByStrategy(
  fileLines: List<String>, searchLines: List<String>,
  comparator: (String, String) -> Boolean
): List<MatchResult> {
  val matchResults = mutableListOf<MatchResult>()
  val searchContent = searchLines.filter { it.isNotBlank() }
  if (searchContent.isEmpty()) return matchResults

  for (startIdx in fileLines.indices) {
    if (fileLines[startIdx].isBlank()) continue

    var fileIndex = startIdx
    var searchIndex = 0

    while (searchIndex < searchContent.size && fileIndex < fileLines.size) {
      if (fileLines[fileIndex].isBlank()) {
        fileIndex++
        continue
      }
      if (comparator(fileLines[fileIndex], searchContent[searchIndex])) {
        fileIndex++
        searchIndex++
      } else break
    }
    if (searchIndex == searchContent.size)
      matchResults.add(MatchResult(startIdx, fileIndex, MatchStrategy.EXACT))
  }
  return matchResults
}

class StaleContentError(val path: String) : Exception("File changed externally:$path")

class FileMutation {
  private val locks = ConcurrentHashMap<String, Mutex>()

  private fun getMutex(path: Path): Mutex {
    val key = path.toAbsolutePath().normalize().toString()
    return locks.computeIfAbsent(key) { Mutex() }
  }

  suspend fun <T> withLock(path: Path, block: suspend () -> T): T {
    return getMutex(path).withLock { block() }
  }

  suspend fun writeIfUnchanged(
    path: Path, content: String, expected: ByteArray
  ): Result<Unit> {
    return withLock(path) {
      val targetFile = path.toFile()
      if (!targetFile.exists()) {
        return@withLock Result.failure(StaleContentError(path.toString()))
      }
      val current = targetFile.readBytes()
      if (!current.contentEquals(expected)) {
        return@withLock Result.failure(StaleContentError(path.toString()))
      }
      targetFile.writeText(content, Charsets.UTF_8)
      Result.success(Unit)
    }
  }

  suspend fun writeTextPreservingBom(path: Path, content: String): Result<Unit> {
    return withLock(path) {
      val targetFile = path.toFile()
      val (cleanContent, newHasBom) = splitBom(content)
      val current = if (targetFile.exists()) targetFile.readBytes() else null
      val currentHasBom = current?.let { hasUtf8Bom(it) } ?: false
      val finalContent = joinBom(cleanContent, currentHasBom || newHasBom)

      targetFile.writeText(finalContent, Charsets.UTF_8)
      targetFile.setLastModified(System.currentTimeMillis())
      Result.success(Unit)
    }
  }

  companion object {
    private fun joinBom(text: String, hasBom: Boolean): String {
      return if (hasBom) "\uFEFF$text" else text
    }

    private fun hasUtf8Bom(bytes: ByteArray): Boolean {
      return bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte()
    }

    private fun splitBom(text: String): Pair<String, Boolean> {
      val stripped = text.replace(Regex("^\\uFEFF+"), "")
      return stripped to (stripped.length != text.length)
    }
  }
}

private fun writeWithLock(
  fileMutation: FileMutation, resolvedPath: Path, content: String,
  originalContent: String, appliedCount: Int = 0
): SkillResult? {
  val result = runBlocking {
    fileMutation.writeIfUnchanged(
      resolvedPath, content, originalContent.toByteArray(Charsets.UTF_8)
    )
  }
  if (result.isFailure && result.exceptionOrNull() is StaleContentError) {
    return makeFailure(
      ErrorCode.CONCURRENT_MODIFICATION,
      buildXmlError(
        code = "CONCURRENT_MODIFICATION",
        message = "File was modified externally. Please read it again.",
        fixHint = "The file changed since you read it. Use read_file to get the latest content.",
        appliedCount = appliedCount
      )
    )
  }
  return null
}
