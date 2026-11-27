package com.coach.desktop.books

import java.text.BreakIterator
import java.util.Locale

/** Candidate sentence filtering and per-paper / cross-paper ranking for the one-page literature guide. */
internal object LiteraturePassages {
    private val publisher = Regex(
        """(?i)jstor|downloaded from|terms (?:and conditions|of use)|all rights reserved|copyright|https?://|doi[.:/]|stable url|accessed[: ]|©|下载于|版权所有|under licen[cs]e|reproduced (?:under|with|by) permission|may not be (?:copied|reproduced|distributed)|cla licen[cs]e|coursepack|course pack|fair dealing|digitisation|digitization""",
    )
    private val copyrightPageTerm = Regex(
        """(?i)copyright|all rights reserved|terms (?:and conditions|of use)|downloaded from|coursepack|course pack|course materials?|course reading|reproduced (?:under|with|by) permission|licensing agency|\bcla\b|\bisbn\b|\bissn\b|doi[.:/]|published by|permission of the publisher|fair dealing|fair use|unauthori[sz]ed|reading list|extract from|under licen[cs]e|university library|©|版权|课程资料|仅供.{0,8}(?:学习|教学|课程)""",
    )
    private val jstorCoverHeader = Regex("""(?im)^(?:stable url|reviewed work|source|published by)\s*:""")
    private val coursepackCoverHeader = Regex("""(?im)^(?:book\s+title|book\s+author(?:\(s\))?|pub\.?\s*year|isbn|publisher|course\s+code|reading\s+list)\s*:""")
    private val note = Regex("""^(?:\[\d{1,3}]|\d{1,3}[.)]|[*†‡])\s+\S""")
    private val bottomBareNote = Regex("""^\d{1,3}\s+[A-Z\p{IsHan}]""")
    private val bibliography = Regex("""(?i)^(?:references|bibliography|works cited|endnotes|notes and references|参考文献|尾注)\s*[:：]?$""")
    // Shared with sectioning (TON-1898): a notes heading, not a sentence that merely mentions notes.
    private val notesBoundary = Regex(
        """(?i)^(?:endnotes?|footnotes?|notes(?:\s+to\s+(?:pages?|chapters?|the\s+text))?|参考文献|尾注|注释)\s*[:：]?$""",
    )
    private val bareNoteNumber = Regex("""^\d{1,3}[.)]?$""")
    private val noteNumberAtStart = Regex("""^(?:\[(\d{1,3})]|(?:(\d{1,3}))[.)]?)(?:\s|$)""")
    private val heading = Regex("""(?i)^(?:abstract|introduction|background|discussion|method(?:s|ology)?|results?|conclusions?|summary|引言|导言|摘要|背景|方法|结果|讨论|结论|小结|(?:[IVX]+\.|\d+(?:\.\d+)*[.)])\s+\p{L}.{1,80})$""")
    private val end = Regex("""[.!?。！？][”’"')\]]*$""")
    private val folio = Regex("""(?i)^(?:page\s*)?(?:\d+|[ivxlcdm]{1,6})$""")
    private val argument = Regex("""(?i)\b(?:argue|argues|argued|propose|because|therefore|however|whereas|suggests?|conclude|consequently|demonstrates?|explains?|evidence|defined?|central|thesis|claim|claims)\b|因此|然而|本文|表明|说明|认为|关键|核心""")
    // Fallback safety net against evasive/deflective prose; primary answerability gate is structured `answerable` + evidence `refs`.
    private val missing = Regex(
        """(?i)(?:所给|现有|当前|本组|上述|这些|提供的)?(?:片段|材料|文中|摘录|原文|资料|文本).{0,22}(?:未(?:曾|直接|具体|正面|充分|详细)?(?:涉及|提及|说明|交代|提供|给出|涵盖|展开|讨论|记录|回答|包含|回应|记载)|并未.{0,8}(?:涉及|提及|说明|讨论|提供|给出|涵盖|展开|回答|包含)|没有(?:直接|具体)?(?:涉及|提及|提供|给出|说明|讨论|涵盖|包含|记载)|不足以|不足|缺少|缺乏|着墨有限|信息有限|仅(?:仅)?(?:提及|提到|涉及|包含|记录|有|显示|展示)|只(?:提及|提到|涉及|包含|记录|有|谈及|涵盖|给出))|(?:无法|不能|难以|无从|不足以|尚不能)(?:据此|直接|仅凭|仅从|从.{1,16}中?)?(?:回答|作答|判断|确定|推断|验证|评估|比较|概括|证实|得出|确认|全面说明|正面回应)|(?:缺乏|缺少|未见|暂无|没有).{0,10}(?:直接证据|充分证据|相关论述|直接论述|相关记载|具体说明|足够信息|足够依据)|(?:并未|未对此|未正面|未直接)(?:提及|说明|讨论|涉及|回应|回答|展开)|(?:超出|不在).{0,8}(?:所给|现有|材料|片段).{0,6}范围|(?:需|需要)(?:结合|补充|查阅)(?:完整|更多|其他)?(?:原文|材料|全文)|(?:cannot|can't|unable to) (?:answer|determine|verify|assess|conclude)|(?:excerpt|passage|material|text)s?.{0,28}(?:does not|do not|doesn't|don't|insufficient|only mentions?|not (?:mention|cover|address|discuss|provide|explain))|no direct evidence|insufficient (?:evidence|information)""",
    )
    // Reject technical reference/block markers leaked into user-facing Chinese prose (keeps 4-digit years like [1660]).
    private val leakedRef = Regex(
        """(?i)\bref\s*[:#=\[\(]?\s*\d+|\bblocks?\s*[:#\[]?\s*\d+|(?:片段|摘录|引文|编号)\s*[#＃:：\[【(（]?\s*\d{1,3}|[\[\【]\s*(?:ref\s*[:=]?\s*)?\d{1,3}(?:\s*[,，、]\s*\d{1,3})*\s*[\]\】]""",
    )
    // Bibliographic footnote lines: Author, Title, (Place, Year), p./pp. N
    private val placeYearParen = Regex("""\([A-Z][\p{L}\s.&,'\-:]{1,45},\s*(?:1[5-9]\d{2}|20\d{2})(?:[-–—]\d{2,4})?\)""")
    private val pageRangeRef = Regex("""(?i)\bpp?\.\s*\d+(?:\s*[-–—]\s*\d+)?|\bvols?\.\s*\d+|\bno\.\s*\d+|\bff\.""")
    private val ibidLine = Regex("""(?i)^(?:see\s+(?:also\s+)?)?(?:ibid|op\.?\s*cit|loc\.?\s*cit)\.?(?:,|\s|$)""")
    private val authorInitialPrefix = Regex("""^(?:(?:see\s+(?:also\s+)?)?(?:[A-Z]\.\s*){1,3}[A-Z][\p{L}'\-]+|[\p{Lu}][\p{Ll}'\-]+,\s+[A-Z]\.)""")
    private val trailingPageRef = Regex("""(?i)\bpp?\.\s*\d+(?:\s*[-–—]\s*\d+)?\s*[.;]?$""")

    // Rhetorical signals still match the question when the prompt and paper use different languages.
    private val intentCues = listOf(
        Regex("(?i)为什么|为何|原因|why|cause") to Regex("(?i)因为|由于|原因|because|owing to|reason|caused"),
        Regex("(?i)定义|含义|什么是|define|meaning|what is") to Regex("(?i)是指|称为|意指|defined|refers to|means|known as"),
        Regex("(?i)比较|不同|区别|相比|compare|differ") to Regex("(?i)相比|不同|而非|whereas|unlike|in contrast|differ"),
        Regex("(?i)如何|怎样|证据|依据|how|evidence") to Regex("(?i)通过|说明|表明|evidence|by means of|demonstrat|showed"),
    )

    fun hasLeakedRef(text: String) = leakedRef.containsMatchIn(text)
    fun substantive(text: String) = text.isNotBlank() && !missing.containsMatchIn(text) && !hasLeakedRef(text)

    fun hasControlOrBrokenChar(s: String): Boolean = s.any { ch ->
        ch == '\u0000' || ch == '\uFFFD' ||
            (Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t')
    }

    fun isBibliographicFootnote(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        if (ibidLine.containsMatchIn(t)) return true
        val hasPlaceYear = placeYearParen.containsMatchIn(t)
        val hasPageRef = pageRangeRef.containsMatchIn(t)
        if (hasPlaceYear && hasPageRef) return true
        if (hasPlaceYear && (authorInitialPrefix.containsMatchIn(t) || Regex("""(?i)\bed(?:s)?\.""").containsMatchIn(t))) return true
        if (trailingPageRef.containsMatchIn(t) && (authorInitialPrefix.containsMatchIn(t) || Regex("""\((?:1[5-9]\d{2}|20\d{2})\)""").containsMatchIn(t))) return true
        return false
    }

    /** Notes heading. Sectioning should call this instead of keeping its own copy. */
    fun isNotesHeading(line: String): Boolean = notesBoundary.matches(line.trim())

    fun isBackMatterHeading(line: String): Boolean =
        bibliography.matches(line.trim()) || isNotesHeading(line)

    fun isDownloadBanner(line: String): Boolean = publisher.containsMatchIn(line)

    fun endsSentence(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotEmpty() && end.containsMatchIn(trimmed)
    }

    fun isBareNoteNumber(line: String): Boolean = bareNoteNumber.matches(line.trim())

    fun noteNumber(line: String): Int? {
        val match = noteNumberAtStart.find(line.trim()) ?: return null
        return match.groupValues.drop(1).firstOrNull(String::isNotEmpty)?.toIntOrNull()
    }

    /**
     * Numbered endnote page: several note markers begin in the top half.
     * One footer note (marker only in the bottom half) is not an endnote page.
     */
    fun isEndnotePage(lines: List<String>): Boolean {
        val nonblank = lines.map(String::trim).filter(String::isNotEmpty)
        if (nonblank.size < 4) return false
        val markers = nonblank.indices.filter { index ->
            val line = nonblank[index]
            note.containsMatchIn(line) || (isBareNoteNumber(line) && index + 1 < nonblank.size)
        }
        if (markers.size < 3 || markers.first() > nonblank.size / 2) return false
        return markers.size >= 5 || markers.size * 4 >= nonblank.size
    }

    /** A short block that opens with a high note number, such as footnote 57, rather than list item 1. */
    fun isFootnoteBlock(lines: List<String>): Boolean {
        val nonblank = lines.map(String::trim).filter(String::isNotEmpty)
        if (nonblank.isEmpty() || nonblank.size > 6) return false
        val number = noteNumber(nonblank.first()) ?: return false
        if (number < 10) return false
        val first = nonblank.first()
        return isBareNoteNumber(first) || note.containsMatchIn(first) || bottomBareNote.containsMatchIn(first)
    }

    /**
     * The sentence at [startUtf16] is the first one near the top of [pageNumber] and continues
     * a sentence the previous page did not finish. Download banners and folios are not that sentence.
     */
    fun continuesPreviousPage(book: Textbook, pageNumber: Int, startUtf16: Int): Boolean {
        if (pageNumber <= 1 || startUtf16 > 220) return false
        val prev = book.pages.firstOrNull { it.number == pageNumber - 1 } ?: return false
        if (isFrontMatterCover(prev)) return false
        val last = lastProseLine(prev) ?: return false
        if (last.length < 40) return false
        val endChar = last.last()
        return endChar != '.' && endChar != '!' && endChar != '?' &&
            endChar != '。' && endChar != '！' && endChar != '？'
    }

    private fun isFrontMatterCover(page: BookPage): Boolean =
        page.number == 1 && (
            jstorCoverHeader.findAll(page.text).count() >= 2 ||
                coursepackCoverHeader.findAll(page.text).count() >= 2
            )

    private fun lastProseLine(page: BookPage): String? =
        page.guideRegions.asSequence()
            .flatMap { it.text.lineSequence() }
            .map(String::trim)
            .lastOrNull { line ->
                line.isNotEmpty() && !folio.matches(line) && !isDownloadBanner(line) &&
                    !isBackMatterHeading(line) && !isBibliographicFootnote(line)
            }

    fun bodySentence(s: String): Boolean = s.length in 6..650 &&
        !LiteratureScaffoldNoise.rejectsSentence(s) &&
        !hasControlOrBrokenChar(s) &&
        end.containsMatchIn(s) &&
        !publisher.containsMatchIn(s) &&
        !note.containsMatchIn(s) &&
        !isBibliographicFootnote(s) &&
        s.dropWhile { it in "“‘\"'([" }.firstOrNull()?.isLowerCase() != true &&
        (s.count { it.code in 0x3400..0x9fff } >= 5 || Regex("""\p{L}{2,}""").findAll(s).take(4).count() >= 4)

    fun countBodySentences(text: String): Int {
        if (text.isBlank()) return 0
        val iterator = BreakIterator.getSentenceInstance(Locale.ROOT).apply { setText(text) }
        var count = 0
        var a = iterator.first()
        var b = iterator.next()
        while (b != BreakIterator.DONE) {
            val quote = text.substring(a, b).trim()
            if (bodySentence(quote)) count++
            a = b
            b = iterator.next()
        }
        return count
    }

    private fun edgeKey(s: String) = s.trim().lowercase().replace(Regex("""\d+"""), "#")

    internal fun tokens(s: String): Set<String> = buildSet {
        for (m in Regex("""[\p{IsHan}]+|[\p{L}\p{N}]+""").findAll(s.lowercase())) {
            val word = m.value
            if (word.first().code in 0x3400..0x9fff) addAll(word.windowed(2))
            else if (word.length >= 3 && word !in setOf(
                    "the", "and", "what", "how", "was", "are", "did", "for", "with", "why", "does",
                    "that", "this", "from", "were", "have", "has", "had", "not", "but", "into", "their",
                )
            ) add(word.removeSuffix("s"))
        }
    }

    private data class Candidate(val page: BookPage, val region: GuideRegion, val offset: Int, val quote: String)

    /** Extract all clean, filtered body sentences for one book in reading order (no section grouping). */
    fun extractCandidates(book: Textbook): List<GuideExcerpt> {
        val edges = book.pages.flatMap { page ->
            page.guideRegions.flatMap { region ->
                val lines = region.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                (lines.take(2) + lines.takeLast(2)).filter { it.length < 150 }.map(::edgeKey)
            }.distinct()
        }.groupingBy { it }.eachCount().filterValues { it >= 3 }.keys

        val candidates = mutableListOf<Candidate>()
        var backMatter = false
        val notesOpen = book.pages.size / 3
        for (page in book.pages) {
            if (isFrontMatterCover(page)) continue
            if (backMatter) continue
            val pageLines = page.guideRegions.flatMap { it.text.lines() }
            if (LiteratureScaffoldNoise.rejectsPage(pageLines, page.number)) continue
            if (page.number > notesOpen && isEndnotePage(pageLines)) {
                backMatter = true
                continue
            }
            val pageCandidates = mutableListOf<Candidate>()
            var base = 0
            var dropPageTop = continuesPreviousPage(book, page.number, 0)
            for (region in page.guideRegions) {
                val regionLines = region.text.lines()
                if (isFootnoteBlock(regionLines)) continue
                var start = -1
                var finish = 0
                var skipFirst = false
                fun finishParagraph() {
                    if (start < 0) return
                    val paragraph = region.text.substring(start, finish)
                    val dropLead = skipFirst
                    var lead = dropLead
                    val iterator = BreakIterator.getSentenceInstance(Locale.ROOT).apply { setText(paragraph) }
                    var a = iterator.first()
                    var b = iterator.next()
                    while (b != BreakIterator.DONE) {
                        val raw = paragraph.substring(a, b)
                        val quote = raw.trim()
                        if (quote.isNotEmpty()) {
                            val localStart = start + a + raw.indexOf(quote)
                            val localEnd = localStart + quote.length
                            val at = base + localStart
                            val pageTopTail = dropPageTop && at <= 220
                            if (pageTopTail) dropPageTop = false
                            val blankTail = lead
                            lead = false
                            if (!pageTopTail && !blankTail &&
                                !region.overlapsWideGap(localStart, localEnd) &&
                                bodySentence(quote) &&
                                region.containsSentence(quote)
                            ) {
                                pageCandidates += Candidate(page, region, at, quote)
                            }
                        }
                        a = b
                        b = iterator.next()
                    }
                    start = -1
                    skipFirst = !endsSentence(paragraph)
                }
                var offset = 0
                var footnotes = false
                for ((index, raw) in regionLines.withIndex()) {
                    val line = raw.trim()
                    val bottom = index >= regionLines.size / 2
                    val isEdge = index < 3 || index >= regionLines.size - 3
                    val isHeading = (heading.matches(line) && !end.containsMatchIn(line)) ||
                        (line.length in 4..70 && !end.containsMatchIn(line) &&
                            regionLines.getOrNull(index - 1).isNullOrBlank() && regionLines.getOrNull(index + 1).isNullOrBlank() &&
                            line.split(' ').all { it.firstOrNull()?.isUpperCase() == true || it in listOf("of", "the", "in", "and", "a", "to") })
                    when {
                        backMatter -> break
                        isBareNoteNumber(line) && bottom -> {
                            finishParagraph()
                            footnotes = true
                        }
                        line.isBlank() || folio.matches(line) || isDownloadBanner(line) ||
                            isBibliographicFootnote(line) ||
                            (isEdge && edgeKey(line) in edges && (!end.containsMatchIn(line) || line.length < 45)) -> finishParagraph()
                        isBackMatterHeading(line) -> {
                            finishParagraph()
                            if (page.number > notesOpen) backMatter = true
                        }
                        isHeading -> finishParagraph()
                        note.containsMatchIn(line) || (bottom && bottomBareNote.containsMatchIn(line)) -> {
                            finishParagraph()
                            if (bottom) footnotes = true
                        }
                        !footnotes -> {
                            if (start < 0) start = offset
                            finish = offset + raw.length
                        }
                    }
                    offset += raw.length + 1
                }
                finishParagraph()
                base += region.text.length + 2
            }
            // Drop coursepack cover / copyright notice pages: >= 2 copyright term hits and < 3 body sentences
            // (or heavy copyright notice cover on page 1 with >= 4 copyright hits and <= 6 sentences).
            val copyrightHits = copyrightPageTerm.findAll(page.text).count()
            if (copyrightHits >= 2 && (pageCandidates.size < 3 || (page.number == 1 && copyrightHits >= 4 && pageCandidates.size <= 6))) continue
            candidates += pageCandidates
        }
        if (candidates.isEmpty()) return emptyList()
        val seen = HashSet<String>(candidates.size)
        return candidates.mapNotNull { c ->
            val norm = LiteratureTextNorm.normalize(c.quote)
            if (!seen.add(norm)) return@mapNotNull null
            val anchorId = "${book.id}#p${c.page.number}-${c.offset}"
            GuideExcerpt(book, BookCompanionAnchor(c.page.number, anchorId, c.offset, c.offset + c.quote.length, c.quote), c.region)
        }
    }

    private fun scoreCandidate(
        quote: String,
        quoteTokens: Set<String>,
        indexInBook: Int,
        totalInBook: Int,
        questionTokens: Set<String>,
        questionCues: List<Regex>,
        paperTokens: Set<String>,
        questionMentionsPaper: Boolean,
    ): Int {
        val overlap = quoteTokens.count(questionTokens::contains)
        val paperOverlap = quoteTokens.count(paperTokens::contains)
        val intent = questionCues.count { it.containsMatchIn(quote) }
        val arg = if (argument.containsMatchIn(quote)) 4 else 0
        val isBoundary = totalInBook <= 6 || indexInBook < maxOf(3, totalInBook * 15 / 100) ||
            indexInBook >= totalInBook - maxOf(3, totalInBook * 15 / 100)
        val posBonus = if (isBoundary) 4 else 0
        val paperMatchBonus = if (questionMentionsPaper) 8 + paperOverlap * 3 else paperOverlap * 2
        return overlap * 12 + intent * 5 + arg + posBonus + paperMatchBonus
    }

    /** Rank candidates within a single paper by question relevance (incl. author/title), argument cues, and intro/conclusion position. */
    fun rankForPaper(
        candidates: List<GuideExcerpt>,
        meta: LiteraturePaperMeta,
        questions: List<String>,
        excludedQuotes: Set<String> = emptySet(),
    ): List<GuideExcerpt> {
        val active = if (excludedQuotes.isEmpty()) candidates
        else candidates.filterNot { LiteratureTextNorm.normalize(it.anchor.quoteAnchor) in excludedQuotes }
        if (active.isEmpty()) return emptyList()
        val paperTokens = tokens("${meta.author} ${meta.title} ${meta.year}")
        val vocab = active.map { tokens(it.anchor.quoteAnchor) }
        val n = active.size

        // General thesis ranking (intro/conclusion + argument + title keywords) for paper card quality.
        val thesisScores = active.indices.map { i ->
            val q = active[i].anchor.quoteAnchor
            val isEdge = i < maxOf(3, n * 15 / 100) || i >= n - maxOf(3, n * 15 / 100)
            val arg = if (argument.containsMatchIn(q)) 6 else 0
            val titleHit = vocab[i].count(paperTokens::contains) * 3
            (if (isEdge) 8 else 0) + arg + titleHit
        }
        val thesisOrder = active.indices.sortedWith(compareByDescending<Int> { thesisScores[it] }.thenBy { it })

        val questionOrders = questions.map { question ->
            val qTokens = tokens(question)
            val cues = intentCues.filter { it.first.containsMatchIn(question) }.map { it.second }
            val mentionsPaper = qTokens.any(paperTokens::contains)
            val scores = active.indices.map { i ->
                scoreCandidate(active[i].anchor.quoteAnchor, vocab[i], i, n, qTokens, cues, paperTokens, mentionsPaper)
            }
            active.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { Integer.reverse(it) ushr 1 })
        }

        val picked = LinkedHashSet<Int>(n)
        // Guarantee top intro & conclusion sentences enter early.
        val introRange = 0 until minOf(maxOf(3, n * 15 / 100), n)
        val conclRange = maxOf(0, n - maxOf(3, n * 15 / 100)) until n
        introRange.sortedByDescending { thesisScores[it] }.take(2).forEach(picked::add)
        conclRange.sortedByDescending { thesisScores[it] }.take(2).forEach(picked::add)
        for (rank in 0 until n) {
            for (order in questionOrders) picked.add(order[rank])
            picked.add(thesisOrder[rank])
            if (picked.size == n) break
        }
        return picked.map(active::get)
    }

    /** Top [limit] most relevant sentences across ALL books for a single failed question (used in repair). */
    fun topAcrossBooksForQuestion(
        books: List<Textbook>,
        metas: Map<String, LiteraturePaperMeta>,
        candidatesByBook: Map<String, List<GuideExcerpt>>,
        question: String,
        excludedQuotes: Set<String> = emptySet(),
        limit: Int = 30,
    ): List<GuideExcerpt> {
        val qTokens = tokens(question)
        val cues = intentCues.filter { it.first.containsMatchIn(question) }.map { it.second }
        val scored = mutableListOf<Pair<GuideExcerpt, Int>>()
        for (book in books) {
            val all = candidatesByBook[book.id].orEmpty()
            val meta = metas[book.id] ?: LiteraturePaperMeta(book.id, LiteratureTextNorm.decodeHtmlEntities(book.title))
            val paperTokens = tokens("${meta.author} ${meta.title} ${meta.year}")
            val mentionsPaper = qTokens.any(paperTokens::contains)
            all.forEachIndexed { idx, excerpt ->
                if (LiteratureTextNorm.normalize(excerpt.anchor.quoteAnchor) in excludedQuotes) return@forEachIndexed
                val q = excerpt.anchor.quoteAnchor
                val score = scoreCandidate(q, tokens(q), idx, all.size, qTokens, cues, paperTokens, mentionsPaper)
                scored += excerpt to score
            }
        }
        return scored.sortedWith(
            compareByDescending<Pair<GuideExcerpt, Int>> { it.second }
                .thenBy { books.indexOf(it.first.book) }
                .thenBy { it.first.anchor.pdfPage }
                .thenBy { it.first.anchor.startUtf16 },
        ).take(limit).map { it.first }
    }
}
