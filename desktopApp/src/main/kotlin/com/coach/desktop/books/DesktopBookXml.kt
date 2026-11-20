package com.coach.desktop.books

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

internal fun parseBookOfficeXml(bytes: ByteArray): Element {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false; isExpandEntityReferences = false
    }
    return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
}

/** Bounded streaming Office XML parser: O(depth) memory, rejects DTD/XXE, supports cancellation. */
internal fun streamBookOfficeXml(input: InputStream, control: BookImportControl, visitor: BookXmlVisitor) {
    val factory = XMLInputFactory.newInstance().apply {
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
        runCatching { setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        runCatching { setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
    }
    val reader = factory.createXMLStreamReader(input)
    try {
        var depth = 0
        var events = 0
        var chars = 0L
        val maxChars = 32L * 1024 * 1024
        while (reader.hasNext()) {
            if ((++events and 1023) == 0) control.check()
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    require(++depth <= 256) { "教材标记层级过多。" }
                    visitor.startElement(reader.localName) { local ->
                        var found: String? = null
                        for (i in 0 until reader.attributeCount) {
                            if (reader.getAttributeLocalName(i) == local) {
                                found = reader.getAttributeValue(i)
                                break
                            }
                        }
                        found
                    }
                }
                XMLStreamConstants.END_ELEMENT -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    visitor.endElement(reader.localName)
                }
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                    val value = reader.text.orEmpty()
                    if (value.isNotEmpty()) {
                        chars += value.length
                        require(chars <= maxChars) { "教材正文文字超过上限，请拆分后导入。" }
                        visitor.text(value)
                    }
                }
                XMLStreamConstants.DTD -> throw IOException("不支持自定义 DTD 或实体声明。")
                XMLStreamConstants.ENTITY_REFERENCE -> throw IOException("教材含有无法确认的 XML 实体。")
            }
        }
        control.check()
    } finally {
        runCatching { reader.close() }
    }
}
