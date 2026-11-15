package com.coach.desktop.books

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.*

class OwnedBookProcessTransportTest {
    private val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"

    @Test fun processSuccessAndFailure() = runBlocking {
        val echo = OwnedBookProcessTransport(listOf(python, "-c", "import sys; sys.stdout.buffer.write(sys.stdin.buffer.read())"))
        assertEquals("synthetic input", echo.complete("synthetic input"))
        val fail = OwnedBookProcessTransport(listOf(python, "-c", "import sys; sys.exit(7)"))
        assertFails { fail.complete("synthetic") }
        Unit
    }

    @Test fun timeoutAndCancellationStopWaiting() = runBlocking {
        val root = Files.createTempDirectory("book-process-test-")
        fun command(name: String) = listOf(python, "-c",
            "import os,sys,time; open(sys.argv[1], 'w').write(str(os.getpid())); time.sleep(30)",
            root.resolve(name).toString())
        fun assertStopped(name: String) {
            val pid = Files.readString(root.resolve(name)).toLong()
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "child $pid still running")
        }
        assertFailsWith<IllegalStateException> { OwnedBookProcessTransport(command("timeout"), 2000).complete("test") }
        assertStopped("timeout")
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(2000) { OwnedBookProcessTransport(command("cancel"), 30_000).complete("test") }
        }
        assertStopped("cancel")
        Unit
    }
}
