package com.coach.desktop.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import com.coach.desktop.books.Textbook
import java.io.ByteArrayOutputStream

internal fun isBookUiThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

internal fun encodeReaderPng(image: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
    check(image.compress(Bitmap.CompressFormat.PNG, 100, output))
    output.toByteArray()
}

internal fun renderFallbackImage(book: Textbook, pdfPage: Int): Bitmap {
    val raster = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(raster)
    canvas.drawColor(Color.rgb(250, 248, 243))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 34, 34); textSize = 25f }
    canvas.drawText(book.title + " · 第 $pdfPage 页", 50f, 80f, paint)
    var y = 140f
    for (line in book.page(pdfPage)?.text.orEmpty().lineSequence().take(40)) {
        canvas.drawText(line, 50f, y, paint)
        y += 34f
    }
    return raster
}
