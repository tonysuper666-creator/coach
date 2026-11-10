package com.coach.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

internal actual fun decodeReaderImage(bytes: ByteArray, observation: ReaderDecodeObservation?): ImageBitmap? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    if (observation != null) ReaderPageTrace.allocation(observation, "decoded", bitmap.width, bitmap.height,
        bitmap.allocationByteCount.toLong(), readerWeakBitmap(bitmap))
    return bitmap.asImageBitmap()
}

internal actual fun releaseReaderImage(bitmap: ImageBitmap, drawn: Boolean): String {
    // A submitted display list can outlive composition. Only never-drawn buffers may be recycled here.
    // Android owns reclamation of displayed buffers; neither this event nor weak reachability proves GPU release.
    if (drawn) return "release_deferred_renderer"
    bitmap.asAndroidBitmap().recycle()
    return "release_unused_bitmap"
}
