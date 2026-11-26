package com.coach.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.jsoup.Jsoup
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory

internal data class UnifiedBookChapter(
    val id: String,
    val title: String,
    val level: Int,
    val kind: String,
    val startPage: Int,
    val endPage: Int,
    val source: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
        put("level", level)
        put("kind", kind)
        put("startPage", startPage)
        put("endPage", endPage)
        put("source", source)
    }
}

internal data class UnifiedBookArtifact(
    val schema: Int = SCHEMA_VERSION,
    val sha256: String,
    val parserVersion: String,
    val format: String,
    val pageCount: Int,
    val bodyStartPage: Int,
    val source: String,
    val chapters: List<UnifiedBookChapter>,
    val pageRoles: Map<Int, String>,
    val skippedPages: List<Int> = emptyList(),
    val totalChars: Long,
    val contentFingerprint: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("schema", schema)
        put("sha256", sha256)
        put("parserVersion", parserVersion)
        put("format", format)
        put("pageCount", pageCount)
        put("bodyStartPage", bodyStartPage)
        put("source", source)
        put("totalChars", totalChars)
        put("contentFingerprint", contentFingerprint)
        put("chapters", JsonArray(chapters.map { it.toJson() }))
        put("skippedPages", JsonArray(skippedPages.map { JsonPrimitive(it) }))
        put(
            "pageRoles",
            buildJsonObject {
                for ((page, role) in pageRoles.entries.sortedBy { it.key }) {
                    put(page.toString(), role)
                }
            },
        )
    }

    companion object {
        const val SCHEMA_VERSION = 3
    }
}

internal fun interface UnifiedBookProcessor {
    suspend fun process(
        blobFile: Path,
        format: String,
        title: String,
        sha256: String,
        parserVersion: String,
        reportProgress: (progressPct: Int, stageLabel: String) -> Unit,
    ): UnifiedBookArtifact
}

/**
 * Server-side deterministic book skeleton processor aligned with TON-1821:
 * - Outline priority: PDF bookmarks (`PDF_OUTLINE`) > EPUB nav/NCX (`EPUB_TOC`) > Word styles (`WORD_STYLE`)
 *   > heading rules (`HEADING_RULE`) > length fallback (`FALLBACK_LENGTH`).
 * - Front-matter & copyright detection skips non-body pages to compute `bodyStartPage`.
 * - Produces an immutable shared artifact keyed strictly by `(sha256, parserVersion)` without logging raw text.
 */
internal class DefaultUnifiedBookProcessor : UnifiedBookProcessor {
    private data class RawPage(val number: Int, val text: String)
    private data class RawOutlineItem(val title: String, val level: Int, val page: Int, val source: String)

    override suspend fun process(
        blobFile: Path,
        format: String,
        title: String,
        sha256: String,
        parserVersion: String,
        reportProgress: (progressPct: Int, stageLabel: String) -> Unit,
    ): UnifiedBookArtifact {
        reportProgress(15, "正在读取原文件")
        val extracted = when (format.trim().lowercase()) {
            "pdf" -> extractPdf(blobFile)
            "epub" -> extractEpub(blobFile)
            "docx" -> extractDocx(blobFile)
            "html" -> extractHtml(blobFile)
            else -> extractPlainText(blobFile)
        }
        val pages = extracted.pages.ifEmpty { listOf(RawPage(1, "")) }

        reportProgress(40, "正在整理目录与正文骨架")
        val pageRoles = classifyPageRoles(pages).toMutableMap()
        for (page in extracted.skippedPages) pageRoles[page] = "DAMAGED"
        val (source, outlineItems) = resolveOutline(pages, extracted.outline)
        val chapters = buildChapters(pages.size, outlineItems, source, title)
        val bodyStartPage = resolveBodyStartPage(pages, pageRoles, chapters, extracted.explicitBodyStartPage)

        reportProgress(75, "正在建立章节索引")
        val digest = MessageDigest.getInstance("SHA-256")
        var totalChars = 0L
        for (page in pages) {
            totalChars += page.text.length.toLong()
            digest.update("${page.number}:".toByteArray(StandardCharsets.UTF_8))
            digest.update(page.text.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
        }
        for (page in extracted.skippedPages) {
            digest.update("skipped:$page".toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
        }
        val contentFingerprint = digest.digest().joinToString("") { "%02x".format(it) }

        return UnifiedBookArtifact(
            sha256 = sha256,
            parserVersion = parserVersion,
            format = format.trim().lowercase(),
            pageCount = pages.size,
            bodyStartPage = bodyStartPage,
            source = source,
            chapters = chapters,
            pageRoles = pageRoles,
            skippedPages = extracted.skippedPages,
            totalChars = totalChars,
            contentFingerprint = contentFingerprint,
        )
    }

    private data class ExtractedBookContent(
        val pages: List<RawPage>,
        val outline: List<RawOutlineItem>,
        val explicitBodyStartPage: Int? = null,
        val skippedPages: List<Int> = emptyList(),
    )

    /**
     * A page that cannot be read is recorded and skipped.
     * The document is closed and opened again after that page, so the failure does not blank the pages after it.
     */
    private fun extractPdf(file: Path): ExtractedBookContent {
        try {
            return readPdf(file)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw pdfOpenFailure(file, error)
        }
    }

    private fun readPdf(file: Path): ExtractedBookContent {
        val memory = MemoryUsageSetting.setupTempFileOnly().streamCache
        var pdf = Loader.loadPDF(file.toFile(), memory)
        try {
            val totalPages = pdf.numberOfPages
            require(totalPages in 1..MAX_PDF_PAGES) { "这本 PDF 的页数暂不支持。" }
            val outline = runCatching { extractPdfOutline(pdf) }.getOrElse { emptyList() }
            val pages = ArrayList<RawPage>(totalPages)
            val skipped = ArrayList<Int>()
            var pageNum = 1
            while (pageNum <= totalPages) {
                try {
                    pages += RawPage(pageNum, pageText(pdf, pageNum))
                    pageNum++
                } catch (_: Exception) {
                    skipped += pageNum
                    pages += RawPage(pageNum, "")
                    pageNum++
                    pdf.close()
                    if (pageNum <= totalPages) pdf = Loader.loadPDF(file.toFile(), memory)
                }
            }
            return ExtractedBookContent(pages = pages, outline = outline, skippedPages = skipped)
        } finally {
            runCatching { pdf.close() }
        }
    }

    private fun pageText(pdf: PDDocument, pageNum: Int): String {
        val stripper = PDFTextStripper().apply {
            sortByPosition = true
            startPage = pageNum
            endPage = pageNum
        }
        return stripper.getText(pdf).trim()
    }

    private fun pdfOpenFailure(file: Path, error: Exception): IllegalArgumentException {
        if (causedByPassword(error)) {
            return IllegalArgumentException("这本 PDF 有密码保护。请在本机去掉密码后再上传。", error)
        }
        val header = runCatching {
            Files.newInputStream(file).use { it.readNBytes(5) }.toString(Charsets.ISO_8859_1)
        }.getOrDefault("")
        val message = if (header.startsWith("%PDF")) {
            "这份 PDF 下到一半就断了，文件已损坏。请换一份完整的文件再导入。"
        } else {
            "这不是 PDF 文件。请换一份 PDF 再导入。"
        }
        return IllegalArgumentException(message, error)
    }

    private fun causedByPassword(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            if (current is InvalidPasswordException) return true
            current = current.cause
        }
        return false
    }

    /** Exact TON-1821 PDF bookmark walk with cycle guard (`BookPdfOutline.kt`). */
    private fun extractPdfOutline(pdf: PDDocument): List<RawOutlineItem> {
        val root = pdf.documentCatalog?.documentOutline ?: return emptyList()
        val pageIndices = IdentityHashMap<Any, Int>(pdf.numberOfPages)
        pdf.pages.forEachIndexed { index, page -> pageIndices[page.cosObject] = index + 1 }
        val seen = IdentityHashMap<Any, Boolean>()
        val result = mutableListOf<RawOutlineItem>()

        fun walk(first: PDOutlineItem?, level: Int) {
            var entry = first
            while (entry != null && seen.size < 10_000) {
                val item = entry
                if (seen.put(item.cosObject, true) != null) break
                val title = cleanHeading(item.title.orEmpty())
                val page = runCatching { item.findDestinationPage(pdf) }.getOrNull()?.let { pageIndices[it.cosObject] }
                if (title.isNotEmpty() && page != null) {
                    result += RawOutlineItem(title, level, page, "PDF_OUTLINE")
                }
                if (level < 32) walk(item.firstChild, level + 1)
                entry = item.nextSibling
            }
        }

        walk(root.firstChild, 1)
        return result
    }

    private fun extractEpub(file: Path): ExtractedBookContent {
        ZipFile(file.toFile()).use { zip ->
            var totalUncompressed = 0L
            fun readEntry(name: String): ByteArray? {
                val clean = name.removePrefix("/")
                val entry = zip.getEntry(clean) ?: return null
                if (entry.isDirectory) return null
                zip.getInputStream(entry).use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16_384)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (out.size() + n > MAX_EPUB_ENTRY_BYTES || totalUncompressed + n > MAX_EPUB_TOTAL_BYTES) {
                            throw IllegalArgumentException("EPUB 解压体积超出上限。")
                        }
                        out.write(buf, 0, n)
                        totalUncompressed += n
                    }
                    return out.toByteArray()
                }
            }

            val containerBytes = readEntry("META-INF/container.xml")
                ?: throw IllegalArgumentException("EPUB 缺少 container.xml。")
            val containerDoc = parseXmlSafe(containerBytes)
            val packagePath = containerDoc.descendants("rootfile")
                .firstNotNullOfOrNull { it.getAttribute("full-path").takeIf(String::isNotBlank) }
                ?: throw IllegalArgumentException("EPUB 缺少 OPF 入口。")
            val opfBytes = readEntry(packagePath)
                ?: throw IllegalArgumentException("EPUB 缺少 OPF 文件。")
            val opf = parseXmlSafe(opfBytes)
            val packageUri = URI(null, null, "/$packagePath", null)

            val manifest = opf.descendants("item")
            val hrefById = manifest.associate { it.getAttribute("id") to it.getAttribute("href") }
            val spineItems = opf.descendants("itemref").filter { it.getAttribute("linear") != "no" }

            val pages = mutableListOf<RawPage>()
            val memberToPage = LinkedHashMap<String, Int>()
            for (itemRef in spineItems) {
                val href = hrefById[itemRef.getAttribute("idref")] ?: continue
                val member = resolveEpubMember(packageUri, href) ?: continue
                val bytes = readEntry(member) ?: continue
                val text = Jsoup.parse(bytes.toString(StandardCharsets.UTF_8)).body()?.text()?.trim().orEmpty()
                val pageNum = pages.size + 1
                pages += RawPage(pageNum, text)
                memberToPage.putIfAbsent(member, pageNum)
            }

            val navEntries = mutableListOf<RawOutlineItem>()
            var bodyStartPage: Int? = null
            val navItem = manifest.firstOrNull { "nav" in it.getAttribute("properties").split(Regex("\\s+")) }
            if (navItem != null) {
                val navMember = resolveEpubMember(packageUri, navItem.getAttribute("href"))
                val navBytes = navMember?.let(::readEntry)
                if (navMember != null && navBytes != null) {
                    val navUri = URI(null, null, "/$navMember", null)
                    runCatching { parseXmlSafe(navBytes) }.getOrNull()?.let { doc ->
                        val navs = doc.descendants("nav")
                        val toc = navs.firstOrNull { "toc" in it.epubTypes() }
                            ?: navs.firstOrNull { it.getAttribute("role") == "doc-toc" }
                        fun walk(list: Element, level: Int) {
                            if (level > 32) return
                            for (li in list.children("li")) {
                                li.children("a").firstOrNull()?.let { a ->
                                    val label = cleanHeading(a.textContent.orEmpty())
                                    val target = resolveEpubMember(navUri, a.getAttribute("href"))
                                    val page = target?.let { memberToPage[it] }
                                    if (label.isNotEmpty() && page != null) {
                                        navEntries += RawOutlineItem(label, level, page, "EPUB_TOC")
                                    }
                                }
                                li.children("ol").forEach { walk(it, level + 1) }
                            }
                        }
                        toc?.children("ol")?.forEach { walk(it, 1) }
                        bodyStartPage = navs.filter { "landmarks" in it.epubTypes() }
                            .flatMap { it.descendants("a") }
                            .filter { "bodymatter" in it.epubTypes() }
                            .firstNotNullOfOrNull { a ->
                                resolveEpubMember(navUri, a.getAttribute("href"))?.let { memberToPage[it] }
                            }
                    }
                }
            }

            if (navEntries.isEmpty()) {
                val ncxId = opf.descendants("spine").firstOrNull()?.getAttribute("toc")
                val ncxItem = manifest.firstOrNull { !ncxId.isNullOrEmpty() && it.getAttribute("id") == ncxId }
                    ?: manifest.firstOrNull { it.getAttribute("media-type") == "application/x-dtbncx+xml" }
                val ncxMember = ncxItem?.let { resolveEpubMember(packageUri, it.getAttribute("href")) }
                val ncxBytes = ncxMember?.let(::readEntry)
                if (ncxMember != null && ncxBytes != null) {
                    val ncxUri = URI(null, null, "/$ncxMember", null)
                    runCatching { parseXmlSafe(ncxBytes) }.getOrNull()?.let { doc ->
                        fun walk(parent: Element, level: Int) {
                            if (level > 32) return
                            for (point in parent.children("navPoint")) {
                                val label = cleanHeading(point.children("navLabel").firstOrNull()?.textContent.orEmpty())
                                val src = point.children("content").firstOrNull()?.getAttribute("src").orEmpty()
                                val page = resolveEpubMember(ncxUri, src)?.let { memberToPage[it] }
                                if (label.isNotEmpty() && page != null) {
                                    navEntries += RawOutlineItem(label, level, page, "EPUB_TOC")
                                }
                                walk(point, level + 1)
                            }
                        }
                        doc.descendants("navMap").firstOrNull()?.let { walk(it, 1) }
                    }
                }
            }

            if (bodyStartPage == null) {
                bodyStartPage = opf.descendants("guide").flatMap { it.children("reference") }
                    .filter { it.getAttribute("type") in setOf("text", "bodymatter") }
                    .firstNotNullOfOrNull { ref ->
                        resolveEpubMember(packageUri, ref.getAttribute("href"))?.let { memberToPage[it] }
                    }
            }

            return ExtractedBookContent(pages = pages, outline = navEntries, explicitBodyStartPage = bodyStartPage)
        }
    }

    private fun extractDocx(file: Path): ExtractedBookContent {
        Files.newInputStream(file).use { input ->
            XWPFDocument(input).use { doc ->
                val pages = mutableListOf<RawPage>()
                val outline = mutableListOf<RawOutlineItem>()
                val current = StringBuilder()
                var pageNum = 1

                fun flushPage() {
                    if (current.isNotEmpty()) {
                        pages += RawPage(pageNum, current.toString().trim())
                        current.setLength(0)
                        pageNum++
                    }
                }

                for (para in doc.paragraphs) {
                    val text = para.text.trim()
                    if (text.isEmpty()) continue
                    val style = para.style.orEmpty()
                    val headingLevel = parseWordHeadingLevel(style)
                    if (current.length >= TARGET_PAGE_CHARS && headingLevel != null) {
                        flushPage()
                    }
                    if (headingLevel != null) {
                        val cleaned = cleanHeading(text)
                        if (cleaned.isNotEmpty()) {
                            outline += RawOutlineItem(cleaned, headingLevel, pageNum, "WORD_STYLE")
                        }
                    }
                    if (current.isNotEmpty()) current.append("\n\n")
                    current.append(text)
                    if (current.length >= MAX_PAGE_CHARS) flushPage()
                }
                flushPage()
                return ExtractedBookContent(pages = pages, outline = outline)
            }
        }
    }

    private fun extractHtml(file: Path): ExtractedBookContent {
        val raw = Files.readString(file, StandardCharsets.UTF_8)
        val doc = Jsoup.parse(raw)
        val body = doc.body() ?: doc
        val lines = mutableListOf<String>()
        for (el in body.select("h1,h2,h3,h4,h5,h6,p,li,pre,blockquote")) {
            val txt = el.text().trim()
            if (txt.isEmpty()) continue
            val tag = el.tagName().lowercase()
            if (tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6') {
                lines += "${"#".repeat(tag[1] - '0')} $txt"
            } else {
                lines += txt
            }
        }
        val combined = if (lines.isNotEmpty()) lines.joinToString("\n\n") else body.text()
        return splitTextIntoPages(combined)
    }

    private fun extractPlainText(file: Path): ExtractedBookContent {
        val raw = Files.readString(file, StandardCharsets.UTF_8)
        return splitTextIntoPages(raw)
    }

    private fun splitTextIntoPages(raw: String): ExtractedBookContent {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val explicitSplits = normalized.split('\u000c')
        val pages = mutableListOf<RawPage>()
        var pageNum = 1
        for (chunk in explicitSplits) {
            val trimmed = chunk.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.length <= MAX_PAGE_CHARS) {
                pages += RawPage(pageNum++, trimmed)
            } else {
                val paragraphs = trimmed.split(Regex("""\n{2,}"""))
                val buf = StringBuilder()
                for (p in paragraphs) {
                    if (buf.isNotEmpty() && buf.length + p.length > TARGET_PAGE_CHARS) {
                        pages += RawPage(pageNum++, buf.toString().trim())
                        buf.setLength(0)
                    }
                    if (buf.isNotEmpty()) buf.append("\n\n")
                    buf.append(p)
                }
                if (buf.isNotEmpty()) pages += RawPage(pageNum++, buf.toString().trim())
            }
        }
        return ExtractedBookContent(pages = pages, outline = emptyList())
    }

    private fun classifyPageRoles(pages: List<RawPage>): Map<Int, String> = buildMap {
        for (page in pages) {
            val text = page.text.trim()
            val role = when {
                text.isEmpty() -> "BLANK"
                isCopyrightPage(text) -> "COPYRIGHT"
                isTocPage(text) -> "TOC"
                looksLikeFrontMatter(text) -> "FRONT_MATTER"
                isReferencesPage(text) -> "REFERENCES"
                isIndexPage(text) -> "INDEX"
                else -> "BODY"
            }
            put(page.number, role)
        }
    }

    private fun resolveOutline(pages: List<RawPage>, nativeOutline: List<RawOutlineItem>): Pair<String, List<RawOutlineItem>> {
        if (nativeOutline.isNotEmpty()) {
            val src = nativeOutline.first().source
            return src to nativeOutline
        }
        val inferred = mutableListOf<RawOutlineItem>()
        for (page in pages) {
            for (rawLine in page.text.lineSequence()) {
                val match = matchHeadingRule(rawLine) ?: continue
                inferred += RawOutlineItem(match.second, match.first, page.number, "HEADING_RULE")
            }
        }
        return if (inferred.isNotEmpty()) {
            "HEADING_RULE" to inferred
        } else {
            "FALLBACK_LENGTH" to emptyList()
        }
    }

    private fun buildChapters(
        pageCount: Int,
        outline: List<RawOutlineItem>,
        source: String,
        title: String,
    ): List<UnifiedBookChapter> {
        if (outline.isEmpty()) {
            return listOf(
                UnifiedBookChapter(
                    id = "ch-1",
                    title = title.ifBlank { "正文" },
                    level = 1,
                    kind = "CHAPTER",
                    startPage = 1,
                    endPage = pageCount.coerceAtLeast(1),
                    source = source,
                ),
            )
        }
        val ordered = outline.sortedWith(compareBy<RawOutlineItem>({ it.page }, { it.level }))
        return ordered.mapIndexed { idx, item ->
            val nextStart = ordered.getOrNull(idx + 1)?.page
            val endPage = when {
                nextStart == null -> pageCount
                nextStart > item.page -> nextStart - 1
                else -> item.page
            }.coerceIn(item.page, pageCount.coerceAtLeast(item.page))
            UnifiedBookChapter(
                id = "ch-${idx + 1}",
                title = item.title,
                level = item.level,
                kind = classifyNodeKind(item.title, item.level),
                startPage = item.page.coerceIn(1, pageCount.coerceAtLeast(1)),
                endPage = endPage,
                source = item.source,
            )
        }
    }

    private fun resolveBodyStartPage(
        pages: List<RawPage>,
        pageRoles: Map<Int, String>,
        chapters: List<UnifiedBookChapter>,
        explicitBodyStartPage: Int?,
    ): Int {
        val total = pages.size.coerceAtLeast(1)
        if (explicitBodyStartPage != null && explicitBodyStartPage in 1..total) {
            return explicitBodyStartPage
        }
        val firstChapter = chapters.firstOrNull { it.kind == "CHAPTER" }
            ?: chapters.firstOrNull { it.kind in setOf("SECTION", "SUBSECTION", "APPENDIX") }
        if (firstChapter != null && firstChapter.startPage in 1..total) {
            return firstChapter.startPage
        }
        val firstBody = pages.firstOrNull { pageRoles[it.number] == "BODY" }
        return firstBody?.number ?: 1
    }

    private fun classifyNodeKind(title: String, level: Int): String = when {
        FRONT_MATTER_HEADING.matches(title) || NON_BODY_HEADING.matches(title) -> "FRONT_MATTER"
        BACK_MATTER_HEADING.matches(title) -> "BACK_MATTER"
        APPENDIX_HEADING.matches(title) -> "APPENDIX"
        PART_HEADING.matches(title) -> "PART"
        level <= 1 -> "CHAPTER"
        level == 2 -> "SECTION"
        else -> "SUBSECTION"
    }

    private fun matchHeadingRule(rawLine: String): Pair<Int, String>? {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty() || trimmed.length > 90) return null
        MD_HEADING.matchEntire(trimmed)?.let { m ->
            val content = cleanHeading(m.groupValues[2])
            if (content.isNotEmpty() && !looksLikeSentence(content)) {
                return (inferHeadingLevel(content) ?: m.groupValues[1].length) to content
            }
        }
        if (looksLikeSentence(trimmed)) return null
        val level = inferHeadingLevel(trimmed) ?: return null
        return level to cleanHeading(trimmed)
    }

    private fun inferHeadingLevel(line: String): Int? = when {
        CN_CHAPTER.matches(line) || EN_CHAPTER.matches(line) -> 1
        CN_SECTION.matches(line) || EN_SECTION.matches(line) -> 2
        SUBSECTION_NUM.matches(line) -> 3
        SECTION_NUM.matches(line) -> 2
        else -> null
    }

    private fun looksLikeSentence(text: String): Boolean {
        if (text.isEmpty()) return true
        val last = text.last()
        return last in setOf('。', '！', '？', '，', ',', ';', '；', '!', '?') || text.count { it in "，。；！？" } >= 2
    }

    private fun isCopyrightPage(text: String): Boolean {
        val lower = text.lowercase()
        val markers = listOf("isbn", "copyright", "all rights reserved", "版权所有", "图书在版编目", "published by")
            .count { it in lower }
        return markers >= 2 || (text.length < 1500 && markers >= 1 && !CHAPTER_IN_TEXT.containsMatchIn(text))
    }

    private fun isTocPage(text: String): Boolean {
        val lines = text.lineSequence().map { it.trim() }.filter(String::isNotEmpty).toList()
        if (lines.isEmpty()) return false
        if (TOC_HEADER.matches(lines.first())) return true
        val tocLines = lines.count { TOC_LINE_PATTERN.matches(it) }
        return lines.size >= 4 && tocLines * 2 >= lines.size
    }

    private fun looksLikeFrontMatter(text: String): Boolean {
        val lines = text.lineSequence().map { it.trim().trimStart('#').trim() }.filter(String::isNotEmpty).toList()
        if (lines.isEmpty()) return true
        if (NON_BODY_HEADING.matches(lines.first()) || FRONT_MATTER_HEADING.matches(lines.first())) return true
        return lines.size <= 4 && text.length < 140 && lines.none { CHAPTER_IN_TEXT.containsMatchIn(it) } &&
            '。' !in text && text.count { it == '.' } <= 1
    }

    private fun isReferencesPage(text: String): Boolean {
        val first = text.lineSequence().map { it.trim().trimStart('#').trim() }.firstOrNull(String::isNotEmpty) ?: return false
        return REFERENCES_HEADING.matches(first)
    }

    private fun isIndexPage(text: String): Boolean {
        val first = text.lineSequence().map { it.trim().trimStart('#').trim() }.firstOrNull(String::isNotEmpty) ?: return false
        return INDEX_HEADING.matches(first)
    }

    private fun parseWordHeadingLevel(style: String): Int? {
        val m = WORD_HEADING_STYLE.find(style) ?: return null
        return m.groupValues[1].toIntOrNull()?.coerceIn(1, 6)
    }

    private fun cleanHeading(raw: String): String =
        raw.replace(Regex("""[\r\n\t]+"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trimStart('#')
            .trim()

    private fun resolveEpubMember(base: URI, href: String): String? {
        val cleanHref = href.substringBefore('#').trim()
        if (cleanHref.isEmpty()) return null
        return runCatching {
            base.resolve(cleanHref).normalize().path.removePrefix("/")
        }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("..") }
    }

    private fun Element.descendants(localName: String): List<Element> {
        val out = mutableListOf<Element>()
        val list = getElementsByTagNameNS("*", localName)
        for (i in 0 until list.length) {
            (list.item(i) as? Element)?.let(out::add)
        }
        return out
    }

    private fun Element.children(localName: String): List<Element> {
        val out = mutableListOf<Element>()
        var node = firstChild
        while (node != null) {
            if (node is Element && (node.localName == localName || node.tagName == localName)) {
                out += node
            }
            node = node.nextSibling
        }
        return out
    }

    private fun Element.epubTypes(): Set<String> =
        getAttributeNS("http://www.idpf.org/2007/ops", "type")
            .ifBlank { getAttribute("epub:type") }
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .toSet()

    /** XXE / DTD-hardened XML parser matching desktop `EpubXml.kt`. */
    private fun parseXmlSafe(bytes: ByteArray): Element {
        val guard = object : DefaultHandler2() {
            override fun startDTD(name: String?, publicId: String?, systemId: String?) {
                val html5 = publicId == null && (systemId == null || systemId == "about:legacy-compat")
                val standard = STANDARD_DTDS[publicId]?.let { suffix ->
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
            XML_FEATURES.forEach { (k, v) -> setFeature(k, v) }
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
            XML_FEATURES.forEach { (k, v) -> setFeature(k, v) }
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }.newDocumentBuilder()
        builder.setEntityResolver(guard)
        builder.setErrorHandler(guard)
        return builder.parse(ByteArrayInputStream(bytes)).documentElement
    }

    companion object {
        private const val MAX_PDF_PAGES = 5_000
        private const val MAX_EPUB_ENTRY_BYTES = 16 * 1024 * 1024
        private const val MAX_EPUB_TOTAL_BYTES = 128L * 1024L * 1024L
        private const val TARGET_PAGE_CHARS = 3_500
        private const val MAX_PAGE_CHARS = 5_000

        private val STANDARD_DTDS = mapOf(
            "-//W3C//DTD XHTML 1.0 Strict//EN" to "xhtml1/DTD/xhtml1-strict.dtd",
            "-//W3C//DTD XHTML 1.0 Transitional//EN" to "xhtml1/DTD/xhtml1-transitional.dtd",
            "-//W3C//DTD XHTML 1.0 Frameset//EN" to "xhtml1/DTD/xhtml1-frameset.dtd",
            "-//W3C//DTD XHTML 1.1//EN" to "xhtml11/DTD/xhtml11.dtd",
        )
        private val XML_FEATURES = mapOf(
            XMLConstants.FEATURE_SECURE_PROCESSING to true,
            "http://apache.org/xml/features/disallow-doctype-decl" to false,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        )

        private val MD_HEADING = Regex("""^(#{1,6})\s+(.+)$""")
        private val CN_CHAPTER = Regex("""^第[一二三四五六七八九十百千0-9]+[篇部编章](?:[\s：:·\-—]+.+|$)""")
        private val CN_SECTION = Regex("""^第[一二三四五六七八九十百千0-9]+[节讲回课单元](?:[\s：:·\-—]+.+|$)""")
        private val EN_CHAPTER = Regex("""^(?:Part|Chapter|Unit)\s+[0-9IVXLC]+(?:\b.*)?$""", RegexOption.IGNORE_CASE)
        private val EN_SECTION = Regex("""^(?:Section|Lesson)\s+[0-9]+(?:\.[0-9]+)*(?:\b.*)?$""", RegexOption.IGNORE_CASE)
        private val SECTION_NUM = Regex("""^\d{1,2}\.\d{1,2}\s+\S.*$""")
        private val SUBSECTION_NUM = Regex("""^\d{1,2}\.\d{1,2}\.\d{1,2}\s+\S.*$""")
        private val CHAPTER_IN_TEXT = Regex("""(?m)^(?:第[一二三四五六七八九十百0-9]+[章节篇]|Chapter\s+[0-9IVXLC]+|1\.\d+\s+)""", RegexOption.IGNORE_CASE)
        private val WORD_HEADING_STYLE = Regex("""(?:Heading|标题)\s*([1-6])""", RegexOption.IGNORE_CASE)
        private val NON_BODY_HEADING = Regex(
            """^(?:Cover|Half\s+Title|Title\s+Page|Copyright(?:\s+Page)?|Brief\s+Contents|Detailed\s+Contents|Contents|Table\s+of\s+Contents|Blank\s+Page|Illustrations|List\s+of\s+(?:Illustrations|Figures|Tables|Boxes)|Preface|Foreword|License|目\s*录|总\s*目\s*录|简\s*目|版权页|授权页|插图目录|封面)[.:]?$""",
            RegexOption.IGNORE_CASE,
        )
        private val FRONT_MATTER_HEADING = Regex(
            """^(?:Preface|Foreword|Acknowledgments?|前\s*言|序\s*言?|自\s*序|编\s*者\s*按|凡\s*例|致\s*谢)$""",
            RegexOption.IGNORE_CASE,
        )
        private val BACK_MATTER_HEADING = Regex(
            """^(?:Bibliography|References|Works\s+Cited|Index|Glossary|Endnotes|参考文献|引用文献|索\s*引|主题索引|后\s*记)$""",
            RegexOption.IGNORE_CASE,
        )
        private val APPENDIX_HEADING = Regex("""^(?:Appendix\b.*|附\s*录.*)$""", RegexOption.IGNORE_CASE)
        private val PART_HEADING = Regex("""^(?:Part\s+[0-9IVXLC]+\b.*|第[一二三四五六七八九十百千0-9]+[编篇部].*)$""", RegexOption.IGNORE_CASE)
        private val TOC_HEADER = Regex("""^(?:Brief\s+Contents|Detailed\s+Contents|Contents|Table\s+of\s+Contents|目\s*录|总\s*目\s*录|简\s*目)$""", RegexOption.IGNORE_CASE)
        private val TOC_LINE_PATTERN = Regex("""^.{2,120}?(?:[…⋯.·]{2,}|\s{2,})\s*(?:\d{1,5}|[ivxlcdm]{1,6})\s*$""", RegexOption.IGNORE_CASE)
        private val REFERENCES_HEADING = Regex("""^(?:参\s*考\s*文\s*献|引用文献|References|Bibliography|Works\s+Cited)$""", RegexOption.IGNORE_CASE)
        private val INDEX_HEADING = Regex("""^(?:索\s*引|主题索引|人名索引|Index|Subject\s+Index)$""", RegexOption.IGNORE_CASE)
    }
}
