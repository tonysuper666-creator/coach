package com.coach.desktop.books

import java.sql.Connection

/**
 * 一本书的笔记页：批注、问答、错题和不确定题。
 * 引用只取用户自己选中并已落盘的那一句，不读页正文、不读讲解时多送出去的原文。
 */
internal enum class BookNoteKind(val label: String) {
    ANNOTATION("批注"),
    QUESTION("问答"),
    MISTAKE("错题和不确定题"),
}

internal data class BookNoteEntry(
    val id: String,
    val kind: BookNoteKind,
    val page: Int?,
    val lines: List<Pair<String, String>>,
)

internal data class BookNotesPage(
    val bookId: String,
    val title: String,
    val annotations: List<BookNoteEntry>,
    val questions: List<BookNoteEntry>,
    val mistakes: List<BookNoteEntry>,
) {
    val itemCount: Int get() = annotations.size + questions.size + mistakes.size

    fun of(kind: BookNoteKind): List<BookNoteEntry> = when (kind) {
        BookNoteKind.ANNOTATION -> annotations
        BookNoteKind.QUESTION -> questions
        BookNoteKind.MISTAKE -> mistakes
    }
}

internal sealed interface NotesFileSave {
    data class Saved(val fileName: String) : NotesFileSave
    data object Cancelled : NotesFileSave
    data class Failed(val message: String) : NotesFileSave
    /** 保存框已经在开，这次点击不再开第二个。 */
    data object Busy : NotesFileSave
}

private const val ANSWER_SUMMARY_LIMIT = 180

internal fun notesPageLabel(page: Int?): String = if (page == null || page <= 0) "出处页未记下" else "第 $page 页"

internal fun notesAnswerSummary(text: String): String {
    val flat = notesOneLine(text)
    if (flat.length <= ANSWER_SUMMARY_LIMIT) return flat
    return flat.take(ANSWER_SUMMARY_LIMIT).trimEnd() + "…"
}

internal fun notesOneLine(text: String): String = buildString {
    var pendingSpace = false
    for (ch in text.trim()) {
        if (ch.isWhitespace()) {
            if (!pendingSpace && isNotEmpty()) pendingSpace = true
        } else {
            if (pendingSpace) append(' ')
            append(ch)
            pendingSpace = false
        }
    }
}.trim()

internal fun notesExportFileName(title: String): String {
    val cleaned = buildString {
        for (ch in title.trim()) {
            if (ch.code < 32 || ch in "\\/:*?\"<>|") append(' ') else append(ch)
        }
    }.trim().replace(Regex(" +"), " ").ifBlank { "这本书" }
    return cleaned.take(40).trim().ifBlank { "这本书" } + "-笔记.md"
}

/** UTF-8 带 BOM，记事本按 UTF-8 打开中文不会当成系统编码。 */
internal fun notesMarkdownUtf8Bytes(markdown: String): ByteArray {
    val body = markdown.toByteArray(Charsets.UTF_8)
    val out = ByteArray(3 + body.size)
    out[0] = 0xEF.toByte()
    out[1] = 0xBB.toByte()
    out[2] = 0xBF.toByte()
    body.copyInto(out, 3)
    return out
}

internal fun sourcePageForLesson(
    lessonId: String,
    lessonFirstPages: Map<String, Int>,
    tocStartPages: Map<String, Int>,
): Int? {
    val base = lessonId.substringBefore("#q")
    lessonFirstPages[lessonId]?.let { return it }
    lessonFirstPages[base]?.let { return it }
    tocStartPages[lessonId]?.let { return it }
    tocStartPages[base]?.let { return it }
    val marker = LESSON_PAGE.find(base) ?: return null
    return marker.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
}

private val LESSON_PAGE = Regex(":p(\\d+):")

internal data class StoredNoteRow(
    val id: String,
    val page: Int,
    val start: Int,
    val quote: String,
    val note: String,
)

internal data class StoredQuestionRow(
    val id: Long,
    val page: Int,
    val selection: String,
    val question: String,
    val answer: String,
)

internal data class StoredMistakeRow(
    val lesson: String,
    val mode: String,
    val prompt: String,
    val solution: String,
    val wrongText: String,
    val answerText: String,
    val revealed: Boolean,
    val understood: Boolean?,
    val guessed: Boolean,
    val completed: Boolean,
    val sources: Int = 0,
    val firstUnderstood: Boolean? = null,
    val firstGuessed: Boolean = false,
)

internal fun assembleBookNotesPage(
    bookId: String,
    title: String,
    notes: List<StoredNoteRow>,
    questions: List<StoredQuestionRow>,
    mistakes: List<StoredMistakeRow>,
    lessonFirstPages: Map<String, Int> = emptyMap(),
    tocStartPages: Map<String, Int> = emptyMap(),
): BookNotesPage {
    val annotations = notes.mapNotNull { row ->
        val mine = notesOneLine(row.note)
        if (mine.isEmpty()) return@mapNotNull null
        val quote = notesOneLine(row.quote)
        val lines = buildList {
            if (quote.isNotEmpty()) add("选中的原句" to quote)
            add("我写的" to mine)
        }
        BookNoteEntry("note-${row.id}", BookNoteKind.ANNOTATION, row.page.takeIf { it > 0 }, lines)
    }.sortedWith(noteOrder)

    val asks = questions.mapNotNull { row ->
        val question = notesOneLine(row.question)
        if (question.isEmpty()) return@mapNotNull null
        val summary = notesAnswerSummary(row.answer).ifBlank { "（没有记下回答）" }
        val selected = notesOneLine(row.selection)
        val lines = buildList {
            add("问题" to question)
            add("回答摘要" to summary)
            if (selected.isNotEmpty()) add("选中的原句" to selected)
        }
        BookNoteEntry("ask-${row.id}", BookNoteKind.QUESTION, row.page.takeIf { it > 0 }, lines)
    }.sortedWith(noteOrder)

    val wrong = mistakes.mapNotNull { row ->
        if (!isWrongOrUncertain(row)) return@mapNotNull null
        val mine = notesOneLine(row.answerText).ifBlank { notesOneLine(row.wrongText) }.ifBlank { "（没有记下我的答案）" }
        val correct = notesOneLine(row.solution).ifBlank { "（没有单独记下正解）" }
        val stem = notesOneLine(row.prompt).ifBlank { "（题干未记下）" }
        val state = if (row.markedUncertain()) "不确定" else "错题"
        val page = sourcePageForLesson(row.lesson, lessonFirstPages, tocStartPages)
        BookNoteEntry(
            "mistake-${row.lesson}-${row.mode}",
            BookNoteKind.MISTAKE,
            page,
            listOf("题干" to stem, "我的答案" to mine, "正解" to correct, "状态" to state),
        )
    }.sortedWith(noteOrder)

    return BookNotesPage(bookId, notesOneLine(title).ifBlank { "未命名的书" }, annotations, asks, wrong)
}

/**
 * 首次作答的「还需复习」和猜答写在 sources 与第一条历史里，复习行的 understood/guessed 列当时仍是空的。
 * 后来答对并完成的题，completed 会改成 1；来源位是或上去的，不能再用来把已掌握的题算进来。
 */
internal fun isWrongOrUncertain(row: StoredMistakeRow): Boolean {
    if (row.completed) return false
    if (row.markedUncertain()) return true
    val mine = row.answerText.ifBlank { row.wrongText }.trim()
    val choiceWrong = row.mode == QuestionMode.CHOICE.name && mine.isNotEmpty() &&
        row.solution.isNotBlank() && mine != row.solution.trim()
    if (choiceWrong) return true
    return row.mode == QuestionMode.CHOICE.name && mine.isNotEmpty()
}

private fun StoredMistakeRow.markedUncertain(): Boolean {
    if (guessed || firstGuessed) return true
    if (understood == false || firstUnderstood == false) return true
    if ((sources and LocalReviewSource.GUESSED.bit) != 0) return true
    if ((sources and LocalReviewSource.SELF_CHECK.bit) != 0) return true
    return false
}

private val noteOrder = compareBy<BookNoteEntry>({ it.page ?: Int.MAX_VALUE }, { it.id })

internal fun renderBookNotesMarkdown(page: BookNotesPage): String = buildString {
    appendLine("# 这本书的笔记")
    appendLine()
    appendLine(page.title)
    appendLine()
    appendLine(notesCountLine(page))
    appendLine()
    append(renderNoteKinds(page, headingLevel = 2))
}.ensureTrailingNewline()

internal data class BooklistNotesExport(val markdown: String, val itemCount: Int)

internal fun loadBooklistNotes(
    library: TextbookLibrary,
    listTitle: String,
    books: List<BookSummary>,
): BooklistNotesExport? = runCatching {
    val pages = books.map { summary -> library.loadBookNotes(summary.id, summary.title) }
    BooklistNotesExport(renderBooklistNotesMarkdown(listTitle, pages), pages.sumOf { it.itemCount })
}.getOrNull()

internal fun renderBooklistNotesMarkdown(listTitle: String, pages: List<BookNotesPage>): String = buildString {
    val total = pages.sumOf { it.itemCount }
    appendLine("# ${notesOneLine(listTitle).ifBlank { "书单" }}")
    appendLine()
    appendLine("共 $total 条 · ${pages.size} 本书")
    appendLine()
    if (pages.isEmpty()) {
        appendLine("这份书单里还没有书。")
    }
    for (page in pages) {
        appendLine("## ${page.title}")
        appendLine()
        appendLine(notesCountLine(page))
        appendLine()
        append(renderNoteKinds(page, headingLevel = 3))
        appendLine()
    }
}.ensureTrailingNewline()

private fun notesCountLine(page: BookNotesPage): String =
    "共 ${page.itemCount} 条 · 批注 ${page.annotations.size} · 问答 ${page.questions.size} · 错题和不确定题 ${page.mistakes.size}"

private fun renderNoteKinds(page: BookNotesPage, headingLevel: Int): String = buildString {
    val marks = "#".repeat(headingLevel.coerceIn(2, 4))
    for (kind in BookNoteKind.entries) {
        val entries = page.of(kind)
        appendLine("$marks ${kind.label}")
        appendLine()
        if (entries.isEmpty()) {
            appendLine("这一类还没有。")
            appendLine()
            continue
        }
        var lastPage: Int? = Int.MIN_VALUE
        for (entry in entries) {
            if (entry.page != lastPage) {
                appendLine("${marks}# ${notesPageLabel(entry.page)}")
                appendLine()
                lastPage = entry.page
            }
            for ((label, text) in entry.lines) {
                appendLine("- $label：$text")
            }
            appendLine()
        }
    }
}

private fun String.ensureTrailingNewline(): String = if (endsWith("\n")) this else this + "\n"

internal fun loadStoredBookNotes(
    db: Connection,
    bookId: String,
    title: String,
    lessonFirstPages: Map<String, Int>,
): BookNotesPage {
    val toc = linkedMapOf<String, Int>()
    if (BookSchemaMigration.tableExists(db, "book_toc_nodes")) {
        db.prepareStatement("SELECT id, start_page FROM book_toc_nodes WHERE book=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                while (rows.next()) toc[rows.getString(1)] = rows.getInt(2)
            }
        }
    }
    val notes = if (!BookSchemaMigration.tableExists(db, "book_notes")) emptyList() else {
        db.prepareStatement(
            "SELECT id, page, start, quote, note FROM book_notes WHERE book=? AND deleted=0 ORDER BY page, start, id",
        ).use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(StoredNoteRow(rows.getString(1), rows.getInt(2), rows.getInt(3), rows.getString(4), rows.getString(5)))
                    }
                }
            }
        }
    }
    val questions = if (!BookSchemaMigration.tableExists(db, "book_ask_turns")) emptyList() else {
        db.prepareStatement(
            """SELECT id, selection_page, selection_text, question_text, answer_text
               FROM book_ask_turns
               WHERE book=? AND question_text IS NOT NULL AND length(trim(question_text))>0
               ORDER BY selection_page, id""".trimIndent(),
        ).use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            StoredQuestionRow(
                                id = rows.getLong(1),
                                page = rows.getInt(2),
                                selection = rows.getString(3).orEmpty(),
                                question = rows.getString(4).orEmpty(),
                                answer = rows.getString(5).orEmpty(),
                            ),
                        )
                    }
                }
            }
        }
    }
    val mistakes = if (!BookSchemaMigration.tableExists(db, "book_reviews")) emptyList() else {
        val withHistory = BookSchemaMigration.tableExists(db, "book_review_history")
        val mistakeSql = if (withHistory) {
            """SELECT r.lesson, r.mode, r.prompt, r.solution, r.wrong_text, r.answer_text,
                      r.revealed, r.understood, r.guessed, r.completed, r.sources,
                      (SELECT h.understood FROM book_review_history h
                        WHERE h.book=r.book AND h.lesson=r.lesson AND h.mode=r.mode
                        ORDER BY h.rowid LIMIT 1),
                      (SELECT h.guessed FROM book_review_history h
                        WHERE h.book=r.book AND h.lesson=r.lesson AND h.mode=r.mode
                        ORDER BY h.rowid LIMIT 1)
               FROM book_reviews r WHERE r.book=? ORDER BY r.lesson, r.mode"""
        } else {
            """SELECT lesson, mode, prompt, solution, wrong_text, answer_text,
                      revealed, understood, guessed, completed, sources
               FROM book_reviews WHERE book=? ORDER BY lesson, mode"""
        }
        db.prepareStatement(mistakeSql.trimIndent()).use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val understoodRaw = rows.getObject(8)
                        val firstUnderstoodRaw = if (withHistory) rows.getObject(12) else null
                        val firstGuessedRaw = if (withHistory) rows.getObject(13) else null
                        add(
                            StoredMistakeRow(
                                lesson = rows.getString(1),
                                mode = rows.getString(2),
                                prompt = rows.getString(3).orEmpty(),
                                solution = rows.getString(4).orEmpty(),
                                wrongText = rows.getString(5).orEmpty(),
                                answerText = rows.getString(6).orEmpty(),
                                revealed = rows.getInt(7) != 0,
                                understood = if (understoodRaw == null) null else rows.getInt(8) != 0,
                                guessed = rows.getInt(9) != 0,
                                completed = rows.getInt(10) != 0,
                                sources = rows.getInt(11),
                                firstUnderstood = if (firstUnderstoodRaw == null) null else rows.getInt(12) != 0,
                                firstGuessed = firstGuessedRaw != null && rows.getInt(13) != 0,
                            ),
                        )
                    }
                }
            }
        }
    }
    val page = assembleBookNotesPage(bookId, title, notes, questions, mistakes, lessonFirstPages, toc)
    if (!BookSchemaMigration.tableExists(db, "learning_draft_versions")) return page
    val recovered = db.prepareStatement("""SELECT parent,generation,writer,slot,source,page,location,text
        FROM learning_draft_versions WHERE book=? ORDER BY writer,parent,generation,slot""").use { q ->
        q.setString(1,bookId)
        q.executeQuery().use { r ->
            buildList {
                while (r.next()) {
                    val label = when (r.getString("source")) {
                        "ask_draft" -> "找回的提问草稿"
                        "answer" -> "找回的作答草稿"
                        else -> "找回的练习草稿"
                    }
                    val number = r.getInt("page").takeIf { it > 0 } ?:
                        sourcePageForLesson(r.getString("location"),lessonFirstPages,toc)
                    val id = listOf("draft",r.getString("parent"),r.getString("generation"),
                        r.getString("writer"),r.getString("slot")).joinToString("/")
                    add(BookNoteEntry(id, BookNoteKind.QUESTION, number, listOf(label to r.getString("text"))))
                }
            }
        }
    }
    return page.copy(questions = page.questions + recovered)
}
