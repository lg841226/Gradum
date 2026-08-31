/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * EditFileSkill.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.skill

import gradum.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private val logger: Logger = LoggerFactory.getLogger("EditFileSkill")

/**
 * Search-and-replace file editing. Local models edit one file at a time with
 * a single oldString/newString pair. Cloud models can batch multiple edits.
 */
class EditFileSkill : Skill() {

  override val alias: String = "Edited"
  override val skillName: String = "edit_file"
  override val description: String =
    "Replace text in a file. Provide the exact text to find (oldString) and the replacement (newString)."

  override val allowedToolModes: Set<ToolMode> = setOf(ToolMode.AGENT, ToolMode.EDIT)

  override val historyKeepCount: Int = 5
  override val historyVolatileKeys: List<String> =
    listOf("originalContent", "modifiedContent")

  override fun prepareHistoryResult(result: Map<String, Any>): Map<String, Any> {
    return result.filterKeys { it != "originalContent" && it != "modifiedContent" }
  }

  override val simpleDescription: String =
    "Replace text in a file. First read the file with read_file, then pass the exact text as oldString and the new" +
      " text as newString. oldString must match exactly. You can only edit one location per call."

  override val schemaProperties: SchemaBuilder.() -> Unit = {
    string(
      name = "path",
      description = "File path relative to project root, e.g. 'src/main.py'.",
      required = true,
    )
    simpleOnly {
      string(
        name = "oldString",
        description = "Exact text to find. Include 2-3 lines of context for uniqueness. " +
          "Must match file content including whitespace and indentation.",
        required = true,
      )
      string(
        name = "newString",
        description = "Replacement text. Can be longer, shorter, or empty to delete.",
        required = true,
      )
    }
    cloudOnly {
      objectArray(
        name = "edits",
        description = "List of edits to apply. Each edit has oldString (text to find) and newString (replacement)." +
          " Edits are applied in order. You can batch multiple edits to the same file in one call.",
        required = true,
        itemRequired = listOf("oldString", "newString"),
        items = {
          string(
            name = "oldString",
            description = "Exact text to find. Must include 2-3 lines of code context. Copy from read_file tool output exactly."
          )
          string(
            name = "newString",
            description = "You want replacement text. Can be empty to delete lines."
          )
        },
      )
    }
  }

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val useSimpleSchema = context.isSimpleModel
    return if (useSimpleSchema) executeLocal(arguments, context) else executeCloud(arguments, context)
  }

  private fun executeLocal(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val filePath: String = arguments["path"] as? String ?: ""
    val oldString: String = arguments["oldString"] as? String ?: ""
    val newString: String = arguments["newString"] as? String ?: ""
    val projectRoot: String = context.projectRoot

    if (filePath.isBlank())
      return makeFailure(
        code = ErrorCode.INVALID_PARAMETER,
        message = buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Missing 'path' parameter in edit_file call.",
          fixHint = "The 'path' parameter is required. Example: 'path': 'src/main.kt'"
        )
      )

    if (oldString.isBlank())
      return makeFailure(
        code = ErrorCode.INVALID_PARAMETER,
        message = buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Missing 'oldString' parameter.",
          fixHint = "Provide the text to find in the 'oldString' parameter."
        )
      )

    val resolved: ResolvedProjectPath = resolveProjectPath(filePath, projectRoot)
    val resolvedPath: Path = resolved.resolved
    if (resolved.rejectionReason != null) {
      return makeFailure(
        code = ErrorCode.PERMISSION_DENIED,
        message = buildXmlError(
          code = "PERMISSION_DENIED",
          message = "Path is outside the project root: ${resolved.rejectionReason}",
          fixHint = "Use a path relative to the project root."
        ),
        context = mapOf("path" to filePath)
      )
    }
    val targetFile: File = resolvedPath.toFile()

    return try {
      val originalBytes: ByteArray = targetFile.readBytes()
      val decodeError: String? = validateUtf8(originalBytes)
      if (decodeError != null) {
        return makeFailure(
          code = ErrorCode.IO_ERROR,
          message = buildXmlError(
            code = "NON_UTF8_FILE",
            message = decodeError,
            fixHint = "Gradum can only safely edit UTF-8 text files. Convert the file to UTF-8 first."
          ),
          context = mapOf("path" to resolvedPath.toString())
        )
      }
      val originalContent = String(originalBytes, Charsets.UTF_8)
      val singleEdit = listOf(EditOperation(oldString, newString, 0))

      applySequentialEdits(resolvedPath, originalContent, originalBytes, singleEdit)
    } catch (_: FileNotFoundException) {
      makeFailure(
        code = ErrorCode.FILE_NOT_FOUND,
        message = buildXmlError(
          code = "FILE_NOT_FOUND",
          message = "Edit File not found: $filePath",
          fixHint = "Check the current file path. you could run_cmd to find the correct path."
        ), context = mapOf("path" to resolvedPath.toString())
      )
    } catch (editException: Exception) {
      makeFailure(
        code = ErrorCode.IO_ERROR,
        message = buildXmlError(
          code = "IO_ERROR",
          message = editException.message ?: "Unknown error during edit.",
          fixHint = "Check file permissions and disk space. Stop editing."
        ), context = mapOf("path" to resolvedPath.toString())
      )
    }
  }

  private fun executeCloud(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val filePath: String = arguments["path"] as? String ?: ""
    val rawEdits: List<Map<String, Any>> =
      try {
        parseEdits(rawInput = arguments["edits"])
      } catch (parseException: Exception) {
        val errorMessage = parseException.message ?: "unknown parse error"
        return makeFailure(
          code = ErrorCode.INVALID_PARAMETER,
          message = buildXmlError(
            code = "INVALID_PARAMETER",
            message = "Failed to parse 'edits' parameter: $errorMessage",
            fixHint = "Provide 'edits' as a JSON array of {oldString, newString} objects, or as a JSON-encoded string of the same shape."
          ),
          context = mapOf("path" to filePath)
        )
      }
    val projectRoot: String = context.projectRoot

    if (filePath.isBlank())
      return makeFailure(
        code = ErrorCode.INVALID_PARAMETER,
        message = buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Missing 'path' parameter.",
          fixHint = "Provide a valid file path in the 'path' parameter."
        )
      )

    if (rawEdits.isEmpty())
      return makeFailure(
        code = ErrorCode.INVALID_PARAMETER,
        message = buildXmlError(
          code = "INVALID_PARAMETER",
          message = "No edits provided.",
          fixHint = "Provide at least one edit object with 'oldString' and 'newString' fields."
        )
      )

    val resolved: ResolvedProjectPath = resolveProjectPath(filePath, projectRoot)
    val resolvedPath: Path = resolved.resolved
    if (resolved.rejectionReason != null) {
      return makeFailure(
        code = ErrorCode.PERMISSION_DENIED,
        message = buildXmlError(
          code = "PERMISSION_DENIED",
          message = "Path is outside the project root: ${resolved.rejectionReason}",
          fixHint = "Use a path relative to the project root."
        ),
        context = mapOf("path" to filePath)
      )
    }
    val targetFile: File = resolvedPath.toFile()

    return try {
      val originalBytes: ByteArray = targetFile.readBytes()
      val decodeError: String? = validateUtf8(originalBytes)
      if (decodeError != null) {
        return makeFailure(
          code = ErrorCode.IO_ERROR,
          message = buildXmlError(
            code = "NON_UTF8_FILE",
            message = decodeError,
            fixHint = "Gradum can only safely edit UTF-8 text files. Convert the file to UTF-8 first."
          ), context = mapOf("path" to resolvedPath.toString())
        )
      }
      val originalContent = String(originalBytes, Charsets.UTF_8)
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
          code = ErrorCode.INVALID_PARAMETER,
          message = buildXmlError(
            code = "INVALID_PARAMETER",
            message = "Edit ${invalidEdit.editIndex + 1} has empty 'search' text.",
            fixHint = "Provide non-empty oldString text that matches the target code block in the file."
          )
        )
      }
      applySequentialEdits(resolvedPath, originalContent, originalBytes, editOperations = parsedEdits)
    } catch (_: FileNotFoundException) {
      makeFailure(
        code = ErrorCode.FILE_NOT_FOUND,
        message = buildXmlError(
          code = "FILE_NOT_FOUND",
          message = "File not found: $filePath",
          fixHint = "Check the file path. Use run_cmd tool to find the correct path."
        ), context = mapOf("path" to resolvedPath.toString())
      )
    } catch (editException: Exception) {
      makeFailure(
        code = ErrorCode.IO_ERROR,
        message = buildXmlError(
          code = "IO_ERROR",
          message = editException.message ?: "Unknown error during edit.",
          fixHint = "Check file permissions and disk space. Stop editing."
        ), context = mapOf("path" to resolvedPath.toString())
      )
    }
  }

  private fun applySequentialEdits(
    resolvedPath: Path, originalContent: String, originalBytes: ByteArray,
    editOperations: List<EditOperation>
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
            writeWithLock(
              fileMutation,
              resolvedPath,
              currentContent,
              expectedBytes = originalBytes,
              appliedCount = appliedEdits.size
            )
              ?.let { return it }

            return makeFailure(
              code = ErrorCode.MULTIPLE_MATCHES,
              message = buildXmlError(
                code = "MULTIPLE_MATCHES",
                message = "Edit ${editOperation.editIndex + 1} found ${matchResult.matches.size} matches. Cannot determine which to replace.",
                fixHint = "Add more surrounding context (function name, class declaration, comments) to make the match unique.",
                appliedCount = appliedEdits.size
              ),
              context = mapOf("path" to resolvedPath.toString(), "appliedCount" to appliedEdits.size)
            )
          }

          val bestMatch = matchResult.matches.first()
          val replaceLines =
            if (editOperation.replaceText.isBlank()) emptyList()
            else editOperation.replaceText.lines()

          linesRemoved += searchLines.size
          linesAdded += replaceLines.size

          val newFileLines = fileLines.subList(0, bestMatch.startIndex) +
            replaceLines + fileLines.subList(bestMatch.endIndex, fileLines.size)
          currentContent = newFileLines.joinToString(separator = "\n")
          appliedEdits.add(editOperation.editIndex)
        }

        is FindResult.NotFound -> {
          writeWithLock(
            fileMutation,
            resolvedPath,
            currentContent,
            expectedBytes = originalBytes,
            appliedCount = appliedEdits.size
          )?.let { return it }

          val failureStep = when (matchResult.failedAtStep) {
            MatchStrategy.NONE -> "matching"
            MatchStrategy.EXACT -> "exact match"
            MatchStrategy.STRIPPED -> "stripped match"
            MatchStrategy.NORMALIZED -> "normalized match"
          }
          return makeFailure(
            code = ErrorCode.CODE_NOT_FOUND,
            message = buildXmlError(
              code = "CODE_NOT_FOUND",
              message = "Edit ${editOperation.editIndex + 1} failed at step: $failureStep. oldString text not found in file.",
              fixHint = "Check for whitespace differences or add more surrounding context (function name, class declaration," +
                " comments) to make the match unique. If this fails after multiple attempts, inform the user and suggest manual editing.",
              appliedCount = appliedEdits.size,
              searchPreview = matchResult.searchPreview
            ),
            mapOf(
              "path" to resolvedPath.toString(),
              "appliedCount" to appliedEdits.size,
              "searchPreview" to matchResult.searchPreview,
              "failedAtStep" to matchResult.failedAtStep.name
            )
          )
        }
      }
    }

    if (currentContent.isBlank() && originalContent.isNotBlank()) {
      writeWithLock(fileMutation, resolvedPath, originalContent, expectedBytes = originalBytes)
        ?.let { return it }

      return makeFailure(
        code = ErrorCode.EMPTY_RESULT,
        message = buildXmlError(
          code = "EMPTY_RESULT",
          message = "Edit resulted in empty content.",
          fixHint = "The oldString block matched the entire file content. Add more context or check the file."
        ),
        context = mapOf("path" to resolvedPath.toString())
      )
    }

    val writeResult = runBlocking {
      fileMutation.writeTextPreservingBom(resolvedPath, currentContent, originalBytes)
    }

    if (writeResult.isFailure) {
      val error = writeResult.exceptionOrNull()
      return if (error is StaleContentError) {
        makeFailure(
          code = ErrorCode.CONCURRENT_MODIFICATION,
          message = buildXmlError(
            code = "CONCURRENT_MODIFICATION",
            message = "File was modified externally. Please read it again.",
            fixHint = "The file changed since you read it. Use read_file to get the latest content."
          )
        )
      } else {
        makeFailure(
          code = ErrorCode.IO_ERROR,
          message = buildXmlError(
            code = "IO_ERROR",
            message = error?.message ?: "Unknown error during write.",
            fixHint = "Check file permissions and disk space."
          )
        )
      }
    }

    return makeSuccess {
      string("path", resolvedPath.toString())
      integer("linesAdded", linesAdded)
      integer("linesRemoved", linesRemoved)
      string("modifiedContent", currentContent)
      integer("editsApplied", appliedEdits.size)
      integer("totalEdits", editOperations.size)
      string("originalContent", originalContent)
    }
  }

  private data class EditOperation(val searchText: String, val replaceText: String, val editIndex: Int)

  companion object {
    private val jsonParser = Json { ignoreUnknownKeys = true }

    @Suppress("UNCHECKED_CAST")
    fun parseEdits(rawInput: Any?): List<Map<String, Any>> {
      if (rawInput is List<*>) return rawInput.filterIsInstance<Map<String, Any>>()
      if (rawInput is String && rawInput.isNotBlank()) {
        try {
          val jsonElement = jsonParser.parseToJsonElement(string = rawInput)
          if (jsonElement is JsonArray) {
            return jsonElement.mapNotNull { jsonItem ->
              if (jsonItem is JsonObject) {
                val oldStringContent = (jsonItem["oldString"] as? JsonPrimitive)?.content ?: ""
                val newStringContent = (jsonItem["newString"] as? JsonPrimitive)?.content ?: ""
                mapOf("oldString" to oldStringContent, "newString" to newStringContent)
              } else null
            }
          }
        } catch (jsonParseException: Exception) {
          logger.debug("Failed to parse 'edits' argument: ${jsonParseException.message}", jsonParseException)
        }
      }
      return emptyList()
    }
  }
}

private enum class MatchStrategy { EXACT, NORMALIZED, STRIPPED, NONE }

private data class MatchResult(
  val startIndex: Int, val endIndex: Int, val strategy: MatchStrategy
)

private sealed class FindResult {
  data class Found(val matches: List<MatchResult>) : FindResult()
  data class NotFound(
    val failedAtStep: MatchStrategy, val searchPreview: List<String>
  ) : FindResult()
}

private fun findMatchesWithFallback(
  fileLines: List<String>, searchLines: List<String>
): FindResult {
  if (searchLines.isEmpty()) return FindResult.NotFound(
    failedAtStep = MatchStrategy.NONE, searchPreview = emptyList()
  )

  val searchNonBlank = searchLines.filter { it.isNotBlank() }
  if (searchNonBlank.isEmpty()) return FindResult.NotFound(
    failedAtStep = MatchStrategy.NONE, searchPreview = searchLines.take(n = 3)
  )

  val fileNonBlankCount = fileLines.count { it.isNotBlank() }
  if (searchNonBlank.size > fileNonBlankCount) return FindResult.NotFound(
    failedAtStep = MatchStrategy.NONE, searchPreview = searchNonBlank.take(n = 3)
  )

  val exactMatches = findMatchesByStrategy(
    fileLines, searchLines = searchNonBlank
  ) { fileLine, searchLine ->
    fileLine.trimEnd('\r', ' ') == searchLine.trimEnd('\r', ' ')
  }
  if (exactMatches.isNotEmpty()) return FindResult.Found(exactMatches)

  val normalizedMatches = findMatchesByStrategy(
    fileLines, searchLines = searchNonBlank
  ) { fileLine, searchLine ->
    fileLine.trim() == searchLine.trim()
  }
  if (normalizedMatches.isNotEmpty()) return FindResult.Found(normalizedMatches)

  val strippedMatches = findMatchesByStrategy(
    fileLines, searchLines = searchNonBlank
  ) { fileLine, searchLine ->
    fileLine.filterNot { it.isWhitespace() } == searchLine.filterNot { it.isWhitespace() }
  }
  if (strippedMatches.isNotEmpty()) return FindResult.Found(strippedMatches)

  return FindResult.NotFound(
    failedAtStep = MatchStrategy.STRIPPED,
    searchPreview = searchNonBlank.take(n = 3)
  )
}

/**
 * Find matches using a custom comparison function.
 *
 * Skips blank lines during matching so that different blank-line counts
 * between the file and search text do not cause false negatives.
 */
private fun findMatchesByStrategy(
  fileLines: List<String>, searchLines: List<String>, comparator: (String, String) -> Boolean
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
      matchResults.add(MatchResult(startIndex = startIdx, endIndex = fileIndex, strategy = MatchStrategy.EXACT))
  }
  return matchResults
}

/** Exception thrown when a file has been modified externally during an edit operation. */
class StaleContentError(val path: String) : Exception("File changed externally:$path")

/**
 * Returns an error message when [bytes] are NOT valid UTF-8, or `null`
 * when they are. Editing a non-UTF-8 file (GBK, UTF-16, Latin-1…) with
 * `readText(Charsets.UTF_8)` + `writeText` silently corrupts every
 * non-ASCII byte (malformed input decodes to U+FFFD, then gets written
 * back verbatim). Reject those files up front instead of mangling them.
 */
private fun validateUtf8(bytes: ByteArray): String? {
  return try {
    Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes))
    null
  } catch (_: CharacterCodingException) {
    "File contains non-UTF-8 bytes and cannot be safely edited (it would be corrupted by a read/write round-trip)."
  }
}

/** Thread-safe wrapper for atomic file mutations with per-path locking. */
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
      if (!targetFile.exists())
        return@withLock Result.failure(exception = StaleContentError(path.toString()))

      val current = targetFile.readBytes()
      if (!current.contentEquals(other = expected)) {
        return@withLock Result.failure(exception = StaleContentError(path.toString()))
      }
      targetFile.writeText(content, Charsets.UTF_8)
      Result.success(value = Unit)
    }
  }

  suspend fun writeTextPreservingBom(path: Path, content: String, expected: ByteArray?): Result<Unit> {
    return withLock(path) {
      val targetFile = path.toFile()
      val (cleanContent, newHasBom) = splitBom(text = content)
      val current = if (targetFile.exists()) targetFile.readBytes() else null

      if (current == null) return@withLock Result.failure(exception = StaleContentError(path.toString()))
      if (expected != null && !current.contentEquals(other = expected)) {
        return@withLock Result.failure(exception = StaleContentError(path.toString()))
      }
      val currentHasBom = hasUtf8Bom(bytes = current)
      val finalContent = joinBom(text = cleanContent, currentHasBom || newHasBom)

      targetFile.writeText(finalContent, Charsets.UTF_8)
      targetFile.setLastModified(System.currentTimeMillis())
      Result.success(value = Unit)
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
      val stripped = text.replace(Regex(pattern = "^\\uFEFF+"), replacement = "")
      return stripped to (stripped.length != text.length)
    }
  }
}

private fun writeWithLock(
  fileMutation: FileMutation, resolvedPath: Path, content: String,
  expectedBytes: ByteArray, appliedCount: Int = 0
): SkillResult? {
  val result = runBlocking {
    fileMutation.writeIfUnchanged(
      resolvedPath, content, expectedBytes
    )
  }
  if (result.isFailure && result.exceptionOrNull() is StaleContentError) {
    return makeFailure(
      code = ErrorCode.CONCURRENT_MODIFICATION,
      message = buildXmlError(
        code = "CONCURRENT_MODIFICATION",
        message = "File was modified externally. Please read it again.",
        fixHint = "The file changed since you read it. Use read_file to get the latest content.",
        appliedCount = appliedCount
      )
    )
  }
  return null
}
