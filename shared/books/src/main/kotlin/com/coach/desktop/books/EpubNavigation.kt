package com.coach.desktop.books

import org.w3c.dom.Element
import java.net.URI

/** EPUB destinations refer to spine members and element IDs, even when labels repeat. */
internal class EpubNavigation(packagePath: String, opf: Element, read: (String) -> ByteArray?) {
    private data class Target(val member: String, val fragment: String?)
    private data class Entry(val label: String, val level: Int, val target: Target)
    private data class TargetText(val element: String, val text: String)

    private val epubNamespace = "http://www.idpf.org/2007/ops"
    private val gutenbergStartLine = Regex("""(?im)^\*{3}\s*START OF (?:THE|THIS) PROJECT GUTENBERG EBOOK\b[^\r\n]*$""")
    private val gutenbergCreditLine = Regex(
        """(?i)^(?:Produced|Transcribed|Prepared|Scanned|Proofread)\s+by\b.*|^Online Distributed Proofreading\b.*|^Updated editions will replace\b.*""",
    )
    private val orphanHeadingMarks = Regex("""^(?:[ \t\r\n\u000c]+|#{1,6}[ \t]*(?=\r?\n|$))+""")
    private val licenseLabelRegex = Regex("""(?i)^(?:The Full )?Project Gutenberg.*License.*$""")
    private val noteHeaderRegex = Regex("""^[□■※*\s]*(?:注\s*釋|注\s*释|附\s*注)[:：︰]?$""")
    private val bareChineseNumeralRegex = Regex("""^[一二三四五六七八九十百〇零]+$""")
    private val authorCreditRegex = Regex("""^(?:[\p{IsHan}]{1,6}\s+)?[\p{IsHan}]{2,6}[著撰]$""")
    private val calendarNoiseRegex = Regex("""^(?:◎.*|正[東西南北]|閏[正一二三四五六七八九十百]+|太初元年.*)$""")
    private val shijiPartTitleRegex = Regex("""^(?:本紀|表|書|世家|列傳)$""")
    private val numberedChineseChapterRegex = Regex("""^第\s*[零〇一二三四五六七八九十百千万两0-9]+\s*[章节回篇].*$""")
    private val prefaceSuffixRegex = Regex("""(?:自\s*序|序\s*言?|前\s*言|代\s*序|导\s*言|導\s*言|引\s*言)$""")
    private val headingForbiddenPunctuation = "。！？，、；：︰:;,.!?“”‘’\"'「」『』…—()（）[]【】﹝﹞<>"
    private val packageUri = URI(null, null, "/$packagePath", null)
    private val navEntries = mutableListOf<Entry>()
    private val ncxEntries = mutableListOf<Entry>()
    private val inferredEntries = mutableListOf<Entry>()
    private val destinations = mutableMapOf<Target, BookPageAnchor>()
    private val bodyTargets = mutableListOf<Target>()
    private val targetText = mutableMapOf<Target, TargetText>()
    private val afterBoilerplate = mutableMapOf<String, BookPageAnchor>()
    private val requested = mutableMapOf<String, MutableSet<String?>>()

    init {
        val manifest = opf.descendants("item")
        val nav = manifest.firstOrNull { "nav" in it.getAttribute("properties").tokens() }
        document(nav, read)?.let { (uri, doc) ->
            val navs = doc.descendants("nav")
            val toc = navs.firstOrNull { "toc" in it.epubTypes() }
                ?: navs.firstOrNull { it.getAttribute("role") == "doc-toc" }
            fun walk(list: Element, level: Int) {
                if (level > 32) return
                for (item in list.children("li")) {
                    item.children("a").firstOrNull()?.let { link ->
                        add(navEntries, link.textContent, level, uri, link.getAttribute("href"))
                    }
                    item.children("ol").forEach { walk(it, level + 1) }
                }
            }
            toc?.children("ol")?.forEach { walk(it, 1) }
            navs.filter { "landmarks" in it.epubTypes() }.flatMap { it.descendants("a") }
                .filter { "bodymatter" in it.epubTypes() }
                .mapNotNullTo(bodyTargets) { target(uri, it.getAttribute("href")) }
        }
        val ncxId = opf.descendants("spine").firstOrNull()?.getAttribute("toc")
        val ncx = manifest.firstOrNull { !ncxId.isNullOrEmpty() && it.getAttribute("id") == ncxId }
            ?: manifest.firstOrNull { it.getAttribute("media-type") == "application/x-dtbncx+xml" }
        document(ncx, read)?.let { (uri, doc) ->
            fun walk(parent: Element, level: Int) {
                if (level > 32) return
                for (point in parent.children("navPoint")) {
                    val label = point.children("navLabel").firstOrNull()?.textContent.orEmpty()
                    val href = point.children("content").firstOrNull()?.getAttribute("src").orEmpty()
                    add(ncxEntries, label, level, uri, href)
                    walk(point, level + 1)
                }
            }
            doc.descendants("navMap").firstOrNull()?.let { walk(it, 1) }
        }
        opf.descendants("guide").flatMap { it.children("reference") }
            .filter { it.getAttribute("type") in setOf("text", "bodymatter") }
            .mapNotNullTo(bodyTargets) { target(packageUri, it.getAttribute("href")) }
        val candidateNav = navEntries.ifEmpty { ncxEntries }
        val nonLicenseNav = candidateNav.filterNot { licenseLabelRegex.matches(it.label) }
        if (nonLicenseNav.isEmpty() || nonLicenseNav.all { it.label.length >= 25 }) {
            inferSpineHeadings(opf, manifest, read)
        }
        for (destination in navEntries.map { it.target } + ncxEntries.map { it.target } + inferredEntries.map { it.target } + bodyTargets) {
            requested.getOrPut(destination.member) { mutableSetOf() }.add(destination.fragment)
        }
    }

    fun fragments(member: String): Set<String?> = requested[member].orEmpty()

    fun recordTargetText(member: String, fragment: String, element: String, text: String) {
        targetText.putIfAbsent(Target(member, fragment), TargetText(element, normalizeLabel(text)))
    }

    private fun record(member: String, fragment: String?, anchor: BookPageAnchor) {
        destinations.putIfAbsent(Target(member, fragment), anchor)
    }

    private fun locateInPages(pages: List<BookPage>, content: String, pageBase: Int, position: Int): BookPageAnchor {
        val clamped = position.coerceIn(0, content.length)
        var pageIndex = 0
        var pageStart = 0
        while (pageIndex < pages.lastIndex && clamped >= pageStart + pages[pageIndex].text.length) {
            pageStart += pages[pageIndex++].text.length
            if (content.getOrNull(pageStart) == '\u000c') pageStart++
        }
        val withinPage = (clamped - pageStart).coerceIn(0, pages[pageIndex].text.length)
        return BookPageAnchor(pageBase + pageIndex + 1, withinPage)
    }

    private fun skipPostBoilerplateCredits(content: String, fromPos: Int): Int {
        var cursor = fromPos.coerceIn(0, content.length)
        while (cursor < content.length) {
            while (cursor < content.length && content[cursor] in " \t\r\n\u000c") cursor++
            if (cursor >= content.length) break
            val lineEnd = content.indexOf('\n', cursor).let { if (it < 0) content.length else it }
            val line = content.substring(cursor, lineEnd).trim().trimStart('#').trim()
            if (line.isEmpty() || gutenbergCreditLine.matches(line)) {
                cursor = if (lineEnd < content.length) lineEnd + 1 else content.length
            } else {
                break
            }
        }
        return cursor.coerceIn(0, content.length)
    }

    fun recordMember(
        member: String, raw: String, cleaned: String, pages: List<BookPage>,
        pageBase: Int, offsets: Map<String?, Int>, boilerplateEnd: Int?,
    ) {
        val start = cleaned.length - cleaned.trimStart().length
        val content = cleaned.trim()
        var rawCursor = 0
        var normalized = 0
        val bodyMarker = "\u0000"
        val targets = if (boilerplateEnd == null) offsets else offsets + (bodyMarker to boilerplateEnd)
        for ((fragment, offset) in targets.entries.sortedBy { it.value }) {
            while (rawCursor < offset) {
                val c = raw[rawCursor]
                if (c != '\u0000' && !(c == '\n' && rawCursor > 0 && raw[rawCursor - 1] == '\r')) normalized++
                rawCursor++
            }
            val position = (normalized - start).coerceIn(0, content.length)
            if (fragment == bodyMarker) {
                val postHeader = skipPostBoilerplateCredits(content, position)
                afterBoilerplate[member] = locateInPages(pages, content, pageBase, postHeader)
            } else if (fragment == null) {
                record(member, null, BookPageAnchor(pageBase + 1, 0))
            } else {
                val skipMarks = orphanHeadingMarks.find(content.substring(position))?.value?.length ?: 0
                val cleanPos = (position + skipMarks).coerceIn(0, content.length)
                record(member, fragment, locateInPages(pages, content, pageBase, cleanPos))
            }
        }
        if (member !in afterBoilerplate) {
            val sep = gutenbergStartLine.find(content)
            if (sep != null) {
                val postHeader = skipPostBoilerplateCredits(content, sep.range.last + 1)
                afterBoilerplate[member] = locateInPages(pages, content, pageBase, postHeader)
            }
        }
    }

    private fun resolvedEntries(): List<Entry> =
        navEntries.filter { it.target in destinations }.ifEmpty { ncxEntries.filter { it.target in destinations } }

    // Some converted books use a verbatim sentence from a paragraph as their nav label.
    private fun copiedParagraph(entry: Entry): Boolean {
        val target = targetText[entry.target] ?: return false
        return entry.label.length >= 25 && target.text == entry.label &&
            (target.element == "p" || entry.target.member in afterBoilerplate && entry.label.count { it in "，。；！？" } >= 2)
    }

    fun outline(): List<BookOutlineItem> {
        val entries = resolvedEntries()
        val nonLicense = entries.filterNot { licenseLabelRegex.matches(it.label) }
        val resolvedInferred = inferredEntries.filter { it.target in destinations }
        if ((nonLicense.isEmpty() || nonLicense.all(::copiedParagraph)) && resolvedInferred.isNotEmpty()) {
            val preservedBackMatter = entries.filter { licenseLabelRegex.matches(it.label) }
            return (resolvedInferred + preservedBackMatter).map { entry ->
                val anchor = destinations.getValue(entry.target)
                BookOutlineItem(entry.label, entry.level, anchor.importPage, HeadingSource.EPUB_TOC, anchor.offsetUtf16)
            }
        }
        return entries.mapIndexed { index, entry ->
            val rawAnchor = destinations.getValue(entry.target)
            val isCopied = copiedParagraph(entry)
            val anchor = if (index == 0 && isCopied) {
                afterBoilerplate[entry.target.member] ?: rawAnchor
            } else rawAnchor
            val title = if (isCopied) "正文" else entry.label
            BookOutlineItem(title, entry.level, anchor.importPage, HeadingSource.EPUB_TOC, anchor.offsetUtf16)
        }
    }

    fun bodyStart(): BookPageAnchor? {
        val explicit = bodyTargets.firstNotNullOfOrNull { target ->
            val anchor = destinations[target] ?: return@firstNotNullOfOrNull null
            val postHeader = afterBoilerplate[target.member]
            if (postHeader != null && (target.fragment == null || anchor.importPage < postHeader.importPage ||
                    (anchor.importPage == postHeader.importPage && anchor.offsetUtf16 < postHeader.offsetUtf16))
            ) {
                postHeader
            } else anchor
        }
        if (explicit != null) return explicit
        val entries = resolvedEntries()
        if (entries.isEmpty()) return afterBoilerplate.values.firstOrNull()
        val nonLicense = entries.filterNot { licenseLabelRegex.matches(it.label) }
        if ((nonLicense.isEmpty() || nonLicense.all(::copiedParagraph)) && inferredEntries.any { it.target in destinations }) {
            return null
        }
        return entries.firstOrNull()?.takeIf(::copiedParagraph)?.let { afterBoilerplate[it.target.member] }
    }

    /**
     * Plain-text-converted EPUBs (such as Project Gutenberg Ebookmaker Chinese editions) often emit a single
     * copied sentence as their only body nav entry while tagging every real preface and story/chapter heading
     * as standalone `<p id="...">` / `<h* id="...">` blocks in the spine XHTML.
     */
    private fun inferSpineHeadings(opf: Element, manifest: List<Element>, read: (String) -> ByteArray?) {
        val bookTitle = normalizeLabel(opf.descendants("title").firstOrNull()?.textContent.orEmpty())
        val normBookTitle = cleanBookTitleForCompare(bookTitle)
        val hrefById = manifest.associate { it.getAttribute("id") to it.getAttribute("href") }
        val spineItems = opf.descendants("itemref").filter { it.getAttribute("linear") != "no" }
        var hasUnnumberedStory = false
        var proseSinceLastHeading = false

        for (itemRef in spineItems) {
            val href = hrefById[itemRef.getAttribute("idref")] ?: continue
            val member = target(packageUri, href)?.member ?: continue
            val bytes = read(member) ?: continue
            val doc = runCatching { EpubXml.parse(bytes) }.getOrNull() ?: continue

            fun walk(el: Element) {
                val tag = el.localName?.lowercase().orEmpty()
                if (tag in setOf("head", "script", "style")) return
                val elId = listOf("id", "xml:id", "name").firstNotNullOfOrNull { attr ->
                    el.getAttribute(attr).takeIf(String::isNotEmpty)
                }
                val cls = el.getAttribute("class")
                if ((tag == "header" || tag == "footer") &&
                    (elId?.startsWith("pg-") == true || "pg-boilerplate" in cls)
                ) {
                    return
                }
                if (tag == "p" || (tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6')) {
                    val txt = normalizeLabel(el.textContent)
                    if (txt.isEmpty()) return
                    if (elId != null && isStandaloneSpineHeading(txt, bookTitle, normBookTitle)) {
                        val isNumberedChapter = numberedChineseChapterRegex.matches(txt)
                        val level = if (!isNumberedChapter) {
                            if (!prefaceSuffixRegex.containsMatchIn(txt)) hasUnnumberedStory = true
                            1
                        } else {
                            if (hasUnnumberedStory) 2 else 1
                        }
                        val entry = Entry(txt, level, Target(member, elId))
                        if (!proseSinceLastHeading && inferredEntries.lastOrNull()?.label == txt) {
                            inferredEntries[inferredEntries.lastIndex] = entry
                        } else if (inferredEntries.lastOrNull()?.label != txt) {
                            inferredEntries += entry
                        }
                        proseSinceLastHeading = false
                    } else {
                        proseSinceLastHeading = true
                    }
                    return
                }
                val children = el.childNodes
                for (i in 0 until children.length) {
                    (children.item(i) as? Element)?.let(::walk)
                }
            }
            walk(doc)
        }
        if (bookTitle.isNotEmpty()) {
            val prefixedCount = inferredEntries.count { it.label.startsWith("$bookTitle ") }
            if (prefixedCount >= 5) {
                inferredEntries.retainAll { entry ->
                    entry.label.startsWith("$bookTitle ") ||
                        shijiPartTitleRegex.matches(entry.label) ||
                        prefaceSuffixRegex.containsMatchIn(entry.label)
                }
            }
        }
    }

    private fun isStandaloneSpineHeading(txt: String, bookTitle: String, normBookTitle: String): Boolean {
        if (txt.length !in 1..18) return false
        if (txt.any { it in headingForbiddenPunctuation }) return false
        if (noteHeaderRegex.matches(txt) || bareChineseNumeralRegex.matches(txt) ||
            authorCreditRegex.matches(txt) || calendarNoiseRegex.matches(txt)
        ) {
            return false
        }
        if (gutenbergCreditLine.matches(txt) || licenseLabelRegex.matches(txt)) return false
        val norm = cleanBookTitleForCompare(txt)
        if (norm.isEmpty()) return false
        if (normBookTitle.isNotEmpty()) {
            if (norm == normBookTitle) return false
            val isBookPrefixedVolume = txt.startsWith("$bookTitle ") && txt.removePrefix("$bookTitle ").trim().length >= 2
            if (!isBookPrefixedVolume && bookTitle in txt && !prefaceSuffixRegex.containsMatchIn(txt) && txt.length <= bookTitle.length + 6) {
                return false
            }
        }
        return true
    }

    private fun cleanBookTitleForCompare(s: String): String =
        s.replace(Regex("""[《》〈〉\s:：︰\-—]"""), "")

    private fun add(entries: MutableList<Entry>, label: String, level: Int, base: URI, href: String) {
        val cleanLabel = normalizeLabel(label)
        if (cleanLabel.isEmpty()) return
        val destination = target(base, href) ?: return
        entries += Entry(cleanLabel, level, destination)
    }

    private fun document(item: Element?, read: (String) -> ByteArray?): Pair<URI, Element>? {
        val member = target(packageUri, item?.getAttribute("href").orEmpty())?.member ?: return null
        val bytes = read(member) ?: return null
        val document = runCatching { EpubXml.parse(bytes) }.getOrNull() ?: return null
        return URI(null, null, "/$member", null) to document
    }

    private fun target(base: URI, href: String): Target? {
        if (href.isBlank()) return null
        val uri = runCatching { base.resolve(href.trim().replace(" ", "%20")).normalize() }.getOrNull() ?: return null
        if (uri.scheme != null || uri.rawAuthority != null) return null
        val member = uri.path.removePrefix("/")
        if (member.isBlank() || '\\' in member || member.split('/').any { it == ".." }) return null
        return Target(member, uri.fragment?.takeIf(String::isNotEmpty))
    }

    private fun Element.children(name: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.localName == name }

    private fun Element.descendants(name: String): List<Element> =
        getElementsByTagNameNS("*", name).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun normalizeLabel(label: String): String = label.trim().replace(Regex("\\s+"), " ")
    private fun Element.epubTypes(): List<String> = getAttributeNS(epubNamespace, "type").tokens()
    private fun String.tokens(): List<String> = split(Regex("\\s+"))
}
