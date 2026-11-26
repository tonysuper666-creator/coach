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
        """(?im)^(?:\s*[-*#•]*\s*)?(?:week|wk)\s*(\d{1,2})\s*[:：\-—.]\s*(.{2,120})$|^第\s*(\d{1,2})\s*周\s*[:：\-—.]\s*(.{2,120})$""",
    )
    private val chapterTokenRegex = Regex("""(?i)\b(?:ch(?:apter)?\.?|第)\s*(\d{1,2})\b""")
    private val chapterHeadingRegex = Regex("""(?i)^chapter\s+\d+|^第\s*[0-9一二三四五六七八九十]+\s*章""")
    private val stopWords = setOf(
        "week", "lecture", "slides", "tutorial", "solution", "solutions", "key", "chapter",
        "textbook", "notes", "summary", "review", "course", "handbook", "syllabus", "part",
        "the", "and", "for", "with", "into", "from", "that", "this", "introduction", "overview",
    )

    data class TextbookChapterRange(
        val bookId: String,
        val tocNodeId: String?,
        val title: String,
        val startPage: Int,
        val endPage: Int,
        val keywords: Set<String>,
    )

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
                val inferredWeek = mat.weekNum ?: CourseMaterialRole.inferWeekNumber(mat.title, mat.sourceFilename, preview)
                CourseMaterialLinkSpec(mat.bookId, inferredRole, inferredWeek, false, idx)
            }
        }
        val specByBook = resolvedMaterials.associateBy { it.bookId }

        // Parse syllabus week outlines when available
        val syllabusTitlesByWeek = LinkedHashMap<Int, String>()
        val excludedHints = mutableSetOf<String>()
        for (mat in current.materials) {
            val effRole = specByBook[mat.bookId]?.role ?: mat.role
            if (effRole != CourseMaterialRole.SYLLABUS) continue
            val pages = CourseStore.loadPagesForBook(db, mat.bookId, maxPages = 30)
            for (line in pages.flatMap { it.text.lines() }) {
                val lower = line.lowercase()
                if ("not examinable" in lower || "excluded" in lower || "不考" in line || "跳过" in line) {
                    excludedHints += keywordsOf(line)
                }
                val m = syllabusWeekLineRegex.find(line) ?: continue
                val wk = m.groupValues[1].toIntOrNull() ?: m.groupValues[3].toIntOrNull() ?: continue
                val title = (m.groupValues[2].ifBlank { m.groupValues[4] }).trim().take(90)
                if (wk in 1..20 && title.isNotEmpty() && wk !in syllabusTitlesByWeek) {
                    syllabusTitlesByWeek[wk] = title
                }
            }
        }

        val textbookChapters = current.materials
            .filter { (specByBook[it.bookId]?.role ?: it.role) == CourseMaterialRole.CORE_TEXTBOOK }
            .flatMap { loadTextbookChapters(db, it) }
        val firstChapterByBook = textbookChapters.distinctBy { it.bookId }.associateBy { it.bookId }

        val coveredChapterTitles = mutableSetOf<String>()
        val weeksWithMaterials = resolvedMaterials.mapNotNull { it.weekNum }.toSortedSet()
        val allWeeks = (weeksWithMaterials + syllabusTitlesByWeek.keys).toSortedSet()

        val topics = mutableListOf<CourseTopicPlan>()
        for (wk in allWeeks) {
            val matsForWeek = current.materials.filter { specByBook[it.bookId]?.weekNum == wk }
                .sortedBy { (specByBook[it.bookId]?.role ?: it.role).displayOrder }
            val hasPrimaryTeachingMaterial = matsForWeek.any {
                val r = specByBook[it.bookId]?.role ?: it.role
                r in setOf(
                    CourseMaterialRole.LECTURE_SLIDES,
                    CourseMaterialRole.TUTORIAL,
                    CourseMaterialRole.SOLUTION_KEY,
                    CourseMaterialRole.TRANSCRIPT,
                    CourseMaterialRole.REQUIRED_READING,
                    CourseMaterialRole.USER_NOTES,
                )
            }
            val weekTitle = syllabusTitlesByWeek[wk]
                ?: matsForWeek.firstOrNull { (specByBook[it.bookId]?.role ?: it.role) == CourseMaterialRole.LECTURE_SLIDES }
                    ?.let { cleanTopicTitle(it.title, wk) }
                ?: matsForWeek.firstOrNull()?.let { cleanTopicTitle(it.title, wk) }
                ?: "第 $wk 周专题"

            val weekKeywords = buildSet {
                addAll(keywordsOf(weekTitle))
                for (m in matsForWeek) {
                    addAll(keywordsOf(m.title))
                    addAll(keywordsOf(previewByBook[m.bookId].orEmpty().take(500)))
                }
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
                val overlaps = ch.keywords.count { it in weekKeywords }
                val sameChapterNum = chapterNumberOf(weekTitle) != null && chapterNumberOf(weekTitle) == chapterNumberOf(ch.title)
                if ((overlaps >= 2 || sameChapterNum) && ch.keywords.none { it in excludedHints && overlaps < 2 }) {
                    coveredChapterTitles += ch.title
                    links += CourseTopicLinkPlan(
                        bookId = ch.bookId,
                        tocNodeId = ch.tocNodeId,
                        startPage = ch.startPage,
                        endPage = ch.endPage,
                        role = CourseMaterialRole.CORE_TEXTBOOK,
                        scopeNote = ch.title,
                    )
                }
            }
            val status = if (hasPrimaryTeachingMaterial || matsForWeek.isNotEmpty()) {
                CourseTopicStatus.EXAMINABLE
            } else {
                CourseTopicStatus.PENDING_MATERIALS
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
            coveredChapterTitles.isNotEmpty()
        if (hasScopeNarrowing) {
            var excludedOrd = 1000
            for (ch in textbookChapters) {
                if (ch.title in coveredChapterTitles) continue
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
            val uncoveredChapters = textbookChapters.filter { it.title !in coveredChapterTitles }
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
        val pages = CourseStore.loadPagesForBook(db, mat.bookId, maxPages = mat.pageCount)
        if (pages.isEmpty()) return emptyList()
        val structure = BookStructureStore.loadStructure(db, mat.bookId, pages)
        val nodes = structure?.tocNodes ?: BookTocStore.loadTocNodes(db, mat.bookId)
        val bodyStart = BookBoundaries.bodyStart(pages, structure, CourseStore.loadCorrectedBodyPage(db, mat.bookId))
        val opening = BookBoundaries.openingNode(nodes)
        val fromToc = nodes.mapNotNull { node ->
            val title = node.title.trim()
            if (title.isBlank() || node.kind == TocNodeKind.FRONT_MATTER || node.kind == TocNodeKind.PART ||
                (node.level > 2 && node.id != opening?.id) ||
                (opening != null && node.ordinal < opening.ordinal) ||
                node.endPage < bodyStart.importPage
            ) return@mapNotNull null
            val start = maxOf(node.startPage, bodyStart.importPage)
            TextbookChapterRange(mat.bookId, node.id, title, start, node.endPage.coerceAtLeast(start), keywordsOf(title))
        }
        if (fromToc.isNotEmpty()) return fromToc

        val starts = pages.asSequence()
            .filter { it.number >= bodyStart.importPage }
            .take(800)
            .filter { structure == null || structure.pageRole(it.number) == BookPageRole.BODY }
            .mapNotNull { page ->
                page.text.lineSequence().map(String::trim).firstOrNull { line ->
                    line.length in 4..80 && (line.startsWith("# ") || chapterHeadingRegex.containsMatchIn(line))
                }?.let { page.number to it.removePrefix("# ").trim() }
            }.toList()
            .ifEmpty { listOf(bodyStart.importPage to mat.title) }
        return starts.mapIndexed { i, (startPage, chTitle) ->
            val endPage = (starts.getOrNull(i + 1)?.first?.minus(1) ?: mat.pageCount).coerceAtLeast(startPage)
            TextbookChapterRange(
                bookId = mat.bookId,
                tocNodeId = null,
                title = chTitle,
                startPage = startPage,
                endPage = endPage,
                keywords = keywordsOf(chTitle),
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

    private fun chapterNumberOf(text: String): Int? =
        chapterTokenRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

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
    ): JsonObject {
        val blocks = buildJsonArray {
            for (src in topic.sources) {
                val pages = pagesByBook[src.bookId].orEmpty()
                    .filter { it.number in src.startPage..src.endPage }
                for (p in pages.take(20)) {
                    val raw = p.text
                    val text = if (src.role == CourseMaterialRole.USER_NOTES) {
                        CourseClientGates.sanitizeUserNote(raw).cleanText
                    } else {
                        raw
                    }
                    if (text.isBlank()) continue
                    add(buildJsonObject {
                        put("blockId", "${src.bookId}:p${p.number}:0")
                        put("bookId", src.bookId)
                        put("bookTitle", src.bookTitle)
                        put("role", src.role.wire)
                        put("authorityRank", src.authorityRank)
                        put("page", p.number)
                        put("startUtf16", 0)
                        put("text", text.take(4500))
                    })
                }
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
