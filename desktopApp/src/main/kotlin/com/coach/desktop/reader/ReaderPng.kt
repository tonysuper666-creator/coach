package com.coach.desktop.reader

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

/** Local page transfer only: preserve pixels without temporary files or PNG compression work. */
internal fun encodeReaderPng(image: BufferedImage): ByteArray {
    val writers = ImageIO.getImageWritersByFormatName("png")
    check(writers.hasNext()) { "PNG encoder unavailable" }
    val writer = writers.next()
    try {
        val bytes = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(bytes).use { output ->
            writer.output = output
            val parameters = writer.defaultWriteParam.apply {
                // The JDK PNG writer maps MODE_DISABLED to Deflater.NO_COMPRESSION.
                // PNG remains lossless; only the in-process transfer is larger.
                compressionMode = ImageWriteParam.MODE_DISABLED
            }
            writer.write(null, IIOImage(image, null, null), parameters)
            output.flush()
        }
        return bytes.toByteArray()
    } finally {
        writer.dispose()
    }
}
