package com.coach.desktop.books

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.floor

internal const val originalPdfTouchEnabled = true
internal const val bookPdfRasterLimit = 3072
internal const val bookPdfCacheBytes = 48L * 1024 * 1024

internal fun bookPdfBitmapBytes(bitmap: BookBitmap): Long = bitmap.allocationByteCount.toLong()

internal fun renderBookPdfPage(file: File, pdf: BookPdfDocument, index: Int, scale: Float): BookBitmap {
    val page = pdf.getPage(index)
    val crop = page.cropBox
    val media = page.mediaBox
    // Keep the renderer used by extraction for displaced, cropped or rotated frames.
    if (Math.floorMod(page.rotation, 360) == 0 && crop.lowerLeftX == 0f && crop.lowerLeftY == 0f &&
        media.lowerLeftX == 0f && media.lowerLeftY == 0f && crop.width == media.width && crop.height == media.height &&
        page.userUnit == 1f
    ) {
        try {
            renderNativeBookPdfPage(file, index, crop.width, crop.height, scale)?.let { return it }
        } catch (_: IOException) {
            // Android's parser and the import parser support different PDF variants.
        } catch (_: SecurityException) {
            // PdfRenderer can reject a document that PDFBox opened with an empty password.
        } catch (_: IllegalArgumentException) {
            // A native page or its raster bounds were rejected. Keep the shared frame.
        }
    }
    return BookPdfRenderer(pdf).renderImage(index, scale)
}

internal fun renderNativeBookPdfPage(file: File, index: Int, width: Float, height: Float, scale: Float): Bitmap? {
    val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = try {
        PdfRenderer(handle)
    } catch (failure: Exception) {
        runCatching { handle.close() }
        throw failure
    }
    return renderer.use { document ->
        if (index !in 0 until document.pageCount) return@use null
        document.openPage(index).use { page ->
            // PdfRenderer rounds physical page sizes to integer points.
            if (abs(page.width - width) >= 1f || abs(page.height - height) >= 1f) return@use null
            val pixelsWide = floor(width * scale).toInt().coerceAtLeast(1)
            val pixelsHigh = floor(height * scale).toInt().coerceAtLeast(1)
            val image = Bitmap.createBitmap(pixelsWide, pixelsHigh, Bitmap.Config.ARGB_8888)
            try {
                image.eraseColor(Color.WHITE)
                page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                image
            } catch (failure: Throwable) {
                image.recycle()
                throw failure
            }
        }
    }
}
