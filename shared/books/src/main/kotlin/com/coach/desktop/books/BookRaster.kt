package com.coach.desktop.books

internal data class BookRaster(val extension: String, val width: Int, val height: Int)

internal data class BookInkBounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)


internal data class BookPdfBounds(var x: Double, var y: Double, var width: Double, var height: Double) {
    val minX get() = x
    val minY get() = y
    val maxX get() = x + width
    val maxY get() = y + height

    fun add(px: Double, py: Double) {
        val right = maxOf(maxX, px)
        val top = maxOf(maxY, py)
        x = minOf(x, px)
        y = minOf(y, py)
        width = right - x
        height = top - y
    }

    fun createUnion(other: BookPdfBounds): BookPdfBounds = copy().apply {
        add(other.minX, other.minY)
        add(other.maxX, other.maxY)
    }
}
