package com.coach.desktop.books

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.nio.file.Files
import java.nio.file.Path

/** Process working set on Windows, RSS elsewhere. This is the import peak, not the JVM used-heap gauge. */
internal object ProcessWorkingSet {
    fun bytes(pid: Long = ProcessHandle.current().pid()): Long {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> windows(pid)
            os.contains("linux") -> linux(pid)
            else -> posixRss(pid)
        }
    }

    private fun linux(pid: Long): Long {
        val status = runCatching { Files.readAllLines(Path.of("/proc/$pid/status")) }.getOrNull() ?: return -1L
        val line = status.firstOrNull { it.startsWith("VmRSS:") } ?: return -1L
        val kb = line.filter { it.isDigit() }.toLongOrNull() ?: return -1L
        return kb * 1024L
    }

    private fun posixRss(pid: Long): Long {
        val process = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString())
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        val kb = text.toLongOrNull() ?: return -1L
        return kb * 1024L
    }

    private val windowsKernel by lazy { Native.load("kernel32", Kernel::class.java) }

    private fun windows(pid: Long): Long {
        val kernel = windowsKernel
        val current = pid == ProcessHandle.current().pid()
        val handle = if (current) {
            kernel.GetCurrentProcess()
        } else {
            kernel.OpenProcess(0x1000, false, pid.toInt()) ?: return -1L
        }
        try {
            val counters = Counters()
            counters.cb = counters.size()
            if (!kernel.K32GetProcessMemoryInfo(handle, counters, counters.cb)) return -1L
            return counters.workingSetSize
        } finally {
            if (!current) kernel.CloseHandle(handle)
        }
    }

    private interface Kernel : Library {
        fun GetCurrentProcess(): Pointer
        fun OpenProcess(access: Int, inherit: Boolean, pid: Int): Pointer?
        fun CloseHandle(handle: Pointer): Boolean
        fun K32GetProcessMemoryInfo(process: Pointer, counters: Counters, cb: Int): Boolean
    }

    class Counters : Structure() {
        @JvmField var cb: Int = 0
        @JvmField var pageFaultCount: Int = 0
        @JvmField var peakWorkingSetSize: Long = 0
        @JvmField var workingSetSize: Long = 0
        @JvmField var quotaPeakPagedPoolUsage: Long = 0
        @JvmField var quotaPagedPoolUsage: Long = 0
        @JvmField var quotaPeakNonPagedPoolUsage: Long = 0
        @JvmField var quotaNonPagedPoolUsage: Long = 0
        @JvmField var pagefileUsage: Long = 0
        @JvmField var peakPagefileUsage: Long = 0

        override fun getFieldOrder() = listOf(
            "cb",
            "pageFaultCount",
            "peakWorkingSetSize",
            "workingSetSize",
            "quotaPeakPagedPoolUsage",
            "quotaPagedPoolUsage",
            "quotaPeakNonPagedPoolUsage",
            "quotaNonPagedPoolUsage",
            "pagefileUsage",
            "peakPagefileUsage",
        )
    }
}
