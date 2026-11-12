package com.coach.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import java.awt.image.DataBuffer
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

internal actual fun decodeReaderImage(bytes: ByteArray, observation: ReaderDecodeObservation?): ImageBitmap? {
    val source = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
    if (observation != null) {
        val buffer = source.raster.dataBuffer
        ReaderPageTrace.allocation(observation, "scratch", source.width, source.height,
            buffer.size.toLong() * buffer.numBanks * DataBuffer.getDataTypeSize(buffer.dataType) / 8, readerWeakBitmap(source))
    }
    val image = source.toComposeImageBitmap()
    if (observation != null) {
        val backing = image.asSkiaBitmap()
        ReaderPageTrace.allocation(observation, "decoded", image.width, image.height,
            backing.rowBytes.toLong() * image.height, readerWeakBitmap(backing))
    }
    return image
}

internal actual fun releaseReaderImage(bitmap: ImageBitmap, drawn: Boolean): String {
    // Already recorded SkImages retain their own pixel references. This releases our SkBitmap handle.
    bitmap.asSkiaBitmap().close()
    return "release_skia_handle"
}
