package com.coach.desktop.books

import android.graphics.Bitmap
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.ceil

internal object PdfWarmup {
    private val started = java.util.concurrent.atomic.AtomicBoolean()
    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            runCatching {
                BookPdfDocument().use { pdf ->
                    pdf.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())
                    BookPdfRenderer(pdf).renderImage(0, .1f).recycle()
                }
            }
        }, "coach-pdf-warmup").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
    }
}

internal fun importedPdfVisuals(pdf: BookPdfDocument, pages: List<BookPage>, control: BookImportControl): List<ExtractedIllustration> {
    val output = mutableListOf<ExtractedIllustration>()
    var bytes = 0L
    for (page in pages) {
        control.report(page.number, pages.size)
        for (image in PdfBookImages.extract(pdf, page, emptyList(), control)) {
            if (output.size >= FormatBookImages.MAX_IMAGES || bytes + image.bytes.size > FormatBookImages.MAX_TOTAL) return output
            output += image
            bytes += image.bytes.size
        }
    }
    return output
}

internal object BookSvgImages {
    fun raster(original: Element): ByteArray? {
        return try {
        if (original.localName != "svg") return null
        val root = original.cloneNode(true) as Element
        val children = root.getElementsByTagNameNS("*", "*")
        if (children.length >= 20_000) return null
        val unsafe = setOf("script", "style", "foreignObject", "image", "filter", "animate", "animateTransform", "set")
        val link = Regex("url\\(([^)]*)\\)", RegexOption.IGNORE_CASE)
        for (element in listOf(root) + (0 until children.length).map { children.item(it) as Element }) {
            if (element.localName in unsafe) return null
            for (i in 0 until element.attributes.length) {
                val attr = element.attributes.item(i)
                val value = attr.nodeValue
                if (attr.nodeName.startsWith("on", true) || (attr.localName == "href" && !value.startsWith('#')) ||
                    link.findAll(value).any { !it.groupValues[1].trim().trim('\'', '"').startsWith('#') }) return null
            }
        }
        root.setAttribute("xmlns", "http://www.w3.org/2000/svg")
        val serialized = ByteArrayOutputStream().use { buffer ->
            TransformerFactory.newInstance().newTransformer().transform(DOMSource(root), StreamResult(buffer))
            buffer.toString(Charsets.UTF_8.name())
        }
        val svg = SVG.getFromString(serialized)
        val width = svg.documentWidth.takeIf { it > 0 } ?: svg.documentViewBox?.width() ?: return null
        val height = svg.documentHeight.takeIf { it > 0 } ?: svg.documentViewBox?.height() ?: return null
        require(width.isFinite() && height.isFinite() && width in 1f..8000f && height in 1f..8000f)
        val scale = minOf(2f, 4000f / maxOf(width, height))
        val w = ceil(width * scale).toInt()
        val h = ceil(height * scale).toInt()
        BookScanImages.checkSize(w, h)
        svg.documentWidth = width
        svg.documentHeight = height
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image).apply { scale(scale, scale) }
            svg.renderToCanvas(canvas)
            bookBitmapCropPng(image, 0, 0, w, h)
        } finally { image.recycle() }
    } catch (cancel: java.util.concurrent.CancellationException) { throw cancel }
        catch (_: Exception) { null }
    }
}

internal object PptBookImages {
    fun render(zip: SafeBookArchive, pages: List<BookPage>, control: BookImportControl): List<ExtractedIllustration> {
        val relationships = StructuredBookExtraction.xml(zip.read("ppt/_rels/presentation.xml.rels"))
            .getElementsByTagNameNS("*", "Relationship")
        val slidesById = (0 until relationships.length).map { relationships.item(it) as Element }.filter {
            it.getAttribute("Type").endsWith("/slide") && it.getAttribute("TargetMode") != "External"
        }.associate { it.getAttribute("Id") to it.getAttribute("Target") }
        val slideIds = StructuredBookExtraction.xml(zip.read("ppt/presentation.xml")).getElementsByTagNameNS("*", "sldId")
        val images = mutableListOf<ExtractedIllustration>()
        var byteCount = 0L
        for (index in 0 until minOf(pages.size, slideIds.length)) {
            control.check()
            val slide = slideIds.item(index) as Element
            val id = slide.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            val target = slidesById[id] ?: continue
            val path = zip.resolve("ppt/presentation.xml", target)
            val rel = path.substringBeforeLast('/') + "/_rels/" + path.substringAfterLast('/') + ".rels"
            if (!zip.contains(rel)) continue
            val assets = StructuredBookExtraction.xml(zip.read(rel)).getElementsByTagNameNS("*", "Relationship")
            val imagePaths = (0 until assets.length).map { assets.item(it) as Element }.filter {
                it.getAttribute("Type").endsWith("/image") && it.getAttribute("TargetMode") != "External"
            }.associate { it.getAttribute("Id") to it.getAttribute("Target") }
            val references = StructuredBookExtraction.xml(zip.read(path)).getElementsByTagNameNS("*", "blip")
            for (i in 0 until references.length) {
                val node = references.item(i) as Element
                val embed = node.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed")
                val image = imagePaths[embed]?.let { zip.resolve(path, it) } ?: continue
                if (image.substringAfterLast('.').lowercase() !in setOf("png", "jpg", "jpeg", "gif")) continue
                val data = zip.read(image)
                if (images.size >= FormatBookImages.MAX_IMAGES || byteCount + data.size > FormatBookImages.MAX_TOTAL) return images
                images += FormatBookImages.image(data, pages[index].number, 0)
                byteCount += data.size
            }
        }
        return images
    }
}

private data class RtfGroup(val skip: Boolean = false, val fallback: Int = 1)

internal fun readBookRtf(path: Path): String {
    val text = Files.readAllBytes(path).toString(Charsets.ISO_8859_1)
    require(text.startsWith("{\\rtf")) { "这份 RTF 不完整。" }
    val skippedDestinations = setOf("fonttbl", "colortbl", "stylesheet", "pict", "object", "info", "header",
        "footer", "listtable", "listoverridetable", "generator", "datastore", "themedata")
    val groups = ArrayDeque<RtfGroup>()
    val output = StringBuilder()
    var group = RtfGroup()
    var charset = Charset.forName("windows-1252")
    var fallbackLeft = 0
    var i = 0
    fun append(value: String) {
        if (group.skip || value.isEmpty()) return
        val skip = fallbackLeft.coerceAtMost(value.length)
        fallbackLeft -= skip
        output.append(value, skip, value.length)
    }
    while (i < text.length) {
        when (val c = text[i++]) {
            '{' -> { require(groups.size < 512) { "RTF 嵌套层级过多。" }; groups.addLast(group) }
            '}' -> { group = groups.removeLastOrNull() ?: error("RTF 分组不完整") }
            '\r', '\n' -> Unit
            '\\' -> {
                if (i == text.length) break
                when (val control = text[i]) {
                    '{', '}', '\\' -> { i++; append(control.toString()) }
                    '*' -> { i++; group = group.copy(skip = true) }
                    '~' -> { i++; append("\u00a0") }
                    '\'' -> {
                        val bytes = ByteArrayOutputStream()
                        while (i + 2 < text.length && text[i] == '\'') {
                            val byte = text.substring(i + 1, i + 3).toIntOrNull(16) ?: break
                            bytes.write(byte); i += 3
                            if (i + 1 < text.length && text[i] == '\\' && text[i + 1] == '\'') i++ else break
                        }
                        if (bytes.size() == 0) i++ else append(bytes.toByteArray().toString(charset))
                    }
                    else -> {
                        if (control !in 'a'..'z') { i++; continue }
                        val start = i
                        while (i < text.length && text[i] in 'a'..'z') i++
                        val word = text.substring(start, i)
                        val parameterStart = i
                        if (i < text.length && text[i] == '-') i++
                        while (i < text.length && text[i].isDigit()) i++
                        val n = text.substring(parameterStart, i).toIntOrNull()
                        if (i < text.length && text[i] == ' ') i++
                        when (word) {
                            "bin" -> if (n != null && n >= 0) i += n.coerceAtMost(text.length - i)
                            in skippedDestinations -> group = group.copy(skip = true)
                            "ansicpg" -> if (n != null && !group.skip) charset = runCatching { Charset.forName("windows-$n") }.getOrDefault(charset)
                            "uc" -> if (n != null) group = group.copy(fallback = n.coerceIn(0, 32))
                            "u" -> if (n != null && !group.skip) { output.append((n and 65535).toChar()); fallbackLeft = group.fallback }
                            "par", "line" -> append("\n")
                            "tab" -> append("\t")
                            "emdash" -> append("—")
                            "endash" -> append("–")
                            "bullet" -> append("•")
                        }
                    }
                }
            }
            else -> append(c.toString())
        }
    }
    require(groups.isEmpty()) { "RTF 分组不完整。" }
    return output.toString()
}

internal fun bookWorkspaceThemeOverride(): Boolean? = null

