package com.coach.desktop.books

/** 一章里的一节，学法流程只靠标题和页码，不保存正文。 */
internal data class ReadingSectionRef(val id: String, val title: String, val page: Int)

internal data class ReadingChapterPlan(
    val id: String,
    val title: String,
    val sections: List<ReadingSectionRef>,
)

/** 一道读前/读中/读后的小题。没有选项就是「用自己的话说」，本卡不批改开放题。 */
internal data class ReadingCheck(
    val id: String,
    val prompt: String,
    val ownWords: Boolean,
    val options: List<String> = emptyList(),
    val correct: String? = null,
    val sectionId: String = "",
    val page: Int = 1,
)

internal object ReadingMethodFlow {
    const val MIN_PRETEST = 3
    const val MAX_PRETEST = 5

    fun planOf(group: ChapterSectionGroup?): ReadingChapterPlan? {
        if (group == null) return null
        val sections = group.sections.map { ReadingSectionRef(it.id, it.title.ifBlank { group.chapterTitle }, it.firstPage) }
            .ifEmpty { listOf(ReadingSectionRef(group.chapterId, group.chapterTitle, group.firstPage)) }
        return ReadingChapterPlan(group.chapterId, group.chapterTitle, sections)
    }

    fun fromQuiz(quiz: BookCompanionQuiz, fallbackPage: Int): ReadingCheck = ReadingCheck(
        id = quiz.id,
        prompt = quiz.stem,
        ownWords = quiz.options.isEmpty() || quiz.correctOption.isBlank(),
        options = quiz.options,
        correct = quiz.correctOption.ifBlank { null },
        sectionId = quiz.sectionId,
        page = quiz.anchor?.pdfPage?.takeIf { it > 0 } ?: fallbackPage,
    )

    fun beforeChecks(plan: ReadingChapterPlan, pretest: List<ReadingCheck>): List<ReadingCheck> {
        val graded = pretest.filter { !it.ownWords && it.options.isNotEmpty() }.take(MAX_PRETEST)
        if (graded.size >= MIN_PRETEST) return graded
        val padded = ArrayList<ReadingCheck>(MAX_PRETEST)
        padded += graded
        val used = graded.map { it.id }.toMutableSet()
        for (section in plan.sections) {
            if (padded.size >= MIN_PRETEST) break
            val id = "expect-${section.id}"
            if (id in used) continue
            padded += ReadingCheck(
                id = id,
                prompt = "读之前，用一句话写下你觉得「${section.title}」会讲什么。",
                ownWords = true,
                sectionId = section.id,
                page = section.page,
            )
            used += id
        }
        val fallbacks = listOf(
            "读之前，用一句话写下你觉得这一章会讲什么。",
            "读之前，写下你最想在这一章里搞清的一点。",
            "读之前，写下你觉得自己已经知道的一点。",
        )
        val page = plan.sections.firstOrNull()?.page ?: 1
        val sectionId = plan.sections.firstOrNull()?.id.orEmpty()
        var extra = 0
        while (padded.size < MIN_PRETEST && extra < fallbacks.size) {
            val id = "expect-${plan.id}-$extra"
            extra++
            if (id in used) continue
            padded += ReadingCheck(id = id, prompt = fallbacks[extra - 1], ownWords = true, sectionId = sectionId, page = page)
            used += id
        }
        return padded.take(MAX_PRETEST)
    }

    fun duringCheck(section: ReadingSectionRef, pretest: List<ReadingCheck>): ReadingCheck {
        val matched = pretest.firstOrNull { it.sectionId == section.id && !it.ownWords && it.options.isNotEmpty() }
        if (matched != null) return matched
        return ReadingCheck(
            id = "pause-${section.id}",
            prompt = "用自己的话说，「${section.title}」在讲什么。",
            ownWords = true,
            sectionId = section.id,
            page = section.page,
        )
    }

    fun afterChecks(plan: ReadingChapterPlan, pretest: List<ReadingCheck>): List<ReadingCheck> {
        val graded = pretest.filter { !it.ownWords && it.options.isNotEmpty() }.take(MAX_PRETEST)
        if (graded.size >= MIN_PRETEST) return graded
        val prompts = plan.sections.take(MAX_PRETEST).map { section ->
            ReadingCheck(
                id = "recall-${section.id}",
                prompt = "不看书写出「${section.title}」在讲什么。写不出来就回书看。",
                ownWords = true,
                sectionId = section.id,
                page = section.page,
            )
        }
        return (graded + prompts).distinctBy { it.id }.take(MAX_PRETEST).ifEmpty {
            listOf(
                ReadingCheck(
                    id = "recall-${plan.id}",
                    prompt = "不看书，用几句话写下这一章你还记得的要点。",
                    ownWords = true,
                    page = plan.sections.firstOrNull()?.page ?: 1,
                ),
            )
        }
    }

    fun recordAnswer(progress: ChapterMethodProgress, checkId: String, value: String): ChapterMethodProgress =
        progress.copy(answers = progress.answers + (checkId to value))

    fun allAnswered(progress: ChapterMethodProgress, checks: List<ReadingCheck>): Boolean =
        checks.all { progress.answers[it.id].orEmpty().isNotBlank() }

    fun finishBeforePretest(progress: ChapterMethodProgress, checks: List<ReadingCheck>): ChapterMethodProgress =
        if (allAnswered(progress, checks)) progress.copy(step = "READ") else progress

    fun finishBeforeRead(progress: ChapterMethodProgress): ChapterMethodProgress =
        progress.copy(step = "COMPARE")

    fun finishBeforeCompare(progress: ChapterMethodProgress): ChapterMethodProgress =
        progress.copy(step = "DONE")

    fun finishDuringRead(progress: ChapterMethodProgress): ChapterMethodProgress =
        progress.copy(step = "PAUSE")

    fun finishDuringPause(progress: ChapterMethodProgress, sectionCount: Int): ChapterMethodProgress {
        val next = progress.sectionIndex + 1
        return if (next >= sectionCount) {
            progress.copy(step = "DONE", sectionIndex = progress.sectionIndex)
        } else {
            progress.copy(step = "READ", sectionIndex = next)
        }
    }

    fun finishRecall(progress: ChapterMethodProgress): ChapterMethodProgress =
        if (progress.recall.isNotBlank()) progress.copy(step = "QUIZ") else progress

    fun finishQuiz(progress: ChapterMethodProgress, checks: List<ReadingCheck>): ChapterMethodProgress {
        if (!allAnswered(progress, checks)) return progress
        val wrong = checks.mapNotNull { check ->
            val given = progress.answers[check.id].orEmpty()
            val missed = if (check.ownWords) given == NEED_BOOK else check.correct != null && given != check.correct
            if (missed) check.id else null
        }
        return if (wrong.isEmpty()) {
            progress.copy(step = "DONE", wrongIds = emptyList())
        } else {
            progress.copy(step = "RETURN", wrongIds = wrong)
        }
    }

    fun markReturned(progress: ChapterMethodProgress, checkId: String): ChapterMethodProgress {
        val returned = (progress.returnedIds + checkId).distinct()
        val done = progress.wrongIds.all { it in returned }
        return progress.copy(returnedIds = returned, step = if (done) "DONE" else "RETURN")
    }

    /** 读后自测里，自己写下「写不出来」时用这个记号，好回书。 */
    const val NEED_BOOK = "写不出来"
}
