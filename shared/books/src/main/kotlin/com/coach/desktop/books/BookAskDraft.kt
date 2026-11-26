package com.coach.desktop.books

/** Local pointer to the unfinished question, including its selected passage and conversation. */
internal data class BookAskDraft(
    val book: String, val page: Int, val text: String, val threadId: String?,
    val blockId: String?, val start: Int, val end: Int, val collectionId: String?,
) {
    fun selection(book: Textbook): SourceBlock? {
        val source = book.page(page)?.text ?: return null
        if (blockId == null || start !in 0 until end || end > source.length) return null
        return SourceBlock(blockId, page, start, end, false, source.substring(start, end))
    }
}

internal fun TextbookLibrary.askDraft(bookId: String? = null): BookAskDraft? = withCourseDatabase { db ->
    db.prepareStatement("""SELECT d.* FROM book_ask_drafts d JOIN books b ON b.id=d.book
        WHERE (? IS NULL OR d.book=?) ORDER BY updated_at DESC, d.rowid DESC LIMIT 1""").use { sql ->
        sql.setString(1, bookId)
        sql.setString(2, bookId)
        sql.executeQuery().use { r ->
            if (r.next()) BookAskDraft(r.getString("book"), r.getInt("page"), r.getString("question"),
                r.getString("thread_id"), r.getString("block_id"), r.getInt("block_start"),
                r.getInt("block_end"), r.getString("collection_id")) else null
        }
    }
}

internal fun TextbookLibrary.saveAskDraft(bookId: String, draft: BookAskDraft?) = withCourseDatabase { db ->
    if (draft == null) {
        db.prepareStatement("DELETE FROM book_ask_drafts WHERE book=?").use {
            it.setString(1, bookId)
            it.executeUpdate()
        }
    } else {
        db.prepareStatement("""INSERT OR REPLACE INTO book_ask_drafts
            (book,page,question,thread_id,block_id,block_start,block_end,collection_id,updated_at)
            SELECT ?,?,?,?,?,?,?,?,? WHERE EXISTS (SELECT 1 FROM books WHERE id=?)""").use {
            it.setString(1, bookId)
            it.setInt(2, draft.page)
            it.setString(3, draft.text)
            it.setString(4, draft.threadId)
            it.setString(5, draft.blockId)
            it.setInt(6, draft.start)
            it.setInt(7, draft.end)
            it.setString(8, draft.collectionId)
            it.setLong(9, clock.millis())
            it.setString(10, bookId)
            it.executeUpdate()
        }
    }
}

/** Serialize the debounced write and the final flush when the reader closes. */
internal class AskDraftWriter(private val library: TextbookLibrary, private val book: String) {
    private var pending: BookAskDraft? = null
    private var dirty = false

    @Synchronized fun edit(value: BookAskDraft?) {
        pending = value
        dirty = true
    }

    @Synchronized fun flush() {
        if (dirty) {
            library.saveAskDraft(book, pending)
            dirty = false
        }
    }
}
