package com.coach.desktop.books

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.sql.Connection

/**
 * Deterministic local course week/topic aligner + offline topic pack builder.
 * Runs synchronously right after import or locally when the network service is unreachable,
 * while server-backed Sonnet alignment overlays richer cross-material structure without
 * ever overwriting user locks (`user_locked = 1` or tombstones `-1`).
 */
internal object CourseAutoAligner {
    private val syllabusWeekLineRegex = Regex(
        """(?i)^(?:\s*[-*#•|]*\s*)(?:weeks?|wk|w)\s*(\d{1,2})(?:\s*[-–—]\s*(\d{1,2}))?(?:\s*[:：\-—.|]\s*(.*)|\s{2,}(.+)|\s+([A-Z\u4e00-\u9fff].{2,})|\s*)$""",
    )
    private val syllabusZhWeekLineRegex = Regex(
        """^(?:\s*[-*#•|]*\s*)第\s*(\d{1,2})(?:\s*[-–—到至]\s*(\d{1,2}))?\s*周(?:\s*[:：\-—.|]\s*(.*)|\s+(.+)|\s*)$""",
    )
    private val chapterTokenRegex = Regex("""(?i)\b(?:ch(?:apter)?s?\.?)\s*(\d{1,2})\b|第\s*(\d{1,2})\s*章""")
    private val leadingChapterNumRegex = Regex("""^(?:chapter\s+|第\s*)?(\d{1,2})(?:[\s.:：—\-]+|章)""", RegexOption.IGNORE_CASE)
    private val chapterEnSequenceRegex = Regex(
        """(?i)\b(?:chapters?|chs?\.?)\s*(\d{1,2}(?:\s*(?:[-–—~]|to)\s*\d{1,2})?(?:\s*(?:[,&/]|\band\b)\s*\d{1,2}(?:\s*(?:[-–—~]|to)\s*\d{1,2})?)*)""",
    )
    private val chapterZhSequenceRegex = Regex(
        """第\s*(\d{1,2}(?:\s*(?:[-–—~到至])\s*\d{1,2})?(?:\s*(?:[,，、&/和与])\s*\d{1,2}(?:\s*(?:[-–—~到至])\s*\d{1,2})?)*)\s*章""",
    )
    private val weekMentionRegex = Regex("""(?i)\b(?:week|wk)\s*(\d{1,2})\b|第\s*(\d{1,2})\s*周""")
    private val overviewSlideRegex = Regex(
        """(?i)\b(?:course\s+(?:outline|schedule|overview|plan|syllabus)|module\s+(?:outline|schedule|overview)|lecture\s+schedule|timetable|assessment\s+schedule)\b|课程安排|教学进度|课程大纲""",
    )
    private val crossRefChapterLineRegex = Regex(
        """(?i)\b(?:see|recall\s+from|covered\s+in|discussed\s+in|return\s+to|later\s+in|earlier\s+in|refer\s+to|preview\s+of)\s+(?:chapters?|chs?\.?)\b|\b(?:next|previous|last)\s+week\b""",
    )
    private val slideReadingLineRegex = Regex(
        """(?i)\b(?:reading|readings|textbook|bht|required|recommended|chapters?|chs?\.?)\b|第\s*\d{1,2}.*章""",
    )
    private val chapterHeadingRegex = Regex("""(?i)^chapter\s+\d+|^第\s*[0-9一二三四五六七八九十]+\s*章""")
    private val sectionHeadingLineRegex = Regex("""^(?:#{2,4}\s+\S.*|\d{1,2}\.\d{1,2}(?:\.\d{1,2})?\s+\S.*)""")
    private val stopWords = setOf(
        "week", "weeks", "lecture", "lectures", "slides", "slide", "tutorial", "tutorials",
        "solution", "solutions", "key", "chapter", "chapters", "section", "sections",
        "textbook", "notes", "summary", "review", "course", "handbook", "syllabus", "part",
        "the", "and", "for", "with", "into", "from", "that", "this", "these", "those",
        "introduction", "overview", "conclusion", "conclusions", "reading", "readings",
        "required", "optional", "recommended", "further", "topic", "topics", "module",
        "unit", "class", "seminar", "workshop", "problem", "problems", "exercise", "exercises",
        "question", "questions", "answer", "answers", "example", "examples", "case", "study",
        "model", "models", "theory", "analysis", "empirical", "evidence", "data", "policy",
        "economics", "economic", "market", "markets", "system", "systems", "care", "public",
        "private", "role", "issues", "perspectives", "basic", "basics", "advanced", "modern",
        "are", "was", "were", "have", "has", "had", "will", "can", "may", "not", "all", "its",
    )

    data class TextbookChapterRange(
        val bookId: String,
        val tocNodeId: String?,
        val title: String,
        val startPage: Int,
        val endPage: Int,
        val keywords: Set<String>,
        val chapterNum: Int? = null,
        val sectionTitles: List<String> = emptyList(),
        val sectionKeywords: Set<String> = emptySet(),
    )

    private data class SyllabusWeekBlock(
        val title: String,
        val fullText: String,
    )

    internal fun extractChapterNumbers(text: String): Set<Int> {
        if (text.isBlank()) return emptySet()
        val out = linkedSetOf<Int>()
        val rangeSep = Regex("""\s*(?:[-–—~到至]|(?i)\bto\b)\s*""")
        val itemSep = Regex("""\s*(?:[,，、&/和与]|(?i)\band\b)\s*""")
        fun collect(regex: Regex) {
            for (match in regex.findAll(text)) {
                val expr = match.groupValues[1]
                for (part in expr.split(itemSep)) {
                    val trimmed = part.trim()
                    if (trimmed.isEmpty()) continue
                    val bounds = trimmed.split(rangeSep).mapNotNull { it.trim().toIntOrNull() }
                    if (bounds.size == 2) {
                        val start = bounds[0]
                        val end = bounds[1]
                        if (start in 1..60 && end in start..60 && (end - start) <= 15) {
                            for (n in start..end) out += n
                        }
                    } else if (bounds.size == 1 && bounds[0] in 1..60) {
                        out += bounds[0]
                    }
                }
            }
        }
        collect(chapterEnSequenceRegex)
        collect(chapterZhSequenceRegex)
        return out
    }

    private fun parseSyllabusWeeks(pages: List<BookPage>): Pair<Map<Int, SyllabusWeekBlock>, Set<String>> {
        val titlesByWeek = LinkedHashMap<Int, String>()
        val linesByWeek = LinkedHashMap<Int, MutableList<String>>()
        val excludedHints = mutableSetOf<String>()
        var activeWeeks: List<Int> = emptyList()
        var activeLineBudget = 0
        for (line in pages.flatMap { it.text.lines() }) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (activeLineBudget > 0) activeLineBudget = (activeLineBudget - 2).coerceAtLeast(0)
                continue
            }
            val lower = trimmed.lowercase()
            if ("not examinable" in lower || "excluded" in lower || "不考" in trimmed || "跳过" in trimmed) {
                excludedHints += keywordsOf(trimmed)
            }
            val enMatch = syllabusWeekLineRegex.matchEntire(trimmed)
            val zhMatch = if (enMatch == null) syllabusZhWeekLineRegex.matchEntire(trimmed) else null
            if (enMatch != null || zhMatch != null) {
                val m = enMatch ?: zhMatch!!
                val startWk = m.groupValues[1].toIntOrNull()
                val endWk = m.groupValues[2].toIntOrNull() ?: startWk
                val rest = (3 until m.groupValues.size)
                    .map { m.groupValues[it].trim() }
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
                if (startWk != null && startWk in 1..20 && endWk != null && endWk in startWk..20 && (endWk - startWk) <= 4) {
                    activeWeeks = (startWk..endWk).toList()
                    activeLineBudget = 6
                    for (wk in activeWeeks) {
                        if (rest.isNotEmpty() && wk !in titlesByWeek) {
                            titlesByWeek[wk] = rest.take(90)
                        }
                        if (rest.isNotEmpty()) {
                            linesByWeek.getOrPut(wk) { mutableListOf() }.add(rest)
                        } else {
                            linesByWeek.getOrPut(wk) { mutableListOf() }
                        }
                    }
                    continue
                }
            }
            if (activeWeeks.isNotEmpty() && activeLineBudget > 0) {
                if (Regex("""(?i)^(?:assessment|grading|office\s+hours|plagiarism|academic\s+integrity|bibliography)\b""").containsMatchIn(trimmed)) {
                    activeWeeks = emptyList()
                    activeLineBudget = 0
                    continue
                }
                for (wk in activeWeeks) {
                    if (wk !in titlesByWeek && trimmed.length >= 3) {
                        titlesByWeek[wk] = trimmed.take(90)
                    }
                    linesByWeek.getOrPut(wk) { mutableListOf() }.add(trimmed)
                }
                activeLineBudget--
            }
        }
        val blocks = LinkedHashMap<Int, SyllabusWeekBlock>()
        for ((wk, collected) in linesByWeek) {
            val title = titlesByWeek[wk]?.takeIf { it.isNotBlank() } ?: continue
            blocks[wk] = SyllabusWeekBlock(title = title, fullText = collected.joinToString("\n").take(900))
        }
        return blocks to excludedHints
    }

    fun alignLocally(db: Connection, courseId: String): CourseDetailSnapshot {
        val current = CourseStore.loadCourseDetail(db, courseId)
        val previewByBook = current.materials.associate { mat ->
            val pages = CourseStore.loadPagesForBook(db, mat.bookId, maxPages = 6)
            mat.bookId to CourseClientGates.sanitizeUserNote(pages.joinToString("\n") { it.text }).cleanText
        }

        val resolvedMaterials = current.materials.mapIndexed { idx, mat ->
            if (mat.userLocked) {
                CourseMaterialLinkSpec(mat.bookId, mat.role, mat.weekNum, true, idx)
            } else {
                val preview = previewByBook[mat.bookId].orEmpty()
                val inferredRole = CourseMaterialRole.infer(
                    title = mat.title,
                    format = mat.format,
                    previewText = preview,
                    sourceFilename = mat.sourceFilename,
                    pageCount = mat.pageCount,
                )
                val inferredWeek = if (inferredRole in setOf(CourseMaterialRole.SYLLABUS, CourseMaterialRole.CORE_TEXTBOOK)) {
                    null
                } else {
                    mat.weekNum ?: CourseMaterialRole.inferWeekNumber(mat.title, mat.sourceFilename, preview)
                }
                CourseMaterialLinkSpec(mat.bookId, inferredRole, inferredWeek, false, idx)
            }
        }
        val specByBook = resolvedMaterials.associateBy { it.bookId }

        // Parse syllabus week outlines (single-line and multi-line table blocks) when available
        val syllabusByWeek = LinkedHashMap<Int, SyllabusWeekBlock>()
        val excludedHints = mutableSetOf<String>()
        for (mat in current.materials) {
            val effRole = specByBook[mat.bookId]?.role ?: mat.role
            if (effRole != CourseMaterialRole.SYLLABUS) continue
            val pages = CourseStore.loadPagesForBook(db, mat.bookId, maxPages = 40)
            val (parsedWeeks, hints) = parseSyllabusWeeks(pages)
            excludedHints += hints
            for ((wk, block) in parsedWeeks) {
                syllabusByWeek.putIfAbsent(wk, block)
            }
        }

        val textbookMaterials = current.materials
            .filter { (specByBook[it.bookId]?.role ?: it.role) == CourseMaterialRole.CORE_TEXTBOOK }
        val textbookChapters = textbookMaterials.flatMap { loadTextbookChapters(db, it) }
        val firstChapterByBook = textbookChapters.distinctBy { it.bookId }.associateBy { it.bookId }

        // Words appearing in the course/textbook title or in >30% of chapters are too generic to match chapters alone.
        val genericCourseWords = buildSet {
            addAll(keywordsOf(current.title))
            textbookMaterials.forEach { addAll(keywordsOf(it.title)) }
            if (textbookChapters.size >= 4) {
                val docFreq = HashMap<String, Int>()
                for (ch in textbookChapters) {
                    for (kw in ch.keywords) docFreq[kw] = (docFreq[kw] ?: 0) + 1
                }
                val threshold = (textbookChapters.size * 30) / 100
                docFreq.forEach { (kw, count) -> if (count > threshold && count >= 3) add(kw) }
            }
        }

        // Precompute authoritative syllabus chapter assignments per week so one week never steals another week's chapters.
        val syllabusChaptersByWeek = syllabusByWeek.mapValues { (_, block) -> extractChapterNumbers(block.fullText) }
        val chapterOwnersFromSyllabus = HashMap<Int, MutableSet<Int>>()
        for ((wk, chNums) in syllabusChaptersByWeek) {
            for (chNum in chNums) chapterOwnersFromSyllabus.getOrPut(chNum) { mutableSetOf() }.add(wk)
        }

        val coveredChapterKeys = mutableSetOf<Triple<String, Int, String>>()
        val weeksWithMaterials = resolvedMaterials.mapNotNull { it.weekNum }.toSortedSet()
        val allWeeks = (weeksWithMaterials + syllabusByWeek.keys).toSortedSet()

        val topics = mutableListOf<CourseTopicPlan>()
        for (wk in allWeeks) {
            val matsForWeek = current.materials.filter {
                val spec = specByBook[it.bookId]
                val role = spec?.role ?: it.role
                spec?.weekNum == wk && (it.userLocked || role !in setOf(CourseMaterialRole.SYLLABUS, CourseMaterialRole.CORE_TEXTBOOK))
            }.sortedBy { (specByBook[it.bookId]?.role ?: it.role).displayOrder }
            val hasPrimaryTeachingMaterial = matsForWeek.any {
                val r = specByBook[it.bookId]?.role ?: it.role
                r in setOf(
                    CourseMaterialRole.LECTURE_SLIDES,
                    CourseMaterialRole.TUTORIAL,
                    CourseMaterialRole.SOLUTION_KEY,
                    CourseMaterialRole.TRANSCRIPT,
                    CourseMaterialRole.REQUIRED_READING,
                    CourseMaterialRole.USER_NOTES,
                    CourseMaterialRole.HANDOUT,
                )
            }
            val syllabusBlock = syllabusByWeek[wk]
            val weekTitle = syllabusBlock?.title
                ?: matsForWeek.firstOrNull { (specByBook[it.bookId]?.role ?: it.role) == CourseMaterialRole.LECTURE_SLIDES }
                    ?.let { cleanTopicTitle(it.title, wk) }
                ?: matsForWeek.firstOrNull()?.let { cleanTopicTitle(it.title, wk) }
                ?: "第 $wk 周专题"

            val syllabusChapters = syllabusChaptersByWeek[wk].orEmpty()
            val slideReadingChapters = linkedSetOf<Int>()
            val primaryTitleText = buildList {
                if (syllabusBlock != null) add(syllabusBlock.fullText) else add(weekTitle)
                for (m in matsForWeek) add(cleanTopicTitle(m.title, wk))
            }
            val slideTitlesNorm = mutableListOf<String>()
            if (syllabusBlock != null) {
                slideTitlesNorm += CourseExamPoints.normalize(syllabusBlock.fullText)
            } else {
                slideTitlesNorm += CourseExamPoints.normalize(weekTitle)
            }

            // Prefer lecture slides/handouts for topic/section matching; only fall back to tutorials/keys if no slides exist.
            val slideLikeMats = matsForWeek.filter {
                val r = specByBook[it.bookId]?.role ?: it.role
                r in setOf(CourseMaterialRole.LECTURE_SLIDES, CourseMaterialRole.HANDOUT)
            }.ifEmpty {
                matsForWeek.filter {
                    val r = specByBook[it.bookId]?.role ?: it.role
                    r !in setOf(CourseMaterialRole.CORE_TEXTBOOK, CourseMaterialRole.SYLLABUS)
                }
            }
            for (m in slideLikeMats) {
                val effRole = specByBook[m.bookId]?.role ?: m.role
                slideTitlesNorm += CourseExamPoints.normalize(cleanTopicTitle(m.title, wk))
                slideReadingChapters += extractChapterNumbers(m.title)
                val matPages = CourseStore.loadPagesForBook(db, m.bookId, maxPages = 120)
                for ((pageIdx, p) in matPages.withIndex()) {
                    val cleanText = if (effRole == CourseMaterialRole.USER_NOTES) {
                        CourseClientGates.sanitizeUserNote(p.text).cleanText
                    } else {
                        p.text
                    }
                    // Skip semester-wide outline / schedule slides that list many weeks or many chapters at once.
                    val weeksMentioned = weekMentionRegex.findAll(cleanText)
                        .mapNotNull { it.groupValues[1].toIntOrNull() ?: it.groupValues[2].toIntOrNull() }
                        .toSet()
                    val isOverviewSlide = overviewSlideRegex.containsMatchIn(cleanText) ||
                        weeksMentioned.size >= 2 ||
                        (weeksMentioned.size == 1 && wk !in weeksMentioned)
                    if (isOverviewSlide) continue

                    val nonEmpty = cleanText.lineSequence().map(String::trim).filter {
                        it.isNotEmpty() && !PRINTED_PAGE_MARK.matches(it) && !ExplainClassifier.isPageNumber(it)
                    }.toList()
                    // Capture only the slide title (first short heading line on each slide) for exact section matching.
                    val firstTitle = nonEmpty.firstOrNull { line ->
                        line.length in 5..90 && line.last() !in setOf('.', '?', '!', '。', '？', '！')
                    }
                    if (firstTitle != null) {
                        slideTitlesNorm += CourseExamPoints.normalize(firstTitle)
                    }
                    // Extract explicit chapter reading assignments ONLY from top intro slides or explicit reading lines,
                    // never from passing cross-references ("see chapter 18") or multi-chapter overview lists.
                    if (pageIdx < 5) {
                        val pageChapters = linkedSetOf<Int>()
                        for (line in nonEmpty) {
                            if (crossRefChapterLineRegex.containsMatchIn(line)) continue
                            val lineWeeks = weekMentionRegex.findAll(line)
                                .mapNotNull { it.groupValues[1].toIntOrNull() ?: it.groupValues[2].toIntOrNull() }
                                .toSet()
                            if (lineWeeks.isNotEmpty() && wk !in lineWeeks) continue
                            if (slideReadingLineRegex.containsMatchIn(line)) {
                                pageChapters += extractChapterNumbers(line)
                            }
                        }
                        if (pageChapters.size in 1..3) {
                            slideReadingChapters += pageChapters
                        }
                    }
                }
            }

            // Focused keywords ONLY from the syllabus week entry and material titles (never 600 body words!).
            val focusedWeekKeywords = primaryTitleText.flatMap { keywordsOf(it) }.toSet()
            val slideTitlesJoined = slideTitlesNorm.joinToString("\n")

            val status = if (hasPrimaryTeachingMaterial || matsForWeek.isNotEmpty()) {
                CourseTopicStatus.EXAMINABLE
            } else {
                CourseTopicStatus.PENDING_MATERIALS
            }

            val links = mutableListOf<CourseTopicLinkPlan>()
            for (m in matsForWeek) {
                val effRole = specByBook[m.bookId]?.role ?: m.role
                links += CourseTopicLinkPlan(
                    bookId = m.bookId,
                    tocNodeId = null,
                    startPage = firstChapterByBook[m.bookId]?.startPage ?: 1,
                    endPage = m.pageCount.coerceAtLeast(1),
                    role = effRole,
                    scopeNote = "第 $wk 周",
                )
            }
            for (ch in textbookChapters) {
                val syllabusOwners = ch.chapterNum?.let { chapterOwnersFromSyllabus[it] }.orEmpty()
                val assignedElsewhereBySyllabus = syllabusOwners.isNotEmpty() && wk !in syllabusOwners

                val explicitSyllabusMatch = ch.chapterNum != null && ch.chapterNum in syllabusChapters
                val explicitSlideMatch = !assignedElsewhereBySyllabus && ch.chapterNum != null && ch.chapterNum in slideReadingChapters

                val cleanChTitle = ch.title
                    .replace(Regex("""(?i)^(?:chapter\s+\d+|第\s*\d+\s*章|\d{1,2})[\s.:：—\-]+"""), "")
                    .trim()
                val normChTitle = CourseExamPoints.normalize(cleanChTitle)
                val exactTitlePhraseMatch = !assignedElsewhereBySyllabus &&
                    normChTitle.length >= 8 &&
                    slideTitlesNorm.any { normChTitle in it }

                val specificTitleKw = (ch.keywords - genericCourseWords).ifEmpty { ch.keywords }
                val titleHits = specificTitleKw.count { it in focusedWeekKeywords }
                val titleKeywordMatch = !assignedElsewhereBySyllabus &&
                    titleHits >= 2 &&
                    titleHits * 2 >= specificTitleKw.size

                // High-precision section match: exact normalized section phrase (len >= 12) in a slide title or syllabus block.
                val sectionMatched = !assignedElsewhereBySyllabus && ch.sectionTitles.any { secTitle ->
                    val stripped = secTitle.replace(Regex("""^\d{1,2}(?:\.\d{1,2})+\s+"""), "").trim()
                    val normSec = CourseExamPoints.normalize(stripped)
                    normSec.length >= 12 && normSec in slideTitlesJoined
                }

                val excludedByHint = !explicitSyllabusMatch && ch.keywords.any { it in excludedHints } && !titleKeywordMatch && !sectionMatched
                if ((explicitSyllabusMatch || explicitSlideMatch || exactTitlePhraseMatch || titleKeywordMatch || sectionMatched) && !excludedByHint) {
                    coveredChapterKeys += Triple(ch.bookId, ch.startPage, ch.title)
                    links += CourseTopicLinkPlan(
                        bookId = ch.bookId,
                        tocNodeId = ch.tocNodeId,
                        startPage = ch.startPage,
                        endPage = ch.endPage,
                        role = CourseMaterialRole.CORE_TEXTBOOK,
                        scopeNote = if (status == CourseTopicStatus.PENDING_MATERIALS) "尚未上课（预习） · ${ch.title}" else ch.title,
                    )
                }
            }
            topics += CourseTopicPlan(
                weekLabel = CourseStore.formatWeekLabel(wk),
                weekNum = wk,
                ord = wk * 10,
                title = weekTitle,
                examStatus = status,
                examWeight = if (status == CourseTopicStatus.EXAMINABLE) 1.0 else 0.5,
                sources = links.distinctBy { Triple(it.bookId, it.startPage, it.endPage) },
            )
        }

        // Uncovered materials with no week (excluding SYLLABUS & CORE_TEXTBOOK unless standalone reading/notes)
        val unweekedExtras = current.materials.filter { mat ->
            val spec = specByBook[mat.bookId]
            spec?.weekNum == null && spec?.role !in setOf(CourseMaterialRole.SYLLABUS, CourseMaterialRole.CORE_TEXTBOOK)
        }
        if (unweekedExtras.isNotEmpty()) {
            topics += CourseTopicPlan(
                weekLabel = "补充材料",
                weekNum = null,
                ord = 900,
                title = "跨周延伸与个人补充材料",
                examStatus = CourseTopicStatus.EXAMINABLE,
                examWeight = 0.7,
                sources = unweekedExtras.map { m ->
                    val r = specByBook[m.bookId]?.role ?: m.role
                    CourseTopicLinkPlan(m.bookId, null, 1, m.pageCount.coerceAtLeast(1), r, "待按周归位")
                },
            )
        }

        // Only narrow out uncovered textbook chapters into EXCLUDED when the course is marked as exam,
        // has an explicit syllabus (same predicate as CourseDetailSnapshot.hasSyllabusOrScope), AND that syllabus
        // actually matched at least one textbook chapter. A syllabus we cannot map to chapters (e.g. a chapter list
        // without week lines) must never exclude the whole book; keep every chapter and let the UI say
        // "已收到考纲，暂按整本书安排".
        val hasScopeNarrowing = current.isExam &&
            hasSyllabusScope(resolvedMaterials.map { it.role }) &&
            coveredChapterKeys.isNotEmpty()
        if (hasScopeNarrowing) {
            var excludedOrd = 1000
            for (ch in textbookChapters) {
                if (Triple(ch.bookId, ch.startPage, ch.title) in coveredChapterKeys) continue
                topics += CourseTopicPlan(
                    weekLabel = "本课不考",
                    weekNum = null,
                    ord = excludedOrd++,
                    title = ch.title,
                    examStatus = CourseTopicStatus.EXCLUDED,
                    examWeight = 0.0,
                    sources = listOf(
                        CourseTopicLinkPlan(
                            bookId = ch.bookId,
                            tocNodeId = ch.tocNodeId,
                            startPage = ch.startPage,
                            endPage = ch.endPage,
                            role = CourseMaterialRole.CORE_TEXTBOOK,
                            scopeNote = "未覆盖教材章节 · 可随时恢复为必考",
                        ),
                    ),
                )
            }
        } else {
            val chapterPosByKey = textbookChapters.withIndex().associate { (i, ch) ->
                Triple(ch.bookId, ch.startPage, ch.title) to (i + 1)
            }
            // Chapter labels can shift when front matter is removed. Keep the existing topic's ID.
            val existingChapterTopics = current.allTopics.mapNotNull { topic ->
                val source = topic.sources.singleOrNull()
                    ?.takeIf { CourseStore.isChapterTopicLabel(topic.weekLabel) && it.role == CourseMaterialRole.CORE_TEXTBOOK }
                source?.tocNodeId?.let { (source.bookId to it) to topic.topicId }
            }.toMap()
            val uncoveredChapters = textbookChapters.filter { Triple(it.bookId, it.startPage, it.title) !in coveredChapterKeys }
            if (uncoveredChapters.isNotEmpty()) {
                uncoveredChapters.forEachIndexed { idx, ch ->
                    val chapterOrder = chapterPosByKey[Triple(ch.bookId, ch.startPage, ch.title)] ?: (idx + 1)
                    topics += CourseTopicPlan(
                        topicId = ch.tocNodeId?.let { existingChapterTopics[ch.bookId to it] },
                        weekLabel = "第 $chapterOrder 章",
                        weekNum = null,
                        ord = if (allWeeks.isEmpty()) chapterOrder * 10 else 500 + chapterOrder,
                        title = ch.title,
                        examStatus = CourseTopicStatus.EXAMINABLE,
                        examWeight = 1.0,
                        sources = listOf(
                            CourseTopicLinkPlan(
                                bookId = ch.bookId,
                                tocNodeId = ch.tocNodeId,
                                startPage = ch.startPage,
                                endPage = ch.endPage,
                                role = CourseMaterialRole.CORE_TEXTBOOK,
                                scopeNote = ch.title,
                            ),
                        ),
                    )
                }
            } else if (topics.isEmpty()) {
                val textbooks = current.materials.filter {
                    (specByBook[it.bookId]?.role ?: it.role) == CourseMaterialRole.CORE_TEXTBOOK
                }.ifEmpty { current.materials }
                textbooks.forEachIndexed { idx, mat ->
                    val effRole = specByBook[mat.bookId]?.role ?: mat.role
                    topics += CourseTopicPlan(
                        weekLabel = "第 ${idx + 1} 章",
                        weekNum = null,
                        ord = (idx + 1) * 10,
                        title = mat.title,
                        examStatus = CourseTopicStatus.EXAMINABLE,
                        examWeight = 1.0,
                        sources = listOf(
                            CourseTopicLinkPlan(
                                bookId = mat.bookId,
                                tocNodeId = null,
                                startPage = 1,
                                endPage = mat.pageCount.coerceAtLeast(1),
                                role = effRole,
                                scopeNote = mat.title,
                            ),
                        ),
                    )
                }
            }
        }

        CourseStore.applyAlignmentRespectingLocks(
            db = db,
            courseId = courseId,
            plan = CourseAlignmentPlan(
                materialUpdates = resolvedMaterials,
                includeExamPoints = current.isExam,
                topics = topics,
            ),
        )
        return CourseStore.loadCourseDetail(db, courseId)
    }

    fun buildOfflineTopicPack(
        detail: CourseDetailSnapshot,
        topic: CourseTopicEntry,
        pagesByBook: Map<String, List<BookPage>>,
    ): CourseTopicPack {
        val orderedSources = topic.sources.sortedWith(
            compareBy<CourseTopicSourceLink>({ it.role.displayOrder }, { it.startPage }),
        )
        val citations = mutableListOf<CourseCitationRef>()
        val sourceTexts = mutableListOf<String>()
        for (src in orderedSources) {
            val pages = pagesByBook[src.bookId].orEmpty()
            val windowPages = pages.filter { it.number in src.startPage..src.endPage }
            sourceTexts += windowPages.joinToString("\n") { it.text }
            val firstPage = windowPages.firstOrNull { it.text.isNotBlank() } ?: continue
            val rawQuote = firstAnchorLine(firstPage.text)
            if (rawQuote.isBlank()) continue
            val tsLabel = CourseTranscriptParser.pageTimeRangeLabel(firstPage.text)?.substringBefore('–')
            val startIdx = firstPage.text.indexOf(rawQuote).coerceAtLeast(0)
            val candidate = CourseCitationRef(
                bookId = src.bookId,
                bookTitle = src.bookTitle,
                role = src.role,
                page = firstPage.number,
                startUtf16 = startIdx,
                endUtf16 = startIdx + rawQuote.length,
                timestampLabel = tsLabel,
                quote = rawQuote,
                verified = true,
            )
            CourseClientGates.verifySingleCitation(candidate, pages)?.let { citations += it }
        }
        val keyPoints = orderedSources.take(4).mapNotNull { src ->
            val srcCits = citations.filter { it.bookId == src.bookId }
            if (srcCits.isEmpty()) return@mapNotNull null
            CourseKeyPoint(
                title = "${src.role.labelZh} · ${src.bookTitle}",
                explanation = "定位到第 ${src.startPage}–${src.endPage} ${if (src.bookFormat == "pptx") "张" else "页"}，已按 L${src.authorityRank} 权威序接入本专题。",
                citations = srcCits,
            )
        }
        val tentativeNotice = if (detail.isExam) {
            buildString {
                append("优先参考教材，其次是课件、讲义")
                if (detail.examProfile.examDateTentative || detail.examProfile.examFormatTentative) {
                    append(" · 考试日期与题型标注为暂定")
                }
            }
        } else {
            "按当前材料顺序整理"
        }
        val technique = detail.pastPaperTechniques[topic.topicId]?.firstOrNull()
        val promptStem = if (technique != null) {
            when (technique) {
                ExamTechnique.CALCULATION -> "为「${topic.title}」设计一组简单数据，列出计算步骤，并解释结果。"
                ExamTechnique.COMPARISON -> "为「${topic.title}」构造两个条件不同的例子，比较结果并解释原因。"
                ExamTechnique.APPLICATION -> "在一个新情境中运用「${topic.title}」，说明适用条件。"
                ExamTechnique.EXPLANATION -> "用自己的话解释「${topic.title}」，再举例说明它如何起作用。"
                ExamTechnique.RECALL -> "写出「${topic.title}」的定义与一个可用来识别它的特征。"
            }
        } else if (detail.isExam) {
            "结合「${topic.title}」的教材和课件，用自己的话阐释其核心机制、适用前提，并对比课堂强调与教材拓展之间的关键边界（建议 300 词左右）。"
        } else {
            "结合「${topic.title}」的核心概念，用自己的话阐释主要思路与适用前提（建议 300 词左右）。"
        }
        val cleanStem = if (CourseClientGates.auditOverlap(promptStem, sourceTexts).passed) {
            promptStem
        } else {
            "请围绕「${topic.title}」的核心框架，分步骤写出关键结论与判别条件，避免直接照抄原文定义。"
        }
        val packHash = topic.packSourcesHash.orEmpty()
        return CourseTopicPack(
            topicId = topic.topicId,
            summary = if (detail.isExam) {
                "本专题共关联 ${orderedSources.size} 份课程材料，已按教材、课件、讲义的优先级排列。"
            } else {
                "本章共关联 ${orderedSources.size} 份材料，已按阅读顺序排好。"
            },
            authorityNotice = tentativeNotice,
            examDateTentative = detail.isExam && detail.examProfile.examDateTentative,
            examFormatTentative = detail.isExam && detail.examProfile.examFormatTentative,
            examFocus = if (!detail.isExam) {
                ""
            } else if (detail.examProfile.examFormat.isNotBlank()) {
                "${detail.examProfile.examFormat}${if (detail.examProfile.examFormatTentative) "（暂定）" else ""}"
            } else {
                "论述与概念辨析（暂定）"
            },
            keyPoints = keyPoints,
            discrepancies = CourseExamPoints.discrepancies(topic, pagesByBook),
            practicePrompt = CoursePracticePrompt(
                promptId = "${topic.topicId}-offline-essay",
                kind = "ESSAY",
                stem = cleanStem,
                wordTarget = 300,
                points = orderedSources.take(3).map { "紧扣 L${it.authorityRank} ${it.role.labelZh}：《${it.bookTitle}》" },
                citations = citations.take(2),
            ),
            citations = citations,
            generatedFromHash = packHash,
            serverBacked = false,
        )
    }

    private fun loadTextbookChapters(db: Connection, mat: CourseMaterialEntry): List<TextbookChapterRange> {
        val pages = CourseStore.loadPagesForBook(db, mat.bookId, maxPages = mat.pageCount.coerceAtLeast(1))
        if (pages.isEmpty()) return emptyList()
        val structure = BookStructureStore.loadStructure(db, mat.bookId, pages)
        val nodes = structure?.tocNodes ?: BookTocStore.loadTocNodes(db, mat.bookId)
        val bodyStart = BookBoundaries.bodyStart(pages, structure, CourseStore.loadCorrectedBodyPage(db, mat.bookId))
        val opening = BookBoundaries.openingNode(nodes)
        val bodyNodes = nodes.filter { node ->
            val title = node.title.trim()
            title.isNotBlank() &&
                node.kind != TocNodeKind.FRONT_MATTER &&
                node.kind != TocNodeKind.BACK_MATTER &&
                node.kind != TocNodeKind.PART &&
                (opening == null || node.ordinal >= opening.ordinal) &&
                node.endPage >= bodyStart.importPage
        }
        val hasExplicitChapters = bodyNodes.any { it.kind == TocNodeKind.CHAPTER }
        val chapterNodes = if (hasExplicitChapters) {
            bodyNodes.filter { it.kind == TocNodeKind.CHAPTER || it.id == opening?.id }
        } else {
            bodyNodes.filter { it.level <= 2 || it.id == opening?.id }
        }
        val parentById = nodes.associate { it.id to it.parentId }
        fun descendsFrom(node: BookTocNode, ancestorId: String): Boolean {
            var cur = node.parentId
            var guard = 0
            while (cur != null && guard++ < 8) {
                if (cur == ancestorId) return true
                cur = parentById[cur]
            }
            return false
        }
        val fromToc = chapterNodes.map { node ->
            val title = node.title.trim()
            val start = maxOf(node.startPage, bodyStart.importPage)
            val end = node.endPage.coerceAtLeast(start)
            val childSections = bodyNodes.filter { other ->
                other.id != node.id &&
                    other.kind in setOf(TocNodeKind.SECTION, TocNodeKind.SUBSECTION) &&
                    (descendsFrom(other, node.id) || (other.startPage in start..end && other.level > node.level))
            }.map { it.title.trim() }.distinct()
            val chNum = node.numberLabel?.trim()?.toIntOrNull()
                ?: chapterNumberOf(title)
                ?: leadingChapterNum(title)
            val secKeywords = childSections.flatMap { keywordsOf(it) }.toSet()
            TextbookChapterRange(
                bookId = mat.bookId,
                tocNodeId = node.id,
                title = title,
                startPage = start,
                endPage = end,
                keywords = keywordsOf(title),
                chapterNum = chNum,
                sectionTitles = childSections,
                sectionKeywords = secKeywords,
            )
        }
        if (fromToc.isNotEmpty()) return fromToc

        val starts = pages.asSequence()
            .filter { it.number >= bodyStart.importPage }
            .take(800)
            .filter { structure == null || structure.pageRole(it.number) == BookPageRole.BODY }
            .filter { !CourseExamPoints.isTocOrIndexPage(it.text) }
            .mapNotNull { page ->
                page.text.lineSequence().map(String::trim).firstOrNull { line ->
                    line.length in 4..80 && (line.startsWith("# ") || chapterHeadingRegex.containsMatchIn(line))
                }?.let { page.number to it.removePrefix("# ").trim() }
            }.toList()
            .ifEmpty { listOf(bodyStart.importPage to mat.title) }
        val pagesByNum = pages.associateBy { it.number }
        return starts.mapIndexed { i, (startPage, chTitle) ->
            val endPage = (starts.getOrNull(i + 1)?.first?.minus(1) ?: mat.pageCount).coerceAtLeast(startPage)
            val sectionTitles = (startPage..endPage).flatMap { pNum ->
                pagesByNum[pNum]?.text?.lineSequence().orEmpty()
                    .map(String::trim)
                    .filter { it.length in 4..90 && sectionHeadingLineRegex.matches(it) }
                    .map { it.trimStart('#', ' ') }
                    .toList()
            }.distinct()
            TextbookChapterRange(
                bookId = mat.bookId,
                tocNodeId = null,
                title = chTitle,
                startPage = startPage,
                endPage = endPage,
                keywords = keywordsOf(chTitle),
                chapterNum = chapterNumberOf(chTitle) ?: leadingChapterNum(chTitle),
                sectionTitles = sectionTitles,
                sectionKeywords = sectionTitles.flatMap { keywordsOf(it) }.toSet(),
            )
        }
    }

    private fun cleanTopicTitle(rawTitle: String, weekNum: Int): String {
        val stripped = rawTitle
            .replace(Regex("""(?i)\b(?:week|wk|w)[\s_\-]*$weekNum\b"""), "")
            .replace(Regex("""(?i)\.(?:pdf|pptx|docx|md|txt|srt|vtt)$"""), "")
            .replace(Regex("""[_]+"""), " ")
            .trim(' ', '-', ':', '—', '·')
        return stripped.ifBlank { "第 $weekNum 周专题" }
    }

    private fun chapterNumberOf(text: String): Int? {
        val m = chapterTokenRegex.find(text) ?: return null
        return (m.groupValues[1].toIntOrNull() ?: m.groupValues[2].toIntOrNull())?.takeIf { it in 1..60 }
    }

    private fun leadingChapterNum(text: String): Int? =
        leadingChapterNumRegex.find(text.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..60 }

    private fun keywordsOf(text: String): Set<String> = buildSet {
        Regex("""[a-z0-9]{3,}|[\u4e00-\u9fff]{2,6}""").findAll(text.lowercase()).forEach { m ->
            val tok = m.value
            if (tok !in stopWords) add(tok)
        }
    }

    private fun firstAnchorLine(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.length >= 12 }
        return (lines.firstOrNull { !it.startsWith("课堂转写 [") } ?: lines.firstOrNull() ?: text.trim())
            .take(90)
    }

    fun buildAlignRequest(db: Connection, detail: CourseDetailSnapshot): JsonObject {
        val materialsJson = buildJsonArray {
            for (mat in detail.materials) {
                val pages = CourseStore.loadPagesForBook(db, mat.bookId, maxPages = 4)
                val preview = CourseClientGates.sanitizeUserNote(pages.joinToString("\n") { it.text }).cleanText
                add(buildJsonObject {
                    put("bookId", mat.bookId)
                    put("title", mat.title)
                    put("format", mat.format)
                    put("role", mat.role.wire)
                    mat.weekNum?.let { put("weekNum", it) }
                    put("userLocked", mat.userLocked)
                    put("pageCount", mat.pageCount)
                    put("previewText", preview.take(1200))
                })
            }
        }
        val chaptersJson = buildJsonArray {
            for (mat in detail.materials.filter { it.role == CourseMaterialRole.CORE_TEXTBOOK }) {
                for (ch in loadTextbookChapters(db, mat)) {
                    add(buildJsonObject {
                        put("bookId", ch.bookId)
                        put("title", ch.title)
                        put("startPage", ch.startPage)
                        put("endPage", ch.endPage)
                        ch.tocNodeId?.let { put("tocNodeId", it) }
                    })
                }
            }
        }
        return buildJsonObject {
            put("courseId", detail.courseId)
            put("courseTitle", detail.title)
            put("materials", materialsJson)
            put("textbookChapters", chaptersJson)
        }
    }

    fun parseAlignResponse(body: JsonObject, existing: CourseDetailSnapshot): CourseAlignmentPlan {
        val existingByBook = existing.materials.associateBy { it.bookId }
        val profileObj = body["examProfile"] as? JsonObject
        val profile = profileObj?.let { p ->
            existing.examProfile.copy(
                examDate = (p["examDate"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { existing.examProfile.examDate },
                examDateTentative = (p["examDateTentative"] as? JsonPrimitive)?.booleanOrNull ?: existing.examProfile.examDateTentative,
                examFormat = (p["examFormat"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { existing.examProfile.examFormat },
                examFormatTentative = (p["examFormatTentative"] as? JsonPrimitive)?.booleanOrNull ?: existing.examProfile.examFormatTentative,
                weightingNote = (p["weightingNote"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { existing.examProfile.weightingNote },
            )
        }
        val updates = ((body["materialUpdates"] as? JsonArray) ?: buildJsonArray {}).mapIndexedNotNull { idx, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val bookId = (o["bookId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val prev = existingByBook[bookId] ?: return@mapIndexedNotNull null
            if (prev.userLocked) {
                CourseMaterialLinkSpec(bookId, prev.role, prev.weekNum, true, idx)
            } else {
                val role = CourseMaterialRole.fromWire((o["role"] as? JsonPrimitive)?.contentOrNull ?: prev.role.wire)
                val week = (o["weekNum"] as? JsonPrimitive)?.intOrNull ?: prev.weekNum
                CourseMaterialLinkSpec(bookId, role, week, false, idx)
            }
        }
        val topics = ((body["topics"] as? JsonArray) ?: buildJsonArray {}).mapIndexedNotNull { idx, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val title = (o["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (title.isBlank()) return@mapIndexedNotNull null
            val weekLabel = (o["weekLabel"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { "第 ${idx + 1} 周" }
            val weekNum = (o["weekNum"] as? JsonPrimitive)?.intOrNull ?: CourseStore.extractWeekNum(weekLabel)
            val status = CourseTopicStatus.fromWire((o["examStatus"] as? JsonPrimitive)?.contentOrNull)
            val weight = (o["examWeight"] as? JsonPrimitive)?.doubleOrNull ?: 1.0
            val links = ((o["sources"] as? JsonArray) ?: buildJsonArray {}).mapNotNull { sEl ->
                val s = sEl as? JsonObject ?: return@mapNotNull null
                val bookId = (s["bookId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (bookId.isBlank()) return@mapNotNull null
                val mat = existingByBook[bookId] ?: return@mapNotNull null
                CourseTopicLinkPlan(
                    bookId = bookId,
                    tocNodeId = (s["tocNodeId"] as? JsonPrimitive)?.contentOrNull,
                    startPage = ((s["startPage"] as? JsonPrimitive)?.intOrNull ?: 1).coerceAtLeast(1),
                    endPage = ((s["endPage"] as? JsonPrimitive)?.intOrNull ?: mat.pageCount).coerceAtLeast(1),
                    role = mat.role,
                    scopeNote = (s["scopeNote"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                )
            }
            CourseTopicPlan(
                topicId = (o["topicId"] as? JsonPrimitive)?.contentOrNull,
                weekLabel = weekLabel,
                weekNum = weekNum,
                ord = (o["ord"] as? JsonPrimitive)?.intOrNull ?: (idx + 1) * 10,
                title = title,
                examStatus = status,
                examWeight = weight,
                sources = links,
            )
        }
        val sanitized = (body["spoilerSanitizedCount"] as? JsonPrimitive)?.intOrNull ?: 0
        return CourseAlignmentPlan(
            examProfile = profile,
            materialUpdates = updates,
            topics = topics,
            spoilerSanitizedCount = sanitized,
            includeExamPoints = existing.isExam,
        )
    }

    fun buildTopicPackRequest(
        detail: CourseDetailSnapshot,
        topic: CourseTopicEntry,
        pagesByBook: Map<String, List<BookPage>>,
        chapterStartPagesByBook: Map<String, Set<Int>> = emptyMap(),
    ): JsonObject {
        val derivedStartsByBook = detail.allTopics
            .asSequence()
            .filter { it.weekLabel != CourseExamPoints.LABEL }
            .flatMap { it.sources.asSequence() }
            .filter { it.role == CourseMaterialRole.CORE_TEXTBOOK }
            .groupBy({ it.bookId }, { it.startPage })
            .mapValues { (_, starts) -> starts.toSet() }
        val cleanTextbookByBook = HashMap<String, Map<Int, String>>()
        val candidateBlocks = mutableListOf<Triple<CourseTopicSourceLink, Int, String>>()
        for (src in topic.sources) {
            val allPages = pagesByBook[src.bookId].orEmpty()
            val windowPages = allPages.filter { it.number in src.startPage..src.endPage }
            if (src.role == CourseMaterialRole.CORE_TEXTBOOK) {
                val cleaned = cleanTextbookByBook.getOrPut(src.bookId) {
                    val chapterStarts = chapterStartPagesByBook[src.bookId].orEmpty() +
                        derivedStartsByBook[src.bookId].orEmpty()
                    CourseExamPoints.cleanTextbookPagesByNumber(allPages, chapterStarts)
                }
                for (p in windowPages.take(20)) {
                    val text = cleaned[p.number].orEmpty()
                    if (text.isBlank()) continue
                    candidateBlocks += Triple(src, p.number, text)
                }
            } else {
                for (p in windowPages.take(20)) {
                    val raw = p.text
                    val text = if (src.role == CourseMaterialRole.USER_NOTES) {
                        CourseClientGates.sanitizeUserNote(raw).cleanText
                    } else {
                        raw
                    }
                    if (text.isBlank() || CourseExamPoints.isTocOrIndexPage(text)) continue
                    candidateBlocks += Triple(src, p.number, text)
                }
            }
        }

        // For exam points matching dozens of pages, prioritize pages where the topic is defined or titled,
        // and keep the prompt focused instead of sending hundreds of loosely related pages.
        val selected = if (topic.weekLabel == CourseExamPoints.LABEL && candidateBlocks.size > 24) {
            val normTitle = CourseExamPoints.normalize(topic.title)
            val pattern = if (normTitle.length >= 2) CourseExamPoints.termPattern(normTitle) else null
            fun score(text: String): Int {
                val norm = CourseExamPoints.normalize(text)
                val mentions = if (pattern != null) pattern.findAll(norm).count().coerceAtMost(10) else 0
                val hasHeadingOrDef = text.lineSequence().map(String::trim).any { line ->
                    val n = CourseExamPoints.normalize(line)
                    n.startsWith("$normTitle:") || n.startsWith("$normTitle：") || n.startsWith("# $normTitle") || n == normTitle
                }
                return (if (hasHeadingOrDef) 100 else 0) + mentions
            }
            val textbookTop = candidateBlocks
                .filter { it.first.role == CourseMaterialRole.CORE_TEXTBOOK }
                .sortedByDescending { score(it.third) }
                .take(20)
                .sortedWith(compareBy({ it.first.bookId }, { it.second }))
            val otherTop = candidateBlocks
                .filter { it.first.role != CourseMaterialRole.CORE_TEXTBOOK }
                .sortedByDescending { score(it.third) }
                .take(8)
                .sortedWith(compareBy({ it.first.authorityRank }, { it.first.bookId }, { it.second }))
            textbookTop + otherTop
        } else {
            candidateBlocks
        }

        val blocks = buildJsonArray {
            for ((src, pageNum, text) in selected) {
                add(buildJsonObject {
                    put("blockId", "${src.bookId}:p$pageNum:0")
                    put("bookId", src.bookId)
                    put("bookTitle", src.bookTitle)
                    put("role", src.role.wire)
                    put("authorityRank", src.authorityRank)
                    put("page", pageNum)
                    put("startUtf16", 0)
                    put("text", text.take(4500))
                })
            }
        }
        return buildJsonObject {
            put("courseId", detail.courseId)
            put("topicId", topic.topicId)
            put("topicTitle", topic.title)
            put("sourcesHash", topic.packSourcesHash.orEmpty())
            put("examDateTentative", detail.examProfile.examDateTentative)
            put("examFormatTentative", detail.examProfile.examFormatTentative)
            put("examFormat", detail.examProfile.examFormat)
            put("examTechniques", buildJsonArray { detail.pastPaperTechniques[topic.topicId].orEmpty().forEach { add(JsonPrimitive(it.name)) } })
            put("blocks", blocks)
        }
    }

    fun decodeVerifiedTopicPack(
        body: JsonObject,
        topic: CourseTopicEntry,
        pagesByBook: Map<String, List<BookPage>>,
    ): CourseTopicPack? {
        val decoded = CourseJson.decodeTopicPack(body.toString()) ?: return null
        val verifiedTop = CourseClientGates.verifyCitations(decoded.citations, pagesByBook)
        val verifiedPoints = decoded.keyPoints.map { kp ->
            kp.copy(citations = CourseClientGates.verifyCitations(kp.citations, pagesByBook))
        }
        val verifiedDiffs = decoded.discrepancies.map { d ->
            d.copy(citations = CourseClientGates.verifyCitations(d.citations, pagesByBook))
        }
        val verifiedPrompt = decoded.practicePrompt?.let { p ->
            p.copy(citations = CourseClientGates.verifyCitations(p.citations, pagesByBook))
        }
        return decoded.copy(
            topicId = topic.topicId,
            keyPoints = verifiedPoints,
            discrepancies = verifiedDiffs,
            practicePrompt = verifiedPrompt,
            citations = verifiedTop,
            serverBacked = true,
        )
    }
}
