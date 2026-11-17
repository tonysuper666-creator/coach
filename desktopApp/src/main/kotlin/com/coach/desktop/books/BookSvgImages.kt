package com.coach.desktop.books

import org.jetbrains.skia.Data
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.w3c.dom.Element
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.PathIterator
import java.io.ByteArrayOutputStream
import javax.xml.XMLConstants
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.ceil

/** Skia's SVGDOM has no usable font manager here. Outline simple SVG text first;
 * refuse unsupported text/layout instead of persisting apparently successful, missing labels.
 * No external resources, scripts, CSS sheets, filters or foreign objects enter the renderer.
 */
internal object BookSvgImages {
    fun raster(original: Element): ByteArray? = try {
        supported(original)
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun supported(original: Element): ByteArray? {
        if (original.localName != "svg") return null
        val root = original.cloneNode(true) as Element
        val elements = root.getElementsByTagNameNS("*", "*").let { nodes ->
            listOf(root) + (0 until nodes.length).map { nodes.item(it) as Element }
        }
        if (elements.size > 20_000) return null
        for (node in elements) {
            if (node.localName in setOf("script", "style", "foreignObject", "image", "filter", "animate", "animateTransform", "set")) return null
            for (i in 0 until node.attributes.length) {
                val attr = node.attributes.item(i)
                if (attr.nodeName.startsWith("on", true) ||
                    (attr.localName == "href" && !attr.nodeValue.startsWith('#')) ||
                    Regex("url\\(([^)]*)\\)", RegexOption.IGNORE_CASE).findAll(attr.nodeValue).any {
                        !it.groupValues[1].trim().trim('\'', '"').startsWith('#')
                    }) return null
            }
        }
        // This subset has no tspan positioning, text paths, length adjustment or CSS rules.
        if (elements.any { it.localName in setOf("tspan", "textPath") }) return null
        for (text in elements.filter { it.localName == "text" }) {
            if (!outline(text)) return null
        }
        val view = root.getAttribute("viewBox").trim().split(Regex("[ ,]+")).mapNotNull(String::toDoubleOrNull)
        val width = length(root.getAttribute("width")) ?: view.takeIf { it.size == 4 }?.get(2) ?: return null
        val height = length(root.getAttribute("height")) ?: view.takeIf { it.size == 4 }?.get(3) ?: return null
        if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 || width > 8000 || height > 8000) return null
        val scale = minOf(2.0, 4000 / maxOf(width, height))
        val w = ceil(width * scale).toInt()
        val h = ceil(height * scale).toInt()
        BookScanImages.checkSize(w, h)
        root.setAttribute("xmlns", "http://www.w3.org/2000/svg")
        root.setAttribute("width", width.toString())
        root.setAttribute("height", height.toString())
        val output = ByteArrayOutputStream()
        TransformerFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        }.newTransformer().transform(DOMSource(root), StreamResult(output))
        return Data.makeFromBytes(output.toByteArray()).use { data ->
            SVGDOM(data).use { svg ->
                Surface.makeRasterN32Premul(w, h).use { surface ->
                    surface.canvas.clear(0)
                    surface.canvas.scale(scale.toFloat(), scale.toFloat())
                    svg.setContainerSize(width.toFloat(), height.toFloat())
                    svg.render(surface.canvas)
                    surface.makeImageSnapshot().use { image -> image.encodeToData()?.use { it.bytes } }
                }
            }
        }
    }

    private fun style(node: Element, key: String, default: String): String {
        val inline = node.getAttribute("style").split(';').map { it.split(':', limit = 2) }
            .firstOrNull { it.size == 2 && it[0].trim() == key }?.get(1)?.trim()
        return inline ?: node.getAttribute(key).takeIf { it.isNotBlank() }
            ?: (node.parentNode as? Element)?.let { style(it, key, default) } ?: default
    }

    private fun outline(node: Element): Boolean {
        if ((0 until node.childNodes.length).any { node.childNodes.item(it) is Element } ||
            listOf("dx", "dy", "rotate", "textLength", "lengthAdjust").any { node.hasAttribute(it) } ||
            style(node, "dominant-baseline", "auto") !in setOf("auto", "alphabetic") ||
            style(node, "baseline-shift", "baseline") !in setOf("baseline", "0") ||
            style(node, "writing-mode", "horizontal-tb") !in setOf("horizontal-tb", "lr-tb") ||
            style(node, "letter-spacing", "normal") !in setOf("normal", "0") ||
            style(node, "word-spacing", "normal") !in setOf("normal", "0")) return false
        val x = length(node.getAttribute("x").ifEmpty { "0" }) ?: return false
        val y = length(node.getAttribute("y").ifEmpty { "0" }) ?: return false
        val size = length(style(node, "font-size", "16")) ?: return false
        if (size <= 0 || size > 1000) return false
        val content = if (style(node, "white-space", "normal") == "pre" ||
            node.getAttributeNS(XMLConstants.XML_NS_URI, "space") == "preserve") node.textContent
            else node.textContent.replace(Regex("\\s+"), " ").trim()
        if (content.length > 4096) return false
        val group = node.ownerDocument.createElementNS("http://www.w3.org/2000/svg", "g")
        for (i in 0 until node.attributes.length) {
            val attribute = node.attributes.item(i)
            if (attribute.nodeName !in setOf("x", "y")) group.setAttributeNS(attribute.namespaceURI, attribute.nodeName, attribute.nodeValue)
        }
        if (content.isNotEmpty()) {
            val family = style(node, "font-family", "Serif").substringBefore(',').trim(' ', '\'', '"').let {
                when (it) { "serif" -> "Serif"; "sans-serif" -> "SansSerif"; "monospace" -> "Monospaced"; else -> it }
            }
            val weight = style(node, "font-weight", "normal")
            val bold = weight == "bold" || (weight.toIntOrNull() ?: 400) >= 600
            val italic = style(node, "font-style", "normal") in setOf("italic", "oblique")
            val font = Font(family, (if (bold) Font.BOLD else 0) or (if (italic) Font.ITALIC else 0), 1).deriveFont(size.toFloat())
            if (font.canDisplayUpTo(content) != -1) return false
            val layout = TextLayout(content, font, FontRenderContext(AffineTransform(), true, true))
            val shift = when (style(node, "text-anchor", "start")) {
                "start" -> 0.0; "middle" -> -layout.advance / 2.0; "end" -> -layout.advance.toDouble(); else -> return false
            }
            val path = layout.getOutline(AffineTransform.getTranslateInstance(x + shift, y)).getPathIterator(null)
            val coords = DoubleArray(6)
            val d = StringBuilder()
            while (!path.isDone) {
                val segment = path.currentSegment(coords)
                val command = when (segment) {
                    PathIterator.SEG_MOVETO -> "M" to 2; PathIterator.SEG_LINETO -> "L" to 2
                    PathIterator.SEG_QUADTO -> "Q" to 4; PathIterator.SEG_CUBICTO -> "C" to 6
                    else -> "Z" to 0
                }
                d.append(command.first)
                repeat(command.second) { d.append(coords[it]).append(' ') }
                if (d.length > 1024 * 1024) return false
                path.next()
            }
            val shape = node.ownerDocument.createElementNS("http://www.w3.org/2000/svg", "path")
            shape.setAttribute("d", d.toString())
            group.appendChild(shape)
        }
        node.parentNode.replaceChild(group, node)
        return true
    }

    private fun length(value: String): Double? {
        val text = value.trim()
        val number = text.removeSuffix("px").removeSuffix("pt").toDoubleOrNull() ?: return null
        return (if (text.endsWith("pt")) number * 96 / 72 else number).takeIf { it.isFinite() }
    }
}
