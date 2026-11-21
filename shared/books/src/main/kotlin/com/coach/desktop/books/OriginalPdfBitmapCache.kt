package com.coach.desktop.books

import androidx.compose.ui.graphics.ImageBitmap

internal data class PdfBitmapCacheKey(
    val bookId: String,
    val page: Int,
    val pixelWidth: Int?,
)

/**
 * 原页位图有界 LRU 缓存（吸收 TON-1463 前后页预取）：
 * - 命中时 UI 组合首帧即可同步拿到 `ImageBitmap`，翻页零闪白、零等待；
 * - 严格按页数上限（默认 6 页，覆盖双页对开当前+前一对+后一对）与字节预算淘汰，不无限增长内存。
 */
internal class OriginalPdfBitmapCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<PdfBitmapCacheKey, ImageBitmap>(maxEntries * 2, 0.75f, true)
    private var totalBytes = 0L

    fun get(key: PdfBitmapCacheKey): ImageBitmap? = synchronized(lock) {
        entries[key]
    }

    fun put(key: PdfBitmapCacheKey, bitmap: ImageBitmap) {
        val bytes = bitmapBytes(bitmap)
        synchronized(lock) {
            entries.remove(key)?.let { previous -> totalBytes -= bitmapBytes(previous) }
            entries[key] = bitmap
            totalBytes += bytes
            evictLocked()
        }
    }

    fun removePage(bookId: String, page: Int) = synchronized(lock) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key.bookId == bookId && entry.key.page == page) {
                totalBytes -= bitmapBytes(entry.value)
                it.remove()
            }
        }
    }

    fun clearBook(bookId: String) = synchronized(lock) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key.bookId == bookId) {
                totalBytes -= bitmapBytes(entry.value)
                it.remove()
            }
        }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    private fun evictLocked() {
        val it = entries.entries.iterator()
        while ((entries.size > maxEntries || totalBytes > maxBytes) && entries.size > 1 && it.hasNext()) {
            val oldest = it.next()
            totalBytes -= bitmapBytes(oldest.value)
            it.remove()
        }
    }

    private fun bitmapBytes(bitmap: ImageBitmap): Long =
        bitmap.width.toLong().coerceAtLeast(1L) * bitmap.height.toLong().coerceAtLeast(1L) * 4L

    companion object {
        const val DEFAULT_MAX_ENTRIES = 6
        const val DEFAULT_MAX_BYTES = 96L * 1024L * 1024L
    }
}

/**
 * 计算前后页预取顺序：先下一页/下一组对开，再上一页/上一组对开，排除当前已展示页。
 */
internal fun adjacentPdfPrefetchPages(
    pageNumbers: List<Int>,
    sheets: List<List<Int>>,
    currentPage: Int,
    scrolling: Boolean,
): List<Int> {
    if (pageNumbers.isEmpty()) return emptyList()
    if (!scrolling && sheets.isNotEmpty()) {
        val sheetIndex = sheets.indexOfFirst { currentPage in it }
        if (sheetIndex >= 0) {
            val visible = sheets[sheetIndex].toSet()
            val next = sheets.getOrNull(sheetIndex + 1).orEmpty()
            val prev = sheets.getOrNull(sheetIndex - 1).orEmpty()
            return (next + prev).filterNot { it in visible }.distinct()
        }
    }
    val idx = pageNumbers.indexOf(currentPage)
    if (idx < 0) return emptyList()
    return buildList {
        pageNumbers.getOrNull(idx + 1)?.let(::add)
        pageNumbers.getOrNull(idx - 1)?.let(::add)
    }
}
