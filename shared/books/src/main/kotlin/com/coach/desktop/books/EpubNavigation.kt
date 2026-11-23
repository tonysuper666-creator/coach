package com.coach.desktop.books

import org.w3c.dom.Element
import java.net.URI

/** EPUB destinations refer to spine members and element IDs, even when labels repeat. */
internal class EpubNavigation(packagePath: String, opf: Element, read: (String) -> ByteArray?) {
    private data class Target(val member: String, val fragment: String?)
    private data class Entry(val label: String, val level: Int, val target: Target)
    private data class TargetText(val element: String, val text: String)

    private val epubNamespace = "http://www.idpf.org/2007/ops"
    private val packageUri = URI(null, null, "/$packagePath", null)
    private val navEntries = mutableListOf<Entry>()
    private val ncxEntries = mutableListOf<Entry>()
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
        for (destination in navEntries.map { it.target } + ncxEntries.map { it.target } + bodyTargets) {
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

    fun recordMember(
        member: String, raw: String, cleaned: String, pages: List<BookPage>,
        pageBase: Int, offsets: Map<String?, Int>, boilerplateEnd: Int?,
    ) {
        val start = cleaned.length - cleaned.trimStart().length
        val content = cleaned.trim()
        var rawCursor = 0
        var normalized = 0
        var pageIndex = 0
        var pageStart = 0
        val bodyMarker = "\u0000"
        val targets = if (boilerplateEnd == null) offsets else offsets + (bodyMarker to boilerplateEnd)
        for ((fragment, offset) in targets.entries.sortedBy { it.value }) {
            while (rawCursor < offset) {
                val c = raw[rawCursor]
                if (c != '\u0000' && !(c == '\n' && rawCursor > 0 && raw[rawCursor - 1] == '\r')) normalized++
                rawCursor++
            }
            val position = (normalized - start).coerceIn(0, content.length)
            while (fragment != null && pageIndex < pages.lastIndex && position >= pageStart + pages[pageIndex].text.length) {
                pageStart += pages[pageIndex++].text.length
                if (content.getOrNull(pageStart) == '\u000c') pageStart++
            }
            val page = if (fragment == null) 0 else pageIndex
            val withinPage = if (fragment == null) 0 else (position - pageStart).coerceIn(0, pages[page].text.length)
            val anchor = BookPageAnchor(pageBase + page + 1, withinPage)
            if (fragment == bodyMarker) afterBoilerplate[member] = anchor else record(member, fragment, anchor)
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

    fun outline(): List<BookOutlineItem> = resolvedEntries().map { entry ->
        val anchor = destinations.getValue(entry.target)
        val title = if (copiedParagraph(entry)) "正文" else entry.label
        BookOutlineItem(title, entry.level, anchor.importPage, HeadingSource.EPUB_TOC, anchor.offsetUtf16)
    }

    fun bodyStart(): BookPageAnchor? = bodyTargets.firstNotNullOfOrNull(destinations::get)
        ?: resolvedEntries().firstOrNull()?.takeIf(::copiedParagraph)?.let { afterBoilerplate[it.target.member] }

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
