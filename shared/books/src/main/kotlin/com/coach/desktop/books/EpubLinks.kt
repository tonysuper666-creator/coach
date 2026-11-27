package com.coach.desktop.books

import java.net.URI

/** 一处书内超链接：[start, end) 是它在所在导入页文字里的范围，[target] 是点击后要去的位置。 */
internal data class EpubPageLink(val start: Int, val end: Int, val target: BookPageAnchor)

/** 按导入页号归好的 EPUB 书内链接（尾注编号、返回箭头、目录项等）。只含能落到正文页上的链接。 */
internal class EpubLinks(private val byPage: Map<Int, List<EpubPageLink>>) {
    fun onPage(page: Int): List<EpubPageLink> = byPage[page].orEmpty()
    val isEmpty: Boolean get() = byPage.isEmpty()
    val count: Int get() = byPage.values.sumOf { it.size }

    /** 导入页 [page] 的第 [offset] 个字上有没有链接。 */
    fun at(page: Int, offset: Int): EpubPageLink? = onPage(page).firstOrNull { offset >= it.start && offset < it.end }

    companion object { val NONE = EpubLinks(emptyMap()) }
}

/**
 * 导入抽取正文时顺手记下每个 `<a href>` 和每个带 id 的元素的位置；全部章节走完后再解析链接去向，
 * 这样「正文 → 尾注」「尾注 → 正文」两个方向（含向前引用）都能落到真实页码上。
 * 位置换算沿用导入时的清洗和分页，只在原文件与已存页文字一致时才会被采用。
 */
internal class EpubLinkCollector {
    private class RawLink(val member: String, val page: Int, val start: Int, val end: Int, val href: String)
    private class MemberAnchors(val first: BookPageAnchor, val ids: Map<String, BookPageAnchor>)

    private val members = mutableMapOf<String, MemberAnchors>()
    private val rawLinks = mutableListOf<RawLink>()

    fun addMember(
        member: String, raw: String, cleaned: String, pages: List<BookPage>, pageBase: Int,
        ids: Map<String, Int>, links: List<Triple<Int, Int, String>>,
    ) {
        if (pages.isEmpty()) return
        val trimStart = cleaned.length - cleaned.trimStart().length
        val content = cleaned.trim()
        // 页在 content 里的起止；分页只在换页符处额外吃掉一个字符。
        val starts = IntArray(pages.size)
        var cursor = 0
        for ((index, page) in pages.withIndex()) {
            starts[index] = cursor
            cursor += page.text.length
            if (content.getOrNull(cursor) == '\u000c') cursor++
        }
        val wanted = (ids.values + links.flatMap { listOf(it.first, it.second) }).distinct().sorted()
        var rawCursor = 0
        var normalized = 0
        val position = HashMap<Int, Int>(wanted.size * 2)
        for (target in wanted) {
            while (rawCursor < target && rawCursor < raw.length) {
                val c = raw[rawCursor]
                if (c != '\u0000' && !(c == '\n' && rawCursor > 0 && raw[rawCursor - 1] == '\r')) normalized++
                rawCursor++
            }
            position[target] = (normalized - trimStart).coerceIn(0, content.length)
        }
        fun pageIndexOf(pos: Int): Int {
            var low = 0
            var high = pages.lastIndex
            while (low < high) {
                val middle = (low + high + 1) / 2
                if (starts[middle] <= pos) low = middle else high = middle - 1
            }
            return low
        }
        fun anchorAt(pos: Int): BookPageAnchor {
            val index = pageIndexOf(pos)
            return BookPageAnchor(pageBase + index + 1, (pos - starts[index]).coerceIn(0, pages[index].text.length))
        }
        members[member] = MemberAnchors(
            BookPageAnchor(pageBase + 1, 0),
            ids.mapValues { anchorAt(position.getValue(it.value)) },
        )
        for ((rawStart, rawEnd, href) in links) {
            val start = position.getValue(rawStart)
            val end = position.getValue(rawEnd)
            if (end <= start) continue
            // 一条链接跨页时，每一页各记一段，点哪段都能走。
            for (index in pageIndexOf(start)..pageIndexOf(end - 1)) {
                val from = maxOf(start, starts[index]) - starts[index]
                val to = minOf(end, starts[index] + pages[index].text.length) - starts[index]
                if (to > from) rawLinks += RawLink(member, pageBase + index + 1, from, to, href)
            }
        }
    }

    fun finish(): EpubLinks {
        val result = mutableMapOf<Int, MutableList<EpubPageLink>>()
        for (link in rawLinks) {
            val target = resolve(link.member, link.href) ?: continue
            result.getOrPut(link.page) { mutableListOf() } += EpubPageLink(link.start, link.end, target)
        }
        return EpubLinks(result)
    }

    private fun resolve(from: String, href: String): BookPageAnchor? {
        val uri = runCatching { URI(href.trim().replace(" ", "%20")) }.getOrNull() ?: return null
        if (uri.scheme != null || uri.host != null) return null
        val member = if (uri.rawPath.isNullOrEmpty()) from
        else URI(null, null, "/$from", null).resolve(uri.rawPath).normalize().path.removePrefix("/")
        val anchors = members[member] ?: return null
        val fragment = uri.fragment?.takeIf { it.isNotEmpty() } ?: return anchors.first
        return anchors.ids[fragment] ?: anchors.first
    }
}
