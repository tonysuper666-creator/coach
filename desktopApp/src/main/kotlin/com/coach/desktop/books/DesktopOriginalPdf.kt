package com.coach.desktop.books

import java.io.File
import javax.imageio.ImageIO

internal const val bookPdfRasterLimit = 4096
internal const val bookPdfCacheBytes = 128L * 1024 * 1024

private val pdfImageCodecsReady by lazy {
    ImageIO.scanForPlugins()
    true
}

internal fun bookPdfBitmapBytes(bitmap: BookBitmap): Long = bitmap.width.toLong() * bitmap.height * 4L

internal fun renderBookPdfPage(file: File, pdf: BookPdfDocument, index: Int, scale: Float): BookBitmap {
    pdfImageCodecsReady
    return newBookPdfRenderer(pdf).renderImage(index, scale)
}
