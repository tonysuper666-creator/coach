package com.coach.desktop.books

import com.coach.domain.fsrs.Fsrs5
import com.coach.domain.fsrs.FsrsState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * 2.0-AI-4 (Stage 6) deterministic (`$0`, `< 2ms`) exam countdown sprint planner.
 *
 * Phases strictly follow `proposal.md` §6.2:
 * - `D > 10` (or no exam date): `FOUNDATION` — fill unseen & high-weight low-mastery topics (`FSRS cap = 20`).
 * - `10 >= D > 3`: `SPRINT_CLEAR` — clear unresolved misconception variants & cross-material discrepancies (`FSRS cap = 30`).
 * - `D <= 3`: `FINAL_MOCK` — schedule 1 full-length timed mock + targeted review of missed original pages (`FSRS cap = 30`).
 *
 * Absence or jumping ahead dynamically rebalances remaining days on next open with zero red overdue alerts.
 */
internal enum class CountdownPhase(
    val labelZh: String,
    val fsrsDailyCap: Int,
) {
    FOUNDATION("补齐高权重薄弱专题", 20),
    SPRINT_CLEAR("清零错因与材料分歧", 30),
    FINAL_MOCK("全真限时模考与失分回看", 30),
}

internal data class MockTopicLossEvidence(
    val topicId: String,
    val bookId: String,
    val missedPages: List<Int> = emptyList(),
    val errorClass: String? = null,
    val recordedAt: Long = 0L,
)

internal data class TopicCountdownGap(
    val topicId: String,
    val weekLabel: String,
    val title: String,
    val examWeight: Double,
    val pMastery: Double,
    val projectedRetrievability: Double,
    val gapScore: Double,
    val priorityScore: Double,
    val unresolvedErrorCount: Int = 0,
    val dominantErrorTags: List<String> = emptyList(),
    val discrepancyCount: Int = 0,
    val hasRecentMockLoss: Boolean = false,
    val missedMockPages: List<Pair<String, Int>> = emptyList(),
    val estimatedMinutes: Int = 35,
    val scheduledDayOffset: Int? = null,
    val cardMetaLine: String = "",
    val hasPracticeEvidence: Boolean = false,
)

internal data class DailySprintSlot(
    val dayOffset: Int,
    val dateLabel: String,
    val phase: CountdownPhase,
    val totalMinutes: Int,
    val budgetMinutes: Int,
    val includeTimedMock: Boolean,
    val topicIds: List<String>,
)

internal data class ExamCountdownPlan(
    val courseId: String,
    val examDate: String? = null,
    val daysRemaining: Int? = null,
    val phase: CountdownPhase,
    val fsrsDailyCap: Int,
    val dailyBudgetMinutes: Int = ExamCountdownPlanner.DEFAULT_DAILY_BUDGET_MINUTES,
    val overdueAlertCount: Int = 0,
    val rankedTopics: List<TopicCountdownGap>,
    val dailySlots: List<DailySprintSlot>,
    val todayFocusTopicId: String?,
    val headerSummaryLine: String,
    val primaryCtaLabel: String,
)

internal object ExamCountdownPlanner {
    const val DEFAULT_DAILY_BUDGET_MINUTES = 90
    const val NORMAL_FSRS_CAP = 20
    const val PRE_EXAM_FSRS_CAP = 30
    const val PRE_EXAM_WINDOW_DAYS = 10L
    private val isoDateRegex = Regex("""(\d{4})[-/](\d{1,2})[-/](\d{1,2})""")
    private val json = Json { ignoreUnknownKeys = true }

    fun parseExamDate(raw: String?): LocalDate? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = isoDateRegex.find(text) ?: return null
        val y = match.groupValues[1].toIntOrNull() ?: return null
        val m = match.groupValues[2].toIntOrNull() ?: return null
        val d = match.groupValues[3].toIntOrNull() ?: return null
        return runCatching { LocalDate.of(y, m, d) }.getOrNull()
    }

    fun phaseFor(daysRemaining: Int?): CountdownPhase = when {
        daysRemaining == null -> CountdownPhase.FOUNDATION
        daysRemaining <= 3 -> CountdownPhase.FINAL_MOCK
        daysRemaining <= 10 -> CountdownPhase.SPRINT_CLEAR
        else -> CountdownPhase.FOUNDATION
    }

    fun compute(
        courseId: String,
        examDateRaw: String?,
        topics: List<CourseTopicEntry>,
        concepts: List<ConceptMasteryRecord>,
        fsrsByBookLesson: Map<Pair<String, String>, FsrsState> = emptyMap(),
        recentMockLosses: List<MockTopicLossEvidence> = emptyList(),
        today: LocalDate = LocalDate.now(),
        nowMs: Long = System.currentTimeMillis(),
        dailyBudgetMinutes: Int = DEFAULT_DAILY_BUDGET_MINUTES,
    ): ExamCountdownPlan {
        val safeBudget = dailyBudgetMinutes.coerceIn(45, 240)
        val parsedExamDate = parseExamDate(examDateRaw)
        val daysRemaining = parsedExamDate?.let { exam ->
            ChronoUnit.DAYS.between(today, exam).toInt().coerceAtLeast(1)
        }
        val phase = phaseFor(daysRemaining)
        val fsrsCap = phase.fsrsDailyCap
        val examinable = topics.filter { it.examStatus == CourseTopicStatus.EXAMINABLE }
        if (examinable.isEmpty()) {
            val header = buildHeaderSummary(daysRemaining, phase, fsrsCap, emptyList())
            return ExamCountdownPlan(
                courseId = courseId,
                examDate = parsedExamDate?.toString(),
                daysRemaining = daysRemaining,
                phase = phase,
                fsrsDailyCap = fsrsCap,
                dailyBudgetMinutes = safeBudget,
                overdueAlertCount = 0,
                rankedTopics = emptyList(),
                dailySlots = emptyList(),
                todayFocusTopicId = null,
                headerSummaryLine = header,
                primaryCtaLabel = "导入大纲、讲义或转写开始备考",
            )
        }

        val todayEpochDay = today.toEpochDay()
        val examEpochDay = todayEpochDay + (daysRemaining ?: 0)
        val horizonDays = (daysRemaining ?: 7).coerceIn(1, 30)

        val rawGaps = examinable.map { topic ->
            val weight = (topic.examWeight ?: 1.0).coerceAtLeast(0.1)
            val topicBookIds = topic.sources.map { it.bookId }.toSet()
            val topicConcepts = matchConceptsForTopic(topic, topicBookIds, concepts)

            val pMastery = if (topicConcepts.isEmpty()) {
                MasteryEngine.DEFAULT_P_MASTERY
            } else {
                topicConcepts.sumOf { it.pMastery } / topicConcepts.size.toDouble()
            }.coerceIn(0.05, 0.98)

            val matchingFsrs = topicBookIds.flatMap { bid ->
                fsrsByBookLesson.entries
                    .filter { (key, _) ->
                        key.first == bid && (key.second == topic.topicId || topicConcepts.any { it.tocNodeId == key.second })
                    }
                    .map { it.value }
            }
            val projectedR = when {
                matchingFsrs.isNotEmpty() -> {
                    val sumR = matchingFsrs.sumOf { st -> Fsrs5.retrievability(st, examEpochDay) }
                    (sumR / matchingFsrs.size.toDouble()).coerceIn(0.05, 1.0)
                }
                topicConcepts.isNotEmpty() -> {
                    val extraDays = (daysRemaining ?: 0).toDouble().coerceAtLeast(0.0)
                    val sumR = topicConcepts.sumOf { rec ->
                        val elapsed = MasteryEngine.elapsedDays(rec.updatedAt, nowMs) + extraDays
                        MasteryEngine.retrievability(rec.fsrsStability, elapsed)
                    }
                    (sumR / topicConcepts.size.toDouble()).coerceIn(0.05, 1.0)
                }
                else -> 1.0
            }

            // Core mastery-gap formula: Gap_i = w_i * (1 - p_i * R_i(D))
            val gapScore = weight * (1.0 - pMastery * projectedR)

            val errorTags = topicConcepts
                .mapNotNull { rec ->
                    rec.dominantErrorTag?.takeIf { tag ->
                        tag.isNotBlank() && !tag.equals("NONE", ignoreCase = true)
                    }
                }
                .distinct()
            val mockForTopic = recentMockLosses.filter { loss ->
                loss.topicId == topic.topicId || (loss.bookId in topicBookIds && loss.topicId.isBlank())
            }
            val missedPages = mockForTopic.flatMap { loss ->
                loss.missedPages.distinct().map { pg -> loss.bookId to pg }
            }.distinct()
            val hasMockLoss = mockForTopic.isNotEmpty()
            val unresolvedErrors = (errorTags.size + if (hasMockLoss) 1 else 0).coerceAtLeast(errorTags.size)
            val discrepancyCount = topic.pack?.discrepancies?.size ?: 0
            val unseen = topicConcepts.none { it.hasActiveTest }

            val priorityScore = when (phase) {
                CountdownPhase.FOUNDATION -> {
                    gapScore + (if (unseen) 0.12 * weight else 0.0) + 0.10 * minOf(2, unresolvedErrors)
                }
                CountdownPhase.SPRINT_CLEAR -> {
                    gapScore + 0.35 * minOf(3, unresolvedErrors) + 0.20 * minOf(2, discrepancyCount)
                }
                CountdownPhase.FINAL_MOCK -> {
                    gapScore + (if (hasMockLoss) 0.65 else 0.0) + 0.35 * minOf(3, unresolvedErrors)
                }
            }

            val baseEst = when {
                hasMockLoss -> 30
                unresolvedErrors > 0 -> 35
                pMastery < 0.45 -> 45
                else -> 25
            }
            val clampedEst = baseEst.coerceIn(20, minOf(55, safeBudget))

            TopicCountdownGap(
                topicId = topic.topicId,
                weekLabel = topic.weekLabel,
                title = topic.title,
                examWeight = weight,
                pMastery = pMastery,
                projectedRetrievability = projectedR,
                gapScore = gapScore,
                priorityScore = priorityScore,
                unresolvedErrorCount = unresolvedErrors,
                dominantErrorTags = errorTags,
                discrepancyCount = discrepancyCount,
                hasRecentMockLoss = hasMockLoss,
                missedMockPages = missedPages,
                estimatedMinutes = clampedEst,
                hasPracticeEvidence = !unseen || matchingFsrs.isNotEmpty(),
            ) to topic.ord
        }

        val sortedGaps = rawGaps
            .sortedWith(
                compareByDescending<Pair<TopicCountdownGap, Int>> { it.first.priorityScore }
                    .thenByDescending { it.first.gapScore }
                    .thenByDescending { it.first.examWeight }
                    .thenBy { it.second },
            )
            .map { it.first }

        // Pack into daily slots strictly respecting dailyBudgetMinutes (zero budget overflow).
        val scheduledDayByTopic = LinkedHashMap<String, Int>()
        val dailySlots = MutableList(horizonDays) { dayOffset ->
            val dLabel = today.plusDays(dayOffset.toLong()).toString()
            DailySprintSlot(
                dayOffset = dayOffset,
                dateLabel = dLabel,
                phase = phase,
                totalMinutes = 0,
                budgetMinutes = safeBudget,
                includeTimedMock = phase == CountdownPhase.FINAL_MOCK && dayOffset == 0,
                topicIds = emptyList(),
            )
        }

        if (phase == CountdownPhase.FINAL_MOCK) {
            // D <= 3: Day 0 schedules 1 full-length timed mock + targeted review of missed mock/error pages only.
            val day0Targets = sortedGaps.filter { it.hasRecentMockLoss || it.unresolvedErrorCount > 0 }
                .ifEmpty { sortedGaps.take(2) }
            var used = 0
            val day0Ids = mutableListOf<String>()
            for (g in day0Targets) {
                val reviewMin = minOf(g.estimatedMinutes, 30)
                if (used + reviewMin <= safeBudget) {
                    used += reviewMin
                    day0Ids += g.topicId
                    scheduledDayByTopic[g.topicId] = 0
                }
            }
            dailySlots[0] = dailySlots[0].copy(
                totalMinutes = used,
                includeTimedMock = true,
                topicIds = day0Ids,
            )
            // Distribute remaining topics onto subsequent days without exceeding budget.
            var dayCursor = if (horizonDays > 1) 1 else 0
            for (g in sortedGaps) {
                if (g.topicId in scheduledDayByTopic) continue
                var placed = false
                for (attempt in dayCursor until horizonDays) {
                    val slot = dailySlots[attempt]
                    if (slot.totalMinutes + g.estimatedMinutes <= slot.budgetMinutes) {
                        dailySlots[attempt] = slot.copy(
                            totalMinutes = slot.totalMinutes + g.estimatedMinutes,
                            topicIds = slot.topicIds + g.topicId,
                        )
                        scheduledDayByTopic[g.topicId] = attempt
                        placed = true
                        break
                    }
                }
                if (!placed && horizonDays > 1) {
                    dayCursor = 1
                }
            }
        } else {
            val pendingQueue = sortedGaps.toMutableList()
            for (dayOffset in 0 until horizonDays) {
                var used = 0
                val dayIds = mutableListOf<String>()
                val it = pendingQueue.iterator()
                while (it.hasNext()) {
                    val candidate = it.next()
                    if (used + candidate.estimatedMinutes <= safeBudget) {
                        used += candidate.estimatedMinutes
                        dayIds += candidate.topicId
                        scheduledDayByTopic[candidate.topicId] = dayOffset
                        it.remove()
                    }
                }
                dailySlots[dayOffset] = dailySlots[dayOffset].copy(
                    totalMinutes = used,
                    topicIds = dayIds,
                )
                if (pendingQueue.isEmpty()) break
            }
        }

        val annotatedTopics = sortedGaps.map { g ->
            val dayOff = scheduledDayByTopic[g.topicId]
            g.copy(
                scheduledDayOffset = dayOff,
                cardMetaLine = buildTopicMetaLine(g, dayOff, phase),
            )
        }

        val todayFocusTopicId = dailySlots.firstOrNull()?.topicIds?.firstOrNull()
            ?: annotatedTopics.firstOrNull()?.topicId
        val topFocus = annotatedTopics.firstOrNull { it.topicId == todayFocusTopicId }
            ?: annotatedTopics.firstOrNull()
        val headerSummary = buildHeaderSummary(daysRemaining, phase, fsrsCap, annotatedTopics)
        val ctaLabel = if (topFocus != null) {
            "继续今日冲刺 · ${topFocus.weekLabel}"
        } else {
            "继续今日冲刺"
        }

        return ExamCountdownPlan(
            courseId = courseId,
            examDate = parsedExamDate?.toString(),
            daysRemaining = daysRemaining,
            phase = phase,
            fsrsDailyCap = fsrsCap,
            dailyBudgetMinutes = safeBudget,
            overdueAlertCount = 0,
            rankedTopics = annotatedTopics,
            dailySlots = dailySlots,
            todayFocusTopicId = todayFocusTopicId,
            headerSummaryLine = headerSummary,
            primaryCtaLabel = ctaLabel,
        )
    }

    fun planForCourse(
        db: Connection,
        detail: CourseDetailSnapshot,
        today: LocalDate = LocalDate.now(),
        nowMs: Long = System.currentTimeMillis(),
        dailyBudgetMinutes: Int = DEFAULT_DAILY_BUDGET_MINUTES,
    ): ExamCountdownPlan {
        val concepts = MasteryStore.loadAllConcepts(db)
        val fsrsMap = loadFsrsStates(db)
        val mockLosses = loadRecentMockLosses(db, detail.courseId)
        return compute(
            courseId = detail.courseId,
            examDateRaw = detail.examProfile.examDate,
            topics = detail.allTopics,
            concepts = concepts,
            fsrsByBookLesson = fsrsMap,
            recentMockLosses = mockLosses,
            today = today,
            nowMs = nowMs,
            dailyBudgetMinutes = dailyBudgetMinutes,
        )
    }

    /**
     * Queries all active non-archived courses and returns the nearest upcoming `exam_date` epoch day
     * (`>= today.toEpochDay()`), or `null` if no upcoming exam is configured.
     */
    fun nearestUpcomingExamEpochDay(db: Connection, today: LocalDate): Long? {
        if (!BookSchemaMigration.tableExists(db, "collections")) return null
        val todayDay = today.toEpochDay()
        var nearest: Long? = null
        db.createStatement().use { sql ->
            sql.executeQuery(
                "SELECT exam_date, metadata_json FROM collections WHERE kind='COURSE' AND archived=0",
            ).use { rows ->
                while (rows.next()) {
                    val colDate = parseExamDate(rows.getString(1))
                    val metaDate = if (colDate == null) {
                        val meta = rows.getString(2)
                        CourseJson.decodeExamProfile(meta, null).examDate.let(::parseExamDate)
                    } else null
                    val parsed = colDate ?: metaDate ?: continue
                    val epochDay = parsed.toEpochDay()
                    if (epochDay >= todayDay && (nearest == null || epochDay < nearest!!)) {
                        nearest = epochDay
                    }
                }
            }
        }
        return nearest
    }

    private fun matchConceptsForTopic(
        topic: CourseTopicEntry,
        topicBookIds: Set<String>,
        concepts: List<ConceptMasteryRecord>,
    ): List<ConceptMasteryRecord> {
        val direct = concepts.filter { rec ->
            rec.tocNodeId == topic.topicId ||
                rec.conceptKey == topic.topicId ||
                rec.conceptKey.endsWith(":${topic.topicId}")
        }
        if (direct.isNotEmpty()) return direct
        if (topic.weekLabel == CourseExamPoints.LABEL) {
            val name = CourseExamPoints.normalize(topic.title)
            return concepts.filter { it.bookId in topicBookIds && CourseExamPoints.normalize(it.canonicalName) == name }
        }
        return concepts.filter { rec ->
            rec.bookId in topicBookIds &&
                (rec.collectionId == null || rec.collectionId == topic.collectionId)
        }
    }

    private fun loadFsrsStates(db: Connection): Map<Pair<String, String>, FsrsState> {
        if (!BookSchemaMigration.tableExists(db, "book_fsrs_cards")) return emptyMap()
        val out = LinkedHashMap<Pair<String, String>, FsrsState>()
        db.createStatement().use { sql ->
            sql.executeQuery(
                "SELECT book, lesson, due_day, stability, difficulty, last_review_day, reps, lapses FROM book_fsrs_cards",
            ).use { r ->
                while (r.next()) {
                    val book = r.getString(1)
                    val lesson = r.getString(2).substringBefore("#q")
                    val st = FsrsState(
                        dueDay = r.getLong(3),
                        stability = r.getDouble(4).let { if (r.wasNull()) null else it },
                        difficulty = r.getDouble(5).let { if (r.wasNull()) null else it },
                        lastReviewDay = r.getLong(6).let { if (r.wasNull()) null else it },
                        reps = r.getInt(7),
                        lapses = r.getInt(8),
                    )
                    out.putIfAbsent(book to lesson, st)
                }
            }
        }
        return out
    }

    private fun loadRecentMockLosses(db: Connection, courseId: String): List<MockTopicLossEvidence> {
        if (!BookSchemaMigration.tableExists(db, "learner_evidence_events")) return emptyList()
        return db.prepareStatement(
            """SELECT concept_key, book_id, page, error_class, detail_json, created_at
               FROM learner_evidence_events
               WHERE signal_type IN ('RUBRIC_GRADE','MISCONCEPTION_DIAG')
                 AND (score_ratio IS NULL OR score_ratio < 0.99 OR (error_class IS NOT NULL AND error_class <> 'NONE'))
               ORDER BY created_at DESC LIMIT 80""",
        ).use { sql ->
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val detailStr = rows.getString(5).orEmpty()
                        val detailObj = runCatching { json.parseToJsonElement(detailStr).jsonObject }.getOrNull()
                        val src = (detailObj?.get("source") as? JsonPrimitive)?.content
                        val evCourse = (detailObj?.get("courseId") as? JsonPrimitive)?.content
                        if (src != "course_mock") continue
                        if (evCourse != null && evCourse != courseId) continue
                        val topicId = (detailObj["topicId"] as? JsonPrimitive)?.content.orEmpty()
                        val pages = (detailObj["missedPages"] as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                            ?.filter { it > 0 }
                            .orEmpty()
                            .ifEmpty { listOf(rows.getInt(3).coerceAtLeast(1)) }
                        add(
                            MockTopicLossEvidence(
                                topicId = topicId,
                                bookId = rows.getString(2),
                                missedPages = pages,
                                errorClass = rows.getString(4),
                                recordedAt = rows.getLong(6),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun buildHeaderSummary(
        daysRemaining: Int?,
        phase: CountdownPhase,
        fsrsCap: Int,
        ranked: List<TopicCountdownGap>,
    ): String {
        val dayStr = if (daysRemaining != null) "距考试 $daysRemaining 天" else "动态备考计划"
        val topWeeks = ranked.take(2).map { it.weekLabel }.distinct().joinToString("与")
        val focusPart = when {
            phase == CountdownPhase.FINAL_MOCK && ranked.any { it.hasRecentMockLoss } ->
                "建议先考一套全真模考，并回看失分原书页"
            phase == CountdownPhase.FINAL_MOCK ->
                "建议做一套限时模考并定向回看薄弱页"
            phase == CountdownPhase.SPRINT_CLEAR && topWeeks.isNotBlank() ->
                "建议清零 $topWeeks 错因与分歧"
            topWeeks.isNotBlank() ->
                "建议先补 $topWeeks"
            else -> phase.labelZh
        }
        return "$dayStr · $focusPart · 每日复习上限 $fsrsCap 题"
    }

    private fun buildTopicMetaLine(
        gap: TopicCountdownGap,
        dayOffset: Int?,
        phase: CountdownPhase,
    ): String {
        val pct = (gap.pMastery * 100.0).roundToInt().coerceIn(5, 98)
        val dayPrefix = when (dayOffset) {
            0 -> "今日优先"
            null -> "后续巩固"
            else -> "第 ${dayOffset + 1} 天"
        }
        val tagPart = when {
            gap.hasRecentMockLoss -> {
                val firstPage = gap.missedMockPages.firstOrNull()?.second
                if (firstPage != null) "模考失分 · 回看第 $firstPage 页" else "模考失分回看"
            }
            gap.dominantErrorTags.isNotEmpty() -> {
                val errLabel = MisconceptionErrorClass.fromWire(gap.dominantErrorTags.first())?.labelZh ?: "待纠错"
                "含错因($errLabel)"
            }
            phase == CountdownPhase.SPRINT_CLEAR && gap.discrepancyCount > 0 ->
                "${gap.discrepancyCount} 处材料口径对照"
            else -> "掌握 $pct%"
        }
        val gapFmt = "%.2f".format(gap.gapScore)
        return "$dayPrefix · $tagPart · 缺口 $gapFmt"
    }
}
