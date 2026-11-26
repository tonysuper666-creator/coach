package com.coach.desktop.books

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs

/** The text and offsets in a passage belong to a single layout region in the source PDF. */
internal object LiteraturePassages {
    private val publisher = Regex("""(?i)jstor|downloaded from|terms (?:and conditions|of use)|all rights reserved|copyright|https?://|doi[.:/]|stable url|accessed[: ]|©|下载于|版权所有""")
    private val note = Regex("""^(?:\[\d+]|\d+[.)]?|[*†‡])\s+\S""")
    private val referenceLine = Regex("""(?i)^(?:ibid\.|op\.\s*cit\.|see (?:also )?.{2,60},\s*(?:pp?\.|\d{4})|(?:[\p{Lu}][\p{L}’'-]+,\s*(?:[\p{Lu}]\.\s*)+)|.{2,90},\s*(?:vol\.|pp\.)\s*\d)|\(\d{4}\)[,.]?\s*(?:pp?\.\s*)?\d+[-–]\d+""")
    private val licence = Regex("""(?i)copyright (?:licen[cs]ing|notice|act)|course (?:pack|materials?|reader)|licensed (?:copy|for use)|copyright holder|digital copy|electronic reserve|educational use only""")
    private val bibliography = Regex("""(?i)^(?:references|bibliography|works cited|endnotes|notes and references|参考文献|尾注)\s*[:：]?$""")
    private val heading = Regex("""(?i)^(?:abstract|introduction|background|discussion|method(?:s|ology)?|results?|conclusions?|summary|引言|导言|摘要|背景|方法|结果|讨论|结论|小结|(?:[IVX]+\.|\d+(?:\.\d+)*[.)])\s+\p{L}.{1,80})$""")
    private val end = Regex("""[.!?。！？][”’"')\]]*$""")
    private val folio = Regex("""(?i)^(?:page\s*)?(?:\d+|[ivxlcdm]{1,6})$""")
    private val argument = Regex("""(?i)\b(?:argue|argues|propose|because|therefore|however|whereas|suggests?|conclude|consequently|demonstrates?|explains?|evidence|defined?)\b|因此|然而|本文|表明|说明|认为""")
    private val missing = Regex("""(?i)(?:片段|材料|文中|摘录|原文|资料).{0,16}(?:未(?:曾)?(?:涉及|提及|说明|交代|提供|给出)|没有(?:涉及|提及|提供|给出)|不足以|[仅只]有.{0,4}页眉)|(?:无法|不能|难以)(?:据此|直接|从.{1,12}中)?(?:回答|作答|判断|确定|推断)|(?:cannot|can't|unable to) (?:answer|determine)|(?:excerpt|passage|material).{0,24}(?:does not|insufficient|not (?:mention|cover))""")
    // Rhetorical signals still match the question when the prompt and paper use different languages.
    private val intentCues = listOf(
        Regex("(?i)为什么|为何|原因|why|cause") to Regex("(?i)因为|由于|原因|because|owing to|reason|caused"),
        Regex("(?i)定义|含义|什么是|define|meaning|what is") to Regex("(?i)是指|称为|意指|defined|refers to|means|known as"),
        Regex("(?i)比较|不同|区别|相比|compare|differ") to Regex("(?i)相比|不同|而非|whereas|unlike|in contrast|differ"),
        Regex("(?i)如何|怎样|证据|依据|how|evidence") to Regex("(?i)通过|说明|表明|evidence|by means of|demonstrat|showed"),
    )
    private val metadataQuestion = Regex("(?i)页眉|下载戳|书目信息|页脚|download stamp|page header")
    fun affirmativeAnswer(text: String): Boolean {
        // A valid reference is mandatory even when none of these explicit abstentions occurs.
        val abstentions = listOf("未直接说明", "未直接提及", "无法直接验证", "无法验证", "材料中没有",
            "原文中没有", "片段中没有", "材料没有", "证据不足", "未能回答", "没有足够信息")
        return substantive(text) && abstentions.none(text::contains)
    }

    fun contentQuestion(text: String) = substantive(text) && !metadataQuestion.containsMatchIn(text)
    fun substantive(text: String) = !missing.containsMatchIn(text)
    fun bodySentence(s: String): Boolean = s.length in 6..650 && end.containsMatchIn(s) &&
        !publisher.containsMatchIn(s) && !referenceLine.containsMatchIn(s) && !note.containsMatchIn(s) &&
        s.dropWhile { it in "“‘\\\"'([" }.firstOrNull()?.isLowerCase() != true &&
        (s.count { it.code in 0x3400..0x9fff } >= 5 || Regex("""\p{L}{2,}""").findAll(s).take(4).count() >= 4)

    private data class Passage(val page: BookPage, val region: GuideRegion, val offset: Int, val quote: String, val title: String?)
    private fun edgeKey(s: String) = s.trim().lowercase().replace(Regex("""\d+"""), "#")
    private fun tokens(s: String): Set<String> = buildSet {
        for (m in Regex("""[\p{IsHan}]+|[\p{L}\p{N}]+""").findAll(s.lowercase())) {
            val word = m.value
            if (word.first().code in 0x3400..0x9fff) addAll(word.windowed(2))
            else if (word.length >= 3 && word !in setOf("the","and","what","how","was","are","did","for","with","why","does")) add(word.removeSuffix("s"))
        }
    }

    fun ranked(book: Textbook, questions: List<String>, revision: Int): List<List<GuideExcerpt>> {
        val edges = book.pages.flatMap { page ->
            page.guideRegions.flatMap { region ->
                val lines = region.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                (lines.take(2) + lines.takeLast(2)).filter { it.length < 150 }.map(::edgeKey)
            }.distinct()
        }.groupingBy { it }.eachCount().filterValues { it >= 3 }.keys
        val passages = mutableListOf<Passage>()
        var title: String? = null
        var backMatter = false
        for (page in book.pages) {
            val printed = page.guideRegions.joinToString("\n") { it.text }
            val reader = LiteratureReaderText(page.text)
            if (reader.interleaved(page.guideRegions)) continue
            if (page.number <= 3 && licence.containsMatchIn(printed) &&
                (publisher.containsMatchIn(printed) || licence.findAll(printed).take(2).count() == 2)) continue
            if (page.number == 1 && Regex("""(?im)^(?:stable url|reviewed work|source|published by)\s*:""").findAll(printed).count() >= 2) continue
            book.tocNodes.lastOrNull { it.startPage == page.number && it.source in setOf(HeadingSource.PDF_OUTLINE, HeadingSource.TOC_PAGE) &&
                it.kind in setOf(TocNodeKind.CHAPTER, TocNodeKind.SECTION, TocNodeKind.SUBSECTION) &&
                edgeKey(it.title) !in edges && !referenceLine.containsMatchIn(it.title) &&
                printed.lineSequence().any { line -> line.trim().equals(it.title.trim(), ignoreCase = true) } }?.let { title = it.title }
            for (region in page.guideRegions) {
                var start = -1
                var finish = 0
                fun finishParagraph() {
                    if (start < 0) return
                    val paragraph = region.text.substring(start, finish)
                    val iterator = BreakIterator.getSentenceInstance(Locale.ROOT).apply { setText(paragraph) }
                    var a = iterator.first()
                    var b = iterator.next()
                    while (b != BreakIterator.DONE) {
                        val raw = paragraph.substring(a, b)
                        val quote = raw.trim()
                        if (bodySentence(quote)) reader.locate(quote)?.let { range ->
                            if (page.guideHighlightCoverage?.let { it.nextClearBit(range.first) <= range.last } == true) return@let
                            val original = page.text.substring(range)
                            if (region.containsSentence(original) && bodySentence(original)) {
                                passages += Passage(page, region, range.first, original, title)
                            }
                        }
                        a = b
                        b = iterator.next()
                    }
                    start = -1
                }
                var offset = 0
                var footnotes = false
                val lines = region.text.lines()
                for ((index, raw) in lines.withIndex()) {
                    val line = raw.trim()
                    val isEdge = index < 3 || index >= lines.size - 3
                    val isHeading = !footnotes && !isEdge && edgeKey(line) !in edges && !referenceLine.containsMatchIn(line) && (line.length in 4..70 && !end.containsMatchIn(line) &&
                        lines.getOrNull(index - 1).isNullOrBlank() && lines.getOrNull(index + 1).isNullOrBlank() &&
                        line.split(' ').all { it.firstOrNull()?.isUpperCase() == true || it in listOf("of","the","in","and","a","to") })
                    val marginTitle = isEdge && line.length in 3..90 && !end.containsMatchIn(line) &&
                        !heading.matches(line) && line.split(' ').filter(String::isNotBlank).all { word ->
                            word.first().isUpperCase() || word.all(Char::isDigit) || word in setOf("of", "the", "in", "and")
                        }
                    when {
                        backMatter -> break
                        bibliography.matches(line) -> { finishParagraph(); backMatter = true }
                        line.isBlank() || marginTitle || folio.matches(line) || publisher.containsMatchIn(line) ||
                            isEdge && edgeKey(line) in edges && (!end.containsMatchIn(line) || line.length < 45) -> finishParagraph()
                        referenceLine.containsMatchIn(line) -> { finishParagraph(); footnotes = true }
                        !footnotes && (isHeading || heading.matches(line) && !end.containsMatchIn(line) && edgeKey(line) !in edges) -> { finishParagraph(); title = line }
                        note.containsMatchIn(line) -> { finishParagraph(); footnotes = true }
                        !footnotes -> {
                            if (start < 0) start = offset
                            finish = offset + raw.length
                        }
                    }
                    offset += raw.length + 1
                }
                finishParagraph()
            }
        }
        require(passages.isNotEmpty()) { "《" + book.title + "》没有可核对的正文句，请检查原件版面或文字识别。" }
        val hasSections = passages.map { it.title }.distinct().size in 2..8
        val stageCount = (3 + passages.size / 65).coerceAtMost(8).coerceAtMost(passages.size)
        val cuts = if (hasSections) emptyList() else (1 until stageCount).map { i ->
            val target = passages.size * i / stageCount
            val radius = passages.size / stageCount / 4
            ((target - radius)..(target + radius)).minBy { j ->
                abs(target - j) - if (argument.containsMatchIn(passages[j].quote.take(48))) radius else 0
            }
        }
        val groups = passages.withIndex().groupBy { (i, p) ->
            if (hasSections) p.title ?: "导言" else when (val stage = cuts.count { i >= it }) {
                0 -> "导言"
                cuts.size -> "结论"
                else -> "展开论证 " + stage
            }
        }
        val queries = questions.map { q ->
            tokens(q) to intentCues.filter { it.first.containsMatchIn(q) }.map { it.second }
        }
        return groups.entries.mapIndexed { index, (name, group) ->
            val id = book.id + "#guide-" + index
            val blocks = group.map { (_, p) ->
                SourceBlock(id + "-" + p.page.number + "-" + p.offset, p.page.number, p.offset, p.offset + p.quote.length, false, p.quote)
            }
            val lesson = BookLesson(id, name, blocks)
            val excerpts = group.zip(blocks).map { (entry, block) ->
                GuideExcerpt(book, lesson, BookCompanionAnchor(block.page, block.id, block.start, block.end, block.text), entry.value.region)
            }.distinctBy { it.anchor.quoteAnchor }
            val vocabulary = excerpts.map { tokens(it.anchor.quoteAnchor) }
            val ranks = queries.map { (query, cues) ->
                val scores = excerpts.mapIndexed { j, excerpt ->
                    val overlap = vocabulary[j].count(query::contains)
                    val quote = excerpt.anchor.quoteAnchor
                    val intent = cues.count { it.containsMatchIn(quote) }
                    val argumentScore = if (argument.containsMatchIn(quote)) 3 else 0
                    val boundaryScore = if (j < excerpts.size / 8 || j >= excerpts.size * 7 / 8) 1 else 0
                    overlap * 10 + intent * 5 + argumentScore + boundaryScore
                }
                // Bit-reversed ordinal interleaves equally relevant passages across the section.
                val order = excerpts.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { Integer.reverse(it) ushr 1 })
                val stride = minOf(9, maxOf(1, order.size / 3))
                val shift = (revision.toLong() * stride % order.size).toInt()
                order.drop(shift) + order.take(shift)
            }
            buildSet {
                if (index == 0 || index == groups.size - 1) {
                    val boundary = if (index == 0) excerpts.indices.take(4) else excerpts.indices.toList().takeLast(4)
                    val best = boundary.sortedByDescending { argument.containsMatchIn(excerpts[it].anchor.quoteAnchor) }
                    add(best[revision % best.size])
                }
                for (i in excerpts.indices) for (rank in ranks) add(rank[i])
            }.map(excerpts::get)
        }
    }
}
