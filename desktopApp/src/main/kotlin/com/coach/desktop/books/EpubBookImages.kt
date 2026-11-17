package com.coach.desktop.books

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.net.URI
import java.util.zip.ZipFile

/** Resource locations are captured in the canonical traversal, before clean/trim/paginate. */
internal class EpubBookImages(private val zip: ZipFile, private val control: BookImportControl) {
    private data class Located(val offset: Int, val image: Element)
    private val located = mutableListOf<Located>()
    private var count = 0
    private var total = 0L

    fun record(node: Element, offset: Int) {
        val name = node.localName?.lowercase()
        if (name != "img" && name != "svg") return
        var parent = node.parentNode
        while (parent is Element) {
            if (parent.localName?.lowercase() == "svg") return
            parent = parent.parentNode
        }
        require(located.size < FormatBookImages.MAX_IMAGES) { "EPUB 插图过多，请分册导入。" }
        located += Located(offset, node)
    }

    fun finish(raw: String, pages: List<BookPage>, member: String, base: Int): List<ExtractedIllustration> {
        if (located.isEmpty()) return emptyList()
        val cleaned = TextbookExtraction.clean(raw)
        val trimmed = cleaned.trim()
        val trimStart = cleaned.length - cleaned.trimStart().length
        data class Span(val page: BookPage, val start: Int, val end: Int)
        val spans = mutableListOf<Span>()
        var cursor = 0
        trimmed.split('\u000c').forEachIndexed { sectionIndex, section ->
            if (sectionIndex > 0) cursor++
            for (page in TextbookExtraction.paginate(section)) {
                spans += Span(pages[spans.size], cursor, cursor + page.text.length)
                cursor += page.text.length
            }
        }
        check(cursor == trimmed.length && spans.size == pages.size)
        check(spans.all { trimmed.substring(it.start, it.end) == it.page.text })
        var rawCursor = 0
        var normalized = 0
        val positions = located.map { it.offset }.distinct().sorted().associateWith { target ->
            while (rawCursor < target) {
                val c = raw[rawCursor]
                if (c != '\u0000' && !(c == '\n' && rawCursor > 0 && raw[rawCursor - 1] == '\r')) normalized++
                rawCursor++
            }
            (normalized - trimStart).coerceIn(0, trimmed.length)
        }
        val result = located.mapNotNull { item ->
            control.check()
            val bytes = if (item.image.localName?.lowercase() == "svg") BookSvgImages.raster(item.image)
                else resource(member, item.image.getAttribute("src"))
            if (bytes == null) return@mapNotNull null
            total += bytes.size
            require(++count <= FormatBookImages.MAX_IMAGES && total <= FormatBookImages.MAX_TOTAL) {
                "EPUB 插图过多或超过 64 MB，请分册导入。"
            }
            val position = positions.getValue(item.offset)
            // At an exact page boundary choose the following page. Form-feed gaps clamp
            // to its start; repeated prose never participates in source placement.
            val span = spans.firstOrNull { position < it.end } ?: spans.last()
            val offset = BookIllustrationAnchors.snapToValidUtf16Boundary(span.page.text,
                (position - span.start).coerceIn(0, span.page.text.length))
            FormatBookImages.image(bytes, span.page.number + base, offset, caption(item.image))
        }
        located.clear()
        return result
    }

    private fun resource(member: String, reference: String): ByteArray? {
        val uri = runCatching { URI(reference) }.getOrNull() ?: return null
        if (reference.isBlank() || uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val path = URI(null, null, "/$member", null).resolve(uri).normalize().path.removePrefix("/")
        require(!path.startsWith('/') && '\\' !in path && path.split('/').none { it == ".." }) { "EPUB 内含无效的插图位置。" }
        val entry = zip.getEntry(path)?.takeUnless { it.isDirectory } ?: return null
        require(entry.size in 0..FormatBookImages.MAX_BYTES.toLong()) { "单张插图请控制在 16 MB 以内。" }
        val bytes = zip.getInputStream(entry).use { it.readNBytes(FormatBookImages.MAX_BYTES + 1) }
        require(bytes.size <= FormatBookImages.MAX_BYTES) { "单张插图请控制在 16 MB 以内。" }
        return if (path.substringAfterLast('.').equals("svg", true)) BookSvgImages.raster(EpubXml.parse(bytes)) else bytes
    }

    private fun caption(image: Element): String? {
        var parent: Node? = image.parentNode
        while (parent is Element) {
            if (parent.localName?.lowercase() == "figure") {
                val captions = parent.getElementsByTagNameNS("*", "figcaption")
                if (captions.length == 1) return captions.item(0).textContent.trim().takeIf { it.isNotEmpty() }
                break
            }
            parent = parent.parentNode
        }
        return image.getAttribute("alt").ifBlank { image.getAttribute("title") }.trim().takeIf { it.isNotEmpty() }
    }
}
