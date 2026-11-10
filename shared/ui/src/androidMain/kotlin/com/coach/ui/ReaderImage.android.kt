package com.coach.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual fun decodeReaderImage(bytes: ByteArray, observation: ReaderDecodeObservation?): ImageBitmap? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    if (observation != null) ReaderPageTrace.allocation(observation, "decoded", bitmap.width, bitmap.height,
        bitmap.allocationByteCount.toLong(), readerWeakBitmap(bitmap))
    return bitmap.asImageBitmap()
}
