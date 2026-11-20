package com.coach.desktop.books

import com.coach.client.CourseView
import com.coach.domain.flag
import com.coach.domain.num
import com.coach.domain.pendingChoiceMistakes
import com.coach.domain.str
import com.coach.domain.strings
import com.coach.domain.weekPoints
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class BookHighlightRecord(
    val page: Int,
    val start: Int,
    val end: Int,
    val quote: String,
)

internal data class BookNoteRecord(
    val id: String,
    val version: String,
    val pdfPage: Int,
    val start: Int,
    val end: Int,
    val quote: String,
    val note: String,
    val textSha256: String,
    val revision: Long,
    val deleted: Boolean = false,
)

internal sealed interface BookNoteWriteResult {
    data class Saved(val revision: Long) : BookNoteWriteResult
    data object Conflict : BookNoteWriteResult
}

internal data class SavedBookAnswer(
    val lessonId: String,
    val mode: QuestionMode,
    val text: String,
    val revealed: Boolean,
    val understood: Boolean?,
    val guessed: Boolean,
)

internal enum class BookNotesScope(val label: String) {
    FULL_BOOK("整本教材"),
    CURRENT_PAGE("当前页附近"),
}

internal data class NotesExportDraft(
    val title: String,
    val markdown: String,
    val itemCount: Int,
)

internal fun buildBookNotesOutline(
    book: Textbook,
    highlights: List<BookHighlightRecord>,
    reviews: List<LocalBookReview>,
    answers: List<SavedBookAnswer>,
    scope: BookNotesScope = BookNotesScope.FULL_BOOK,
    currentPage: Int = book.readingPage,
    notes: List<BookNoteRecord> = emptyList(),
): NotesExportDraft {
    val activeLesson = book.lessons.firstOrNull { currentPage in it.firstPage..it.lastPage }
    val pageWindow = if (scope == BookNotesScope.FULL_BOOK) {
        book.pages.map { it.number }.toSet()
    } else {
        buildSet {
            add(currentPage)
            add((currentPage - 1).coerceAtLeast(1))
            add(currentPage + 1)
            activeLesson?.let { lesson -> addAll(lesson.firstPage..lesson.lastPage) }
        }
    }
    val selectedLessons = if (scope == BookNotesScope.FULL_BOOK) {
        book.lessons
    } else {
        book.lessons.filter { lesson -> (lesson.firstPage..lesson.lastPage).any { it in pageWindow } }
    }
    val lessonIds = selectedLessons.map { it.id }.toSet()
    val filteredHighlights = highlights.filter { scope == BookNotesScope.FULL_BOOK || it.page in pageWindow }
    val filteredNotes = notes.filter { !it.deleted && (scope == BookNotesScope.FULL_BOOK || it.pdfPage in pageWindow) }
    val filteredReviews = reviews.filter { scope == BookNotesScope.FULL_BOOK || it.lesson in lessonIds }
    val reviewKeys = filteredReviews.mapTo(HashSet()) { it.lesson to it.mode }
    val extraAnswers = answers.filter {
        (scope == BookNotesScope.FULL_BOOK || it.lessonId in lessonIds) &&
            (it.text.isNotBlank() || it.revealed) &&
            (it.lessonId to it.mode) !in reviewKeys
    }
    val lessonById = book.lessons.associateBy { it.id }

    val md = buildString {
        appendLine("# ${book.title} · 复习提纲")
        appendLine()
        appendLine("导出范围：${scope.label} · 当前进度第 ${book.readingPage} / ${book.pages.size} 页 · 高亮 ${filteredHighlights.size} 条 · 批注 ${filteredNotes.size} 条 · 复习与练习 ${filteredReviews.size + extraAnswers.size} 条")
        appendLine()
        if (filteredHighlights.isNotEmpty()) {
            appendLine("## 划线与原文摘录")
            appendLine()
            filteredHighlights.groupBy { it.page }.toSortedMap().forEach { (pageNum, marks) ->
                appendLine("### 第 $pageNum 页")
                marks.forEach { mark ->
                    appendLine("- ${mark.quote.replace("\n", " ").trim()}")
                }
                appendLine()
            }
        }
        if (filteredNotes.isNotEmpty()) {
            appendLine("## 批注笔记")
            appendLine()
            filteredNotes.groupBy { it.pdfPage }.toSortedMap().forEach { (pageNum, entries) ->
                appendLine("### 第 $pageNum 页")
                entries.forEach { entry ->
                    val quoteText = entry.quote.replace("\n", " ").trim()
                    val noteText = entry.note.replace("\n", " ").trim()
                    if (noteText.isNotEmpty()) appendLine("- $quoteText — 批注：$noteText")
                    else appendLine("- $quoteText")
                }
                appendLine()
            }
        }
        if (filteredReviews.isNotEmpty()) {
            appendLine("## 待复习与错题记录")
            appendLine()
            filteredReviews.forEach { review ->
                val stateLabel = if (review.completed) "已掌握" else {
                    review.sources.joinToString("、") { it.label }.ifBlank { "待复习" }
                }
                appendLine("### ${review.lessonTitle}（$stateLabel · ${review.mode.label}）")
                appendLine("- 题目：${review.practice.prompt}")
                if (review.originalAnswer.isNotBlank()) appendLine("- 原作答：${review.originalAnswer}")
                if (review.answer.text.isNotBlank()) appendLine("- 最近复习作答：${review.answer.text}")
                if (review.practice.answer.isNotBlank()) appendLine("- 参考答案：${review.practice.answer}")
                if (review.practice.reference.isNotBlank()) appendLine("- 原文依据：${review.practice.reference}")
                appendLine()
            }
        }
        if (extraAnswers.isNotEmpty()) {
            appendLine("## 已保存练习作答")
            appendLine()
            extraAnswers.forEach { ans ->
                val title = lessonById[ans.lessonId]?.title ?: ans.lessonId
                val tags = buildList {
                    add(ans.mode.label)
                    if (ans.guessed) add("猜的")
                    when (ans.understood) {
                        true -> add("已理解")
                        false -> add("还需复习")
                        null -> {}
                    }
                }.joinToString(" · ")
                appendLine("- $title（$tags）：${ans.text.ifBlank { "已查看参考" }}")
            }
            appendLine()
        }
        if (selectedLessons.isNotEmpty()) {
            appendLine("## 章节提纲")
            appendLine()
            selectedLessons.forEach { lesson ->
                appendLine("- ${lesson.title}（第 ${lesson.firstPage}–${lesson.lastPage} 页）")
            }
            appendLine()
        }
        if (filteredHighlights.isEmpty() && filteredNotes.isEmpty() && filteredReviews.isEmpty() && extraAnswers.isEmpty()) {
            appendLine("当前范围内还没有划线、批注或练习作答，已先保留教材章节提纲。")
        }
    }
    return NotesExportDraft(
        title = book.title,
        markdown = md.trimEnd() + "\n",
        itemCount = filteredHighlights.size + filteredNotes.size + filteredReviews.size + extraAnswers.size,
    )
}

internal fun buildCourseNotesOutline(
    view: CourseView,
    courseId: String,
    week: Int? = null,
    pointId: String? = null,
): NotesExportDraft {
    require(view.events.isNotEmpty()) { "课程尚无账本，请先同步" }
    val allPoints = weekPoints(view.events)
    val points = allPoints.filter { p -> week?.let { it in p.weeks } ?: (p.id == pointId) }
    require(points.isNotEmpty() || week != null) { "找不到该知识点" }
    val selectedIds = points.map { it.id }.toSet()
    val mistakes = pendingChoiceMistakes(view.events, selectedIds)
    val grades = view.events.filter { it.type in setOf("attempt_graded", "grade_reviewed") }
        .associateBy { it.payload.str("attemptId") }
    val submitted = view.events.filter { it.type == "answer_submitted" }
        .associateBy { it.payload.str("attemptId") }
    val notes = view.events.filter {
        it.type in setOf("note_saved", "kp_note", "annotation_added") &&
            (selectedIds.isEmpty() || it.payload.str("kpId").ifBlank { it.payload.str("pointId") } in selectedIds)
    }

    val heading = week?.let { "第 $it 周复习提纲" } ?: "${points.first().title} · 复习提纲"
    val md = buildString {
        appendLine("# $heading")
        appendLine()
        appendLine("课程：$courseId · 知识点 ${points.size} 个 · 待练错题 ${mistakes.size} 道")
        appendLine()
        if (points.isNotEmpty()) {
            appendLine("## 知识点与掌握度")
            appendLine()
            points.forEach { p ->
                val kp = view.kp(p.id)
                val status = kp?.status?.label ?: "未开始"
                val ref = kp?.reference?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                appendLine("- **${p.title}**$ref — $status")
            }
            appendLine()
        }
        if (notes.isNotEmpty()) {
            appendLine("## 课程笔记")
            appendLine()
            notes.forEach { e ->
                val text = e.payload.str("text").ifBlank { e.payload.str("body") }.ifBlank { e.payload.str("note") }
                if (text.isNotBlank()) appendLine("- $text")
            }
            appendLine()
        }
        if (mistakes.isNotEmpty()) {
            appendLine("## 待练错题")
            appendLine()
            mistakes.forEachIndexed { idx, q ->
                val p = q.payload
                val attemptId = p.str("attemptId")
                val stem = p.str("stem").ifBlank { "题干未记录" }
                val letters = p["letterMap"] as? JsonObject
                val options = (p["options"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                val rawAnswer = submitted[attemptId]?.payload?.str("answer").orEmpty()
                val myAnswer = resolveOptionLabel(rawAnswer, options, letters)
                val feedback = grades[attemptId]?.payload?.let { g ->
                    g.str("feedback").ifBlank { (g["revised"] as? JsonObject)?.str("feedback").orEmpty() }
                }.orEmpty()
                appendLine("### ${idx + 1}. $stem")
                options.forEach { opt ->
                    val oid = opt.str("optionId").ifBlank { opt.str("id") }
                    val letter = (letters?.get(oid) as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val prefix = if (letter.isNotBlank()) "$letter. " else ""
                    appendLine("- $prefix${opt.str("text")}")
                }
                if (myAnswer.isNotBlank()) {
                    val guessTag = if (p.flag("guessed") || submitted[attemptId]?.payload?.flag("guessed") == true) "（标记为猜的）" else ""
                    appendLine("- 我的答案：$myAnswer$guessTag")
                }
                if (feedback.isNotBlank()) appendLine("- 讲解：$feedback")
                appendLine()
            }
        } else {
            appendLine("本范围暂无待练错题。")
        }
    }
    return NotesExportDraft(
        title = heading,
        markdown = md.trimEnd() + "\n",
        itemCount = mistakes.size + points.size + notes.size,
    )
}

private fun resolveOptionLabel(raw: String, options: List<JsonObject>, letters: JsonObject?): String {
    if (raw.isBlank()) return ""
    val normalized = raw.trim().uppercase()
    for (opt in options) {
        val oid = opt.str("optionId").ifBlank { opt.str("id") }
        val letter = (letters?.get(oid) as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (raw == oid || (letter.isNotBlank() && letter.uppercase() == normalized)) {
            return if (letter.isNotBlank()) "$letter. ${opt.str("text")}" else opt.str("text")
        }
    }
    return raw
}
