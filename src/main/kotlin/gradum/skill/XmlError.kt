/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * XmlError.kt  2026-08-25 17:11:13 Changed by gwy
 */

package gradum.skill

/**
 * Builds an XML-formatted error message for AI consumption.
 *
 * All AI-facing error messages MUST use this format.
 * XML tags use PascalCase naming convention.
 *
 * Example output:
 * ```xml
 * <Error>
 *   <Code>INVALID_PARAMETER</Code>
 *   <Message>Missing 'path' parameter.</Message>
 *   <FixHint>Provide a valid file path in the 'path' parameter.</FixHint>
 * </Error>
 * ```
 */
fun buildXmlError(
  code: String,
  message: String,
  fixHint: String,
  appliedCount: Int? = null,
  searchPreview: List<String> = emptyList()
): String {
  val xmlBuilder = StringBuilder()
  xmlBuilder.appendLine(value = "<Error>")
  xmlBuilder.appendLine(value = "  <Code>$code</Code>")
  xmlBuilder.appendLine(value = "  <Message>$message</Message>")
  if (searchPreview.isNotEmpty()) {
    xmlBuilder.appendLine(value = "  <SearchPreview>")
    searchPreview.forEach { previewLine ->
      xmlBuilder.appendLine(value = "    <Line>${previewLine.trimEnd()}</Line>")
    }
    xmlBuilder.appendLine(value = "  </SearchPreview>")
  }
  if (appliedCount != null && appliedCount > 0) {
    xmlBuilder.appendLine(value = "  <Partial>$appliedCount edit(s) applied before failure.</Partial>")
  }
  xmlBuilder.appendLine(value = "  <FixHint>$fixHint</FixHint>")
  xmlBuilder.appendLine(value = "</Error>")
  return xmlBuilder.toString().trimEnd()
}
