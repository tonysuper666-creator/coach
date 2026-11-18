package com.coach.desktop.books

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** Visual extraction never inserts markers into the authoritative page text. */
internal object FormatBookImages {
    const val MAX_IMAGES = 1000
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_TOTAL = 64 * 1024 * 1024
    data class Placement(val offset: Int, val bytes: ByteArray, val caption: String? = null)

    fun image(bytes: ByteArray, page: Int, offset: Int, caption: String? = null): ExtractedIllustration {
        require(bytes.size in 1..MAX_BYTES) { "单张插图请控制在 16 MB 以内。" }
        val raster = inspectBookRaster(bytes)
        return ExtractedIllustration(page, offset, bytes, raster.extension, raster.width, raster.height, caption)
    }

    /** Locate pages in the unchanged extraction stream, including trimmed chapter/page gaps. */
    fun attach(raw: String, pages: List<BookPage>, placements: List<Placement>): List<ExtractedIllustration> {
        require(placements.size <= MAX_IMAGES && placements.sumOf { it.bytes.size.toLong() } <= MAX_TOTAL) {
            "这份材料插图过多或超过 64 MB，请分册导入。"
        }
        if (placements.isEmpty()) return emptyList()
        val text = TextbookExtraction.clean(raw)
        data class Span(val page: BookPage, val start: Int, val end: Int)
        var cursor = 0
        val spans = pages.map { page ->
            val cleaned = TextbookExtraction.clean(page.text)
            val start = text.indexOf(cleaned, cursor)
            check(start >= 0) { "无法确认插图对应的正文位置。" }
            cursor = start + cleaned.length
            Span(page, start, cursor)
        }
        // Only CRLF and NUL are removed by clean(); Kotlin indices are UTF-16.
        var rawCursor = 0
        var normalized = 0
        val positions = placements.map { it.offset }.distinct().sorted().associateWith { target ->
            while (rawCursor < target) {
                val c = raw[rawCursor]
                if (c != '\u0000' && !(c == '\n' && rawCursor > 0 && raw[rawCursor - 1] == '\r')) normalized++
                rawCursor++
            }
            normalized
        }
        return placements.map { placement ->
            val position = positions.getValue(placement.offset)
            val span = spans.firstOrNull { position <= it.end } ?: spans.last()
            val local = (position - span.start).coerceIn(0, span.end - span.start)
            var index = 0
            var count = 0
            while (index < span.page.text.length && count < local) {
                val c = span.page.text[index]
                if (c != '\u0000' && !(c == '\n' && index > 0 && span.page.text[index - 1] == '\r')) count++
                index++
            }
            image(placement.bytes, span.page.number,
                BookIllustrationAnchors.snapToValidUtf16Boundary(span.page.text, index), placement.caption)
        }
    }

    /** Snapshot embedded data or assets beneath the selected HTML's directory. Never fetch URLs. */
    fun htmlAsset(source: String, directory: Path?): ByteArray? {
        if (source.startsWith("data:", ignoreCase = true)) {
            val comma = source.indexOf(',')
            require(comma > 0 && source.substring(0, comma).matches(Regex("(?i)data:image/(png|jpeg|jpg|gif);base64"))) {
                "网页内嵌插图请使用 PNG、JPEG 或 GIF。"
            }
            require(source.length - comma <= (MAX_BYTES.toLong() * 4 / 3 + 8)) { "单张插图请控制在 16 MB 以内。" }
            return Base64.getDecoder().decode(source.substring(comma + 1))
        }
        if (directory == null || source.isBlank()) return null
        val uri = URI(source)
        if (uri.isAbsolute || uri.rawAuthority != null || source.startsWith('/')) return null
        val relative = uri.path.orEmpty()
        require('\\' !in relative && ':' !in relative && relative.split('/').none { it == ".." }) {
            "网页插图必须在所选网页的目录内。"
        }
        val root = directory.toRealPath()
        val candidate = root.resolve(relative).normalize()
        require(candidate.startsWith(root)) { "网页插图必须在所选网页的目录内。" }
        if (!Files.isRegularFile(candidate)) return null
        require(candidate.toRealPath().startsWith(root)) { "网页插图不能链接到所选目录外。" }
        require(Files.size(candidate) <= MAX_BYTES) { "单张插图请控制在 16 MB 以内。" }
        return Files.newInputStream(candidate).use { it.readNBytes(MAX_BYTES + 1) }
    }
}
