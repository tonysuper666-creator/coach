package com.coach.ui

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.nativeCanvas
import java.lang.ref.WeakReference
import kotlinx.serialization.json.*

internal actual fun readerWeakBitmap(value: Any): ReaderBitmapWeakReference {
    val weak = WeakReference(value)
    return ReaderBitmapWeakReference { weak.get() != null }
}

internal actual fun readerProcessMemory(): JsonObject = buildJsonObject {
    val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
    val runtime = Runtime.getRuntime()
    put("pid", Process.myPid())
    put("totalPssKiB", memory.totalPss)
    put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
    put("graphicsSummaryKiB", memory.getMemoryStat("summary.graphics"))
    put("nativeHeapSummaryKiB", memory.getMemoryStat("summary.native-heap"))
    put("jvmHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
    put("clockSync", buildJsonObject {
        put("traceNsBefore", ReaderPageTrace.nowNs())
        put("systemNanoTime", System.nanoTime())
        put("elapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
        put("traceNsAfter", ReaderPageTrace.nowNs())
    })
}

internal actual fun readerTraceMarker(kind: String, action: Long, image: Long) {
    if (Trace.isEnabled()) {
        Trace.beginSection("ReaderPage/$kind/$action/$image")
        Trace.endSection()
    }
}

internal actual fun readerWindowDraw(canvas: Canvas): Boolean = canvas.nativeCanvas.isHardwareAccelerated

@Composable
internal actual fun readerFrameSubmitted(): (ReaderDecodeObservation, Long) -> Unit {
    val view = LocalView.current
    val pending = remember(view) { mutableSetOf<Runnable>() }
    DisposableEffect(view) {
        onDispose {
            val callbacks = synchronized(pending) { pending.toList().also { pending.clear() } }
            if (view.viewTreeObserver.isAlive) callbacks.forEach { view.viewTreeObserver.unregisterFrameCommitCallback(it) }
        }
    }
    return remember(view) { { observation, action ->
        if (ReaderPageTrace.enabled && view.isHardwareAccelerated && view.viewTreeObserver.isAlive) {
            lateinit var callback: Runnable
            callback = Runnable {
                synchronized(pending) { pending.remove(callback) }
                ReaderPageTrace.submitted(observation, action)
            }
            synchronized(pending) { pending.add(callback) }
            // Submission to the swap chain is explicitly not proof of presentation on the display.
            view.viewTreeObserver.registerFrameCommitCallback(callback)
        }
    } }
}
