package com.coach.desktop.books

import android.util.Xml
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

internal object EpubXml {
    fun parse(bytes: ByteArray): Element = parseLocalBookXml(bytes, xhtml = true)
}
internal fun parseBookOfficeXml(bytes: ByteArray): Element = parseLocalBookXml(bytes, xhtml = false)

/** Bounded streaming Office XML parser: O(depth) memory, rejects DTD/XXE, supports cancellation. */
internal fun streamBookOfficeXml(input: InputStream, control: BookImportControl, visitor: BookXmlVisitor) {
    val reader = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        setInput(input, null)
    }
    var events = 0
    var chars = 0L
    val maxChars = 32L * 1024 * 1024
    while (true) {
        if ((++events and 1023) == 0) control.check()
        when (reader.nextToken()) {
            XmlPullParser.START_TAG -> {
                require(reader.depth <= 256) { "教材标记层级过多。" }
                visitor.startElement(reader.name) { local ->
                    var found: String? = null
                    for (i in 0 until reader.attributeCount) {
                        if (reader.getAttributeName(i) == local) {
                            found = reader.getAttributeValue(i)
                            break
                        }
                    }
                    found
                }
            }
            XmlPullParser.END_TAG -> visitor.endElement(reader.name)
            XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                val value = reader.text.orEmpty()
                if (value.isNotEmpty()) {
                    chars += value.length
                    require(chars <= maxChars) { "教材正文文字超过上限，请拆分后导入。" }
                    visitor.text(value)
                }
            }
            XmlPullParser.ENTITY_REF -> {
                val value = reader.text ?: throw IOException("教材含有无法确认的 XML 实体。")
                chars += value.length
                require(chars <= maxChars) { "教材正文文字超过上限，请拆分后导入。" }
                visitor.text(value)
            }
            XmlPullParser.DOCDECL -> throw IOException("不支持自定义 DTD 或实体声明。")
            XmlPullParser.END_DOCUMENT -> break
        }
    }
    control.check()
}

private fun parseLocalBookXml(bytes: ByteArray, xhtml: Boolean): Element {
    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
    val reader = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        setInput(ByteArrayInputStream(bytes), null)
    }
    var parent: Node = doc
    var elements = 0
    var attributes = 0
    var chars = 0L
    val standard = Regex("""\s*html(?:\s+SYSTEM\s+["']about:legacy-compat["']|\s+PUBLIC\s+["']-//W3C//DTD XHTML (?:1\.0 (?:Strict|Transitional|Frameset)|1\.1)//EN["']\s+["']https?://www\.w3\.org/TR/(?:xhtml1/DTD/xhtml1-(?:strict|transitional|frameset)\.dtd|xhtml11/DTD/xhtml11\.dtd)["'])?\s*""")
    while (true) {
        when (reader.nextToken()) {
            XmlPullParser.START_TAG -> {
                require(++elements <= 200_000 && reader.depth <= 256) { "教材标记层级或元素过多。" }
                val prefix = reader.prefix?.let { "$it:" }.orEmpty()
                val node = doc.createElementNS(reader.namespace.orEmpty().takeIf(String::isNotEmpty), prefix + reader.name)
                attributes += reader.attributeCount
                require(attributes <= 200_000)
                for (index in 0 until reader.attributeCount) {
                    val name = reader.getAttributePrefix(index)?.let { "$it:" }.orEmpty() + reader.getAttributeName(index)
                    val value = reader.getAttributeValue(index)
                    chars += value.length
                    node.setAttributeNS(reader.getAttributeNamespace(index)?.takeIf(String::isNotEmpty), name, value)
                }
                parent.appendChild(node)
                parent = node
            }
            XmlPullParser.END_TAG -> parent = requireNotNull(parent.parentNode)
            XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> if (parent is Element) {
                val text = requireNotNull(reader.text) { "教材含有无法确认的 XML 实体。" }
                chars += text.length
                parent.appendChild(doc.createTextNode(text))
            }
            XmlPullParser.DOCDECL -> require(xhtml && standard.matches(reader.text.orEmpty())) {
                "不支持自定义 DTD 或实体声明。"
            }
            XmlPullParser.END_DOCUMENT -> break
        }
        require(chars <= 16 * 1024 * 1024) { "教材标记内容过大。" }
    }
    return requireNotNull(doc.documentElement) { "教材缺少正文。" }
}
