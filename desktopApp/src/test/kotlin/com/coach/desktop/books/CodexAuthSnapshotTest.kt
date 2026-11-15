package com.coach.desktop.books

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CodexAuthSnapshotTest {
    private class Fixture : AutoCloseable {
        val root: Path = Files.createTempDirectory("SYNTHETIC-auth-cas-")
        val originalHome: Path = Files.createDirectory(root.resolve("original"))
        val runs: Path = Files.createDirectory(root.resolve("runs"))
        val source: Path = originalHome.resolve("auth.json")
        val original = auth("ORIGINAL")
        init { Files.write(source, original) }
        fun snapshot() = CodexAuthSnapshot.create(source, runs, WindowsAuthAtomicCommit(nativeExecutable))
        fun refresh(snapshot: CodexAuthSnapshot, label: String = "REFRESHED") {
            Files.write(snapshot.home.resolve("auth.json"), auth(label))
        }
        fun intact(expected: ByteArray = original) = assertTrue(Files.readAllBytes(source).contentEquals(expected), "Original bytes must be preserved")
        fun clean() {
            Files.list(runs).use { assertEquals(0, it.count(), "No run homes remain") }
            Files.list(originalHome).use { paths ->
                assertFalse(paths.anyMatch { it.fileName.toString().startsWith(".coach-auth-stage-") || it.fileName.toString().startsWith(".coach-auth-tx-") }, "No staged auth remains")
            }
        }
        override fun close() {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun snapshotContainsOnlyAuthAndDiscardDoesNotWriteBack() = Fixture().use { f ->
        Files.writeString(f.originalHome.resolve("config.toml"), "SYNTHETIC_CONFIG_DO_NOT_COPY")
        Files.createDirectory(f.originalHome.resolve("sessions"))
        Files.writeString(f.originalHome.resolve("history.jsonl"), "SYNTHETIC_HISTORY_DO_NOT_COPY")
        val snapshot = f.snapshot()
        Files.list(snapshot.home).use { assertEquals(listOf("auth.json"), it.map { p -> p.fileName.toString() }.toList()) }
        assertTrue(Files.readAllBytes(snapshot.home.resolve("auth.json")).contentEquals(f.original))
        f.refresh(snapshot)
        snapshot.close()
        snapshot.close()
        assertEquals(AuthCommitStatus.ALREADY_FINISHED, snapshot.commit().status)
        f.intact()
        f.clean()
    }

    @Test fun unchangedCopyDoesNotReplaceTheOriginal() = Fixture().use { f ->
        val before = Files.getLastModifiedTime(f.source)
        val receipt = f.snapshot().commit()
        assertEquals(AuthCommitReceipt(AuthCommitStatus.UNCHANGED, true), receipt)
        assertEquals(before, Files.getLastModifiedTime(f.source))
        f.intact()
        f.clean()
    }

    @Test fun oneRefreshAtomicallyCommitsExactBytesAndCleans() = Fixture().use { f ->
        val before = Files.readAttributes(f.source, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        val acl = Files.getAttribute(f.source, "acl:acl")
        val snapshot = f.snapshot()
        f.refresh(snapshot)
        assertEquals(AuthCommitReceipt(AuthCommitStatus.COMMITTED, true), snapshot.commit())
        f.intact(auth("REFRESHED"))
        assertEquals(before, Files.readAttributes(f.source, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
        assertEquals(acl, Files.getAttribute(f.source, "acl:acl"), "Original ACL is preserved without alteration")
        f.clean()
    }

    @Test fun concurrentSourceChangeIsPreservedRatherThanOverwritten() = Fixture().use { f ->
        val snapshot = f.snapshot()
        f.refresh(snapshot)
        Files.write(f.source, auth("OTHER_PROCESS"))
        assertEquals(AuthCommitReceipt(AuthCommitStatus.SOURCE_CHANGED, true), snapshot.commit())
        f.intact(auth("OTHER_PROCESS"))
        f.clean()
    }

    @Test fun replacementWithEqualBytesIsStillANewSourceVersion() = Fixture().use { f ->
        val snapshot = f.snapshot()
        f.refresh(snapshot)
        val replacement = Files.write(f.originalHome.resolve("new-auth.tmp"), f.original)
        Files.setLastModifiedTime(replacement, java.nio.file.attribute.FileTime.fromMillis(1_000))
        Files.move(replacement, f.source, ATOMIC_MOVE, REPLACE_EXISTING)
        assertEquals(AuthCommitStatus.SOURCE_CHANGED, snapshot.commit().status)
        f.intact()
        f.clean()
    }

    @Test fun twoRunsRacingToFinishCanPublishOnlyOneRefresh() = Fixture().use { f ->
        val first = f.snapshot()
        val second = f.snapshot()
        f.refresh(first, "FIRST_RUN")
        f.refresh(second, "SECOND_RUN")
        val receipts = race({ first.commit() }, { second.commit() })
        assertEquals(1, receipts.count { it.status == AuthCommitStatus.COMMITTED })
        assertTrue(receipts.all { it.cleaned })
        assertTrue(receipts.all { it.status in setOf(AuthCommitStatus.COMMITTED, AuthCommitStatus.WRITER_BUSY, AuthCommitStatus.SOURCE_CHANGED) })
        f.intact(auth(if (receipts[0].status == AuthCommitStatus.COMMITTED) "FIRST_RUN" else "SECOND_RUN"))
        f.clean()
    }

    @Test fun duplicateCompletionCallbacksCannotCommitTwice() = Fixture().use { f ->
        val snapshot = f.snapshot()
        f.refresh(snapshot)
        val receipts = race({ snapshot.commit() }, { snapshot.commit() })
        assertEquals(setOf(AuthCommitStatus.COMMITTED, AuthCommitStatus.ALREADY_FINISHED), receipts.map { it.status }.toSet())
        assertTrue(receipts.all { it.cleaned })
        f.intact(auth("REFRESHED"))
        f.clean()
    }

    @Test fun damagedJsonAndUtf8AreRejectedWithoutChangingOriginal() = Fixture().use { f ->
        for (invalid in listOf("{".toByteArray(), "[]".toByteArray(), "{}".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28), ByteArray(1_048_577) { 32 })) {
            val snapshot = f.snapshot()
            Files.write(snapshot.home.resolve("auth.json"), invalid)
            assertEquals(AuthCommitReceipt(AuthCommitStatus.INVALID_AUTH, true), snapshot.commit())
            f.intact()
            f.clean()
        }
    }

    @Test fun invalidOriginalDoesNotAllocateAHomeOrExposeParserInput() = Fixture().use { f ->
        val broken = "{SYNTHETIC_SECRET_MUST_NOT_APPEAR_IN_EXCEPTION".toByteArray()
        Files.write(f.source, broken)
        val failure = assertFailsWith<AuthSnapshotFailure> { f.snapshot() }
        assertEquals("INVALID_AUTH", failure.message)
        assertNull(failure.cause)
        f.intact(broken)
        f.clean()
    }

    @Test fun atomicReplaceFailureHasNoNonAtomicFallbackAndPreservesOriginal() = Fixture().use { f ->
        for (unsupported in listOf(true, false)) {
            var calls = 0
            val snapshot = CodexAuthSnapshot.create(f.source, f.runs, AuthAtomicReplace { _, _, _, _, _ ->
                calls++
                if (unsupported) throw AtomicMoveNotSupportedException("synthetic", "synthetic", "synthetic failure")
                throw IOException("synthetic replacement failure")
            })
            f.refresh(snapshot)
            assertEquals(AuthCommitReceipt(AuthCommitStatus.ATOMIC_REPLACE_FAILED, true), snapshot.commit())
            assertEquals(1, calls)
            f.intact()
            f.clean()
        }
    }

    @Test fun portableDefaultRefusesRefreshRatherThanUsingUnsafeCheckAndRename() = Fixture().use { f ->
        val snapshot = CodexAuthSnapshot.create(f.source, f.runs)
        f.refresh(snapshot)
        assertEquals(AuthCommitReceipt(AuthCommitStatus.ATOMIC_REPLACE_FAILED, true), snapshot.commit())
        f.intact()
        f.clean()
    }

    @Test fun nativeComparisonRejectsAnUpdateBetweenTheNioCheckAndTransaction() = Fixture().use { f ->
        val backend = WindowsAuthAtomicCommit(nativeExecutable)
        val snapshot = CodexAuthSnapshot.create(f.source, f.runs, AuthAtomicReplace { staged, source, hash, modified, refreshed ->
            Files.write(source, auth("LATE_UNCOOPERATIVE_UPDATE"))
            backend.replace(staged, source, hash, modified, refreshed)
        })
        f.refresh(snapshot)
        assertEquals(AuthCommitReceipt(AuthCommitStatus.SOURCE_CHANGED, true), snapshot.commit())
        f.intact(auth("LATE_UNCOOPERATIVE_UPDATE"))
        f.clean()
    }

    @Test fun shorterRefreshHasNoTrailingOriginalBytes() = Fixture().use { f ->
        val snapshot = f.snapshot()
        val short = """{"tokens":{"access_token":"SYNTHETIC_SHORT"}}""".toByteArray()
        Files.write(snapshot.home.resolve("auth.json"), short)
        assertEquals(AuthCommitReceipt(AuthCommitStatus.COMMITTED, true), snapshot.commit())
        f.intact(short)
        f.clean()
    }

    @Test fun hardLinkedOriginalIsRefusedWithoutModifyingEitherName() = Fixture().use { f ->
        val alias = Files.createLink(f.originalHome.resolve("SYNTHETIC-alias.json"), f.source)
        val snapshot = f.snapshot()
        f.refresh(snapshot)
        assertEquals(AuthCommitReceipt(AuthCommitStatus.ATOMIC_REPLACE_FAILED, true), snapshot.commit())
        f.intact()
        assertTrue(Files.readAllBytes(alias).contentEquals(f.original))
        f.clean()
    }

    @Test fun transactionRollbackPreservesOriginalAfterComparisonWriteAndHandleClose() = Fixture().use { f ->
        for (phase in listOf("compared", "written", "closed")) {
            val snapshot = CodexAuthSnapshot.create(f.source, f.runs, WindowsAuthAtomicCommit(nativeHarness))
            f.refresh(snapshot)
            val marker = Files.writeString(f.originalHome.resolve("SYNTHETIC-fail-$phase"), "SYNTHETIC_FAILURE")
            assertEquals(AuthCommitReceipt(AuthCommitStatus.ATOMIC_REPLACE_FAILED, true), snapshot.commit())
            Files.delete(marker)
            f.intact()
            f.clean()
        }
    }

    @Test fun transactionBlocksAnUncooperativeWriterAfterTheLastComparison() = exerciseBarrier("compared")

    @Test fun transactionRemainsAtomicAndLockedAfterWriteAndHandleClose() = exerciseBarrier("closed")

    private fun exerciseBarrier(phase: String) = Fixture().use { f ->
        Files.writeString(f.originalHome.resolve("SYNTHETIC-pause-$phase"), "SYNTHETIC_BARRIER")
        val snapshot = CodexAuthSnapshot.create(f.source, f.runs, WindowsAuthAtomicCommit(nativeHarness))
        f.refresh(snapshot)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<AuthCommitReceipt> { snapshot.commit() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.exists(f.originalHome.resolve("SYNTHETIC-ready")) && System.nanoTime() < deadline && !future.isDone) Thread.sleep(5)
            assertTrue(Files.exists(f.originalHome.resolve("SYNTHETIC-ready")), "Transactional comparison reached")
            f.intact() // Ordinary readers still see the original, including after transactional write.
            assertFailsWith<IOException> { Files.write(f.source, auth("UNCOOPERATIVE_WRITER")) }
            val replacement = Files.write(f.originalHome.resolve("SYNTHETIC-foreign.tmp"), auth("UNCOOPERATIVE_RENAME"))
            assertFailsWith<IOException> { Files.move(replacement, f.source, ATOMIC_MOVE, REPLACE_EXISTING) }
            Files.writeString(f.originalHome.resolve("SYNTHETIC-release"), "RELEASE")
            assertEquals(AuthCommitReceipt(AuthCommitStatus.COMMITTED, true), future.get(10, TimeUnit.SECONDS))
            f.intact(auth("REFRESHED"))
            f.clean()
        } finally {
            Files.writeString(f.originalHome.resolve("SYNTHETIC-release"), "RELEASE")
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
            snapshot.close()
        }
    }

    @Test fun unknownCommitOutcomeIsTerminalWithoutARetryOrFallback() = Fixture().use { f ->
        var calls = 0
        val snapshot = CodexAuthSnapshot.create(f.source, f.runs, AuthAtomicReplace { _, _, _, _, _ ->
            calls++
            AuthCommitStatus.OUTCOME_UNKNOWN
        })
        f.refresh(snapshot)
        assertEquals(AuthCommitReceipt(AuthCommitStatus.OUTCOME_UNKNOWN, true), snapshot.commit())
        assertEquals(AuthCommitStatus.ALREADY_FINISHED, snapshot.commit().status)
        assertEquals(1, calls)
        f.intact()
        f.clean()
    }

    @Test fun aDeletedOriginalIsNotResurrected() = Fixture().use { f ->
        val snapshot = f.snapshot()
        f.refresh(snapshot)
        Files.delete(f.source)
        assertEquals(AuthCommitReceipt(AuthCommitStatus.SOURCE_CHANGED, true), snapshot.commit())
        assertFalse(Files.exists(f.source))
        f.clean()
    }

    @Test fun concurrentCorruptionIsPreservedByteForByte() = Fixture().use { f ->
        val snapshot = f.snapshot()
        f.refresh(snapshot)
        val foreign = "SYNTHETIC_NON_JSON_FROM_OTHER_WRITER".toByteArray()
        Files.write(f.source, foreign)
        assertEquals(AuthCommitStatus.SOURCE_CHANGED, snapshot.commit().status)
        f.intact(foreign)
        f.clean()
    }

    @Test fun missingCopyFailsClosedAndNestedRunFilesAreCleaned() = Fixture().use { f ->
        val snapshot = f.snapshot()
        Files.createDirectory(snapshot.home.resolve("sessions"))
        Files.writeString(snapshot.home.resolve("sessions/synthetic.txt"), "SYNTHETIC_CHILD_FILE")
        Files.delete(snapshot.home.resolve("auth.json"))
        assertEquals(AuthCommitReceipt(AuthCommitStatus.IO_FAILED, true), snapshot.commit())
        f.intact()
        f.clean()
    }

    @Test fun anotherJvmHoldingTheGuardCausesRefusalAndCleanup() = Fixture().use { f ->
        val snapshot = f.snapshot()
        f.refresh(snapshot)
        val process = holdLock(f, null)
        try {
            val ready = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLine() }.get(20, TimeUnit.SECONDS)
            assertEquals("SYNTHETIC_LOCK_HELD", ready)
            assertEquals(AuthCommitReceipt(AuthCommitStatus.WRITER_BUSY, true), snapshot.commit())
            f.intact()
            f.clean()
            process.outputStream.close()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        } finally {
            if (process.isAlive) process.destroyForcibly()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        }
    }

    @Test fun briefLockHolderInAnotherJvmIsWaitedOutAndRefreshCommits() = Fixture().use { f ->
        val snapshot = f.snapshot()
        f.refresh(snapshot)
        val process = holdLock(f, 1_000)
        try {
            val ready = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLine() }.get(20, TimeUnit.SECONDS)
            assertEquals("SYNTHETIC_LOCK_HELD", ready)
            val started = System.nanoTime()
            val receipt = snapshot.commit()
            val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            println("SYNTHETIC_BRIEF_LOCK hold_ms=1000 waited_ms=$waitedMillis status=${receipt.status} cleaned=${receipt.cleaned}")
            assertEquals(AuthCommitReceipt(AuthCommitStatus.COMMITTED, true), receipt)
            assertTrue(waitedMillis >= 300, "Commit must have waited for the other JVM's lock")
            f.intact(auth("REFRESHED"))
            f.clean()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        } finally {
            if (process.isAlive) process.destroyForcibly()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        }
    }

    @Test fun junctionMadeInsideRunHomeIsUnlinkedWithoutDeletingItsTarget() = Fixture().use { f ->
        val victim = Files.createDirectory(f.root.resolve("SYNTHETIC-victim"))
        val keep = Files.writeString(victim.resolve("KEEP_SYNTHETIC.txt"), "SYNTHETIC_OUTSIDE_HOME")
        for (finish in listOf("commit", "close")) {
            val snapshot = f.snapshot()
            f.refresh(snapshot)
            // A top-level junction and one nested under an ordinary child directory.
            junction(snapshot.home.resolve("link"), victim)
            junction(Files.createDirectory(snapshot.home.resolve("sessions")).resolve("deep"), victim)
            assertTrue(Files.exists(snapshot.home.resolve("link/KEEP_SYNTHETIC.txt")), "Junction must resolve to the victim")
            val cleaned = if (finish == "commit") snapshot.commit().cleaned else { snapshot.close(); true }
            val victimKept = Files.exists(keep) && Files.readString(keep) == "SYNTHETIC_OUTSIDE_HOME"
            println("SYNTHETIC_JUNCTION finish=$finish cleaned=$cleaned victim_file_exists=$victimKept home_exists=${Files.exists(snapshot.home, java.nio.file.LinkOption.NOFOLLOW_LINKS)}")
            assertTrue(cleaned)
            assertTrue(victimKept, "Cleanup must not follow a junction out of the run home")
            assertFalse(Files.exists(snapshot.home, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            f.clean()
        }
        f.intact(auth("REFRESHED")) // Only the commit pass publishes; close() never writes back.
    }

    @Test fun negativeControlShowsUnconditionalCopyLosesConcurrentUpdate() = Fixture().use { f ->
        val unsafeCopy = Files.write(f.runs.resolve("SYNTHETIC-unsafe-auth.json"), auth("STALE_REFRESH"))
        Files.write(f.source, auth("NEW_OTHER_PROCESS"))
        // The old copy-back shape succeeds but destroys the newer login state.
        Files.copy(unsafeCopy, f.source, REPLACE_EXISTING)
        assertFalse(Files.readAllBytes(f.source).contentEquals(auth("NEW_OTHER_PROCESS")), "Negative control must lose the update")
        f.intact(auth("STALE_REFRESH"))
        Files.delete(unsafeCopy)
        f.clean()
        println("SYNTHETIC_UNGUARDED_COPY lost_updates=1; protected_case=concurrentSourceChangeIsPreservedRatherThanOverwritten")
    }

    private fun holdLock(f: Fixture, holdMillis: Long?): Process {
        val fixture = Path.of("src/test/resources/synthetic-auth/HoldAuthLock.java").toAbsolutePath()
        val executable = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val command = mutableListOf(executable.toString(), fixture.toString(), f.originalHome.resolve(".auth.json.coach-refresh.lock").toString())
        if (holdMillis != null) command += holdMillis.toString()
        return ProcessBuilder(command).redirectErrorStream(true).start()
    }

    private fun junction(link: Path, target: Path) {
        val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), "Synthetic junction creation failed")
    }

    private fun race(first: () -> AuthCommitReceipt, second: () -> AuthCommitReceipt): List<AuthCommitReceipt> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(first, second).map { action -> pool.submit<AuthCommitReceipt> { start.await(); action() } }
            start.countDown()
            return futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    companion object {
        private fun auth(label: String) = """{"auth_mode":"chatgpt","tokens":{"access_token":"SYNTHETIC_NOT_A_CREDENTIAL_$label","refresh_token":"SYNTHETIC_REFRESH_$label"},"last_refresh":"SYNTHETIC_$label"}""".toByteArray()
        private val nativeExecutable by lazy { compileNative(false) }
        private val nativeHarness by lazy { compileNative(true) }
        private fun compileNative(harness: Boolean): Path {
            val output = Files.createTempDirectory("SYNTHETIC-auth-native-")
            val executable = output.resolve("CoachAuthCommit.exe")
            val compiler = Path.of(System.getenv("SystemRoot"), "Microsoft.NET", "Framework64", "v4.0.30319", "csc.exe")
            val command = mutableListOf(compiler.toString(), "/nologo", "/target:exe", "/out:$executable",
                "/main:${if (harness) "AuthTransactionHarness" else "CoachAuthCommit"}",
                Path.of("windows-auth/CoachAuthCommit.cs").toAbsolutePath().toString())
            if (harness) command += Path.of("src/test/resources/synthetic-auth/AuthTransactionHarness.cs").toAbsolutePath().toString()
            val log = output.resolve("compile.log")
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue(), "Synthetic native fixture compilation failed")
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            // The fixtures themselves contain no credentials. Clean compiled helpers at JVM exit.
            output.toFile().deleteOnExit()
            log.toFile().deleteOnExit()
            executable.toFile().deleteOnExit()
            return executable
        }
    }
}
