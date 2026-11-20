package com.coach.desktop.books

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

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
