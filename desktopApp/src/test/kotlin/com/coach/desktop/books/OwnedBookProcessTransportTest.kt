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

    @Test fun timeoutAndCancellationStopDetachedGrandchildWhileParentLives() = runBlocking {
        val root = Files.createTempDirectory("book-process-tree-test-")
        val childCode = "import os,sys,time; open(sys.argv[1], 'w').write(str(os.getpid())); time.sleep(90)"
        val parentCode = """
            import os, subprocess, sys, time
            kwargs = dict(stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if os.name == 'nt': kwargs['creationflags'] = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
            else: kwargs['start_new_session'] = True
            subprocess.Popen([sys.executable, '-c', sys.argv[2], sys.argv[1]], **kwargs)
            time.sleep(90)
        """.trimIndent()
        fun command(name: String) = listOf(python, "-c", parentCode, root.resolve(name).toString(), childCode)
        fun childPid(name: String) = Files.readString(root.resolve(name)).toLong()
        fun assertStopped(name: String) {
            val pid = childPid(name)
            val alive = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            println("detached grandchild $name pid=$pid alive=$alive")
            assertFalse(alive, "grandchild $pid still running")
        }
        try {
            assertFailsWith<IllegalStateException> { OwnedBookProcessTransport(command("timeout"), 3000).complete("test") }
            assertStopped("timeout")
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(3000) { OwnedBookProcessTransport(command("cancel"), 30_000).complete("test") }
            }
            assertStopped("cancel")
        } finally {
            listOf("timeout", "cancel").forEach { name ->
                if (Files.exists(root.resolve(name))) runCatching { ProcessHandle.of(childPid(name)).ifPresent { it.destroyForcibly() } }
            }
        }
    }
}
