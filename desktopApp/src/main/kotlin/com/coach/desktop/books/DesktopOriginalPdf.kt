package com.coach.desktop.books

import java.io.File

internal const val originalPdfTouchEnabled = false
internal const val bookPdfRasterLimit = 4096
internal const val bookPdfCacheBytes = Long.MAX_VALUE

internal fun bookPdfBitmapBytes(bitmap: BookBitmap): Long = 0L

internal fun renderBookPdfPage(file: File, pdf: BookPdfDocument, index: Int, scale: Float): BookBitmap =
    BookPdfRenderer(pdf).renderImage(index, scale)
