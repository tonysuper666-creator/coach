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
    private val heading = Regex("""(?i)^(?:abstract|introduction|background|discussion|method(?:s|ology)?|results?|conclusions?|summary|引言|导言|摘要|背景|方法|结果|讨论|结论|小结|(?:[IVX]+\.|\d+(?:\.\d+)*[.)])\s+\p{L}.{1,80})$""")
    private val end = Regex("""[.!?。！？][”’"')\]]*$""")
    private val folio = Regex("""(?i)^(?:page\s*)?(?:\d+|[ivxlcdm]{1,6})$""")
    private val argument = Regex("""(?i)\b(?:argue|argues|argued|propose|because|therefore|however|whereas|suggests?|conclude|consequently|demonstrates?|explains?|evidence|defined?|central|thesis|claim|claims)\b|因此|然而|本文|表明|说明|认为|关键|核心""")
    // Fallback safety net; primary answerability gate is structured `answerable` + evidence `refs`.
    private val missing = Regex(
        """(?i)(?:片段|材料|文中|摘录|原文|资料).{0,16}(?:未(?:曾)?(?:直接)?(?:涉及|提及|说明|交代|提供|给出)|没有(?:直接)?(?:涉及|提及|提供|给出|说明)|不足以|[仅只]有.{0,4}页眉)|(?:无法|不能|难以)(?:据此|直接|从.{1,12}中)?(?:回答|作答|判断|确定|推断|验证)|(?:cannot|can't|unable to) (?:answer|determine|verify)|(?:excerpt|passage|material).{0,24}(?:does not|insufficient|not (?:mention|cover))""",
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

    fun substantive(text: String) = text.isNotBlank() && !missing.containsMatchIn(text)

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

    fun bodySentence(s: String): Boolean = s.length in 6..650 &&
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
        for (page in book.pages) {
            if (page.number == 1 && (jstorCoverHeader.findAll(page.text).count() >= 2 ||
                    coursepackCoverHeader.findAll(page.text).count() >= 2)
            ) continue
            val pageCandidates = mutableListOf<Candidate>()
            var base = 0
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
                        if (quote.isNotEmpty()) {
                            val localStart = start + a + raw.indexOf(quote)
                            val localEnd = localStart + quote.length
                            if (!region.overlapsWideGap(localStart, localEnd) &&
                                bodySentence(quote) &&
                                region.containsSentence(quote)
                            ) {
                                pageCandidates += Candidate(page, region, base + localStart, quote)
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
                    val isHeading = (heading.matches(line) && !end.containsMatchIn(line)) ||
                        (line.length in 4..70 && !end.containsMatchIn(line) &&
                            lines.getOrNull(index - 1).isNullOrBlank() && lines.getOrNull(index + 1).isNullOrBlank() &&
                            line.split(' ').all { it.firstOrNull()?.isUpperCase() == true || it in listOf("of", "the", "in", "and", "a", "to") })
                    when {
                        backMatter -> break
                        line.isBlank() || folio.matches(line) || publisher.containsMatchIn(line) ||
                            isBibliographicFootnote(line) ||
                            (isEdge && edgeKey(line) in edges && (!end.containsMatchIn(line) || line.length < 45)) -> finishParagraph()
                        bibliography.matches(line) -> { finishParagraph(); backMatter = true }
                        isHeading -> finishParagraph()
                        note.containsMatchIn(line) || (index >= lines.size / 2 && bottomBareNote.containsMatchIn(line)) -> {
                            finishParagraph()
                            footnotes = true
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
        require(candidates.isNotEmpty()) { "《${book.title}》没有可核对的正文句，请检查原件版面或文字识别。" }
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
