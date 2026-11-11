package com.coach.domain.reader

import kotlinx.serialization.Serializable

/** Normalized unrotated CropBox coordinates, origin top-left, x right/y down. */
@Serializable data class ReaderRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    init {
        require(listOf(x, y, width, height).all { it.isFinite() })
        require(x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= 1.000000001 && y + height <= 1.000000001)
    }
}
/** PDF user-space points, origin bottom-left; coordinates may be negative. */
@Serializable data class PdfPointRect(val left: Double, val bottom: Double, val right: Double, val top: Double) {
    init { require(listOf(left, bottom, right, top).all { it.isFinite() }); require(right > left && top > bottom) }
}
@Serializable data class PdfPageFrame(
    val mediaBox: PdfPointRect, val cropBox: PdfPointRect = mediaBox, val rotationClockwise: Int = 0
) {
    init {
        require(rotationClockwise in listOf(0, 90, 180, 270))
        require(cropBox.left >= mediaBox.left && cropBox.right <= mediaBox.right &&
            cropBox.bottom >= mediaBox.bottom && cropBox.top <= mediaBox.top)
    }

    fun normalize(box: PdfPointRect): ReaderRect? {
        val left = maxOf(box.left, cropBox.left); val right = minOf(box.right, cropBox.right)
        val bottom = maxOf(box.bottom, cropBox.bottom); val top = minOf(box.top, cropBox.top)
        if (left >= right || bottom >= top) return null
        return ReaderRect((left - cropBox.left) / (cropBox.right - cropBox.left),
            (cropBox.top - top) / (cropBox.top - cropBox.bottom),
            (right - left) / (cropBox.right - cropBox.left), (top - bottom) / (cropBox.top - cropBox.bottom))
    }
}
@Serializable data class ReaderGeometry(
    val frame: PdfPageFrame, val boxes: List<ReaderRect>, val coordinateSystem: String = "unrotated-crop-top-left-v1"
) {
    init { require(boxes.isNotEmpty()); require(coordinateSystem == "unrotated-crop-top-left-v1") }

    /** Clip to a reader's additional trim in canonical space, then apply the PDF rotation once. */
    fun displayBoxes(trim: ReaderRect = ReaderRect(0.0, 0.0, 1.0, 1.0)): List<ReaderRect> = boxes.mapNotNull { box ->
        val x = maxOf(box.x, trim.x); val y = maxOf(box.y, trim.y)
        val right = minOf(box.x + box.width, trim.x + trim.width)
        val bottom = minOf(box.y + box.height, trim.y + trim.height)
        if (x >= right || y >= bottom) return@mapNotNull null
        val nx = (x - trim.x) / trim.width; val ny = (y - trim.y) / trim.height
        val w = (right - x) / trim.width; val h = (bottom - y) / trim.height
        when (frame.rotationClockwise) {
            90 -> ReaderRect(maxOf(0.0, 1 - ny - h), nx, h, w)
            180 -> ReaderRect(maxOf(0.0, 1 - nx - w), maxOf(0.0, 1 - ny - h), w, h)
            270 -> ReaderRect(ny, maxOf(0.0, 1 - nx - w), h, w)
            else -> ReaderRect(nx, ny, w, h)
        }
    }
}
