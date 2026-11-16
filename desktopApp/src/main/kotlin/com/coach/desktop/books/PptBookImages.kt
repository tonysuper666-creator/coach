package com.coach.desktop.books

import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.sl.draw.Drawable
import java.awt.Color
import java.awt.geom.Rectangle2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import javax.xml.XMLConstants
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Render the slide, including text, shapes and pictures, before its unchanged extracted text. */
internal object PptBookImages {
    fun render(zip: SafeBookArchive, pages: List<BookPage>, control: BookImportControl): List<ExtractedIllustration> {
        require(pages.size <= 200) { "幻灯片请控制在 200 页以内，较长的材料请分册导入。" }
        control.check()
        // POI receives only bounded internal parts, with all external relationships removed.
        val safe = ByteArrayOutputStream()
        val unpositionedText = mutableMapOf<String, String>()
        ZipOutputStream(safe).use { output ->
            for (name in zip.names()) {
                control.check()
                var bytes = zip.read(name)
                if (name.endsWith(".xml") || name.endsWith(".rels")) {
                    val root = StructuredBookExtraction.xml(bytes)
                    // Text-only exporters sometimes omit every shape property and layout. Preserve
                    // their import support, but label the reconstructed page rather than imply
                    // that its original (unavailable) layout was retained.
                    if (name.matches(Regex("ppt/slides/slide[0-9]+[.]xml"))) {
                        val trees = root.getElementsByTagNameNS("*", "spTree")
                        val tree = trees.item(0)
                        val shapes = tree?.childNodes?.let { nodes -> (0 until nodes.length).map { nodes.item(it) }
                            .filterIsInstance<org.w3c.dom.Element>() }.orEmpty()
                        if (shapes.isNotEmpty() && shapes.all { shape ->
                            shape.localName == "sp" && shape.getElementsByTagNameNS("*", "spPr").length == 0 &&
                                shape.getElementsByTagNameNS("*", "nvSpPr").length == 0
                        }) {
                            val paragraphs = root.getElementsByTagNameNS("*", "p")
                            unpositionedText[name] = (0 until paragraphs.length).joinToString("\n") { i ->
                                val runs = (paragraphs.item(i) as org.w3c.dom.Element).getElementsByTagNameNS("*", "t")
                                (0 until runs.length).joinToString("") { runs.item(it).textContent }
                            }
                        }
                    }
                    if (name.endsWith(".rels")) {
                        val relationships = root.getElementsByTagNameNS("*", "Relationship")
                        for (index in relationships.length - 1 downTo 0) {
                            val node = relationships.item(index) as org.w3c.dom.Element
                            if (node.getAttribute("TargetMode").equals("External", ignoreCase = true)) node.parentNode.removeChild(node)
                        }
                    }
                    if (name == "ppt/presentation.xml" && root.getElementsByTagNameNS("*", "sldSz").length == 0) {
                        root.appendChild(root.ownerDocument.createElementNS(root.namespaceURI, "p:sldSz").apply {
                            setAttribute("cx", "9144000"); setAttribute("cy", "6858000")
                        })
                    }
                    // Some exporters omit individual slide overrides. These are still readable OOXML parts.
                    if (name == "[Content_Types].xml") {
                        for (part in zip.names().filter { it.matches(Regex("ppt/(slides/slide|notesSlides/notesSlide)[0-9]+[.]xml")) }) {
                            val overrides = root.getElementsByTagNameNS("*", "Override")
                            val present = (0 until overrides.length).any {
                                (overrides.item(it) as org.w3c.dom.Element).getAttribute("PartName") == "/$part"
                            }
                            if (!present) root.appendChild(root.ownerDocument.createElementNS(root.namespaceURI, "Override").apply {
                                setAttribute("PartName", "/$part")
                                setAttribute("ContentType", "application/vnd.openxmlformats-officedocument.presentationml.${if ("notesSlides" in part) "notesSlide" else "slide"}+xml")
                            })
                        }
                    }
                    val factory = TransformerFactory.newInstance().apply {
                        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                        setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
                    }
                    bytes = ByteArrayOutputStream().also { factory.newTransformer().transform(DOMSource(root), StreamResult(it)) }.toByteArray()
                } else if (name.startsWith("ppt/media/")) {
                    // Check dimensions before POI's renderer decodes any raster image.
                    FormatBookImages.image(bytes, 1, 0)
                }
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return XMLSlideShow(ByteArrayInputStream(safe.toByteArray())).use { show ->
            require(show.slides.size == pages.size) { "幻灯片的画面顺序不完整，请重新保存。" }
            val size = show.pageSize
            require(size.width > 0 && size.height > 0) { "幻灯片的页面尺寸不完整。" }
            val scale = minOf(2.0, 1600.0 / maxOf(size.width, size.height))
            val width = (size.width * scale).toInt().coerceAtLeast(1)
            val height = (size.height * scale).toInt().coerceAtLeast(1)
            var total = 0L
            show.slides.mapIndexed { index, slide ->
                control.check()
                control.report(index + 1, pages.size)
                val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                val graphics = canvas.createGraphics()
                try {
                    graphics.color = Color.WHITE
                    graphics.fillRect(0, 0, width, height)
                    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    // POI otherwise reuses the original Latin face for missing CJK glyphs.
                    // Java's logical font delegates to installed Chinese/emoji fonts.
                    graphics.setRenderingHint(Drawable.FONT_FALLBACK, mapOf("*" to java.awt.Font.SANS_SERIF))
                    graphics.scale(scale, scale)
                    val text = unpositionedText[slide.packagePart.partName.name.removePrefix("/")]
                    if (text == null) slide.draw(graphics) else XMLSlideShow().use { reconstructed ->
                        reconstructed.pageSize = size
                        reconstructed.createSlide().createTextBox().apply {
                            anchor = Rectangle2D.Double(24.0, 24.0, size.width - 48.0, size.height - 48.0)
                            this.text = text
                        }
                        reconstructed.slides.single().draw(graphics)
                    }
                    control.check()
                    val bytes = ByteArrayOutputStream().also { ImageIO.write(canvas, "png", it) }.toByteArray()
                    total += bytes.size
                    require(total <= FormatBookImages.MAX_TOTAL) { "幻灯片画面超过 64 MB，请分册导入。" }
                    FormatBookImages.image(bytes, pages[index].number, 0, "第 ${index + 1} 张幻灯片" +
                            if (text == null) "" else "（原文件缺少版式，按文字排版）")
                } finally { graphics.dispose(); canvas.flush() }
            }
        }
    }
}
