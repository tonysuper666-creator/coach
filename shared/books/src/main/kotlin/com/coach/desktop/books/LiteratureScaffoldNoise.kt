package com.coach.desktop.books

/**
 * Drops contents lines, table titles, figure captions, publication boilerplate, errata notes,
 * acknowledgments, funding/grant statements, and front-matter author/affiliation noise
 * before a sentence can enter the one-page guide, comparison table, or station follow route.
 * A body sentence that only mentions a figure number stays.
 */
internal object LiteratureScaffoldNoise {
    private val spacedDots = Regex("""(?:\.\s{1,4}){4,}""")
    private val contentsHeading = Regex(
        """(?i)^(?:table of contents|contents|list of (?:figures|tables|illustrations|plates)|目\s*录|总\s*目|图\s*目|表\s*目)[.。:：]?$""",
    )
    private val errataHeading = Regex("""(?i)^(?:errata|erratum|勘误|更正说明)[.。:：]?$""")
    // Delimited figure/table captions: "Figure 2:", "Fig. 2.", "Table 5.", "Table 1:", "图 1：", "表 2."
    // Negative lookahead (?!\d) after '.' prevents decimal numbers like "Figure 11.29" from backtracking onto '.'.
    private val delimitedCaptionStart = Regex(
        """(?i)^(?:(?:figures?|fig\.?|tables?|tab\.?|illustrations?|plates?|schemes?|listings?|algorithms?)\s+\d+(?:\.\d+)*(?:[a-z])?\s*(?:[:—–-]|\.(?!\d))|(?:图|表)\s*\d+(?:\.\d+)*(?:[a-z])?\s*(?:[:：—–-]|\.(?!\d)))""",
    )
    // OpenStax-style undelimited captions: "Figure 12.5 The tradeoff...", "Table 1.1 Benefit use..."
    // Requires an uppercase lead word after the number so body prose like "Figure 5 shows..." stays.
    private val undelimitedCaptionStart = Regex(
        """^(?:Figures?|Fig\.?|Tables?|Tab\.?|Illustrations?|Plates?)\s+\d+(?:\.\d+)*(?:[a-z])?\s+(?=[A-Z])|^(?:图|表)\s*\d+(?:\.\d+)*(?:[a-z])?\s+(?!展示|显示|表明|说明|给出|列出|对比|反映|中|的|所示)\S""",
    )
    private val figureCaptionLine = Regex(
        """(?i)^(?:figures?|fig\.?|illustrations?|plates?)\s+\d+(?:\.\d+)*(?:[a-z])?(?:\s*[:.—–-]|\s+[A-Z])""",
    )
    private val subfigureStart = Regex("""^\([a-z]\)\s+[A-Z]""")
    private val inlineSecondSubfigure = Regex("""\([b-z]\)\s+[A-Z]""")
    private val captionLegendSentence = Regex(
        """(?i)^(?:(?:the\s+)?error\s+bars?\s+(?:represent|show|indicate|denote)s?\b|(?:the\s+)?(?:dashed|dotted|solid)\s+lines?\s+(?:indicate|represent|show|denote)s?\b|\*+\s*(?:represents?|indicates?|denotes?)\s+(?:a\s+)?significant\s+difference\b)""",
    )
    private val publicationCue = Regex(
        """(?i)openstax|creative commons|\bcc\s*by\b|rice university|openly licensed|\bisbn\b|all rights reserved|nonprofit based at|版权所有|图书在版编目|版权页""",
    )
    // Page-level front-matter cues exclude bare ISBNs so a References page citing books with ISBNs is not misclassified.
    private val frontMatterPublicationCue = Regex(
        """(?i)openstax|creative commons|\bcc\s*by\b|rice university|openly licensed|all rights reserved|nonprofit based at|版权所有|图书在版编目|版权页""",
    )
    private val errataCue = Regex(
        """(?i)\berrata\b|\berratum\b|勘误|更正说明|correction to suggest|submit it through the link on your book page""",
    )
    private val skipSectionHeading = Regex(
        """(?i)^(?:\d+(?:\.\d+)*\.?\s+|[IVX]+\.?\s+)?(?:acknowledg(?:e)?ments?|funding(?:\s+(?:statement|information|sources?))?|financial\s+support|grant\s+support|conflict(?:s)?\s+of\s+interest|competing\s+interests?|author\s+contributions?|ethics\s+(?:statement|approval)|declarations?|data\s+availability|code\s+availability|致谢|鸣谢|基金资助|资助说明|利益冲突)\s*[.:：]?$""",
    )
    private val inlineSkipSectionStart = Regex(
        """(?i)^(?:acknowledg(?:e)?ments?|funding|grant\s+support|致谢|鸣谢)\s*[:：.—–-]?\s+(?:we(?:'d|\s+would)?\b|the\s+authors?\b|this\s+(?:work|study|research|paper|project)\b|our\s+(?:work|research|study)\b|本)""",
    )
    private val fundingOrAckSentence = Regex(
        """(?i)\b(?:this\s+(?:work|research|study|paper|project)\s+(?:was|is)\s+(?:partially\s+|partly\s+|generously\s+|financially\s+)?(?:supported|funded)\s+by\b|supported\s+(?:in\s+part\s+)?by\s+(?:the\s+)?(?:nsf|onr|nih|darpa|jsps|kakenhi|erc|nsfc|mitacs|grant|a\s+grant|microsoft|google|meta|amazon|funding)\b|(?:was|is)\s+(?:partially\s+|partly\s+|generously\s+)?funded\s+by\b|grant\s+(?:no\.?|number|numbers|#)\s*[A-Z0-9]|we(?:'d|\s+would)?\s+(?:like\s+to\s+|gratefully\s+|sincerely\s+)?thank\b|the\s+authors?\s+(?:would\s+like\s+to\s+|gratefully\s+|sincerely\s+)?thank\b|additional\s+thanks\s+are\s+extended\s+to\b|we\s+thank\b|acknowledg(?:e)?\s+(?:the\s+)?(?:financial\s+support|funding|grant|assistance\s+of|support\s+from)\b)|基金项目|本项目受|本研究受|国家自然科学基金|资助项目|感谢.{0,20}(?:资助|支持|帮助|建议|指导)""",
    )
    private val keywordsOrMetaLine = Regex(
        """(?i)^(?:key\s*words?|index\s+terms|correspondence|关键词)\s*[:：—–-]""",
    )
    private val emailPattern = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")
    private val preprintNotice = Regex(
        """(?i)\barxiv\s*:\s*\d{4}\.\d+|^this\s+is\s+a\s+preprint\b|\bversion\s+of\s+record\b""",
    )
    private val superscriptAuthorList = Regex("""\b[A-Z][\p{L}'\-]{2,20}\d+(?:\s*,\s*\d+)+\b""")
    private val affiliationAbstractTail = Regex(
        """(?i)\b(?:chair|university|institute|department|college|inc\.|corp\.|mila|mbzuai|laboratory|school\s+of)\s+abstract\.?$""",
    )
    private const val FRONT_MATTER_PAGE = 20

    fun isDelimitedCaptionStart(line: String): Boolean = delimitedCaptionStart.containsMatchIn(line.trim())

    fun isUndelimitedCaptionStart(line: String): Boolean = undelimitedCaptionStart.containsMatchIn(line.trim())

    fun isSubfigureCaptionLine(lines: List<String>, index: Int): Boolean {
        val line = lines.getOrNull(index)?.trim().orEmpty()
        if (!subfigureStart.containsMatchIn(line)) return false
        if (inlineSecondSubfigure.containsMatchIn(line)) return true
        val end = minOf(lines.lastIndex, index + 25)
        for (i in (index + 1)..end) {
            if (figureCaptionLine.containsMatchIn(lines[i].trim())) return true
        }
        return false
    }

    fun isSkipSectionHeading(line: String): Boolean = skipSectionHeading.matches(line.trim())

    fun isInlineSkipSectionStart(line: String): Boolean = inlineSkipSectionStart.containsMatchIn(line.trim())

    fun isKeywordsOrMetaLine(line: String): Boolean {
        val trimmed = line.trim()
        return keywordsOrMetaLine.containsMatchIn(trimmed) ||
            emailPattern.containsMatchIn(trimmed) ||
            preprintNotice.containsMatchIn(trimmed)
    }

    fun rejectsSentence(raw: String): Boolean {
        val line = raw.trim()
        if (line.isEmpty()) return false
        return isLeaderLine(line) ||
            isContentsHeading(line) ||
            isErrataHeading(line) ||
            isSkipSectionHeading(line) ||
            isInlineSkipSectionStart(line) ||
            delimitedCaptionStart.containsMatchIn(line) ||
            undelimitedCaptionStart.containsMatchIn(line) ||
            captionLegendSentence.containsMatchIn(line) ||
            publicationCue.containsMatchIn(line) ||
            errataCue.containsMatchIn(line) ||
            fundingOrAckSentence.containsMatchIn(line) ||
            keywordsOrMetaLine.containsMatchIn(line) ||
            emailPattern.containsMatchIn(line) ||
            preprintNotice.containsMatchIn(line) ||
            superscriptAuthorList.containsMatchIn(line) ||
            affiliationAbstractTail.containsMatchIn(line)
    }

    /**
     * A contents page, a front-matter publication page, or an errata note page.
     * Two publisher mentions on a later chapter page are not enough to drop that page:
     * those sentences are rejected on their own, and the body stays.
     * A page containing a References/Bibliography heading is back-matter, not front-matter.
     */
    fun rejectsPage(lines: List<String>, pageNumber: Int): Boolean {
        val nonblank = lines.map(String::trim).filter(String::isNotEmpty)
        if (nonblank.isEmpty()) return false
        if (nonblank.any { LiteraturePassages.isBackMatterHeading(it) }) return false
        val leaders = nonblank.count(::isLeaderLine)
        if (leaders >= 4 && leaders * 2 >= nonblank.size) return true
        if (nonblank.any(::isContentsHeading) && leaders >= 3) return true
        val boilerplate = nonblank.count { frontMatterPublicationCue.containsMatchIn(it) || errataCue.containsMatchIn(it) }
        if (pageNumber <= FRONT_MATTER_PAGE && boilerplate >= 2) return true
        return nonblank.any(::isErrataHeading) && boilerplate >= 1
    }

    private fun isContentsHeading(line: String) = contentsHeading.matches(line.trim())

    private fun isErrataHeading(line: String) = errataHeading.matches(line.trim())

    private fun isLeaderLine(line: String): Boolean {
        val trimmed = line.trim()
        if (spacedDots.containsMatchIn(trimmed)) return true
        if (trimmed.length < 8) return false
        val marks = trimmed.count { it == '.' || it == '·' || it == '…' || it == '．' }
        return marks >= 6 && marks * 2 >= trimmed.length
    }
}
