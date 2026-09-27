/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * XmlError.kt  2026-09-27 Changed by gwy
 */

package gradum.skill

import gradum.skill.dsl.XmlBuilder
import gradum.skill.dsl.buildXml

/**
 * Builds an XML-formatted error message for AI consumption.
 *
 * All AI-facing error messages MUST use this format.
 * XML tags use PascalCase naming convention.
 *
 * The payload is assembled through the [buildXml] DSL, then serialized
 * with an indenting transformer so every text node is escaped automatically
 * (no handwritten mixed-indent concatenation).
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
  return buildXml {
    element(tagName = "Error") {
      element(tagName = "Code") { text(content = code) }
      element(tagName = "Message") { text(content = message) }
      searchPreviewElement(searchPreview)
      if (appliedCount != null && appliedCount > 0) {
        element(tagName = "Partial") {
          text(content = "$appliedCount edit(s) applied before failure.")
        }
      }
      element(tagName = "FixHint") { text(content = fixHint) }
    }
  }
}

private fun XmlBuilder.searchPreviewElement(searchPreview: List<String>) {
  if (searchPreview.isEmpty()) return
  element(tagName = "SearchPreview") {
    searchPreview.forEach { previewLine ->
      element(tagName = "Line") {
        text(content = previewLine.trimEnd())
      }
    }
  }
}
