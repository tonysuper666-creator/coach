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
    private val sectionNumber = Regex("""(?i)^(?:(?:chapter|section|lecture|week)\s+\d+(?:\.\d+)*|第[一二三四五六七八九十\d]+[章节周]|\d+(?:\.\d+)*)[\s.、:：—-]+""")
    private val definition = Regex("""^([^:：]{2,64})[:：]\s*\S""")
    private val whitespace = Regex("""[\s\p{Z}]+""")
    private val cjkChar = Regex("""[\u4E00-\u9FFF]""")
    private val trailingPageNum = Regex("""^(.+?\S)(?:\s*[…⋯]+|\s*(?:[.．·•\-–—_]\s*){2,}|\s+)(\d{1,4}|[ivxlcdm]{1,5})\s*$""")
    private val allowedSmallNumberPrefix = Regex(
        """(?i)(?:^|\s)(?:part|type|phase|stage|tier|level|class|grade|order|wave|pillar|generation|act|amendment|rule|version)$""",
    )
    private val formulaOrNoiseChar = Regex("""[=\[\]{}<>∑∫→←⇒≤≥≠≈±×÷^|\\−+]""")
    private val courseCodeNoise = Regex("""\b[A-Z]{2,6}\s*\d{3,6}\b""")
    private val institutionOrFooterNoise = Regex("""(?i)\b(?:university\s+of|department\s+of|school\s+of|faculty\s+of|semester\s+\d|academic\s+year|lecture\s+\d+\s*[-–—|])\b""")
    private val numericCohortLabelNoise = Regex("""(?i)^(?:ages?|years?|months?|days?|grades?|groups?|cohorts?|panels?|periods?|waves?|quartiles?|quintiles?|deciles?)\s+\d""")
    private val functionCallNoise = Regex("""^[A-Za-z]{1,2}\([^)]{1,20}\)$""")
    private val journalCitationNoise = Regex("""(?i)(?:,\s*\d{1,4}\(\d{1,4}\)|\b(?:journal|quarterly|review)\s+of\b.{0,40}\d)""")
    private val sentenceVerbNoise = Regex("""(?i)\b(?:is|are|was|were|has|have|had|can|could|will|would|should|must|does|do|did|getting|becoming|leads|makes|causes|shows)\b""")
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
        "sources", "examples", "side note", "sidebar", "enrollment", "next week", "last week",
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

        // Do not mistake an exercise continuation page for a chapter opening.
        if (looksLikeExerciseContinuationPage(text)) return false

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
     * Strips end-of-chapter exercises from a single textbook page/block.
     * Returns empty string when the entire page is exercises.
     */
    fun stripExerciseSection(text: String): String {
        val rawLines = text.lines()
        val cutIndex = findExerciseStartLineIndex(rawLines)
        if (cutIndex >= 0) {
            val before = rawLines.subList(0, cutIndex).joinToString("\n").trim()
            return if (hasSubstantiveBody(before)) before else ""
        }
        if (looksLikeExerciseContinuationPage(text)) return ""
        return text
    }

    /**
     * Walks consecutive textbook pages in page order so multi-page end-of-chapter exercises
     * (e.g., heading on page N, numbered True/False/Unclear statements on N+1, analytical problems on N+2)
     * are stripped cleanly up to the next chapter boundary — and NEVER past the next chapter.
     */
    fun stripExercisesFromTextbookPages(
        pages: List<ExamTeachingPage>,
        chapterStartPagesByBook: Map<String, Set<Int>> = emptyMap(),
    ): List<ExamTeachingPage> {
        val out = mutableListOf<ExamTeachingPage>()
        var currentBook: String? = null
        var inExerciseTail = false
        var tailPages = 0
        var exerciseChapterNum: Int? = null
        for (page in pages) {
            if (page.role != CourseMaterialRole.CORE_TEXTBOOK) {
                out += page
                continue
            }
            if (page.bookId != currentBook) {
                currentBook = page.bookId
                inExerciseTail = false
                tailPages = 0
                exerciseChapterNum = null
            }
            val chapterStarts = chapterStartPagesByBook[page.bookId].orEmpty()
            val startsChapter = isChapterOpeningPage(page.page, page.text, chapterStarts, if (inExerciseTail) exerciseChapterNum else null)
            if (startsChapter) {
                inExerciseTail = false
                tailPages = 0
                exerciseChapterNum = null
            }
            val rawLines = page.text.lines()
            val cutIndex = findExerciseStartLineIndex(rawLines)
            val looksExercise = looksLikeExerciseContinuationPage(page.text)
            if (inExerciseTail) {
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
            if (looksExercise) {
                inExerciseTail = true
                tailPages = 1
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
        var inExerciseTail = false
        var tailPages = 0
        var exerciseChapterNum: Int? = null
        for (page in pages.sortedBy { it.number }) {
            if (isTocOrIndexPage(page.text)) continue
            val startsChapter = isChapterOpeningPage(page.number, page.text, chapterStartPages, if (inExerciseTail) exerciseChapterNum else null)
            if (startsChapter) {
                inExerciseTail = false
                tailPages = 0
                exerciseChapterNum = null
            }
            val rawLines = page.text.lines()
            val cutIndex = findExerciseStartLineIndex(rawLines)
            val looksExercise = looksLikeExerciseContinuationPage(page.text)
            if (inExerciseTail) {
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
            if (looksExercise) {
                inExerciseTail = true
                tailPages = 1
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
        if (subPartMarker.containsMatchIn(text)) return false
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
        val questionMarks = trimmed.count { it == '?' || it == '？' }
        if (subParts >= 3 && (numbered.isNotEmpty() || questionMarks >= 1)) return true
        if (numbered.size >= 2 && (promptLike >= 2 || (subParts >= 2 && questionMarks >= 1))) return true
        if (numbered.size < 3) return false
        return promptLike >= 1 || subParts >= 2 || questionMarks >= 3
    }

    private fun hasSubstantiveBody(text: String): Boolean {
        val nonWs = text.count { !it.isWhitespace() }
        if (nonWs < 40) return false
        val lines = text.lineSequence().map(String::trim).filter {
            it.isNotEmpty() && !PRINTED_PAGE_MARK.matches(it) && !ExplainClassifier.isPageNumber(it)
        }.toList()
        return lines.any { it.length >= 20 }
    }

    fun isValidExamPointTitle(
        rawLine: String,
        heading: String,
        defined: Boolean,
        numbered: Boolean,
        markdown: Boolean,
    ): Boolean {
        if (heading.length !in 2..64) return false
        if (heading.any { it in "。！？!?；;." }) return false
        if (heading.none(Char::isLetter)) return false
        if (ExplainClassifier.isTocLine(rawLine) || hasTrailingPageNumber(rawLine) || hasTrailingPageNumber(heading)) {
            return false
        }
        if (formulaOrNoiseChar.containsMatchIn(heading)) return false
        if (courseCodeNoise.containsMatchIn(heading) || institutionOrFooterNoise.containsMatchIn(heading) ||
            numericCohortLabelNoise.containsMatchIn(heading) || functionCallNoise.matches(heading)
        ) return false
        if (journalCitationNoise.containsMatchIn(heading)) return false
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
            if (!markdown && !defined && !numbered && words.size > 7) return false
            if (words.size >= 6 && sentenceVerbNoise.containsMatchIn(heading)) return false
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
        if (teaching.size < 2) return emptyList()
        val pages = mutableListOf<ExamTeachingPage>()
        val chapterStartsByBook = mutableMapOf<String, Set<Int>>()
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
            if (structure != null) {
                val starts = structure.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }.map { it.startPage }.toSet()
                if (starts.isNotEmpty()) chapterStartsByBook[mat.bookId] = starts
            }
            for (page in bookPages) {
                if (page.number < first || page.ocrPending || excludedRanges.any { page.number in it }) continue
                val role = structure?.pageRole(page.number)
                if (role in setOf(BookPageRole.TOC, BookPageRole.COPYRIGHT, BookPageRole.INDEX, BookPageRole.REFERENCES, BookPageRole.BLANK)) {
                    continue
                }
                if (role == BookPageRole.FRONT_MATTER && (hasChapters || BookBoundaries.looksLikeFrontMatter(page.text))) {
                    continue
                }
                if (isTocOrIndexPage(page.text)) continue
                pages += ExamTeachingPage(mat.bookId, page.number, mat.role, page.text)
            }
        }
        val cleaned = stripExercisesFromTextbookPages(pages, chapterStartsByBook)
        return identifyFromPages(courseId, cleaned)
    }

    /**
     * Headings that the textbook itself defines, or numbers as a section, stay even when
     * the slides never repeat the name. A bare first line still needs a second book.
     */
    fun identifyFromPages(courseId: String, pages: List<ExamTeachingPage>): List<CourseTopicPlan> {
        val bodyPages = stripExercisesFromTextbookPages(
            pages.filter { it.role in teachingRoles && it.text.isNotBlank() && !isTocOrIndexPage(it.text) },
        )
        // Detect repeating running headers / slide footers (lecturer name + course name, etc.) across pages.
        val edgeFreqByBook = HashMap<Pair<String, String>, Int>()
        val slideEdgeFreq = HashMap<String, Int>()
        for (page in bodyPages) {
            val nonEmpty = page.text.lineSequence().map(String::trim)
                .filter { it.isNotEmpty() && !PRINTED_PAGE_MARK.matches(it) && !ExplainClassifier.isPageNumber(it) }
                .toList()
            if (nonEmpty.size < 2) continue
            val edges = (nonEmpty.take(2) + nonEmpty.takeLast(2)).map(::normalize).distinct()
            for (edge in edges) {
                if (edge.length < 3) continue
                val key = page.bookId to edge
                edgeFreqByBook[key] = (edgeFreqByBook[key] ?: 0) + 1
                if (page.role != CourseMaterialRole.CORE_TEXTBOOK) {
                    slideEdgeFreq[edge] = (slideEdgeFreq[edge] ?: 0) + 1
                }
            }
        }
        val repeatingEdgesByBook = edgeFreqByBook.filterValues { it >= 3 }.keys
        val repeatingSlideEdges = slideEdgeFreq.filterValues { it >= 4 }.keys

        val titles = linkedMapOf<String, String>()
        val textbookStrong = mutableSetOf<String>()
        val indexed = mutableListOf<ExamTeachingPage>()
        for (page in bodyPages) {
            val lines = page.text.lineSequence().map(String::trim)
                .filter { it.isNotEmpty() && !PRINTED_PAGE_MARK.matches(it) }
                .toList()
            for ((index, line) in lines.withIndex()) {
                val normLine = normalize(line)
                if ((page.bookId to normLine) in repeatingEdgesByBook || normLine in repeatingSlideEdges) continue
                val defined = definition.containsMatchIn(line)
                val markdown = line.startsWith("#")
                val numbered = sectionNumber.containsMatchIn(line)
                val heading = when {
                    markdown -> line.trimStart('#', ' ')
                    defined -> definition.find(line)!!.groupValues[1]
                    numbered || index == 0 -> line
                    else -> continue
                }.replace(sectionNumber, "").trim()
                val key = normalize(heading)
                if ((page.bookId to key) in repeatingEdgesByBook || key in repeatingSlideEdges) continue
                if (!isValidExamPointTitle(line, heading, defined, numbered, markdown)) continue
                titles.putIfAbsent(key, heading)
                if (page.role == CourseMaterialRole.CORE_TEXTBOOK && (defined || numbered || markdown)) textbookStrong += key
            }
            indexed += page.copy(text = normalize(page.text))
        }
        return titles.mapNotNull { (key, title) ->
            val pattern = termPattern(key)
            val sources = indexed.filter { pattern.containsMatchIn(it.text) }.map {
                CourseTopicLinkPlan(it.bookId, startPage = it.page, endPage = it.page, role = it.role)
            }
            val textbookBacked = key in textbookStrong && sources.any { it.role == CourseMaterialRole.CORE_TEXTBOOK }
            // A name that is only a slide title stays on its weekly topic. A textbook definition does not.
            if (sources.distinctBy { it.bookId }.size < 2 && !textbookBacked) return@mapNotNull null
            val id = UUID.nameUUIDFromBytes("$courseId|point|$key".toByteArray(Charsets.UTF_8))
            CourseTopicPlan("point-$id", LABEL, null, 0, title, CourseTopicStatus.EXAMINABLE, sources = sources)
        }.mapIndexed { index, topic -> topic.copy(ord = index + 1) }
    }
}

internal data class ExamTeachingPage(
    val bookId: String,
    val page: Int,
    val role: CourseMaterialRole,
    val text: String,
)
