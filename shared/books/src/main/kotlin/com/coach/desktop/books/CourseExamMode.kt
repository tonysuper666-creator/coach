package com.coach.desktop.books

/**
 * TON-1820: a course is either 「要考」 or 「不考」, and 不考 is the default.
 *
 * It only counts as an exam once an exam date is set or the user taps 「要考」 ([CourseExamProfile.isExam]).
 * Without a syllabus there is nothing to narrow the scope by, so the whole book stays in, in book order.
 * Everything here is pure so the wording can be checked without a window.
 */
internal object CourseExamMode {
    /** Week label for a textbook chapter that sits in the course because the whole book is in scope. */
    const val WHOLE_BOOK_LABEL = "教材"

    /** Label the older aligner wrote on uncovered chapters; only meaningful when a syllabus narrowed the scope. */
    private const val LEGACY_EXCLUDED_LABEL = "本课不考"

    fun hasSyllabus(materials: List<CourseMaterialEntry>): Boolean =
        materials.any { it.role == CourseMaterialRole.SYLLABUS }

    /**
     * Courses aligned before TON-1820 parked every uncovered chapter in 「本课不考」 even without a syllabus.
     * Read those back as whole-book chapters. Topics the user moved by hand stay where they put them.
     */
    fun normalizeTopics(topics: List<CourseTopicEntry>, materials: List<CourseMaterialEntry>): List<CourseTopicEntry> {
        if (hasSyllabus(materials)) return topics
        return topics.map { t ->
            if (t.examStatus == CourseTopicStatus.EXCLUDED && !t.userLocked && t.weekLabel == LEGACY_EXCLUDED_LABEL) {
                t.copy(examStatus = CourseTopicStatus.EXAMINABLE, weekLabel = WHOLE_BOOK_LABEL, examWeight = 1.0)
            } else {
                t
            }
        }
    }

    /** Without an exam everything is in: the course page lists every chapter in book order, with no status split. */
    fun selfStudyTopics(detail: CourseDetailSnapshot): List<CourseTopicEntry> =
        (detail.examinableTopics + detail.excludedTopics.map { t ->
            if (t.weekLabel == LEGACY_EXCLUDED_LABEL) t.copy(weekLabel = WHOLE_BOOK_LABEL) else t
        })
            .sortedWith(compareBy<CourseTopicEntry>({ it.weekNum ?: 999 }, { it.ord }, { it.title }))

    /** The topic today's first step points at: where the user left off, otherwise the first chapter. */
    fun firstStepTopic(detail: CourseDetailSnapshot): CourseTopicEntry? {
        val topics = if (detail.examProfile.isExam) detail.examinableTopics else selfStudyTopics(detail)
        val anchored = detail.activeAnchor?.topicId?.let { id -> topics.firstOrNull { it.topicId == id } }
        return anchored ?: topics.firstOrNull { it.sources.isNotEmpty() } ?: topics.firstOrNull()
    }

    /** About 2.5 minutes a page, rounded to 5, kept between 15 and 40; 20 when the page span is unknown. */
    fun estimateMinutes(topic: CourseTopicEntry): Int {
        val pages = topic.sources
            .filter { it.role == CourseMaterialRole.CORE_TEXTBOOK || it.role == CourseMaterialRole.REQUIRED_READING }
            .sumOf { (it.endPage - it.startPage + 1).coerceAtLeast(0) }
        if (pages <= 1) return DEFAULT_MINUTES
        val raw = pages * 2.5
        val rounded = ((raw / 5.0).toInt().coerceAtLeast(1)) * 5
        return rounded.coerceIn(15, 40)
    }

    /** 「从第一章开始 · 约 20 分钟」 or 「接着读「…」 · 约 20 分钟」; null when the course has nothing to read yet. */
    fun firstStepLabel(detail: CourseDetailSnapshot): String? {
        val topic = firstStepTopic(detail) ?: return null
        val minutes = estimateMinutes(topic)
        val resumed = detail.activeAnchor?.topicId == topic.topicId
        return if (resumed) {
            "接着读「${topic.title}」 · 约 $minutes 分钟"
        } else {
            "从「${topic.title}」开始 · 约 $minutes 分钟"
        }
    }

    /** One sentence on how the course is arranged. */
    fun arrangementLine(detail: CourseDetailSnapshot): String = when {
        !detail.examProfile.isExam -> "按原书章节顺序，一章一章往下读"
        hasSyllabus(detail.materials) -> "按考纲安排，只排要考的部分"
        else -> SCOPE_HINT
    }

    /** Exam course, no syllabus yet: the clickable line that offers to narrow the scope. */
    const val SCOPE_HINT = "按整本书安排，放进考纲后可以缩小范围"

    const val SCOPE_ASK = "有考纲或往年题吗？没有也能先学"

    /** Ask once per exam course, only while nothing narrows the scope and the user hasn't answered. */
    fun shouldAskScope(profile: CourseExamProfile, materials: List<CourseMaterialEntry>): Boolean =
        profile.isExam && profile.scopeAsk.isBlank() && !hasSyllabus(materials)

    /** The recorded answer, kept visible on the course page; null when there is nothing to show. */
    fun scopeAnswerLine(profile: CourseExamProfile, materials: List<CourseMaterialEntry>): String? = when {
        !profile.isExam -> null
        hasSyllabus(materials) -> null
        profile.scopeAsk == CourseExamProfile.SCOPE_ASK_SKIPPED -> "考纲或往年题：先不放"
        profile.scopeAsk == CourseExamProfile.SCOPE_ASK_PROVIDED -> "考纲或往年题：说过要放，还没导入"
        else -> null
    }

    /** 「考前排程」 in the last ten days, 「排本周计划」 before that. */
    fun planEntryLabel(plan: ExamCountdownPlan?): String {
        val days = plan?.daysRemaining
        return if (days != null && days in 0..10) "考前排程" else "排本周计划"
    }

    /** Header meta for a course without an exam; zero counts are left out. */
    fun selfStudyMeta(detail: CourseDetailSnapshot): String = buildList {
        val mats = detail.materials.size
        if (mats > 0) add("已纳入 $mats 份材料")
        val topics = selfStudyTopics(detail)
        val chapters = topics.size
        if (chapters > 0) {
            val allChapters = topics.all { it.weekLabel == WHOLE_BOOK_LABEL }
            add(if (allChapters) "共 $chapters 章" else "共 $chapters 部分")
        }
    }.joinToString(" · ")

    private const val DEFAULT_MINUTES = 20
}
