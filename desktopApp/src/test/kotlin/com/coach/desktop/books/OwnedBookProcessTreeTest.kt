package com.coach.desktop.books

import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
        withTree("graceful") { tree ->
            val result = async { OwnedBookProcessTransport(tree.command, 30_000).complete("synthetic") }
            tree.awaitReady()
            result.cancel()
            withTimeout(8000) { result.join() }
            assertTrue(tree.adapterCleanedUp(), "adapter SIGTERM cleanup did not finish")
            tree.assertStopped()
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

    private suspend fun withTree(mode: String, block: suspend (Tree) -> Unit) {
        val tree = Tree(mode)
        try {
            block(tree)
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
