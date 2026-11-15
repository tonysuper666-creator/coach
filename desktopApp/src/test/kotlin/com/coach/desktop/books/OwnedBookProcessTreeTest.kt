package com.coach.desktop.books

import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Actual owned Python parent -> child -> grandchild; no CLI, materials or production processes. */
class OwnedBookProcessTreeTest {
    private val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"

    @Test fun timeoutReapsTreeEvenWhenParentExitsOnTerminate() = runBlocking {
        withTree("timeout") { tree ->
            val result = async {
                assertFailsWith<IllegalStateException> {
                    OwnedBookProcessTransport(tree.command, 3000).complete("synthetic")
                }
            }
            tree.awaitReady()
            result.await()
            tree.assertStopped()
        }
    }

    @Test fun repeatedCancellationReapsTreeAndLeavesUnrelatedProcessAlone() = runBlocking {
        val unrelated = ProcessBuilder(python, "-c", "import time; time.sleep(60)").start()
        println("SENTINEL pid=${unrelated.pid()} start=${unrelated.info().startInstant().orElse(null)}")
        try {
            withTree("cancel") { tree ->
                val result = async { OwnedBookProcessTransport(tree.command, 30_000).complete("synthetic") }
                tree.awaitReady()
                result.cancel()
                result.cancel()
                withTimeout(8000) { result.join() }
                tree.assertStopped()
                assertTrue(unrelated.isAlive, "unrelated owned sentinel was killed")
                println("SENTINEL pid=${unrelated.pid()} alive=${unrelated.isAlive} after cancellation")
            }
        } finally {
            unrelated.destroyForcibly()
            assertTrue(unrelated.waitFor(5, TimeUnit.SECONDS))
            println("SENTINEL pid=${unrelated.pid()} alive=${unrelated.isAlive} after fixture cleanup")
        }
    }

    @Test fun cancellationLetsAdapterRunItsOwnCleanupBeforeReapingSurvivors() = runBlocking {
        // Calibrate against a real ProcessBuilder process, independently of the transport.
        // Windows destroy() is forced termination: Python's SIGTERM handler cannot run there.
        // A missing cleanup marker is not evidence that the process survived cancellation.
        val normalTermination = withTree("graceful") { tree ->
            val adapter = ProcessBuilder(tree.command).start()
            try {
                tree.awaitReady()
                val supported = adapter.supportsNormalTermination()
                adapter.destroy()
                assertTrue(adapter.waitFor(5, TimeUnit.SECONDS), "direct destroy did not stop the adapter")
                assertFalse(adapter.isAlive)
                assertEquals(supported, tree.adapterCleanedUp(), "fixture disagrees with Process.destroy contract")
                println("TERMINATION probe_pid=${adapter.pid()} normal=$supported cleaned=${tree.adapterCleanedUp()} alive=${adapter.isAlive}")
                supported
            } finally {
                adapter.destroyForcibly()
                assertTrue(adapter.waitFor(5, TimeUnit.SECONDS))
            }
        }
        withTree("graceful") { tree ->
            val result = async { OwnedBookProcessTransport(tree.command, 30_000).complete("synthetic") }
            tree.awaitReady()
            result.cancel()
            withTimeout(8000) { result.join() }
            // Every platform must reap the parent, child and grandchild before fixture cleanup.
            tree.assertStopped()
            if (normalTermination) {
                assertTrue(tree.adapterCleanedUp(), "adapter SIGTERM cleanup did not finish")
            } else {
                assertFalse(tree.adapterCleanedUp(), "forced termination unexpectedly ran SIGTERM cleanup")
            }
            println("TERMINATION transport_pid=${tree.pid("parent")} normal=$normalTermination cleaned=${tree.adapterCleanedUp()}")
        }
    }

    @Test fun failedInputReapsTree() = runBlocking {
        withTree("broken-input") { tree ->
            val result = async {
                assertFails { OwnedBookProcessTransport(tree.command, 10_000).complete("s".repeat(1_048_576)) }
            }
            tree.awaitReady()
            result.await()
            tree.assertStopped()
        }
    }

    @Test fun normalTreeCompletesAndEarlyExitAndStartFailureReturn() = runBlocking {
        withTree("success") { tree ->
            val result = async { OwnedBookProcessTransport(tree.command, 10_000).complete("synthetic") }
            tree.awaitReady()
            assertEquals("synthetic tree complete", result.await())
            tree.assertStopped()
        }
        assertEquals("", OwnedBookProcessTransport(listOf(python, "-c", "pass"), 2000).complete(""))
        assertFails { OwnedBookProcessTransport(listOf(python, "-c", "raise SystemExit(7)"), 2000).complete("") }
        assertFails { OwnedBookProcessTransport(listOf("/nonexistent-owned-book-test-executable"), 2000).complete("") }
        Unit
    }

    /**
     * Cancellation handlers run on whichever thread cancels: the UI thread for a user cancel, the
     * coroutine timer for a timeout. "stubborn" ignores SIGTERM, so a handler that reaps inline
     * would hold that thread for the adapter's full 2s grace period.
     */
    @Test fun cancellationFromPlainThreadDoesNotBlockTheCancellingThread() = runBlocking {
        withTree("stubborn") { tree ->
            val result = async(Dispatchers.Default) {
                OwnedBookProcessTransport(tree.command, 30_000).complete("synthetic")
            }
            tree.awaitReady()
            var blockedMillis = 0L
            val caller = Thread {
                val started = System.nanoTime()
                result.cancel()
                blockedMillis = (System.nanoTime() - started) / 1_000_000
            }
            caller.start()
            withContext(Dispatchers.IO) { caller.join(10_000) }
            println("CANCEL caller_blocked_ms=$blockedMillis parent_pid=${tree.pid("parent")}")
            assertTrue(blockedMillis < 200, "cancel blocked the cancelling thread for ${blockedMillis}ms")
            withTimeout(15_000) { result.join() }
            tree.assertStopped()
        }
    }

    /** A timeout must not delay unrelated timers: inline reaping on the timer thread pushed them out. */
    @Test fun timeoutDoesNotStallUnrelatedDelays() = runBlocking {
        withTree("stubborn") { tree ->
            var worstLatenessMillis = 0L
            val ticker = launch(Dispatchers.Default) {
                while (isActive) {
                    val started = System.nanoTime()
                    delay(50)
                    worstLatenessMillis = maxOf(worstLatenessMillis, (System.nanoTime() - started) / 1_000_000 - 50)
                }
            }
            val result = async(Dispatchers.Default) {
                assertFailsWith<IllegalStateException> {
                    OwnedBookProcessTransport(tree.command, 3000).complete("synthetic")
                }
            }
            tree.awaitReady()
            withTimeout(15_000) { result.await() }
            ticker.cancelAndJoin()
            println("TIMEOUT unrelated_delay50_worst_lateness_ms=$worstLatenessMillis parent_pid=${tree.pid("parent")}")
            assertTrue(worstLatenessMillis < 200, "unrelated delay(50) ran ${worstLatenessMillis}ms late during timeout")
            tree.assertStopped()
        }
    }

    /**
     * The adapter exits first while a descendant keeps the inherited stdout pipe open, so
     * descendants() is empty by cleanup time and the pipe read never ends on its own.
     */
    @Test fun parentExitDoesNotStrandDescendantHoldingThePipe() = runBlocking {
        val root = Files.createTempDirectory("owned-book-parent-exit-")
        val script = root.resolve("exit.py")
        try {
            Files.writeString(script, """
                import os, signal, subprocess, sys, time
                from pathlib import Path
                role, root = sys.argv[1:]
                root = Path(root)
                (root / (role + '.pid')).write_text(str(os.getpid()))
                if role == 'parent':
                    # stdout is inherited on purpose: the descendant holds the transport's pipe.
                    subprocess.Popen([sys.executable, __file__, 'desc', str(root)],
                        stdin=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                    deadline = time.monotonic() + 10
                    while not (root / 'desc.pid').exists():
                        if time.monotonic() > deadline: raise RuntimeError('fixture readiness timeout')
                        time.sleep(.01)
                    time.sleep(.5)
                    raise SystemExit(0)
                else:
                    signal.signal(signal.SIGTERM, signal.SIG_IGN)
                    time.sleep(8)
                """.trimIndent())
            val started = System.nanoTime()
            val result = async(Dispatchers.Default) {
                assertFailsWith<IllegalStateException> {
                    OwnedBookProcessTransport(listOf(python, script.toString(), "parent", root.toString()), 1500).complete("synthetic")
                }
            }
            // Snapshot while the descendant is still alive: a reaped PID can no longer be looked up.
            val descendant = async(Dispatchers.IO) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                val pidFile = root.resolve("desc.pid")
                while (!Files.exists(pidFile) && System.nanoTime() < deadline) delay(10)
                ProcessHandle.of(Files.readString(pidFile).trim().toLong()).orElse(null)
            }.await()
            withTimeout(15_000) { result.await() }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            println("PARENT-EXIT elapsed_ms=$elapsedMillis desc_pid=${descendant?.pid()} desc_alive_at_return=${descendant?.isAlive}")
            assertTrue(elapsedMillis < 2500, "returned after ${elapsedMillis}ms; timeout 1500ms + 1s")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (descendant != null && descendant.isAlive && System.nanoTime() < deadline) delay(10)
            println("PARENT-EXIT desc_pid=${descendant?.pid()} desc_alive_final=${descendant?.isAlive}")
            assertTrue(descendant != null && !descendant.isAlive, "recorded descendant survived the timeout")
        } finally {
            for (role in listOf("desc", "parent")) {
                val pidFile = root.resolve("$role.pid")
                if (!Files.exists(pidFile)) continue
                val handle = ProcessHandle.of(Files.readString(pidFile).trim().toLong()).orElse(null) ?: continue
                if (handle.isAlive) handle.destroyForcibly()
                runCatching { handle.onExit().get(5, TimeUnit.SECONDS) }
                println("CLEANUP parent-exit $role pid=${handle.pid()} alive=${handle.isAlive}")
            }
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private suspend fun <T> withTree(mode: String, block: suspend (Tree) -> T): T {
        val tree = Tree(mode)
        try {
            return block(tree)
        } finally {
            // Red regressions must also collect every process before the foreground test exits.
            tree.cleanup()
        }
    }

    private inner class Tree(private val mode: String) {
        private val root = Files.createTempDirectory("owned-book-tree-")
        private val script = root.resolve("tree.py")
        private val identities = linkedMapOf<String, Pair<ProcessHandle, Instant?>>()
        val command: List<String>

        init {
            Files.writeString(script, """
                import os, signal, subprocess, sys, time
                from pathlib import Path
                role, root, mode = sys.argv[1:]
                root = Path(root)
                (root / (role + '.pid')).write_text(str(os.getpid()))
                def spawn(role):
                    return subprocess.Popen([sys.executable, __file__, role, str(root), mode],
                        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                def wait_file(name):
                    deadline = time.monotonic() + 10
                    while not (root / name).exists():
                        if time.monotonic() > deadline: raise RuntimeError('fixture readiness timeout')
                        time.sleep(.01)
                if role == 'parent':
                    child = spawn('child')
                    wait_file('grand.ready')
                    if mode == 'stubborn': signal.signal(signal.SIGTERM, signal.SIG_IGN)
                    if mode == 'graceful':
                        def stop(signum, frame):
                            child.kill()
                            child.wait(timeout=1)
                            (root / 'adapter.cleaned').touch()
                            raise SystemExit(0)
                        signal.signal(signal.SIGTERM, stop)
                    (root / 'ready').touch()
                    if mode == 'success':
                        sys.stdin.buffer.read()
                        child.wait(timeout=10)
                        sys.stdout.write('synthetic tree complete')
                    else:
                        if mode == 'broken-input':
                            wait_file('release')
                            os.close(0)
                        time.sleep(60)
                elif role == 'child':
                    signal.signal(signal.SIGTERM, signal.SIG_IGN)
                    grand = spawn('grand')
                    if mode == 'success': grand.wait(timeout=10)
                    else: time.sleep(60)
                else:
                    signal.signal(signal.SIGTERM, signal.SIG_IGN)
                    (root / 'grand.ready').touch()
                    if mode == 'success': wait_file('release')
                    time.sleep(.1 if mode == 'success' else 60)
                """.trimIndent())
            command = listOf(python, script.toString(), "parent", root.toString(), mode)
        }

        suspend fun awaitReady() = withTimeout(10_000) {
            while (!Files.exists(root.resolve("ready"))) delay(10)
            capture()
            assertEquals(3, identities.size)
            val parent = identities.getValue("parent").first
            val child = identities.getValue("child").first
            val grand = identities.getValue("grand").first
            assertEquals(parent.pid(), child.parent().orElseThrow().pid())
            assertEquals(child.pid(), grand.parent().orElseThrow().pid())
            Files.createFile(root.resolve("release"))
        }

        fun adapterCleanedUp(): Boolean = Files.exists(root.resolve("adapter.cleaned"))

        fun pid(role: String): Long? =
            root.resolve("$role.pid").takeIf { Files.exists(it) }?.let { Files.readString(it).trim().toLong() }

        fun capture() {
            for (role in listOf("parent", "child", "grand")) {
                val pidFile = root.resolve("$role.pid")
                if (Files.exists(pidFile)) {
                    val pid = Files.readString(pidFile).toLong()
                    val handle = ProcessHandle.of(pid).orElse(null) ?: continue
                    if (role !in identities) {
                        identities[role] = handle to handle.info().startInstant().orElse(null)
                        println("TREE $mode $role pid=$pid start=${identities[role]!!.second}")
                    }
                }
            }
        }

        suspend fun assertStopped() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (identities.values.any { it.first.isAlive } && System.nanoTime() < deadline) delay(10)
            val survivors = identities.filterValues { it.first.isAlive }.keys
            identities.forEach { (role, identity) ->
                println("TREE $mode $role pid=${identity.first.pid()} alive=${identity.first.isAlive} checked=${Instant.now()}")
            }
            assertTrue(survivors.isEmpty(), "$mode owned tree still alive: $survivors")
        }

        suspend fun cleanup() {
            capture()
            identities.values.toList().asReversed().forEach { (handle, start) ->
                if (handle.isAlive && handle.info().startInstant().orElse(null) == start) handle.destroyForcibly()
            }
            // Last-resort cleanup is independent of transport assertions, and cannot turn a red into a green.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (identities.values.any { it.first.isAlive } && System.nanoTime() < deadline) delay(10)
            identities.forEach { (role, identity) ->
                println("CLEANUP $mode $role pid=${identity.first.pid()} alive=${identity.first.isAlive}")
                assertFalse(identity.first.isAlive, "fixture cleanup failed: $role")
            }
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
