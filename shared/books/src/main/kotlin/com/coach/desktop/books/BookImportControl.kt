package com.coach.desktop.books

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal const val OCR_NOTICE = "这页是识别出来的文字，可能有错字"
internal data class BookImportProgress(val page: Int, val total: Int, val recognizing: Boolean = false)
internal class BookImportCancelled : CancellationException()

/** Cancellation is independent of the controller's action mutex and SQLite lock. */
internal class BookImportControl(
    private val cancelledExternally: () -> Boolean = { false },
    private val progress: (BookImportProgress) -> Unit = {},
) {
    private val cancelled = AtomicBoolean()
    fun cancel() { cancelled.set(true) }
    fun isCancelled() = cancelled.get() || cancelledExternally()
    fun check() { if (isCancelled()) throw BookImportCancelled() }
    fun report(page: Int, total: Int, recognizing: Boolean = false) {
        check(); progress(BookImportProgress(page, total, recognizing)); check()
    }
}

internal fun interface BookOcr {
    fun recognize(image: BookBitmap, control: BookImportControl): String
}
