package com.coach.desktop.books

import java.sql.Connection

internal data class DiagnosticReview(val section: BookLesson, val practice: Practice) {
    fun answer(option: String?) = PracticeAnswer(option ?: "不确定", revealed = true, guessed = option == null)

    fun commit(option: String?, record: (BookLesson, Int, Practice, PracticeAnswer) -> Unit) =
        record(section, 0, practice, answer(option))

    companion object {
        fun fromQuiz(group: ChapterSectionGroup, q: BookCompanionQuiz): DiagnosticReview? {
            val section = group.sections.firstOrNull { it.id == q.sectionId }
                ?: group.sections.firstOrNull { (q.anchor?.pdfPage ?: 0) in it.firstPage..it.lastPage }
                ?: return null
            return DiagnosticReview(section.copy(id = "${section.id}#qdiag-${q.id}"),
                Practice(q.stem, q.anchor?.quoteAnchor.orEmpty(), q.correctOption, q.options,
                    q.anchor?.pdfPage ?: section.firstPage))
        }

        fun fromGate(group: ChapterSectionGroup, chapter: Int, gate: DiagnosticSkipGateItem): DiagnosticReview? {
            val correct = gate.options.getOrNull(gate.correctIndex) ?: return null
            val section = resolveMatchingSectionForGate(gate, group) ?: group.sections.firstOrNull() ?: return null
            val page = gate.missJumpPdfPage.takeIf { it in group.firstPage..group.lastPage } ?: section.firstPage
            return DiagnosticReview(section.copy(id = "${section.id}#qdiag-live-$chapter-${gate.gateId}"),
                Practice(gate.questionPrompt, gate.quoteAnchor, correct, gate.options, page))
        }
    }
}

/** Version 7 saved diagnostic selections in chapter drafts without creating review rows.
 * Only reconstruct a question from its own cached pack, never from a regenerated question. */
internal fun restoreDiagnosticReviews(db: Connection, store: TextbookReviewStore, loadBook: (String) -> Textbook) {
    val drafts = db.createStatement().use { sql ->
        sql.executeQuery("""SELECT p.practice_draft_json,p.book_id,p.toc_node_id FROM book_chapter_progress p JOIN books b ON p.book_id=b.id
            WHERE p.practice_draft_json LIKE '%pretestSubmitted%'""").use { rows ->
            buildList {
                while (rows.next()) {
                    val draft = runCatching { CompanionJson.decodeDraft(rows.getString(1)) }.getOrNull()
                    if (draft != null && draft.pretestSubmitted.isNotEmpty()) {
                        add(draft.copy(bookId = rows.getString(2), tocNodeId = rows.getString(3)))
                    }
                }
            }
        }
    }
    val hasModes = BookSchemaMigration.tableExists(db, "book_study_mode_packs")
    for ((id, saved) in drafts.groupBy { it.bookId }) {
        val book = loadBook(id)
        for (draft in saved) {
            val index = book.lessons.indexOfFirst { draft.page in it.firstPage..it.lastPage }
            val group = ChapterCompanionGrouping.groupForSection(book, index) ?: continue
            if (group.chapterId != draft.tocNodeId) continue
            fun restore(review: DiagnosticReview?, option: String?) {
                if (review == null || option == review.practice.answer) return
                if (store.find(db, id, review.section.id, QuestionMode.CHOICE) == null) {
                    store.recordLesson(db, book, review.section, QuestionMode.CHOICE, review.practice,
                        review.answer(option), migrated = true)
                }
            }
            val pack = ChapterProgressStore.loadCachedPack(db, id, group.chapterId, draft.updatedAt)
            for ((index, quiz) in pack?.pretest.orEmpty().withIndex()) {
                if (quiz.id !in draft.pretestSubmitted) continue
                val card = DiagnosticReview.fromQuiz(group, quiz) ?: continue
                // The ordinary pretest uses the same draft keys and already scheduled its answer.
                val base = card.section.id.substringBefore("#q")
                val ordinaryKey = if (index == 0) base else "$base#q${index + 1}"
                if (store.find(db, id, ordinaryKey, QuestionMode.CHOICE)?.practice?.prompt == quiz.stem) continue
                val unsure = draft.pretestGuessed[quiz.id] == true
                val selected = if (unsure) null else draft.pretestSelections[quiz.id]?.takeIf { it in quiz.options } ?: continue
                restore(card, selected)
            }
            if (!hasModes) continue
            val chapter = StudyModeChapterNumbering.resolve(book, draft.page, group.firstPage).chapterNumber
            val gates = db.prepareStatement("""SELECT pack_json FROM book_study_mode_packs
                WHERE book_id=? AND chapter_num=? AND study_mode='DIAGNOSTIC_SKIP' AND updated_at<=?""").use { sql ->
                sql.setString(1, id)
                sql.setInt(2, chapter)
                sql.setLong(3, draft.updatedAt)
                sql.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val pack = runCatching { StudyModeStore.parseDiagnosticSkipPack(rows.getString(1)) }.getOrNull()
                            addAll(pack?.gates.orEmpty())
                        }
                    }
                }
            }
            for ((gateId, variants) in gates.groupBy { it.gateId }) {
                val gate = variants.distinct().singleOrNull() ?: continue
                val key = "diag-live-$chapter-$gateId"
                if (key !in draft.pretestSubmitted) continue
                val selected = if (draft.pretestGuessed[key] == true) null else
                    draft.pretestSelections[key]?.toIntOrNull()?.let(gate.options::getOrNull) ?: continue
                restore(DiagnosticReview.fromGate(group, chapter, gate), selected)
            }
        }
    }
}
