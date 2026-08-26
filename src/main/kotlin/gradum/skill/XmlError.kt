/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * XmlError.kt  2026-08-26 11:51:00 Changed by gwy
 */

package gradum.skill

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.*
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

private const val INDENT_AMOUNT = "2"
private const val XML_ENCODING = "UTF-8"
private const val INDENT_PROPERTY = "{http://xml.apache.org/xslt}indent-amount"

/**
 * Builds an XML-formatted error message for AI consumption.
 *
 * All AI-facing error messages MUST use this format.
 * XML tags use PascalCase naming convention.
 *
 * The payload is assembled through the [XmlBuilder] DSL, then serialized
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
  val builder = XmlBuilder()
  builder.error(
    code = code,
    message = message,
    fixHint = fixHint,
    appliedCount = appliedCount,
    searchPreview = searchPreview
  )
  return builder.toString()
}

/**
 * Minimal `element {}` / `text` DSL over the JDK DOM API. Nesting is
 * tracked by an active-element stack; [toString] serializes the whole tree
 * with uniform indentation and automatic escaping.
 */
internal class XmlBuilder {
  private val document: Document = newEmptyDocument()
  private val activeElements: ArrayDeque<Element> = ArrayDeque()
  fun element(tagName: String, init: XmlBuilder.() -> Unit) {
    val childElement: Element = document.createElement(tagName)
    val parentElement: Element? = activeElements.lastOrNull()
    if (parentElement != null) {
      parentElement.appendChild(childElement)
    } else {
      document.appendChild(childElement)
    }
    activeElements.addLast(childElement)
    init()
    activeElements.removeLast()
  }

  fun text(content: String) {
    activeElements.lastOrNull()?.appendChild(
      document.createTextNode(content)
    )
  }

  override fun toString(): String {
    return serializeXml(document)
  }
}

private fun XmlBuilder.error(
  code: String,
  message: String,
  fixHint: String,
  appliedCount: Int?,
  searchPreview: List<String>
) {
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

private fun newEmptyDocument(): Document {
  try {
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
  } catch (parserException: ParserConfigurationException) {
    throw IllegalStateException("Unable to create XML document builder", parserException)
  }
}

private fun serializeXml(document: Document): String {
  val writer = StringWriter()
  try {
    indentedTransformer().transform(
      DOMSource(document), StreamResult(writer)
    )
  } catch (transformerException: TransformerException) {
    throw IllegalStateException("Unable to serialize XML error message", transformerException)
  }
  return writer.toString().trimEnd()
}

private fun indentedTransformer(): Transformer {
  try {
    return TransformerFactory.newInstance().newTransformer().apply {
      setOutputProperty(OutputKeys.INDENT, "yes")
      setOutputProperty(INDENT_PROPERTY, INDENT_AMOUNT)
      setOutputProperty(OutputKeys.ENCODING, XML_ENCODING)
      setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
    }
  } catch (configurationException: TransformerConfigurationException) {
    throw IllegalStateException("Unable to create XML transformer", configurationException)
  }
}
