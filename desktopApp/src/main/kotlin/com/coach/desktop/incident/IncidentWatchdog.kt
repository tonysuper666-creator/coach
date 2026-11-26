package com.coach.desktop.incident

import java.awt.Dialog
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun freezeDue(
    now: Long,
    lastBeat: Long,
    armed: Boolean,
    modal: Boolean,
    lastFreezeAt: Long,
    thresholdMs: Long = 10_000,
    cooldownMs: Long = 60_000,
): Boolean {
    if (!armed || modal || lastBeat <= 0L) return false
    if (now - lastBeat < thresholdMs) return false
    return lastFreezeAt == 0L || now - lastFreezeAt >= cooldownMs
}

/** Writes one local record when the UI thread stays silent for 10 seconds. A visible file dialog does not count. */
object IncidentWatchdog {
    private val started = AtomicBoolean(false)
    private val lastBeat = AtomicLong(0)
    @Volatile var armed: Boolean = false
        private set

    fun beat() {
        lastBeat.set(System.currentTimeMillis())
        armed = true
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            var lastFreezeAt = 0L
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    break
                }
                val now = System.currentTimeMillis()
                if (!freezeDue(now, lastBeat.get(), armed, modalOpen(), lastFreezeAt)) continue
                lastFreezeAt = now
                val stack = uiStack()
                IncidentLog.record("freeze", "ui_unresponsive", stack)
            }
        }, "incident-watchdog").apply { isDaemon = true }.start()
    }

    private fun modalOpen(): Boolean = Window.getWindows().any { it is Dialog && it.isVisible }

    private fun uiStack(): String {
        val ui = Thread.getAllStackTraces().keys.firstOrNull { it.name.startsWith("AWT-EventQueue") }
        val frames = ui?.stackTrace?.filter { it.className.startsWith("com.coach.") }?.take(8).orEmpty()
        if (frames.isEmpty()) return "ui thread did not answer for 10s"
        return frames.joinToString("\n") { "at ${it.className}.${it.methodName}(${it.fileName ?: "Unknown"}:${it.lineNumber})" }
    }
}
