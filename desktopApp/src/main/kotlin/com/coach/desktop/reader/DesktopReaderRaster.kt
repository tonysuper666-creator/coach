package com.coach.desktop.reader

import com.coach.desktop.books.Textbook
import java.awt.image.BufferedImage

internal fun isBookUiThread() = java.awt.EventQueue.isDispatchThread()

internal fun renderFallbackImage(book: Textbook, pdfPage: Int): BufferedImage {
    val img = BufferedImage(1200, 1600, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    try {
        g.color = java.awt.Color(0xFA, 0xF8, 0xF3)
        g.fillRect(0, 0, 1200, 1600)
        g.color = java.awt.Color(0x22, 0x22, 0x22)
        g.drawString("${book.title} · 第 $pdfPage 页", 50, 80)
        val p = book.page(pdfPage)
        var y = 140
        for (line in p?.text?.lines().orEmpty().take(40)) {
            g.drawString(line, 50, y)
            y += 30
        }
    } finally {
        g.dispose()
    }
    return img
}
