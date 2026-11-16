package com.coach.desktop.books

import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory

/** EPUB XHTML may name a standard DTD, but never reads one or accepts DTD declarations.
 * SAX validates the original bytes first (including unused entity declarations); DOM then
 * reads those same bytes with external access disabled. Neither pass substitutes or strips
 * source bytes, so member hashes, element paths and canonical text keep their meaning.
 */
internal object EpubXml {
    private val standardDtds = mapOf(
        "-//W3C//DTD XHTML 1.0 Strict//EN" to "xhtml1/DTD/xhtml1-strict.dtd",
        "-//W3C//DTD XHTML 1.0 Transitional//EN" to "xhtml1/DTD/xhtml1-transitional.dtd",
        "-//W3C//DTD XHTML 1.0 Frameset//EN" to "xhtml1/DTD/xhtml1-frameset.dtd",
        "-//W3C//DTD XHTML 1.1//EN" to "xhtml11/DTD/xhtml11.dtd",
    )
    private val features = mapOf(
        XMLConstants.FEATURE_SECURE_PROCESSING to true,
        "http://apache.org/xml/features/disallow-doctype-decl" to false,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    )

    fun parse(bytes: ByteArray): Element {
        val guard = object : DefaultHandler2() {
            override fun startDTD(name: String?, publicId: String?, systemId: String?) {
                val html5 = publicId == null && (systemId == null || systemId == "about:legacy-compat")
                val standard = standardDtds[publicId]?.let { suffix ->
                    systemId == "http://www.w3.org/TR/$suffix" || systemId == "https://www.w3.org/TR/$suffix"
                } == true
                if (name != "html" || !(html5 || standard)) reject()
            }
            override fun internalEntityDecl(name: String?, value: String?) = reject()
            override fun externalEntityDecl(name: String?, publicId: String?, systemId: String?) = reject()
            override fun unparsedEntityDecl(name: String?, publicId: String?, systemId: String?, notationName: String?) = reject()
            override fun notationDecl(name: String?, publicId: String?, systemId: String?) = reject()
            override fun elementDecl(name: String?, model: String?) = reject()
            override fun attributeDecl(eName: String?, aName: String?, type: String?, mode: String?, value: String?) = reject()
            override fun skippedEntity(name: String?) = reject()
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = reject()
            override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource = reject()
            override fun error(e: SAXParseException): Unit = throw e
            override fun fatalError(e: SAXParseException): Unit = throw e
            private fun reject(): Nothing = throw SAXException("EPUB 不支持自定义 DTD 或实体声明。")
        }
        val reader = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            features.forEach { (name, value) -> setFeature(name, value) }
        }.newSAXParser().xmlReader
        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", guard)
        reader.setProperty("http://xml.org/sax/properties/declaration-handler", guard)
        reader.entityResolver = guard
        reader.errorHandler = guard
        reader.contentHandler = guard
        reader.dtdHandler = guard
        reader.parse(InputSource(ByteArrayInputStream(bytes)))

        val builder = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            features.forEach { (name, value) -> setFeature(name, value) }
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }.newDocumentBuilder()
        builder.setEntityResolver(guard)
        builder.setErrorHandler(guard)
        val doc = builder.parse(ByteArrayInputStream(bytes))
        require(doc.doctype == null || doc.documentElement.tagName == "html") { "EPUB 的声明与正文根元素不符。" }
        return doc.documentElement
    }
}
