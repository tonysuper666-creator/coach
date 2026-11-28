package com.coach.desktop.books

import java.sql.Connection
import java.text.Normalizer
import java.util.UUID

/** Page-level exam topics coexist with the course schedule. Only teaching materials supply names and links. */
internal object CourseExamPoints {
    const val LABEL = "考点"
    private val teachingRoles = setOf(
        CourseMaterialRole.CORE_TEXTBOOK, CourseMaterialRole.LECTURE_SLIDES, CourseMaterialRole.HANDOUT,
    )
    private val sectionNumber = Regex(
        """(?i)^(?:(?:chapter|section|lecture|week)\s+\d+(?:\.\d+)*|第[一二三四五六七八九十\d]+[章节周]|(?:[1-9]|[1-5]\d)(?:\.(?:[1-9]|[1-3]\d)){0,2})[\s.、:：—-]+""",
    )
    private val explicitSectionPrefix = Regex("""(?i)^(?:(?:chapter|section|lecture|week)\s+\d|第[一二三四五六七八九十\d]+[章节周])""")
    private val pureNumericTickLine = Regex("""^[\(\[]?[-−+$£€¥]?\d+(?:[.,]\d+)?%?(?:[\)\]])?$""")
    private val definition = Regex("""^([^:：]{2,64})[:：]\s*(\S.*)$""")
    private val bulletDefinition = Regex(
        """^[•▪◦‣–—-]\s+([A-Z][A-Za-z0-9\s\-–—/]{4,56}?)(?:\s*\([A-Z]{2,8}\))?\s*[.:：—–-]\s+([A-Za-z\u4e00-\u9fff].{12,})$""",
    )
    private val numericLeadValue = Regex("""^[\(\[]?[-−+$£€¥]?\d""")
    private val latinWord = Regex("""[A-Za-z]+""")
    private val whitespace = Regex("""[\s\p{Z}]+""")
    private val cjkChar = Regex("""[\u4E00-\u9FFF]""")
    private val trailingPageNum = Regex("""^(.+?\S)(?:\s*[…⋯]+|\s*(?:[.．·•\-–—_]\s*){2,}|\s+)(\d{1,4}|[ivxlcdm]{1,5})\s*$""")
    private val allowedSmallNumberPrefix = Regex(
        """(?i)(?:^|\s)(?:part|type|phase|stage|tier|level|class|grade|order|wave|pillar|generation|act|amendment|rule|version)$""",
    )
    private val formulaOrNoiseChar = Regex("""[=\[\]{}<>∑∫→←⇒≤≥≠≈±×÷^|\\−+]""")
    private val courseCodeNoise = Regex("""\b[A-Z]{2,6}\s*\d{3,6}\b""")
    private val institutionOrFooterNoise = Regex("""(?i)\b(?:university\s+of|department\s+of|school\s+of|faculty\s+of|semester\s+\d|academic\s+year|lecture\s+\d+\s*[-–—|])\b""")
    private val lecturerTitlePrefix = Regex("""^(?:Dr\.?|Prof\.?|Professor)\s+[A-Z]""")
    private val trailingSlideFraction = Regex("""\s+\d{1,3}\s*/\s*\d{1,3}\s*$""")
    private val tableMetaSuffixNoise = Regex("""(?i)\s+(?:source|sources|note|notes|table|figure|fig\.?|panel|column|row)$""")
    private val navigationHeading = Regex("""(?i)^(?:view\s+(?:a|an|the)\b|student\s+resources\b|link\s+to\s+learning\b)""")
    private val urlInHeading = Regex("""(?i)(?:\bhttps?(?:://|\s*$)|[(（]\s*https?\b|\bwww\.)""")
    private val nativeSectionNumber = Regex("""\d{1,2}\.\d{1,2}""")
    private val tableCaption = Regex("""(?i)^(?:table\s+|表\s*)\d+""")
    private val rankedCell = Regex("""^(\d{1,3})\s+\p{L}""")
    private val numericCohortLabelNoise = Regex("""(?i)^(?:ages?|years?|months?|days?|grades?|groups?|cohorts?|panels?|periods?|waves?|quartiles?|quintiles?|deciles?)\s+\d""")
    private val parentheticalUnitCohortNoise = Regex("""(?i)\(\s*\d+(?:\s*[-–—]\s*\d+)?\s*(?:yrs?|years?|mos?|months?|wks?|weeks?|days?|hrs?|hours?|mins?|%|kg|mg|cm|mm)\s*\)""")
    private val conditionalLeadNoise = Regex("""(?i)^(?:if|unless|whether|whereas|although|though|because|however|therefore|moreover|furthermore|nevertheless|meanwhile|otherwise|but)\s+""")
    private val conversationalLeadNoise = Regex("""(?i)^(?:let\s+us|let's|consider\s+(?:the|two|three|a|an|how|why|what)|suppose\s+(?:that|a|an|we)|assume\s+(?:that|a|an|we)|imagine\s+(?:that|a|an)|think\s+about|notice\s+that|note\s+that|recall\s+that|remember\s+that|those\s+(?:in|who|with|conceived|born|aged)\b|(?:a\s+)?special\s+note\s+for)\b""")
    private val truncatedTrailingWordNoise = Regex("""(?i)\s+(?:the|a|an|of|for|and|or|to|in|on|at|by|from|with|into|onto|upon|as|than|that|which|who|whose|where|when|while|between|among|under|over|through|during|without|within|against|about|across|after|before|toward|towards)$""")
    private val incompleteBetweenNoise = Regex("""(?i)^(?:the\s+)?relationship\s+between\s+(?!.*\band\b)""")
    private val demonstrativeSubjectLeadNoise = Regex("""(?i)^(?:this|these|that|those|it|they|we|you)\s+""")
    private val forVariousTableNoise = Regex("""(?i)\bfor\s+various\b""")
    private val chartTitleLeadNoise = Regex("""(?i)^(?:chart|graph|plot|diagram|histogram|scatter\s*plot)\s+of\b""")
    private val finiteCopulaNoise = Regex("""\b(?:is|are|was|were)\b""")
    private val leadingEdgeCounterNoise = Regex("""^(?:\d{1,3}\s*/\s*\d{1,3}|\d{1,3}[.)]?)\s+""")
    private val trailingEdgeCounterNoise = Regex("""(?i)(?:\s*\(\s*\d{1,3}\s*/\s*\d{1,3}\s*\)|\s+\d{1,3}\s*/\s*\d{1,3}|\s+page\s+\d{1,4}|\s+\d{4}(?:\s*[-/]\s*\d{2,4})?|\s+\d{1,4})+$""")
    private val edgeSeparatorTrim = Regex("""^[\s\-–—|·•:：,，/]+|[\s\-–—|·•:：,，/]+$""")
    private val functionCallNoise = Regex("""^[A-Za-z]{1,2}\([^)]{1,20}\)$""")
    private val journalCitationNoise = Regex("""(?i)(?:,\s*\d{1,4}\(\d{1,4}\)|\b\d{1,4}\(\d{1,4}\)\s*:\s*\d{1,5}\s*[-–—]\s*\d{1,5}|\b(?:journal|quarterly|review)\s+of\b.{0,40}\d)""")
    private val leadingArticlePrefix = Regex("""^(?:the|a|an)\s+""")
    // Case-sensitive lowercase verbs so uppercase acronyms/variables (e.g. "IS" in "IH–IS") are never misread as verbs.
    private val sentenceVerbNoise = Regex("""\b(?:is|are|was|were|has|have|had|can|could|will|would|should|must|does|do|did|getting|becoming|leads|makes|causes|shows|starts|begins|involves|depends|requires|implies|suggests|indicates|assumes|relies|satisfies|prevents|allows|enables|creates|produces|reduces|increases|decreases|determines|explains|describes|predicts|yields|generates|states|argues|claims|holds|exists|occurs|happens|follows|remains|becomes|provides|offers|gives|takes|uses|needs|wants|prefers|chooses|faces|pays|charges|sets|raises|lowers|changes|shifts|moves|leaves)\b""")
    private val nonTopicLabel = Regex(
        """(?i)^(?:example|examples|note|notes|side\s*note|sidebar|aside|figure|fig\.?|table|box|source|sources|step|steps|case|cases|question|questions|problem|problems|exercise|exercises|answer|answers|solution|solutions|hint|hints|remark|remarks|proof|equation|eq\.?|formula|definition|def\.?|theorem|lemma|proposition|corollary|assumption|assumptions|chart|graph|panel|exhibit|diagram|warning|tip|recall|key|true|false|unclear|uncertain|appendix|part|chapter|section|unit|module|lecture|week)(?:\s+[a-z0-9]+(?:\.[a-z0-9]+)*)?$""",
    )
    private val boilerplate = setOf(
        "contents", "brief contents", "detailed contents", "table of contents", "outline", "summary",
        "references", "bibliography", "works cited", "overview", "conclusions", "conclusion",
        "introduction", "background", "motivation", "recap", "agenda", "roadmap", "objectives",
        "learning objectives", "takeaways", "key takeaways", "discussion", "interpretation",
        "intuition", "application", "applications", "extension", "extensions", "empirics",
        "evidence", "results", "findings", "setup", "framework", "implications", "remarks",
        "readings", "suggested reading", "review", "wrap-up", "today", "plan", "lecture plan",
        "sources", "examples", "experiment", "experiments", "side note", "sidebar", "enrollment", "next week", "last week",
        "healthy", "sick",
        "germany", "france", "canada", "japan", "sweden", "switzerland", "netherlands",
        "spain", "italy", "norway", "denmark", "finland", "australia", "india", "china",
        "england", "scotland", "britain", "america",
        "hypothesis", "hypotheses", "possible explanation", "possible explanations",
        "alternative explanation", "stylized facts", "key facts", "empirical evidence",
        "empirical results", "main results", "main findings", "key findings",
        "policy implications", "concluding remarks", "final remarks",
        "exercises", "chapter exercises", "review questions", "discussion questions", "study questions",
        "practice questions", "comprehension questions", "analytical problems", "numerical problems",
        "problems", "problems and applications", "questions and problems", "self-test",
        "true or false", "true, false, or unclear", "true, false, or uncertain",
        "further reading", "key concepts", "key terms", "glossary", "index", "subject index",
        "author index", "preface", "foreword", "acknowledgments", "acknowledgements", "appendix",
        "list of figures", "list of tables", "list of boxes",
        "目录", "总目录", "简目", "小结", "本章小结", "参考文献", "索引", "前言", "序言", "致谢", "附录",
        "习题", "本章习题", "章末习题", "课后习题", "思考题", "复习思考题", "练习题", "判断题", "简答题", "计算题",
    )
    private val partDividerHeadingLine = Regex(
        """^(?:#{1,6}\s*)?(?:Part\s+(?:[IVXLC]+|\d+)|第\s*[一二三四五六七八九十\d]+\s*[部编篇])(?:\s*[:：\-—.]\s*.*|\s*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val partMiniTocLine = Regex("""^\d{1,2}\s+[A-Z\u4e00-\u9fff][^\r\n]{3,90}$""")
    private val tocHeadingLine = Regex(
        """^(?:Brief\s+Contents|Detailed\s+Contents|Contents|Table\s+of\s+Contents|List\s+of\s+(?:Figures|Tables|Boxes|Illustrations)|目\s*录|总\s*目\s*录|简\s*目)$""",
        RegexOption.IGNORE_CASE,
    )
    private val indexHeadingLine = Regex(
        """^(?:Index|Subject\s+Index|Author\s+Index|Name\s+Index|索\s*引|主题索引|人名索引)$""",
        RegexOption.IGNORE_CASE,
    )
    private val indexEntryLine = Regex(
        """^.{2,70}?[,，、]\s*(?:p\.?\s*)?\d{1,5}(?:\s*[-–—,，、]\s*\d{1,5})*\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val referencesHeadingLine = Regex(
        """^(?:References|Bibliography|Works\s+Cited|Further\s+Reading|参\s*考\s*文\s*献|引用文献)$""",
        RegexOption.IGNORE_CASE,
    )
    private val referenceEntryLine = Regex(
        """^(?:\[\d{1,4}\]|\d{1,3}[.．]\s+.{10,}(?:\d{4}|Press|Journal|Vol\.|pp\.|出版社)|[A-Z][a-z]+,\s+[A-Z]\..{6,}\(\d{4}\))""",
    )
    private val tocTailExcludedWord = Regex(
        """(?i)(?:^|\s)(?:in|of|at|to|by|for|from|on|with|and|or|as|than|over|under|between|about|after|before|year|years|age|chapter|section|table|figure|fig\.?|box|part|week|step|equation|eq\.?|definition|theorem|example|question|problem|exercise|case|stage|phase|level|type|class|grade|tier|group)$""",
    )
    private val exerciseHeadingLine = Regex(
        """^(?:#{1,6}\s*)?(?:\d+(?:\.\d+)*\s+)?(?:Exercises(?:\s+and\s+Problems)?|Chapter\s+Exercises|End[- ]of[- ]Chapter\s+(?:Exercises|Questions|Problems)|Review\s+Questions|Study\s+Questions|Discussion\s+Questions|Practice\s+Questions|Comprehension\s+Questions|Analytical\s+(?:Problems|Questions)|Numerical\s+(?:Problems|Questions)|Problems(?:\s+and\s+Applications)?|Questions\s+and\s+Problems|Essay\s+Questions|Short\s+Answer\s+Questions|Multiple\s+Choice\s+Questions|Self[- ]Test|Check\s+Your\s+Understanding|True\s*,\s*False\s*,?\s*(?:or\s+)?(?:Unclear|Uncertain)|True\s+or\s+False|章末习题|本章习题|课后习题|习题|思考题|复习思考题|练习题|判断题|简答题|计算题|案例分析题)\s*[:：.]?$""",
        RegexOption.IGNORE_CASE,
    )
    private val exerciseInstructionLine = Regex(
        """(?i)(?:(?:state|determine|decide|indicate|explain|evaluate)\s+whether\s+(?:each\s+of\s+)?(?:the\s+following\s+)?statements?\s+(?:is|are)\s+true\s*,?\s*false|\btrue\s*,\s*false\s*,?\s*(?:or\s+)?(?:unclear|uncertain)\b|判断下列(?:说法|陈述|命题)是否正确)""",
    )
    private val chapterBoundaryLine = Regex(
        """^(?:#{1,6}\s*)?(?:(?:Chapter|Part|Unit)\s+(?:\d{1,2}|[IVXLC]+)\b|第\s*[0-9一二三四五六七八九十百千]+\s*[章节篇])""",
        RegexOption.IGNORE_CASE,
    )
    /** Numeric chapter heading such as "3 Demand for health: the Grossman model" (no period/paren after number). */
    private val numericChapterHeadingLine = Regex(
        """^(?:#{1,6}\s*)?(\d{1,2})\s+([A-Z\u4e00-\u9fff][^\r\n]{2,110})$""",
    )
    /** First numbered section of a chapter, e.g. "3.1 The demand for health". */
    private val firstSectionOfChapterLine = Regex(
        """^(?:#{1,6}\s*)?(\d{1,2})\.1\s+([A-Z\u4e00-\u9fff][^\r\n]{2,100})$""",
    )
    private val bareChapterNumberLine = Regex("""^(\d{1,2})$""")
    private val chapterUnitLead = Regex(
        """(?i)^(?:years?|months?|days?|hours?|minutes?|seconds?|percent|percentage|million|billion|thousand|hundred|dollars?|pounds?|euros?|mg|kg|km|cm|mm|per|vs\.?|and|or|to|of|for|from|with|in|on|at|by)\b""",
    )
    private val numberedItemLine = Regex("""^\s*(?:\d{1,2}[.)]|(?:Question|Problem|Exercise)\s+\d{1,2}[.:]?)\s+\S""", RegexOption.IGNORE_CASE)
    private val exercisePromptLead = Regex(
        """(?i)^\s*(?:\d{1,2}[.)]\s+)?(?:Explain|Discuss|Compare|Contrast|Calculate|Compute|Suppose|Assume|Consider|Evaluate|Determine|Show\s+that|Prove|Derive|Draw|Graph|Sketch|Use|Why|How|What|Which|Is\s+it|Are\s+the|True\s+or\s+false|Does|Do|Can|Could|Would|Should|Briefly|Illustrate|Analyze|Analyse|State|Indicate|Decide)\b""",
    )
    private val subPartMarker = Regex("""(?m)(?:^|\s)(?:\([a-e]\)|[a-e]\.)\s+[A-Z]""")
    private val exerciseSubPartPrompt = Regex(
        """(?m)(?:^|\s)(?:\([a-e]\)|[a-e]\.)\s+(?:Explain|Discuss|Compare|Contrast|Calculate|Compute|Suppose|Assume|Consider|Evaluate|Determine|Show|Prove|Derive|Draw|Graph|Sketch|Use|Using|Find|Identify|Describe|State|Indicate|Decide|Interpret|Illustrate|Analyze|Analyse|Why|How|What|Which|Is|Are|Does|Do|Can|Could|Would|Should)\b""",
        RegexOption.IGNORE_CASE,
    )

    private const val MAX_EXERCISE_TAIL_PAGES = 6

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase().replace(whitespace, " ").trim()

    // Avoid matching a short English term inside an unrelated word; Chinese terms need no word separator.
    fun termPattern(name: String): Regex {
        val start = if (name.first() in 'a'..'z') "(?<![a-z0-9])" else ""
        val end = if (name.last() in 'a'..'z') "(?![a-z0-9])" else ""
        return Regex(start + Regex.escape(name) + end)
    }

    /** True when a line looks like a TOC row with a trailing page number (Arabic or Roman). */
    fun hasTrailingPageNumber(rawLine: String): Boolean {
        val line = rawLine.trim()
        if (line.isEmpty()) return false
        val m = trailingPageNum.matchEntire(line) ?: return false
        val prefix = m.groupValues[1].trim()
        val pageTok = m.groupValues[2]
        if (prefix.length < 2 || prefix.none(Char::isLetter)) return false
        if (prefix.last() in setOf('。', '！', '？', '.', '!', '?', '=', '+', '-', '/', ':', '：')) return false
        val num = pageTok.toIntOrNull()
        if (num != null) {
            if (num in 1900..2099 || num == 0) return false
            if (num in 1..5 && allowedSmallNumberPrefix.containsMatchIn(prefix)) return false
            if (tocTailExcludedWord.containsMatchIn(prefix)) return false
            return true
        }
        return pageTok.all { it in "ivxlcdm" } && !tocTailExcludedWord.containsMatchIn(prefix)
    }

    /** True when a page is a Table of Contents, Index, References, or Copyright page. */
    fun isTocOrIndexPage(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        val lines = trimmed.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !PRINTED_PAGE_MARK.matches(it) && !ExplainClassifier.isPageNumber(it) }
            .toList()
        if (lines.isEmpty()) return true
        if (ExplainClassifier.isCopyrightBlock(trimmed)) return true

        val hasTocHeading = lines.take(4).any { tocHeadingLine.matches(it.trimStart('#', ' ')) }
        val tocLineCount = lines.count { ExplainClassifier.isTocLine(it) || hasTrailingPageNumber(it) }
        val proseLines = lines.count { it.length >= 110 && (it.contains(". ") || it.contains('。')) && !hasTrailingPageNumber(it) }
        if (hasTocHeading && (tocLineCount >= 2 || proseLines == 0)) return true
        if (tocLineCount >= 4 && proseLines <= 1 && tocLineCount * 100 >= lines.size * 45) return true
        if (tocLineCount >= 6 && proseLines <= 2 && tocLineCount * 100 >= lines.size * 35) return true

        val hasIndexHeading = lines.take(3).any { indexHeadingLine.matches(it.trimStart('#', ' ')) }
        val indexCount = lines.count { indexEntryLine.matches(it) }
        if (hasIndexHeading && (indexCount >= 2 || proseLines == 0)) return true
        if (indexCount >= 4 && indexCount * 10 >= lines.size * 5) return true

        val hasRefHeading = lines.take(3).any { referencesHeadingLine.matches(it.trimStart('#', ' ')) }
        val refCount = lines.count { referenceEntryLine.containsMatchIn(it) }
        if (hasRefHeading && (refCount >= 2 || proseLines <= 1)) return true
        if (refCount >= 4 && proseLines == 0 && refCount * 10 >= lines.size * 5) return true

        // Part divider pages (e.g. "Part IV / Health Systems / 15 ... / 16 ... / 17 ...") have no narrative body prose.
        val hasPartHeading = lines.take(4).any { partDividerHeadingLine.matches(it) }
        if (hasPartHeading && proseLines == 0) {
            val miniTocEntries = lines.count { partMiniTocLine.matches(it) }
            val totalChars = trimmed.count { !it.isWhitespace() }
            if (miniTocEntries >= 2 || totalChars < 350) return true
        }

        return false
    }

    /**
     * Recognizes the opening page of a new chapter using BOTH TOC chapter start pages AND text headings.
     * When authoritative TOC [chapterStartPages] are known (>= 2 chapters), heuristic bare/numeric line guesses
     * are disabled so wrapped numbered exercise items ("7 An insured...") can never exit an exercise tail prematurely.
     */
    fun isChapterOpeningPage(
        pageNumber: Int,
        text: String,
        chapterStartPages: Set<Int> = emptySet(),
        excludedChapterNum: Int? = null,
        inExerciseTail: Boolean = false,
    ): Boolean {
        if (pageNumber in chapterStartPages) return true
        val lines = text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !PRINTED_PAGE_MARK.matches(it) }
            .toList()
        if (lines.isEmpty()) return false
        val topLines = lines.take(6)
        if (topLines.any { chapterBoundaryLine.containsMatchIn(it) }) return true

        // If authoritative TOC chapter start pages are supplied, trust them rather than guessing from numeric lines.
        if (chapterStartPages.size >= 2) return false

        // Do not mistake an exercise continuation page for a chapter opening while already inside an exercise tail.
        if (inExerciseTail && looksLikeExerciseContinuationPage(text)) return false

        for (line in topLines) {
            val numMatch = numericChapterHeadingLine.matchEntire(line)
            if (numMatch != null) {
                val chNum = numMatch.groupValues[1].toIntOrNull() ?: 0
                val title = numMatch.groupValues[2].trim()
                val chapterProgressOk = excludedChapterNum == null || chNum in (excludedChapterNum + 1)..(excludedChapterNum + 2)
                if (chNum in 1..60 && chapterProgressOk && isPlausibleChapterTitle(title)) return true
            }
        }
        for (i in topLines.indices) {
            val bareMatch = bareChapterNumberLine.matchEntire(topLines[i])
            if (bareMatch != null && i + 1 < lines.size) {
                val chNum = bareMatch.groupValues[1].toIntOrNull() ?: 0
                val nextTitle = lines[i + 1].trimStart('#', ' ')
                val chapterProgressOk = excludedChapterNum == null || chNum in (excludedChapterNum + 1)..(excludedChapterNum + 2)
                if (chNum in 1..40 && chapterProgressOk &&
                    nextTitle.firstOrNull()?.isLetter() == true &&
                    isPlausibleChapterTitle(nextTitle) && nextTitle.length >= 6
                ) return true
            }
        }
        // A "N.1 <Title>" first-section heading on a non-exercise page also proves we have entered chapter N.
        if (lines.take(15).any { line ->
                val m = firstSectionOfChapterLine.matchEntire(line) ?: return@any false
                val chNum = m.groupValues[1].toIntOrNull() ?: 0
                val title = m.groupValues[2].trim()
                val chapterProgressOk = excludedChapterNum == null || chNum in (excludedChapterNum + 1)..(excludedChapterNum + 2)
                chNum in 1..60 && chapterProgressOk && !exerciseHeadingLine.matches(line) && isPlausibleChapterTitle(title)
            }
        ) {
            return true
        }
        return false
    }

    private fun extractExerciseChapterNumber(rawLines: List<String>, cutIndex: Int): Int? {
        val line = rawLines.getOrNull(cutIndex)?.trim().orEmpty()
        return Regex("""^(?:#{1,6}\s*)?(\d{1,2})\.\d+""").find(line)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun isPlausibleChapterTitle(title: String): Boolean {
        if (title.length !in 4..100) return false
        if (title.split(whitespace).size > 12) return false
        if (title.last() in setOf('.', '?', '!', '。', '？', '！', ';', '；')) return false
        if (exerciseHeadingLine.matches(title) || exercisePromptLead.containsMatchIn(title)) return false
        if (chapterUnitLead.containsMatchIn(title)) return false
        if (hasTrailingPageNumber(title)) return false
        return title.any(Char::isLetter)
    }

    /**
     * Strips explicit end-of-chapter exercise headings/instructions from a single textbook page/block.
     * Returns empty string when nothing substantive precedes the exercise start.
     * Standalone pages without an explicit exercise start are never dropped as continuations.
     */
    fun stripExerciseSection(text: String): String {
        val rawLines = text.lines()
        val cutIndex = findExerciseStartLineIndex(rawLines)
        if (cutIndex >= 0) {
            val before = rawLines.subList(0, cutIndex).joinToString("\n").trim()
            return if (hasSubstantiveBody(before)) before else ""
        }
        return text
    }

    /**
     * Walks consecutive textbook pages in page order so multi-page end-of-chapter exercises
     * (e.g., heading on page N, numbered True/False/Unclear statements on N+1, analytical problems on N+2)
     * are stripped cleanly up to the next chapter boundary — and NEVER past the next chapter.
     * Continuation heuristics ONLY run when a page immediately follows an explicit end-of-chapter exercise zone.
     */
    fun stripExercisesFromTextbookPages(
        pages: List<ExamTeachingPage>,
        chapterStartPagesByBook: Map<String, Set<Int>> = emptyMap(),
    ): List<ExamTeachingPage> {
        val out = mutableListOf<ExamTeachingPage>()
        var currentBook: String? = null
        var lastTextbookPage = -1
        var inExerciseTail = false
        var tailPages = 0
        var exerciseChapterNum: Int? = null
        for (page in pages) {
            if (page.role != CourseMaterialRole.CORE_TEXTBOOK) {
                out += page
                continue
            }
            val consecutive = page.bookId == currentBook && lastTextbookPage >= 0 && page.page in lastTextbookPage..(lastTextbookPage + 2)
            if (!consecutive) {
                inExerciseTail = false
                tailPages = 0
                exerciseChapterNum = null
            }
            currentBook = page.bookId
            lastTextbookPage = page.page
            val chapterStarts = chapterStartPagesByBook[page.bookId].orEmpty()
            val startsChapter = isChapterOpeningPage(
                page.page,
                page.text,
                chapterStarts,
                if (inExerciseTail) exerciseChapterNum else null,
                inExerciseTail = inExerciseTail,
            )
            if (startsChapter) {
                inExerciseTail = false
                tailPages = 0
                exerciseChapterNum = null
            }
            val rawLines = page.text.lines()
            val cutIndex = findExerciseStartLineIndex(rawLines)
            if (inExerciseTail) {
                val looksExercise = looksLikeExerciseContinuationPage(page.text)
                val hasAuthoritativeChapters = chapterStarts.size >= 2
                val tailCap = if (hasAuthoritativeChapters) 10 else MAX_EXERCISE_TAIL_PAGES
                if (looksExercise || cutIndex == 0 || tailPages < tailCap) {
                    // Only exit an exercise tail early on narrative prose when authoritative TOC chapter starts are absent.
                    if (!hasAuthoritativeChapters && tailPages >= 1 && !looksExercise && cutIndex < 0 && looksLikeNarrativeBodyPage(page.text)) {
                        inExerciseTail = false
                        tailPages = 0
                        exerciseChapterNum = null
                    } else {
                        tailPages++
                        continue
                    }
                } else {
                    inExerciseTail = false
                    tailPages = 0
                    exerciseChapterNum = null
                }
            }
            if (cutIndex >= 0) {
                inExerciseTail = true
                tailPages = 1
                exerciseChapterNum = extractExerciseChapterNumber(rawLines, cutIndex)
                val before = rawLines.subList(0, cutIndex).joinToString("\n").trim()
                if (hasSubstantiveBody(before)) {
                    out += page.copy(text = before)
                }
                continue
            }
            out += page
        }
        return out
    }

    /** Compute cleaned textbook pages by page number after stripping TOC and bounded chapter-end exercise tails. */
    fun cleanTextbookPagesByNumber(
        pages: List<BookPage>,
        chapterStartPages: Set<Int> = emptySet(),
    ): Map<Int, String> {
        val result = LinkedHashMap<Int, String>(pages.size)
        var lastPage = -1
        var inExerciseTail = false
        var tailPages = 0
        var exerciseChapterNum: Int? = null
        for (page in pages.sortedBy { it.number }) {
            if (isTocOrIndexPage(page.text)) continue
            val consecutive = lastPage >= 0 && page.number in lastPage..(lastPage + 2)
            if (!consecutive) {
                inExerciseTail = false
                tailPages = 0
                exerciseChapterNum = null
            }
            lastPage = page.number
            val startsChapter = isChapterOpeningPage(
                page.number,
                page.text,
                chapterStartPages,
                if (inExerciseTail) exerciseChapterNum else null,
                inExerciseTail = inExerciseTail,
            )
            if (startsChapter) {
                inExerciseTail = false
                tailPages = 0
                exerciseChapterNum = null
            }
            val rawLines = page.text.lines()
            val cutIndex = findExerciseStartLineIndex(rawLines)
            if (inExerciseTail) {
                val looksExercise = looksLikeExerciseContinuationPage(page.text)
                val hasAuthoritativeChapters = chapterStartPages.size >= 2
                val tailCap = if (hasAuthoritativeChapters) 10 else MAX_EXERCISE_TAIL_PAGES
                if (looksExercise || cutIndex == 0 || tailPages < tailCap) {
                    if (!hasAuthoritativeChapters && tailPages >= 1 && !looksExercise && cutIndex < 0 && looksLikeNarrativeBodyPage(page.text)) {
                        inExerciseTail = false
                        tailPages = 0
                        exerciseChapterNum = null
                    } else {
                        tailPages++
                        continue
                    }
                } else {
                    inExerciseTail = false
                    tailPages = 0
                    exerciseChapterNum = null
                }
            }
            if (cutIndex >= 0) {
                inExerciseTail = true
                tailPages = 1
                exerciseChapterNum = extractExerciseChapterNumber(rawLines, cutIndex)
                val before = rawLines.subList(0, cutIndex).joinToString("\n").trim()
                if (hasSubstantiveBody(before)) result[page.number] = before
                continue
            }
            result[page.number] = page.text
        }
        return result
    }

    private fun looksLikeNarrativeBodyPage(text: String): Boolean {
        val lines = text.lineSequence().map(String::trim).filter {
            it.isNotEmpty() && !PRINTED_PAGE_MARK.matches(it) && !ExplainClassifier.isPageNumber(it)
        }.toList()
        if (lines.any { numberedItemLine.containsMatchIn(it) }) return false
        if (exerciseSubPartPrompt.containsMatchIn(text)) return false
        if (text.count { it == '?' || it == '？' } >= 3) return false
        val proseChars = lines.filter { it.length >= 60 }.sumOf { it.length }
        return proseChars >= 500
    }

    private fun findExerciseStartLineIndex(rawLines: List<String>): Int {
        for (i in rawLines.indices) {
            val line = rawLines[i].trim()
            if (line.isEmpty()) continue
            if (exerciseHeadingLine.matches(line) || exerciseInstructionLine.containsMatchIn(line)) {
                return i
            }
        }
        return -1
    }

    fun looksLikeExerciseContinuationPage(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (exerciseInstructionLine.containsMatchIn(trimmed)) return true
        val lines = trimmed.lineSequence().map(String::trim).filter {
            it.isNotEmpty() && !PRINTED_PAGE_MARK.matches(it) && !ExplainClassifier.isPageNumber(it)
        }.toList()
        if (lines.any { exerciseHeadingLine.matches(it) }) return true
        val numbered = lines.filter { numberedItemLine.containsMatchIn(it) }
        val promptLike = numbered.count { it.endsWith("?") || exercisePromptLead.containsMatchIn(it) }
        val subParts = subPartMarker.findAll(trimmed).count()
        val promptSubParts = exerciseSubPartPrompt.findAll(trimmed).count()
        val questionMarks = trimmed.count { it == '?' || it == '？' }
        // Figure/table panel labels like "(a) Outpatient ... (b) Inpatient ..." plus a rhetorical question are NOT exercises.
        if (promptSubParts >= 3) return true
        if (promptSubParts >= 2 && (numbered.isNotEmpty() || questionMarks >= 2)) return true
        if (numbered.size >= 2 && (promptLike >= 2 || (subParts >= 2 && promptSubParts >= 1 && questionMarks >= 1))) return true
        if (numbered.size < 3) return false
        return promptLike >= 1 || promptSubParts >= 2 || questionMarks >= 3
    }

    private fun hasSubstantiveBody(text: String): Boolean {
        val nonWs = text.count { !it.isWhitespace() }
        if (nonWs < 40) return false
        val lines = text.lineSequence().map(String::trim).filter {
            it.isNotEmpty() && !PRINTED_PAGE_MARK.matches(it) && !ExplainClassifier.isPageNumber(it)
        }.toList()
        return lines.any { it.length >= 20 }
    }

    /**
     * True when the right-hand side of a `LHS: RHS` line is a numeric table cell (e.g. "-0.22", "-2.27 (or -1.6)")
     * rather than a prose definition, math expression (e.g. "U(E[X]) > E[U(X)]"), or alphabetic slide subtitle.
     */
    private fun isNumericTableRowValue(rhs: String): Boolean {
        val trimmed = rhs.trim()
        if (trimmed.isEmpty()) return true
        val cjkCount = cjkChar.findAll(trimmed).count()
        if (cjkCount >= 3) return false
        val alphaWords = latinWord.findAll(trimmed).count()
        if (alphaWords == 0) return true
        return numericLeadValue.containsMatchIn(trimmed) && alphaWords < 3
    }

    /** PDF text flattens a rank and its neighbouring cell into what looks like a numbered section. */
    private fun rankedTableRows(lines: List<String>): Set<Int> = buildSet {
        for ((caption, line) in lines.withIndex()) {
            if (!tableCaption.containsMatchIn(line)) continue
            // A caption can wrap, followed by a column header. Only suppress a run of consecutive ranks.
            val first = (caption + 1 until minOf(lines.size, caption + 5))
                .firstOrNull { rankedCell.containsMatchIn(lines[it]) } ?: continue
            var rank = rankedCell.find(lines[first])!!.groupValues[1].toInt()
            var end = first + 1
            while (end < lines.size && rankedCell.find(lines[end])?.groupValues?.get(1)?.toInt() == rank + 1) {
                rank++
                end++
            }
            if (end - first >= 2) addAll(first until end)
        }
    }

    fun isValidExamPointTitle(
        rawLine: String,
        heading: String,
        defined: Boolean,
        numbered: Boolean,
        markdown: Boolean,
        officialTocSection: Boolean = false,
    ): Boolean {
        if (heading.length !in 2..64) return false
        if (navigationHeading.containsMatchIn(heading) || urlInHeading.containsMatchIn(heading)) return false
        var openParens = 0
        for (c in heading) {
            if (c == '(' || c == '（') openParens++
            if (c == ')' || c == '）') openParens--
            if (openParens < 0) return false
        }
        if (openParens != 0) return false
        if (heading.any { it in "。！？!?；;.:：" }) return false
        if (heading.none(Char::isLetter)) return false
        if (ExplainClassifier.isTocLine(rawLine) || hasTrailingPageNumber(rawLine) || hasTrailingPageNumber(heading)) {
            return false
        }
        if (formulaOrNoiseChar.containsMatchIn(heading)) return false
        if (courseCodeNoise.containsMatchIn(heading) || institutionOrFooterNoise.containsMatchIn(heading) ||
            lecturerTitlePrefix.containsMatchIn(heading) || trailingSlideFraction.containsMatchIn(rawLine) ||
            trailingSlideFraction.containsMatchIn(heading) || tableMetaSuffixNoise.containsMatchIn(heading) ||
            numericCohortLabelNoise.containsMatchIn(heading) || parentheticalUnitCohortNoise.containsMatchIn(heading) ||
            conditionalLeadNoise.containsMatchIn(heading) || conversationalLeadNoise.containsMatchIn(heading) ||
            demonstrativeSubjectLeadNoise.containsMatchIn(heading) || forVariousTableNoise.containsMatchIn(heading) ||
            chartTitleLeadNoise.containsMatchIn(heading) ||
            truncatedTrailingWordNoise.containsMatchIn(heading) || incompleteBetweenNoise.containsMatchIn(heading) ||
            functionCallNoise.matches(heading)
        ) return false
        if (journalCitationNoise.containsMatchIn(heading) || journalCitationNoise.containsMatchIn(rawLine)) return false
        val first = heading.first()
        // Reject lowercase-initial fragments ("to live", "in the past", "regime are clear", ...)
        if (first in 'a'..'z') return false
        // Reject symbol/arrow/digit-leading noise ("→ Example", "• ...", "(a) ...")
        if (!first.isLetter()) return false
        val hasCjk = cjkChar.containsMatchIn(heading)
        // Reject short Latin fragments of <= 6 characters ("Model", "Movies", "j=1", "C2", ...)
        if (!hasCjk && heading.length <= 6) return false
        // Reject all-uppercase running headers / section banners ("HEALTH ECONOMICS", "GRANDPA", ...)
        if (!hasCjk) {
            val lettersOnly = heading.filter(Char::isLetter)
            if (lettersOnly.length >= 5 && lettersOnly.all(Char::isUpperCase)) return false
        }
        val key = normalize(heading)
        if (key.length < 2 || key in boilerplate) return false
        if (nonTopicLabel.matches(heading)) return false
        if (!hasCjk) {
            val words = heading.split(whitespace)
            if (words.size > 8) return false
            if (!officialTocSection && !markdown && !defined && !numbered && words.size > 7) return false
            // Short finite-copula clauses ("Customers are risk-neutral") are sentences, not topic nouns,
            // except official TOC section headings ("Health is uncertain and contagious").
            if (!officialTocSection && words.size >= 3 && finiteCopulaNoise.containsMatchIn(heading)) return false
            if (!officialTocSection && words.size >= 6 && sentenceVerbNoise.containsMatchIn(heading)) return false
        }
        return true
    }

    /** Offline comparison is deliberately limited to explicit numeric definitions with identical units. */
    fun discrepancies(topic: CourseTopicEntry, pages: Map<String, List<BookPage>>): List<CourseDiscrepancyItem> {
        if (topic.weekLabel != LABEL) return emptyList()
        val assignment = Regex("""^\s*${Regex.escape(topic.title)}\s*[:：=]\s*([+-]?\d+(?:\.\d+)?)\s*([%\p{L}/]*)\s*[.。]?\s*$""", RegexOption.IGNORE_CASE)
        data class Definition(val link: CourseTopicSourceLink, val value: java.math.BigDecimal, val unit: String, val citation: CourseCitationRef)
        val definitions = topic.sources.mapNotNull { link ->
            val page = pages[link.bookId]?.firstOrNull { it.number == link.startPage } ?: return@mapNotNull null
            val line = page.text.lineSequence().firstOrNull(assignment::matches) ?: return@mapNotNull null
            val match = assignment.matchEntire(line) ?: return@mapNotNull null
            val offset = page.text.indexOf(line)
            Definition(link, match.groupValues[1].toBigDecimal(), match.groupValues[2], CourseCitationRef(
                link.bookId, link.bookTitle, link.role, page.number, offset, offset + line.length, quote = line,
            ))
        }
        val primary = definitions.firstOrNull { it.link.role == CourseMaterialRole.CORE_TEXTBOOK } ?: return emptyList()
        return definitions.filter { it.link.role in setOf(CourseMaterialRole.LECTURE_SLIDES, CourseMaterialRole.HANDOUT) &&
            it.unit == primary.unit && it.value.compareTo(primary.value) != 0
        }.map { other ->
            CourseDiscrepancyItem(topic.title, primary.citation.quote, other.citation.quote,
                "同一项的数值不同，按教材第 ${primary.citation.page} 页核对。", listOf(primary.citation, other.citation))
        }
    }

    fun build(db: Connection, courseId: String, materials: List<CourseMaterialEntry>, excluded: List<CourseTopicLinkPlan>): List<CourseTopicPlan> {
        val teaching = materials.filter { it.role in teachingRoles && (it.userLocked || !CourseClientGates.isPastExamMaterial(it.title, it.sourceFilename)) }
            .sortedBy { it.role.authorityRank }
        val standaloneTextbook = materials.size == 1 && teaching.singleOrNull()?.role == CourseMaterialRole.CORE_TEXTBOOK
        if (teaching.size < 2 && !standaloneTextbook) return emptyList()
        val pages = mutableListOf<ExamTeachingPage>()
        val chapterStartsByBook = mutableMapOf<String, Set<Int>>()
        val chapterRangesByBook = mutableMapOf<String, List<IntRange>>()
        val sectionSpansByBook = mutableMapOf<String, List<ExamSectionSpan>>()
        val fullTocTitlesByBook = mutableMapOf<String, Set<String>>()
        for (mat in teaching) {
            val excludedRanges = excluded.filter { it.bookId == mat.bookId }.map { it.startPage..it.endPage }
            val bookPages = CourseStore.loadPagesForBook(db, mat.bookId, mat.pageCount.coerceAtLeast(1))
            val structure = if (mat.role == CourseMaterialRole.CORE_TEXTBOOK) {
                BookStructureStore.loadStructure(db, mat.bookId, bookPages)
            } else null
            val corrected = CourseStore.loadCorrectedBodyPage(db, mat.bookId)
            val openingStart = structure?.tocNodes?.let { BookBoundaries.openingNode(it)?.startPage }
            val first = corrected ?: structure?.correctedStart ?: structure?.nativeBodyStart?.importPage ?: openingStart ?: 1
            val hasChapters = structure?.tocNodes?.any { it.kind == TocNodeKind.CHAPTER } == true
            val recovered = if (standaloneTextbook && structure != null) {
                bodyConfirmedSections(structure.tocNodes, bookPages)
            } else emptyList()
            val recoveredPages = recovered.flatMapTo(hashSetOf()) { it.pages }
            if (structure != null) {
                val chapterNodes = structure.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }
                val starts = chapterNodes.map { it.startPage }.toSet()
                if (starts.isNotEmpty()) chapterStartsByBook[mat.bookId] = starts
                val chRanges = chapterNodes.map { it.startPage..it.endPage.coerceAtLeast(it.startPage) }
                if (chRanges.isNotEmpty()) chapterRangesByBook[mat.bookId] = chRanges
                val allTocTitles = structure.tocNodes
                    .filter { it.kind in setOf(TocNodeKind.CHAPTER, TocNodeKind.SECTION, TocNodeKind.SUBSECTION) }
                    .map { normalize(it.title.trim().replace(sectionNumber, "").trim().trimEnd(':', '：', '-', '–', '—').trim()) }
                    .filter { it.length >= 4 }
                    .toSet()
                if (allTocTitles.isNotEmpty()) fullTocTitlesByBook[mat.bookId] = allTocTitles
                val spans = structure.tocNodes
                    .filter { it.kind in setOf(TocNodeKind.SECTION, TocNodeKind.SUBSECTION) }
                    .mapNotNull { node ->
                        val rawTitle = if (standaloneTextbook && node.source == HeadingSource.PDF_OUTLINE) {
                            node.title.trim().trimEnd('*')
                        } else node.title.trim()
                        if (exerciseHeadingLine.matches(rawTitle)) return@mapNotNull null
                        val cleanTitle = rawTitle.replace(sectionNumber, "").trim().trimEnd(':', '：', '-', '–', '—').trim()
                        val normTitle = normalize(cleanTitle)
                        if (normTitle.length < 2 || normTitle in boilerplate) return@mapNotNull null
                        ExamSectionSpan(cleanTitle, normTitle, node.startPage..node.endPage.coerceAtLeast(node.startPage))
                    } + recovered
                if (spans.isNotEmpty()) sectionSpansByBook[mat.bookId] = spans
            }
            for (page in bookPages) {
                if (page.number < first || page.ocrPending || excludedRanges.any { page.number in it }) continue
                val role = structure?.pageRole(page.number)
                val sectionEvidence = role == BookPageRole.REFERENCES && page.number in recoveredPages
                if (!sectionEvidence && role in setOf(BookPageRole.TOC, BookPageRole.COPYRIGHT, BookPageRole.INDEX, BookPageRole.REFERENCES, BookPageRole.BLANK)) {
                    continue
                }
                if (role == BookPageRole.FRONT_MATTER && (hasChapters || BookBoundaries.looksLikeFrontMatter(page.text))) {
                    continue
                }
                if (isTocOrIndexPage(page.text)) continue
                pages += ExamTeachingPage(mat.bookId, page.number, mat.role, page.text, sectionEvidenceOnly = sectionEvidence)
            }
        }
        val cleaned = stripExercisesFromTextbookPages(pages, chapterStartsByBook)
        return identifyFromPages(courseId, cleaned, chapterStartsByBook, sectionSpansByBook, chapterRangesByBook, fullTocTitlesByBook)
    }

    // Some PDF outlines classify every section after the first chapter glossary as back matter.
    // Only trust a numbered native bookmark if its destination prints the same number and heading.
    internal fun bodyConfirmedSections(nodes: List<BookTocNode>, bookPages: List<BookPage>): List<ExamSectionSpan> {
        val byPage = bookPages.associateBy { it.number }
        return nodes.mapNotNull { node ->
            val number = node.numberLabel ?: return@mapNotNull null
            if (node.source != HeadingSource.PDF_OUTLINE || node.kind != TocNodeKind.BACK_MATTER ||
                !nativeSectionNumber.matches(number)) return@mapNotNull null
            val title = node.title.replace(sectionNumber, "").trimEnd('*').trim()
            if (!isValidExamPointTitle(title, title, defined = false, numbered = true, markdown = false, officialTocSection = true)) return@mapNotNull null
            val page = byPage[node.startPage] ?: return@mapNotNull null
            val key = normalize(title)
            val words = key.split(' ').joinToString("""\s+""", transform = Regex::escape)
            val heading = Regex("""(?m)^${Regex.escape(number)}\s*[|.:：–—-]?\s*$words(?!\p{L})""")
            val lines = page.text.lineSequence().joinToString("\n", transform = ::normalize)
            if (!heading.containsMatchIn(lines) || !hasSubstantiveBody(page.text)) return@mapNotNull null
            ExamSectionSpan(title, key, node.startPage..node.endPage)
        }
    }

    private fun normalizeEdgeCandidate(line: String): String {
        var s = normalize(line)
        s = leadingEdgeCounterNoise.replace(s, "")
        s = trailingEdgeCounterNoise.replace(s, "")
        return edgeSeparatorTrim.replace(s, "")
    }

    private fun edgeKeysOf(raw: String): Set<String> {
        val keys = linkedSetOf<String>()
        val full = normalize(raw)
        val stripped = normalizeEdgeCandidate(raw)
        if (full.length >= 3) keys += full
        if (stripped.length >= 3) keys += stripped
        val colonIdx = stripped.indexOfFirst { it == ':' || it == '：' }
        if (colonIdx >= 3) {
            val prefix = edgeSeparatorTrim.replace(stripped.substring(0, colonIdx), "")
            if (prefix.length >= 3) keys += prefix
        }
        return keys
    }

    /**
     * Headings that the textbook itself defines, or numbers as a section, stay even when
     * the slides never repeat the name. A bare first line still needs a second book.
     */
    fun identifyFromPages(
        courseId: String,
        pages: List<ExamTeachingPage>,
        chapterStartPagesByBook: Map<String, Set<Int>> = emptyMap(),
        sectionSpansByBook: Map<String, List<ExamSectionSpan>> = emptyMap(),
        chapterRangesByBook: Map<String, List<IntRange>> = emptyMap(),
        fullTocTitlesByBook: Map<String, Set<String>> = emptyMap(),
    ): List<CourseTopicPlan> {
        val bodyPages = stripExercisesFromTextbookPages(
            pages.filter { it.role in teachingRoles && it.text.isNotBlank() && !isTocOrIndexPage(it.text) },
            chapterStartPagesByBook,
        )
        val headingPages = bodyPages.filterNot { it.sectionEvidenceOnly }
        // Detect repeating running headers / slide footers without suppressing legitimate multi-slide topic titles
        // or textbook chapter titles that repeat as running headers.
        val pageCountByBook = HashMap<String, Int>()
        val roleByBook = HashMap<String, CourseMaterialRole>()
        val topEdgeFreqByBook = HashMap<Pair<String, String>, Int>()
        val bottomEdgeFreqByBook = HashMap<Pair<String, String>, Int>()
        val textbookEdgeFreqByBook = HashMap<Pair<String, String>, Int>()
        val slideTopEdgeBooks = HashMap<String, MutableSet<String>>()
        val slideBottomEdgeBooks = HashMap<String, MutableSet<String>>()
        for (page in headingPages) {
            pageCountByBook[page.bookId] = (pageCountByBook[page.bookId] ?: 0) + 1
            roleByBook[page.bookId] = page.role
            val nonEmpty = page.text.lineSequence().map(String::trim)
                .filter { it.isNotEmpty() && !PRINTED_PAGE_MARK.matches(it) && !ExplainClassifier.isPageNumber(it) }
                .toList()
            if (nonEmpty.isEmpty()) continue
            val topKeys = nonEmpty.take(2).flatMap(::edgeKeysOf).toSet()
            val bottomKeys = if (nonEmpty.size >= 2) nonEmpty.drop(1).takeLast(3).flatMap(::edgeKeysOf).toSet() else emptySet()
            if (page.role == CourseMaterialRole.CORE_TEXTBOOK) {
                for (edge in (topKeys + bottomKeys)) {
                    val key = page.bookId to edge
                    textbookEdgeFreqByBook[key] = (textbookEdgeFreqByBook[key] ?: 0) + 1
                }
            } else {
                for (edge in topKeys) {
                    val key = page.bookId to edge
                    topEdgeFreqByBook[key] = (topEdgeFreqByBook[key] ?: 0) + 1
                    slideTopEdgeBooks.getOrPut(edge) { linkedSetOf() }.add(page.bookId)
                }
                for (edge in bottomKeys) {
                    val key = page.bookId to edge
                    bottomEdgeFreqByBook[key] = (bottomEdgeFreqByBook[key] ?: 0) + 1
                    slideBottomEdgeBooks.getOrPut(edge) { linkedSetOf() }.add(page.bookId)
                }
            }
        }
        val repeatingTextbookEdgesByBook = textbookEdgeFreqByBook.filterValues { it >= 3 }.keys
        // Slide top headers only count as banners when spanning >= 3 slides AND >= 35% of that deck,
        // or repeating >= 2 times within each of >= 3 distinct slide decks (so cross-cutting topics
        // mentioned once per lecture across 3 decks are never suppressed).
        val repeatingSlideTopByBook = topEdgeFreqByBook.filter { (bookAndEdge, count) ->
            val deckPages = pageCountByBook[bookAndEdge.first] ?: 1
            count >= 3 && count * 100 >= deckPages * 35
        }.keys
        val repeatingSlideBottomByBook = bottomEdgeFreqByBook.filterValues { it >= 3 }.keys
        val repeatingSlideTopAcrossDecks = slideTopEdgeBooks.filter { (edge, books) ->
            books.count { b -> (topEdgeFreqByBook[b to edge] ?: 0) >= 2 } >= 3
        }.keys
        val repeatingSlideBottomAcrossDecks = slideBottomEdgeBooks.filterValues { it.size >= 2 }.keys

        val cleanedTextbookPagesByBook = bodyPages
            .filter { it.role == CourseMaterialRole.CORE_TEXTBOOK }
            .groupBy({ it.bookId }, { it.page })
            .mapValues { (_, pgs) -> pgs.toSet() }
        // Narrative prose lines in the textbook (excluding numeric table rows & chart ticks) let us confirm
        // whether a term has dedicated textbook body discussion before suppressing it via slide/table heuristics.
        val textbookProseLines = headingPages
            .asSequence()
            .filter { it.role == CourseMaterialRole.CORE_TEXTBOOK }
            .flatMap { it.text.lineSequence() }
            .map(String::trim)
            .filter { line ->
                line.length >= 45 &&
                    !pureNumericTickLine.matches(line) &&
                    (cjkChar.findAll(line).count() >= 12 || latinWord.findAll(line).count() >= 7)
            }
            .map(::normalize)
            .toList()
        val textbookProseCache = HashMap<String, Boolean>()
        fun hasDedicatedTextbookProse(key: String): Boolean = textbookProseCache.getOrPut(key) {
            if (key.length < 4) return@getOrPut false
            val pat = termPattern(key)
            textbookProseLines.any { pat.containsMatchIn(it) }
        }

        val titles = linkedMapOf<String, String>()
        val textbookStrong = mutableSetOf<String>()
        val officialTocSectionKeys = HashSet<String>()
        // 1. Seed official textbook TOC SECTION/SUBSECTION titles on cleaned body pages FIRST.
        //    Even if the same phrase also appears inside a table cell elsewhere, its textbook section guarantees retention.
        for ((bookId, spans) in sectionSpansByBook) {
            val available = cleanedTextbookPagesByBook[bookId].orEmpty()
            for (span in spans) {
                if (span.pages.none { it in available }) continue
                if (!isValidExamPointTitle(span.title, span.title, defined = false, numbered = true, markdown = false, officialTocSection = true)) {
                    continue
                }
                titles.putIfAbsent(span.normalizedTitle, span.title)
                textbookStrong += span.normalizedTitle
                officialTocSectionKeys += span.normalizedTitle
            }
        }

        val allFullTocTitles = fullTocTitlesByBook.values.flatten().toSet() +
            sectionSpansByBook.values.flatten().map { it.normalizedTitle }.toSet()

        val indexed = mutableListOf<ExamTeachingPage>()
        for (page in headingPages) {
            val lines = page.text.lineSequence().map(String::trim)
                .filter { it.isNotEmpty() && !PRINTED_PAGE_MARK.matches(it) }
                .toList()
            val isSlideLike = page.role != CourseMaterialRole.CORE_TEXTBOOK
            val tableRows = rankedTableRows(lines)
            val bookTocTitles = fullTocTitlesByBook[page.bookId].orEmpty() +
                sectionSpansByBook[page.bookId].orEmpty().map { it.normalizedTitle }
            fun isRepeatingSlideEdge(candidate: String): Boolean {
                val norm = normalize(candidate)
                val stripped = normalizeEdgeCandidate(candidate)
                return (page.bookId to norm) in repeatingSlideTopByBook ||
                    (page.bookId to stripped) in repeatingSlideTopByBook ||
                    (page.bookId to norm) in repeatingSlideBottomByBook ||
                    (page.bookId to stripped) in repeatingSlideBottomByBook ||
                    norm in repeatingSlideTopAcrossDecks ||
                    stripped in repeatingSlideTopAcrossDecks ||
                    norm in repeatingSlideBottomAcrossDecks ||
                    stripped in repeatingSlideBottomAcrossDecks
            }
            fun isRepeatingTextbookEdge(candidate: String): Boolean {
                val norm = normalize(candidate)
                val stripped = normalizeEdgeCandidate(candidate)
                return (page.bookId to norm) in repeatingTextbookEdgesByBook ||
                    (page.bookId to stripped) in repeatingTextbookEdgesByBook
            }
            for ((index, line) in lines.withIndex()) {
                if (index in tableRows) continue
                val bulletMatch = if (!isSlideLike) bulletDefinition.matchEntire(line) else null
                val defMatch = if (bulletMatch == null) definition.matchEntire(line) else null
                val numericTableDef = defMatch != null && isNumericTableRowValue(defMatch.groupValues[2])
                if (numericTableDef) {
                    // Allow a slide title like "Transition to outpatient care: 1980-2000" ONLY when the LHS
                    // is explicitly discussed in textbook body prose; reject table rows like "All care: -0.22".
                    val lhsClean = defMatch!!.groupValues[1].replace(sectionNumber, "").trim().trimEnd(':', '：', '-', '–', '—').trim()
                    val lhsKey = normalize(lhsClean)
                    if (!(isSlideLike && index == 0 && (lhsKey in officialTocSectionKeys || hasDedicatedTextbookProse(lhsKey)))) {
                        continue
                    }
                }
                // Reject tiny math-symbol textbook labels like "Healthy: I'" (rhs < 6 chars).
                if (!isSlideLike && defMatch != null && defMatch.groupValues[2].trim().length < 6) continue
                val defined = bulletMatch != null || (defMatch != null && !numericTableDef)
                val markdown = line.startsWith("#")
                val numbered = sectionNumber.containsMatchIn(line)
                // On textbook pages, only skip bare unnumbered/undefined running headers — never ban real chapter/section definitions.
                if (!isSlideLike && !defined && !numbered && !markdown && isRepeatingTextbookEdge(line)) continue
                // Skip bare numeric chart/table axis labels surrounded by pure numeric tick lines (e.g. "150.0 All medical").
                if (numbered && !defined && !markdown && !explicitSectionPrefix.containsMatchIn(line)) {
                    val neighborhood = lines.subList(maxOf(0, index - 3), minOf(lines.size, index + 4))
                    val numericTickNeighbors = neighborhood.count { it != line && pureNumericTickLine.matches(it) }
                    if (numericTickNeighbors >= 3) continue
                }
                val heading = when {
                    bulletMatch != null -> bulletMatch.groupValues[1]
                    markdown -> line.trimStart('#', ' ')
                    defMatch != null -> defMatch.groupValues[1]
                    numbered || index == 0 -> line
                    else -> continue
                }.replace(sectionNumber, "").trim().trimEnd(':', '：', '-', '–', '—').trim()
                val key = normalize(heading)
                // Skip ONLY actual 2-line wrapped TOC headings on this page (e.g. "13 Technology and the price"
                // immediately followed by "of health care" matching TOC "technology and the price of health care"),
                // never standalone concepts like "Adverse selection" that share a prefix with a longer TOC title.
                if (!isSlideLike && !defined && index + 1 < lines.size) {
                    val joined = normalize("$heading ${lines[index + 1]}")
                    if (joined != key && (joined in bookTocTitles || joined in allFullTocTitles)) continue
                }
                val bodyBacked = key in officialTocSectionKeys || hasDedicatedTextbookProse(key)
                if (isSlideLike && !bodyBacked && (isRepeatingSlideEdge(line) || isRepeatingSlideEdge(heading))) continue
                if (!isSlideLike && !defined && !numbered && !markdown && isRepeatingTextbookEdge(heading)) continue
                if (!isValidExamPointTitle(line, heading, defined, numbered, markdown, officialTocSection = key in officialTocSectionKeys)) continue
                titles.putIfAbsent(key, heading)
                if (page.role == CourseMaterialRole.CORE_TEXTBOOK && (defined || numbered || markdown)) textbookStrong += key
            }
            indexed += page.copy(text = normalize(page.text))
        }

        val sectionRangesByKey = HashMap<String, MutableList<Pair<String, IntRange>>>()
        for ((bookId, spans) in sectionSpansByBook) {
            for (span in spans) {
                sectionRangesByKey.getOrPut(span.normalizedTitle) { mutableListOf() }.add(bookId to span.pages)
            }
        }

        data class DraftTopic(
            val key: String,
            val title: String,
            val sources: MutableList<CourseTopicLinkPlan>,
            val sectionStartByBook: MutableMap<String, Int>,
            var textbookBacked: Boolean,
        )

        val rawDrafts = titles.mapNotNull { (key, title) ->
            val pattern = termPattern(key)
            val isStrong = key in textbookStrong
            val linkMap = LinkedHashMap<Pair<String, Int>, CourseTopicLinkPlan>()
            for (pg in indexed) {
                if (pattern.containsMatchIn(pg.text)) {
                    linkMap.putIfAbsent(
                        pg.bookId to pg.page,
                        CourseTopicLinkPlan(pg.bookId, startPage = pg.page, endPage = pg.page, role = pg.role),
                    )
                }
            }
            val secStartByBook = HashMap<String, Int>()
            for ((bookId, range) in sectionRangesByKey[key].orEmpty()) {
                val available = cleanedTextbookPagesByBook[bookId].orEmpty()
                secStartByBook[bookId] = minOf(secStartByBook[bookId] ?: Int.MAX_VALUE, range.first)
                for (p in range) {
                    if (p in available) {
                        linkMap.putIfAbsent(
                            bookId to p,
                            CourseTopicLinkPlan(bookId, startPage = p, endPage = p, role = CourseMaterialRole.CORE_TEXTBOOK),
                        )
                    }
                }
            }
            val sources = linkMap.values.sortedWith(compareBy({ it.role.authorityRank }, { it.bookId }, { it.startPage })).toMutableList()
            val textbookBacked = isStrong && sources.any { it.role == CourseMaterialRole.CORE_TEXTBOOK }
            // A name that is only a slide title stays on its weekly topic. A textbook definition does not.
            if (sources.distinctBy { it.bookId }.size < 2 && !textbookBacked) return@mapNotNull null
            DraftTopic(key, title, sources, secStartByBook, textbookBacked)
        }

        // Canonicalize and merge near-duplicate exam points:
        // 1. Leading article variants ("The efficient producer hypothesis" vs "Efficient producer hypothesis")
        // 2. PDF missing-space / hyphen variants ("Ex postmoral hazard" vs "Ex post moral hazard")
        val canonicalGroups = LinkedHashMap<String, MutableList<DraftTopic>>()
        for (draft in rawDrafts) {
            val bare = leadingArticlePrefix.replace(draft.key, "").trim()
            val collapsed = bare.replace(Regex("""[\s\-–—]+"""), "")
            val groupKey = if (collapsed.length >= 6) collapsed else bare
            canonicalGroups.getOrPut(groupKey) { mutableListOf() }.add(draft)
        }
        val drafts = canonicalGroups.values.map { group ->
            if (group.size == 1) {
                group.single()
            } else {
                // Prefer: spaced form (more words, avoiding PDF glued words like "postmoral") -> without leading "The " -> textbookBacked.
                val winner = group.maxWith(
                    compareBy<DraftTopic>(
                        { it.key.split(whitespace).size - if (leadingArticlePrefix.containsMatchIn(it.key)) 1 else 0 },
                        { !leadingArticlePrefix.containsMatchIn(it.key) },
                        { it.textbookBacked },
                        { it.sources.size },
                    ),
                )
                val mergedLinks = LinkedHashMap<Pair<String, Int>, CourseTopicLinkPlan>()
                val mergedSecStarts = HashMap<String, Int>()
                for (d in group) {
                    for (s in d.sources) mergedLinks.putIfAbsent(s.bookId to s.startPage, s)
                    for ((b, sp) in d.sectionStartByBook) {
                        mergedSecStarts[b] = minOf(mergedSecStarts[b] ?: Int.MAX_VALUE, sp)
                    }
                }
                winner.copy(
                    sources = mergedLinks.values.sortedWith(compareBy({ it.role.authorityRank }, { it.bookId }, { it.startPage })).toMutableList(),
                    sectionStartByBook = mergedSecStarts,
                    textbookBacked = group.any { it.textbookBacked },
                )
            }
        }

        // Attach orphan cleaned textbook body pages (e.g. a chapter's Conclusion or unrepeated section page)
        // to the nearest preceding exam point in the same chapter so no taught body page is silently dropped.
        if (chapterRangesByBook.isNotEmpty() && sectionSpansByBook.isNotEmpty() && drafts.isNotEmpty()) {
            val coveredPages = HashSet<Pair<String, Int>>()
            for (d in drafts) {
                val tbSources = d.sources.filter { it.role == CourseMaterialRole.CORE_TEXTBOOK }
                if (tbSources.size <= 20) {
                    for (s in tbSources) coveredPages += s.bookId to s.startPage
                }
            }
            for ((bookId, chapterRanges) in chapterRangesByBook) {
                val cleanedPages = cleanedTextbookPagesByBook[bookId].orEmpty().sorted()
                for (p in cleanedPages) {
                    if ((bookId to p) in coveredPages) continue
                    val chapter = chapterRanges.firstOrNull { p in it } ?: continue
                    val candidate = drafts
                        .filter { d ->
                            val tbPages = d.sources.filter { it.role == CourseMaterialRole.CORE_TEXTBOOK && it.bookId == bookId }
                            tbPages.size < 16 && tbPages.any { it.startPage in chapter && it.startPage <= p }
                        }
                        .maxByOrNull { d ->
                            val maxBefore = d.sources
                                .filter { it.role == CourseMaterialRole.CORE_TEXTBOOK && it.bookId == bookId && it.startPage in chapter && it.startPage <= p }
                                .maxOf { it.startPage }
                            val sectionBoost = if (bookId in d.sectionStartByBook) 10_000 else 0
                            sectionBoost + maxBefore
                        } ?: continue
                    if (candidate.sources.none { it.bookId == bookId && it.startPage == p }) {
                        candidate.sources += CourseTopicLinkPlan(bookId, startPage = p, endPage = p, role = CourseMaterialRole.CORE_TEXTBOOK)
                        candidate.sources.sortWith(compareBy({ it.role.authorityRank }, { it.bookId }, { it.startPage }))
                        coveredPages += bookId to p
                    }
                }
            }
        }

        return drafts.mapIndexed { index, d ->
            val id = UUID.nameUUIDFromBytes("$courseId|point|${d.key}".toByteArray(Charsets.UTF_8))
            CourseTopicPlan("point-$id", LABEL, null, index + 1, d.title, CourseTopicStatus.EXAMINABLE, sources = d.sources)
        }
    }
}

internal data class ExamSectionSpan(
    val title: String,
    val normalizedTitle: String,
    val pages: IntRange,
) {
    constructor(normalizedTitle: String, pages: IntRange) : this(normalizedTitle, normalizedTitle, pages)
}

internal data class ExamTeachingPage(
    val bookId: String,
    val page: Int,
    val role: CourseMaterialRole,
    val text: String,
    val sectionEvidenceOnly: Boolean = false,
)
