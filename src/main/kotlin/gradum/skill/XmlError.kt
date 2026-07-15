/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * XmlError.kt  2026-07-14 21:27:12 Changed by gwy
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
  searchPreview: List<String> = emptyList(),
  appliedCount: Int? = null
): String {
  val xmlBuilder = StringBuilder()
  xmlBuilder.appendLine("<Error>")
  xmlBuilder.appendLine("  <Code>$code</Code>")
  xmlBuilder.appendLine("  <Message>$message</Message>")
  if (searchPreview.isNotEmpty()) {
    xmlBuilder.appendLine("  <SearchPreview>")
    searchPreview.forEach { previewLine ->
      xmlBuilder.appendLine("    <Line>${previewLine.trimEnd()}</Line>")
    }
    xmlBuilder.appendLine("  </SearchPreview>")
  }
  if (appliedCount != null && appliedCount > 0) {
    xmlBuilder.appendLine("  <Partial>$appliedCount edit(s) applied before failure.</Partial>")
  }
  xmlBuilder.appendLine("  <FixHint>$fixHint</FixHint>")
  xmlBuilder.appendLine("</Error>")
  return xmlBuilder.toString().trimEnd()
}
