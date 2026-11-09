package com.coach.ui.v7

import androidx.compose.runtime.*
import com.coach.client.*
import com.coach.ui.CoachController
import com.coach.ui.PageKey
import com.coach.ui.splitTitle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

/** Uses the same ledger and read-only source client as Study/Resources. Missing data stays absent. */
@Composable
internal fun pageDetails(c: CoachController, view: CourseView, week: Int?, kp: KpRow? = null, reading: String = "", includeSource: Boolean = true): V7PageDetails {
    LocalV7PageDetails.current?.let { return it }
    val points = week?.let(view::weekKps).orEmpty()
    val target = kp ?: view.weekTarget?.takeIf { week == null || points.any { p -> p.id == it.id } } ?: points.firstOrNull()
    val http = c.course
    val loaded by produceState<Pair<List<SourceDoc>, SourcePage?>>(emptyList<SourceDoc>() to null, http, Triple(target?.anchorDoc, target?.anchorPdfPage, includeSource)) {
        value = emptyList<SourceDoc>() to null
        value = coroutineScope {
            val docs = async { http?.sources().orEmpty() }
            val page = async {
                target?.takeIf { includeSource && it.anchorDoc.isNotBlank() && it.anchorPdfPage > 0 }?.let { row ->
                    val key = PageKey(row.anchorDoc, row.anchorPdfPage)
                    c.sourcePages[key] ?: http?.page(key.docId, key.pdfPage)?.also { c.sourcePages[key] = it }
                }
            }
            docs.await() to page.await()
        }
    }
    return remember(view, week, target, reading, loaded) { coursePageDetails(c.courseId, view, week, target, reading, loaded.first, loaded.second) }
}

internal fun coursePageDetails(course: String, view: CourseView, week: Int?, kp: KpRow?, reading: String,
    docs: List<SourceDoc>, page: SourcePage?): V7PageDetails {
    val points = week?.let(view::weekKps).orEmpty()
    val types = points.map { point ->
        view.events.filter { e -> e.type == "question_posed" &&
            (e.payload["kpIds"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.contentOrNull == point.id } }
            .mapNotNull { when ((it.payload["format"] as? JsonPrimitive)?.contentOrNull) {
                "SINGLE_CHOICE" -> "选择"
                "SHORT_ANSWER", "CALCULATION" -> "分析"
                "ESSAY" -> "论述"
                else -> null
            } }.distinct().joinToString(" · ")
    }
    val cards = kp?.let { view.quizGroups(view.tutorThreadId(it.id)).flatMap { g -> g.cards } }.orEmpty()
    val card = cards.lastOrNull()
    val question = card?.let { q -> V7MarginQuestion(q.stem, q.options.map { it.second },
        if (q.grade?.correct == true) q.options.indexOfFirst { it.first == q.answer } else -1,
        q.grade?.feedback.orEmpty(), cards.size, kp?.reference.orEmpty()) }
    val lecture = footnotes(reading).firstOrNull { Regex("课上|课堂|录音|lecture", RegexOption.IGNORE_CASE).containsMatchIn(it.text) }?.text.orEmpty()
    val deleted = view.events.filter { it.type == "note_deleted" }.mapNotNull { (it.payload["noteId"] as? JsonPrimitive)?.contentOrNull }.toSet()
    val notes = view.events.filter { it.type == "note_saved" && (it.payload["kpId"] as? JsonPrimitive)?.contentOrNull == kp?.id &&
        ((it.payload["noteId"] as? JsonPrimitive)?.contentOrNull ?: it.id) !in deleted }
        .mapNotNull { (it.payload["content"] as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }
    val highlights = notes.flatMap { note -> note.lines().filter { it.startsWith("> ") }.map { it.removePrefix("> ") } }.distinct()
    val personal = notes.map { note -> note.lines().filterNot { it.startsWith("> ") }.joinToString("\n").trim() }.filter { it.isNotBlank() }
    val passed = points.count { it.status == KpStatus.PASSED || it.status == KpStatus.SOLID }
    val active = points.count { it.status == KpStatus.IN_PROGRESS }
    val missing = points.filter { it.status == KpStatus.NEEDS_REPAIR }
    val index = points.indexOfFirst { it.id == kp?.id }
    val source = page?.takeIf { it.docId == kp?.anchorDoc && it.pdfPage == kp?.anchorPdfPage && it.text.isNotBlank() }
    return V7PageDetails(
        course = view.events.asReversed().firstNotNullOfOrNull {
            ((it.payload["course"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        } ?: course,
        sectionLabel = if (week != null && index >= 0) "$week.${index + 1}" else "",
        sectionTitle = kp?.title?.let { splitTitle(it).second }.orEmpty(),
        meta = kp?.reference.orEmpty(),
        lectureLabel = if (lecture.isNotBlank()) "课上强调" else "", lecture = lecture,
        personalNote = personal.joinToString("\n\n"), highlights = highlights,
        sourceLabel = source?.let { "教材原页 · ${it.bookPage?.let { p -> "p.$p" } ?: "PDF p.${it.pdfPage}"}" }.orEmpty(),
        sourceCaption = source?.let { docs.firstOrNull { d -> d.docId == it.docId }?.title ?: it.docId }.orEmpty(),
        sourceText = source?.text.orEmpty(),
        emphasis = Regex("\\*\\*(.+?)\\*\\*").find(reading)?.groupValues?.get(1).orEmpty(),
        types = types,
        weekStats = "通过 $passed · 在读 $active · 还缺 ${missing.size}",
        nextStep = kp?.let { "先把 ${splitTitle(it.title).second} ${if (view.isResume(it)) "读完" else "开始学习"}。" }.orEmpty(),
        mustKnow = points.filter { it.status != KpStatus.PASSED && it.status != KpStatus.SOLID }.map { splitTitle(it.title).second },
        missing = missing.map { splitTitle(it.title).second },
        directory = view.weeks.map { n ->
            val rows = view.weekKps(n)
            V7DirectoryEntry(n, rows.firstOrNull()?.let { it.module.ifBlank { splitTitle(it.title).second } }.orEmpty(), when {
                n in view.midtermExemptionWeeks -> "豁免"
                n == week -> "在读"
                rows.any { it.status == KpStatus.NEEDS_REPAIR } -> "要回炉"
                rows.isNotEmpty() && rows.all { it.status == KpStatus.PASSED || it.status == KpStatus.SOLID } -> "已读"
                else -> "未读"
            })
        },
        shelf = docs.map { it.title }.filter { it.isNotBlank() }.distinct(), question = question,
    )
}
