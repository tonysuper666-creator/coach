package com.coach.desktop.books

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

internal const val OCR_NOTICE = "这页是识别出来的文字，可能有错字"
internal data class BookImportProgress(
    val page: Int,
    val total: Int,
    val recognizing: Boolean = false,
    val bookId: String? = null,
    val bookTitle: String? = null,
    val completedOcr: Int = 0,
    val totalOcr: Int = 0,
    val estimatedRemainingSeconds: Long? = null,
)
internal data class BookImportNotice(val id: Long, val message: String)
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
    fun report(
        page: Int,
        total: Int,
        recognizing: Boolean = false,
        bookId: String? = null,
        bookTitle: String? = null,
        completedOcr: Int = 0,
        totalOcr: Int = 0,
        estimatedRemainingSeconds: Long? = null,
    ) {
        check()
        progress(BookImportProgress(page, total, recognizing, bookId, bookTitle, completedOcr, totalOcr, estimatedRemainingSeconds))
        check()
    }
}

/**
 * Estimates remaining OCR seconds from a sliding window of recent page durations.
 * Returns null until [minSamples] pages complete so early jitter never flashes wildly wrong ETAs.
 */
internal class ImportEtaEstimator(private val windowSize: Int = 8, private val minSamples: Int = 3) {
    private val durationsMs = ArrayDeque<Long>()

    fun record(elapsedMs: Long) {
        if (elapsedMs <= 0) return
        if (durationsMs.size >= windowSize) durationsMs.removeFirst()
        durationsMs.addLast(elapsedMs)
    }

    fun estimateRemainingSeconds(remainingPages: Int): Long? {
        if (remainingPages <= 0 || durationsMs.size < minSamples) return null
        val averageMs = durationsMs.average()
        return ((averageMs * remainingPages) / 1000.0).roundToLong().coerceAtLeast(1L)
    }
}

internal fun formatImportEta(seconds: Long): String {
    if (seconds < 60L) return "约剩 ${seconds.coerceAtLeast(1L)} 秒"
    val minutes = ((seconds + 30L) / 60L).coerceAtLeast(1L)
    return if (minutes < 60L) "约剩 $minutes 分钟"
    else {
        val hours = minutes / 60L
        val remMinutes = minutes % 60L
        if (remMinutes == 0L) "约剩 $hours 小时" else "约剩 $hours 小时 $remMinutes 分钟"
    }
}

internal fun formatImportProgressLabel(progress: BookImportProgress): String = when {
    !progress.recognizing && progress.total <= 0 -> "正在准备教材…"
    !progress.recognizing -> "正在读取教材 · 第 ${progress.page} / ${progress.total} 页"
    else -> buildString {
        append("识别文字 ${progress.page} / ${progress.total} 页")
        progress.estimatedRemainingSeconds?.let { append(" · "); append(formatImportEta(it)) }
    }
}

internal fun interface BookOcr {
    fun recognize(image: BookBitmap, control: BookImportControl): String
    fun recognize(image: BookBitmap, control: BookImportControl, englishOnly: Boolean, singleBlock: Boolean): String =
        recognize(image, control)
}

internal interface BookXmlVisitor {
    fun startElement(localName: String, attr: (String) -> String?)
    fun text(value: String)
    fun endElement(localName: String)
}

/** Privacy-safe OCR timing trace: logs only fixed stage labels, status, monotonic ms and raster size. */
internal object OcrTiming {
    fun mark(stage: String, status: String) {
        println("CoachOcrTiming ms=${System.nanoTime() / 1_000_000} stage=$stage status=$status")
    }

    inline fun <T> measure(stage: String, action: () -> T): T {
        mark(stage, "start")
        try {
            return action().also { mark(stage, "ok") }
        } catch (failure: Throwable) {
            mark(stage, if (failure is java.util.concurrent.TimeoutException) "timeout" else "error")
            throw failure
        }
    }
}
