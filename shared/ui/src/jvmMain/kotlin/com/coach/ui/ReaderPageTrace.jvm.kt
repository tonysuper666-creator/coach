package com.coach.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Canvas
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference
import kotlinx.serialization.json.*

internal actual fun readerWeakBitmap(value: Any): ReaderBitmapWeakReference {
    val weak = WeakReference(value)
    return ReaderBitmapWeakReference { weak.get() != null }
}

internal actual fun readerProcessMemory(): JsonObject = buildJsonObject {
    put("pid", ProcessHandle.current().pid())
    put("jvmHeapUsedBytes", ManagementFactory.getMemoryMXBean().heapMemoryUsage.used)
    put("jvmNonHeapUsedBytes", ManagementFactory.getMemoryMXBean().nonHeapMemoryUsage.used)
    put("rssObserved", false)
    put("clockSync", buildJsonObject {
        put("traceNsBefore", ReaderPageTrace.nowNs())
        put("systemNanoTime", System.nanoTime())
        put("traceNsAfter", ReaderPageTrace.nowNs())
    })
}

internal actual fun readerTraceMarker(kind: String, action: Long, image: Long) = Unit

// The desktop host still has no confirmed window-presentation endpoint.
internal actual fun readerWindowDraw(canvas: Canvas): Boolean = false

@Composable
internal actual fun readerFrameSubmitted(): (ReaderDecodeObservation, Long) -> Unit = remember { { _, _ -> } }
