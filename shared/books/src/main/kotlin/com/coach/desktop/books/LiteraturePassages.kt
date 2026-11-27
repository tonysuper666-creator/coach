package com.coach.desktop.books

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
    private val bibliography = Regex("""(?i)^(?:\d+(?:\.\d+)*\.?\s+|[IVX]+\.?\s+)?(?:references|bibliography|works cited|endnotes|notes and references|参考文献|尾注)\s*[:：]?$""")
    // Shared with sectioning (TON-1898): a notes heading, not a sentence that merely mentions notes.
    private val notesBoundary = Regex(
        """(?i)^(?:endnotes?|footnotes?|notes(?:\s+to\s+(?:pages?|chapters?|the\s+text))?|参考文献|尾注|注释)\s*[:：]?$""",
    )
    private val bareNoteNumber = Regex("""^\d{1,3}[.)]?$""")
    private val leadingNote = Regex("""^\s*\d{1,3}(?=\s+\p{Lu}|\p{Lu})""")
    private val noteNumberAtStart = Regex("""^(?:\[(\d{1,3})]|(?:(\d{1,3}))[.)]?)(?:\s|$)""")
    private val heading = Regex("""(?i)^(?:abstract|introduction|background|discussion|method(?:s|ology)?|results?|conclusions?|summary|appendix(?:\s+[A-Z0-9]+)?|引言|导言|摘要|背景|方法|结果|讨论|结论|小结|(?:[IVX]+\.|\d+(?:\.\d+)*[.)])\s+\p{L}.{1,80})$""")
    private val standaloneAbstract = Regex("""(?i)^(?:abstract|摘要)\s*[.:：]?$""")
    private val inlineAbstractLead = Regex("""(?i)^abstract\s*(?:[.:—–-]\s*|\s+(?=[A-Z]))""")
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
    private val authorYearPage = Regex(
        """\([A-Z][\p{L}'\-]+(?:\s+et\s+al\.?)?,\s*(?:1[5-9]\d{2}|20\d{2})\s*[,:;]\s*(?:pp?\.\s*)?\d+""",
    )
    private val cjkYearPage = Regex("""（[^）]{0,40}(?:1[5-9]\d{2}|20\d{2})[^）]{0,20}第\s*\d+\s*页""")
    private val noteCue = Regex(
        """(?i)\bon whom see\b|\bsee also\b|\b(?:ibid|op\.?\s*cit|loc\.?\s*cit)\b|^\s*see\s+|\(\s*(?:cf\.?|see)\s+(?-i:[A-Z])|^\s*(?-i:[A-Z])[\p{L}'\-]{2,},\s+(?-i:[A-Z])[^.]{0,80}\b(?:1[5-9]\d{2}|20\d{2})\b""",
    )
    private val seeShortEnd = Regex("""(?i)\bsee\s+(?:also\s+)?(?:[A-Z]\.|vol\.|ch\.|pp?\.)\s*\d*\s*$""")
    private val bibliographyPageEnd = Regex(
        """(?i)(?:\bpp?\.\s*\d+(?:\s*[-–—]\s*\d+)?|(?:1[5-9]\d{2}|20\d{2})\)?,?\s+(?:pp?\.\s*)?\d{1,4})\s*[.。]?\s*$""",
    )
    private val abbreviations = setOf(
        "ph", "phil", "tr", "mr", "mrs", "ms", "dr", "st", "jr", "sr", "prof", "vol", "vols",
        "no", "nos", "pp", "p", "cf", "ed", "eds", "fig", "figs", "al", "etc", "eg", "ie",
        "ibid", "op", "cit", "loc", "vs", "ca", "ch", "esp", "viz", "rev", "trans",
        "dept", "univ", "inc", "ltd", "mt", "ft", "gen", "col", "capt", "sgt", "sig",
    )
    private val compactSpace = Regex("""\s+""")
    private const val BODY_SENTENCE_MAX = 650
    /** A real sentence that keeps an abbreviation such as Sig. or Ph.Tr. can run past the ordinary cap. */
    private const val ABBREVIATED_SENTENCE_MAX = 1300
    private val closingQuotes = "”’\"')]"

    /** Quiz and guide repair text. The part before the colon is the rule name. */
    const val FOOTNOTE_REJECTION = "footer_note:这是页脚注释或尾注，不能当作正文出处"

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

    /**
     * Footer, endnote, or bibliographic citation. Shared by the one-pager, quiz, comparison, and follow-along.
     * A prose sentence that merely mentions a year is not a footnote.
     */
    fun isFootnoteSentence(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        return isBibliographicFootnote(t) ||
            authorYearPage.containsMatchIn(t) ||
            cjkYearPage.containsMatchIn(t) ||
            noteCue.containsMatchIn(t) ||
            seeShortEnd.containsMatchIn(t) ||
            bibliographyPageEnd.containsMatchIn(t)
    }

    /**
     * Footer, endnote, or a span the PDF layout marked as the note zone.
     * Candidate extraction and citation checks both call this, so a sentence that entered
     * the pool is not later rejected for the same reason.
     */
    fun rejectedFootnote(
        quote: String,
        pageText: String,
        pageOffset: Int,
        region: GuideRegion,
        localStart: Int,
        localEnd: Int,
    ): Boolean = region.overlapsFootnote(localStart, localEnd) ||
        (!region.measured && (isFootnoteSentence(quote) || followsFootnoteMarker(pageText, pageOffset)))

    fun rejectedFootnote(excerpt: GuideExcerpt): Boolean {
        val anchor = excerpt.anchor
        val page = excerpt.book.page(anchor.pdfPage)
        val base = if (page == null) 0 else regionBase(page, excerpt.region)
        return rejectedFootnote(
            anchor.quoteAnchor,
            page?.text.orEmpty(),
            anchor.startUtf16,
            excerpt.region,
            anchor.startUtf16 - base,
            anchor.endUtf16 - base,
        )
    }

    /** Page text joins guide regions with a blank line, so a quote's page offset is base plus its region offset. */
    fun regionBase(page: BookPage, region: GuideRegion): Int {
        var base = 0
        for (candidate in page.guideRegions) {
            if (candidate === region) return base
            base += candidate.text.length + 2
        }
        base = 0
        for (candidate in page.guideRegions) {
            if (candidate.text == region.text && candidate.footnoteRanges == region.footnoteRanges) return base
            base += candidate.text.length + 2
        }
        return 0
    }

    /**
     * The span sits on the sentence after a footnote marker on its own line.
     * A note number glued to the previous body sentence, such as "look.8", is a callout.
     * The next sentence is still body text. A page number at the top is not a marker either.
     */
    fun followsFootnoteMarker(pageText: String, start: Int): Boolean {
        if (start <= 0 || start > pageText.length) return false
        val priorLines = pageText.substring(0, start).lineSequence().map(String::trim).toList()
        val prevIndex = priorLines.indexOfLast(String::isNotEmpty)
        if (prevIndex < 0) return false
        val prev = priorLines[prevIndex]
        if (!isBareNoteNumber(prev)) return false
        val number = noteNumber(prev) ?: return false
        return number >= 10 && priorLines.take(prevIndex).any { endsSentence(it) }
    }

    /** Notes heading. Sectioning should call this instead of keeping its own copy. */
    fun isNotesHeading(line: String): Boolean = notesBoundary.matches(line.trim())

    fun isBackMatterHeading(line: String): Boolean =
        bibliography.matches(line.trim()) || isNotesHeading(line)

    fun isDownloadBanner(line: String): Boolean = publisher.containsMatchIn(line)

    fun endsSentence(text: String): Boolean {
        val core = withoutTrailingNoteMark(text.trimEnd()).trimEnd().dropLastWhile { it in closingQuotes }
        if (core.isEmpty()) return false
        val last = core.last()
        if (last == '!' || last == '?' || last == '。' || last == '！' || last == '？') return true
        if (last != '.') return false
        return !isAbbreviationPeriod(core, core.length - 1)
    }

    /** "meeting.12" and "society'.12" end at the period. A decimal such as "3.14" does not. */
    private fun withoutTrailingNoteMark(text: String): String {
        var i = text.length
        var digits = 0
        while (i > 0 && text[i - 1].isDigit() && digits < 3) {
            digits++
            i--
        }
        if (digits == 0 || i == 0) return text
        var quote = i
        while (quote > 0 && text[quote - 1] in closingQuotes) quote--
        if (quote == 0 || text[quote - 1] != '.') return text
        if (quote >= 2 && text[quote - 2].isDigit()) return text
        return text.substring(0, quote)
    }

    /** Periods inside Ph.Tr., Phil., Sig., p. 21, and initials are not sentence boundaries. */
    private fun containsAbbreviationPeriod(text: String): Boolean {
        for (i in text.indices) {
            if (text[i] == '.' && isAbbreviationPeriod(text, i)) return true
        }
        return false
    }

    private fun isAbbreviationPeriod(text: String, period: Int): Boolean {
        if (period <= 0 || period >= text.length || text[period] != '.') return false
        var i = period - 1
        while (i >= 0 && text[i].isLetter()) i--
        val word = text.substring(i + 1, period)
        if (word.isEmpty()) return false
        if (word.length == 1 && (word[0].isUpperCase() ||
                i >= 1 && text[i] == '.' && text[i - 1].isLetter() && (i == 1 || !text[i - 2].isLetter()))) return true
        return word.lowercase() in abbreviations
    }

    private fun sentenceBounds(text: String): List<Pair<Int, Int>> {
        val bounds = ArrayList<Pair<Int, Int>>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val hard = ch == '!' || ch == '?' || ch == '。' || ch == '！' || ch == '？'
            val closes = ch == '.' && !isAbbreviationPeriod(text, i) && !staysInside(text, i) && closerFollows(text, i)
            if (hard || closes) {
                var bound = i + 1
                while (bound < text.length && text[bound] in "”’\"')]") bound++
                if (text.substring(start, bound).isNotBlank()) bounds += start to bound
                val noteDigits = noteDigitsAfter(text, bound)
                start = bound + noteDigits
                i = start
            } else {
                i++
            }
        }
        if (start < text.length && text.substring(start).isNotBlank()) bounds += start to text.length
        return bounds
    }

    /**
     * A period glued to a lowercase letter, a continuing digit, or a quote is not a cut.
     * "meeting.12" still ends: those digits are a note mark and are stripped from the next sentence.
     * A closing quote with a note mark after it, as in "society'.12", also ends.
     */
    private fun staysInside(text: String, period: Int): Boolean {
        val next = period + 1
        if (next >= text.length) return false
        val ch = text[next]
        if (ch.isLowerCase()) return true
        if (ch == '\'' || ch == '"' || ch == '\u2018' || ch == '\u2019' || ch == '\u201C' || ch == '\u201D') {
            var quote = next + 1
            while (quote < text.length && text[quote] in closingQuotes) quote++
            return noteDigitsAfter(text, quote) == 0
        }
        return ch.isDigit() && noteDigitsAfter(text, next) == 0
    }

    private fun closerFollows(text: String, period: Int): Boolean {
        var j = period + 1
        while (j < text.length && text[j] in "”’\"')]") j++
        return j >= text.length || text[j].isWhitespace() || noteDigitsAfter(text, j) > 0
    }

    /** Digits glued to a sentence end, as in "meeting.12", are a note mark, not "3.14". */
    private fun noteDigitsAfter(text: String, index: Int): Int {
        var mark = index - 1
        while (mark >= 0 && text[mark] in "”’\"')]") mark--
        if (mark > 0 && text[mark] == '.' && text[mark - 1].isDigit()) return 0
        var j = index
        var digits = 0
        while (j < text.length && text[j].isDigit() && digits < 3) {
            digits++
            j++
        }
        if (digits == 0) return 0
        return if (j >= text.length || text[j].isWhitespace()) digits else 0
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
                    !isBackMatterHeading(line) && !isFootnoteSentence(line)
            }

    fun bodySentence(excerpt: GuideExcerpt): Boolean = bodySentence(excerpt.anchor.quoteAnchor, excerpt.region.measured)

    fun bodySentence(s: String, measured: Boolean = false): Boolean {
        val compact = compactSpace.replace(s, " ").trim()
        val limit = if (containsAbbreviationPeriod(compact)) ABBREVIATED_SENTENCE_MAX else BODY_SENTENCE_MAX
        return compact.length in 6..limit &&
            !LiteratureScaffoldNoise.rejectsSentence(compact) &&
            !hasControlOrBrokenChar(s) &&
            end.containsMatchIn(compact) &&
            !publisher.containsMatchIn(s) &&
            !(if (measured) isBibliographicFootnote(s) else note.containsMatchIn(s) || isFootnoteSentence(s)) &&
            compact.dropWhile { it in "“‘\"'([" }.firstOrNull()?.isLowerCase() != true &&
            (compact.count { it.code in 0x3400..0x9fff } >= 5 || Regex("""\p{L}{2,}""").findAll(compact).take(4).count() >= 4)
    }

    fun countBodySentences(text: String): Int {
        if (text.isBlank()) return 0
        var count = 0
        for ((a, b) in sentenceBounds(text)) {
            if (bodySentence(text.substring(a, b).trim())) count++
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
            if (page.number > notesOpen && page.guideRegions.none { it.measured && it.footnoteRanges.isNotEmpty() } && isEndnotePage(pageLines)) {
                backMatter = true
                continue
            }
            val pageCandidates = mutableListOf<Candidate>()
            var footnoteLines = 0
            var proseLines = 0
            var base = 0
            var dropPageTop = continuesPreviousPage(book, page.number, 0)
            var skipSection = false
            for (region in page.guideRegions) {
                val regionLines = region.text.lines()
                if (!region.measured && isFootnoteBlock(regionLines)) {
                    base += region.text.length + 2
                    continue
                }
                // On page 1 of an academic paper, drop title/author/affiliation/email header lines above Abstract.
                val abstractLineIdx = if (page.number == 1) {
                    regionLines.indexOfFirst { rawLine ->
                        val t = rawLine.trim()
                        standaloneAbstract.matches(t) || inlineAbstractLead.containsMatchIn(t)
                    }.takeIf { it in 1..60 } ?: -1
                } else -1
                var start = -1
                var finish = 0
                var skipFirst = false
                fun finishParagraph() {
                    if (start < 0) return
                    val paragraph = region.text.substring(start, finish)
                    val dropLead = skipFirst
                    var lead = dropLead
                    for ((a, b) in sentenceBounds(paragraph)) {
                        var from = a
                        val noteLead = leadingNote.find(paragraph.substring(a, b))
                        if (noteLead != null && noteLead.range.first == 0) from = a + noteLead.value.length
                        val raw = paragraph.substring(from, b)
                        val quote = raw.trim()
                        if (quote.isNotEmpty()) {
                            val localStart = start + from + raw.indexOf(quote)
                            val localEnd = localStart + quote.length
                            val at = base + localStart
                            val pageTopTail = dropPageTop && at <= 220
                            if (pageTopTail) dropPageTop = false
                            val blankTail = lead
                            lead = false
                            if (!pageTopTail && !blankTail &&
                                !rejectedFootnote(quote, page.text, at, region, localStart, localEnd) &&
                                !region.overlapsWideGap(localStart, localEnd) &&
                                bodySentence(quote, region.measured) &&
                                region.containsSentence(quote)
                            ) {
                                pageCandidates += Candidate(page, region, at, quote)
                            }
                        }
                    }
                    start = -1
                    skipFirst = !endsSentence(paragraph)
                }
                var offset = 0
                var footnotes = false
                var inCaption = false
                var captionDelimited = false
                var prevCaptionEnded = false
                for ((index, raw) in regionLines.withIndex()) {
                    val line = raw.trim()
                    if (region.overlapsFootnote(offset, offset + raw.length)) {
                        finishParagraph()
                        offset += raw.length + 1
                        continue
                    }
                    if (index < abstractLineIdx) {
                        offset += raw.length + 1
                        continue
                    }
                    val bottom = index >= regionLines.size / 2
                    val isEdge = index < 3 || index >= regionLines.size - 3
                    val isHeading = (heading.matches(line) && !end.containsMatchIn(line)) ||
                        standaloneAbstract.matches(line) ||
                        (line.length in 4..70 && !end.containsMatchIn(line) &&
                            regionLines.getOrNull(index - 1).isNullOrBlank() && regionLines.getOrNull(index + 1).isNullOrBlank() &&
                            line.split(' ').all { it.firstOrNull()?.isUpperCase() == true || it in listOf("of", "the", "in", "and", "a", "to") })
                    val isBackMatter = isBackMatterHeading(line)
                    val isSkipHeading = LiteratureScaffoldNoise.isSkipSectionHeading(line)
                    val isInlineSkip = LiteratureScaffoldNoise.isInlineSkipSectionStart(line)
                    if (inCaption) {
                        val breakCaption = line.isBlank() || isBackMatter || isSkipHeading || isHeading ||
                            (captionDelimited && prevCaptionEnded && line.firstOrNull()?.isLowerCase() == true) ||
                            (!captionDelimited && prevCaptionEnded)
                        if (breakCaption) {
                            inCaption = false
                            if (captionDelimited && prevCaptionEnded && line.firstOrNull()?.isLowerCase() == true) {
                                skipFirst = true
                            }
                        } else {
                            prevCaptionEnded = endsSentence(line)
                            offset += raw.length + 1
                            continue
                        }
                    }
                    val delimitedCap = LiteratureScaffoldNoise.isDelimitedCaptionStart(line) ||
                        LiteratureScaffoldNoise.isSubfigureCaptionLine(regionLines, index)
                    val undelimitedCap = !delimitedCap && LiteratureScaffoldNoise.isUndelimitedCaptionStart(line)
                    val abstractMatch = if (page.number == 1) inlineAbstractLead.find(raw) else null
                    val number = noteNumber(line) ?: 0
                    val afterBody = !region.measured && !isEdge &&
                        region.text.substring(0, offset.coerceAtMost(region.text.length)).lineSequence().any { endsSentence(it) }
                    val midBare = isBareNoteNumber(line) && number >= 10 && afterBody
                    val marker = isBareNoteNumber(line) || note.containsMatchIn(line) ||
                        (bottom && bottomBareNote.containsMatchIn(line)) ||
                        (bottomBareNote.containsMatchIn(line) && number >= 10 && afterBody)
                    val open = if (start >= 0) region.text.substring(start, finish.coerceAtMost(region.text.length)) else ""
                    val unfinished = start >= 0 && open.isNotBlank() && !endsSentence(open)
                    val nextLine = regionLines.getOrNull(index + 1)?.trim().orEmpty()
                    val nextIsNote = nextLine.isEmpty() || isFootnoteSentence(nextLine) || isBareNoteNumber(nextLine) ||
                        note.containsMatchIn(nextLine) || bottomBareNote.containsMatchIn(nextLine)
                    // A superscript note mark is its own line when its baseline sits above the words it follows.
                    // That line is not the note. The sentence keeps going, including when the next word is capitalized.
                    val superscriptInsideSentence = unfinished && isBareNoteNumber(line) && number >= 10 && !nextIsNote
                    val continuesOpenSentence = unfinished && !marker
                    val footnoteLine = !region.measured && line.isNotEmpty() && !continuesOpenSentence && !superscriptInsideSentence &&
                        (isFootnoteSentence(line) || marker)
                    when {
                        backMatter -> break
                        isBackMatter -> {
                            finishParagraph()
                            skipSection = false
                            if (page.number > notesOpen) backMatter = true
                        }
                        isSkipHeading || isInlineSkip -> {
                            finishParagraph()
                            skipSection = true
                        }
                        isHeading -> {
                            finishParagraph()
                            skipSection = false
                        }
                        skipSection -> {
                            // Inside Acknowledgments / Funding / Ethics / Declarations section.
                        }
                        delimitedCap || undelimitedCap -> {
                            finishParagraph()
                            inCaption = true
                            captionDelimited = delimitedCap
                            prevCaptionEnded = endsSentence(line)
                        }
                        LiteratureScaffoldNoise.isKeywordsOrMetaLine(line) -> finishParagraph()
                        superscriptInsideSentence -> Unit
                        footnoteLine -> {
                            finishParagraph()
                            footnoteLines++
                            if ((bottom && marker) || midBare) footnotes = true
                        }
                        line.isBlank() -> {
                            if (start >= 0) {
                                val open = region.text.substring(start, finish)
                                if (open.isBlank() || endsSentence(open)) finishParagraph()
                            }
                        }
                        folio.matches(line) || isDownloadBanner(line) ||
                            (isEdge && edgeKey(line) in edges && (!end.containsMatchIn(line) || line.length < 45)) -> finishParagraph()
                        abstractMatch != null && !footnotes -> {
                            finishParagraph()
                            val bodyStart = offset + abstractMatch.range.last + 1
                            if (bodyStart < offset + raw.length) {
                                start = bodyStart
                                finish = offset + raw.length
                            }
                        }
                        !footnotes -> {
                            if (start < 0) start = offset
                            finish = offset + raw.length
                            proseLines++
                        }
                    }
                    offset += raw.length + 1
                }
                finishParagraph()
                base += region.text.length + 2
            }
            if (footnoteLines >= 3 && footnoteLines * 2 >= footnoteLines + proseLines) continue
            // Drop coursepack cover / copyright notice pages: >= 2 copyright term hits and < 3 body sentences
            // (or heavy copyright notice cover on page 1 with >= 4 copyright hits and <= 6 sentences).
            // Never drop the final body sentences on a page that transitions into back-matter References (ISBN citations).
            val copyrightHits = copyrightPageTerm.findAll(page.text).count()
            if (!backMatter && copyrightHits >= 2 && (pageCandidates.size < 3 || (page.number == 1 && copyrightHits >= 4 && pageCandidates.size <= 6))) continue
            candidates += pageCandidates
        }
        if (candidates.isEmpty()) return emptyList()
        return candidates.map { c ->
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
        val seen = excludedQuotes.toMutableSet()
        val active = candidates.filter { seen.add(LiteratureTextNorm.normalize(it.anchor.quoteAnchor)) }
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
            val all = candidatesByBook[book.id].orEmpty().distinctBy { LiteratureTextNorm.normalize(it.anchor.quoteAnchor) }
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
