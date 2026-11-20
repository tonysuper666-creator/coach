package com.coach.desktop.books

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal data class BookXmlName(val namespace: String, val localName: String, val prefix: String)
internal data class BookXmlAttribute(val name: BookXmlName, val value: String)
internal sealed interface BookXmlContent
internal data class BookXmlText(val value: String) : BookXmlContent
internal data class BookXmlElement(
    val name: BookXmlName, val attributes: List<BookXmlAttribute>, val content: List<BookXmlContent>,
) : BookXmlContent {
    val children: List<BookXmlElement> = content.filterIsInstance<BookXmlElement>()
}

/** Inert XML data from one digest-checked archive member. Keeps mixed text, namespaces,
 * attributes and ancestor context; does not execute markup, resolve links, compute styles,
 * render formulas or grant permission to replace canonical text. Comments/PIs are omitted.
 */
internal class BookNativeDocument private constructor(
    val format: String, val member: String, val memberSha256: String, val root: BookXmlElement,
    val sources: List<BookNativeElement>, private val resolved: Map<BookNativeElement, BookXmlElement>,
) {
    /** Repeated EPUB spine occurrences resolve to the same XML node with separate page ranges. */
    fun resolve(source: BookNativeElement): BookXmlElement? = resolved[source]

    companion object {
        private const val MAX_NODES = 200_000
        private const val MAX_ATTRIBUTES = 200_000
        private const val MAX_CHARACTERS = 16 * 1024 * 1024

        fun read(path: Path, index: BookNativeSources, member: String, control: BookImportControl): BookNativeDocument? {
            control.check()
            return try {
                require(index.format in setOf("epub", "docx"))
                val sources = index.elements.filter { it.member == member }
                require(sources.isNotEmpty())
                val digest = sources.first().memberSha256
                require(sources.all { it.memberSha256 == digest })
                val bytes = SafeBookArchive(path).use { it.read(member) }
                control.check()
                // Check raw bytes before allowing any node, attribute or text to escape.
                require(BookIllustrationAssets.sha256(bytes) == digest)
                val dom = if (index.format == "epub") EpubXml.parse(bytes) else StructuredBookExtraction.xml(bytes)
                var nodes = 0
                var attributes = 0
                var characters = 0L
                fun counted(value: String): String {
                    characters += value.length
                    require(characters <= MAX_CHARACTERS)
                    return value
                }
                fun name(node: Node) = BookXmlName(counted(node.namespaceURI.orEmpty()),
                    counted(node.localName ?: node.nodeName), counted(node.prefix.orEmpty()))
                fun copy(element: Element, depth: Int): BookXmlElement {
                    control.check()
                    require(depth <= 256 && ++nodes <= MAX_NODES)
                    attributes += element.attributes.length
                    require(attributes <= MAX_ATTRIBUTES)
                    val attrs = (0 until element.attributes.length).map { i ->
                        val attr = element.attributes.item(i)
                        BookXmlAttribute(name(attr), counted(attr.nodeValue))
                    }
                    val content = buildList {
                        for (i in 0 until element.childNodes.length) {
                            control.check()
                            val child = element.childNodes.item(i)
                            when (child.nodeType) {
                                Node.ELEMENT_NODE -> add(copy(child as Element, depth + 1))
                                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                                    require(++nodes <= MAX_NODES)
                                    add(BookXmlText(counted(child.nodeValue)))
                                }
                                Node.COMMENT_NODE, Node.PROCESSING_INSTRUCTION_NODE -> Unit
                                else -> error("Unsupported XML content")
                            }
                        }
                    }
                    return BookXmlElement(name(element), attrs, content)
                }
                val root = copy(dom, 0)
                val resolved = sources.associateWith { source ->
                    control.check()
                    var node = root
                    require(source.nodePath.size <= 256)
                    for (position in source.nodePath) node = node.children.getOrNull(position)
                        ?: error("Native source path no longer exists")
                    require(node.name.namespace == source.namespace && node.name.localName == source.localName)
                    node
                }
                control.check()
                BookNativeDocument(index.format, member, digest, root, sources, resolved)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { control.check(); null }
        }
    }
}
