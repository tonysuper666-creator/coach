package com.coach.desktop.books

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Architect's after-exec probes, with the residue/attribute outcomes made strict regressions. */
class WindowsBookCleanupBoundaryTest {
    private suspend fun support(native: SyntheticIsolatedCodex) =
        ConsentedBookLearningSupport(native.fixture.library, native.fixture.store, native.transport()).also {
            it.grant(it.consentOffer(native.fixture.a.id), OwnedBookAction.entries.toSet())
        }
    private fun readonly(path: Path) = Files.getAttribute(path, "dos:readonly", NOFOLLOW_LINKS) as Boolean
    private fun afterExec(native: SyntheticIsolatedCodex, inject: (WindowsBookRun) -> Unit): () -> Boolean {
        var injected = false
        native.beforePhaseReceipt = { receipt ->
            if (receipt.phase == "exec" && receipt.exit == 0 && !injected) {
                inject(native.runs.single()); injected = true
            }
        }
        return { injected }
    }
    private fun junction(link: Path, target: Path) {
        val process = ProcessBuilder(Path.of(System.getenv("SystemRoot"), "System32/cmd.exe").toString(),
            "/d", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, process.exitValue())
    }
    private fun release(native: SyntheticIsolatedCodex, vararg paths: Path) {
        // Test-only recovery keeps even the old-code red controls free of synthetic residues.
        paths.filter { Files.exists(it, NOFOLLOW_LINKS) }.forEach { Files.setAttribute(it, "dos:readonly", false, NOFOLLOW_LINKS) }
        native.runs.forEach { run ->
            val root = run.work.parent
            if (Files.exists(root, NOFOLLOW_LINKS)) discardIsolatedBookRun(root, root.toRealPath())
        }
    }

    @Test fun readonlyDirectoryAfterExecLeavesNoRunRoot() = runBlocking {
        val native = SyntheticIsolatedCodex(OwnedBookConsentTest.Fixture(), "success")
        var directory: Path? = null
        val injected = afterExec(native) { run ->
            directory = Files.createDirectory(run.work.resolve("SYNTHETIC-rodir"))
            Files.writeString(directory!!.resolve("x.txt"), "SYNTHETIC")
            Files.setAttribute(directory!!, "dos:readonly", true, NOFOLLOW_LINKS)
            assertTrue(readonly(directory!!))
        }
        try {
            assertIs<BookLearningReply.Text>(support(native).explain(native.fixture.explain()))
            assertTrue(injected()); native.clean()
            assertContentEquals(native.original, Files.readAllBytes(native.auth))
            println("SYNTHETIC_CLEANUP_RODIR injected=true run_root_left=false rodir_left=false home_left=false original_preserved=true")
        } finally { release(native, *listOfNotNull(directory).toTypedArray()) }
    }

    @Test fun hardlinkToOutsideReadonlyFileKeepsItsAttributes() = runBlocking {
        val native = SyntheticIsolatedCodex(OwnedBookConsentTest.Fixture(), "success")
        val victim = Files.writeString(native.root.resolve("SYNTHETIC-hl-victim.txt"), "SYNTHETIC_HL_KEEP")
        Files.setAttribute(victim, "dos:readonly", true, NOFOLLOW_LINKS)
        val injected = afterExec(native) { run -> Files.createLink(run.work.resolve("SYNTHETIC-hl.txt"), victim) }
        try {
            assertIs<BookLearningReply.Text>(support(native).explain(native.fixture.explain()))
            assertTrue(injected())
            assertEquals("SYNTHETIC_HL_KEEP", Files.readString(victim))
            assertTrue(readonly(victim), "Deleting our hardlink must not clear the outside file's readonly bit")
            native.clean(); assertContentEquals(native.original, Files.readAllBytes(native.auth))
            println("SYNTHETIC_CLEANUP_HARDLINK injected=true victim_content_kept=true victim_readonly_after=true run_root_left=false")
        } finally { release(native, victim) }
    }

    @Test fun junctionToOutsideReadonlyFileKeepsItsAttributes() = runBlocking {
        val native = SyntheticIsolatedCodex(OwnedBookConsentTest.Fixture(), "success")
        val victim = Files.createDirectory(native.root.resolve("SYNTHETIC-j-victim"))
        val keep = Files.writeString(victim.resolve("KEEP.txt"), "SYNTHETIC_J_KEEP")
        Files.setAttribute(keep, "dos:readonly", true, NOFOLLOW_LINKS)
        val injected = afterExec(native) { run -> junction(run.work.resolve("SYNTHETIC-j"), victim) }
        try {
            assertIs<BookLearningReply.Text>(support(native).explain(native.fixture.explain()))
            assertTrue(injected()); assertEquals("SYNTHETIC_J_KEEP", Files.readString(keep))
            assertTrue(readonly(keep)); native.clean()
            println("SYNTHETIC_CLEANUP_JUNCTION injected=true victim_content_kept=true victim_readonly_after=true run_root_left=false")
        } finally { release(native, keep) }
    }

    @Test fun nativeCleanupRejectsAReparseHandleAndWrongPinnedPath() {
        val root = Files.createTempDirectory("SYNTHETIC-native-cleanup-").toRealPath()
        val victim = Files.createDirectory(root.resolve("SYNTHETIC-victim"))
        val keep = Files.writeString(victim.resolve("KEEP.txt"), "SYNTHETIC_KEEP")
        val link = root.resolve("SYNTHETIC-link")
        Files.setAttribute(keep, "dos:readonly", true, NOFOLLOW_LINKS)
        junction(link, victim)
        try {
            assertFailsWith<IOException> { WindowsReadonlyBookEntry.delete(link, link, true) }
            assertTrue(Files.exists(link, NOFOLLOW_LINKS))
            assertFailsWith<IOException> { WindowsReadonlyBookEntry.delete(keep, root.resolve("SYNTHETIC-wrong-name"), false) }
            assertEquals("SYNTHETIC_KEEP", Files.readString(keep)); assertTrue(readonly(keep))
            println("SYNTHETIC_CLEANUP_HANDLE reparse=refused wrong_path=refused victim_readonly_after=true")
        } finally {
            Files.delete(link)
            Files.setAttribute(keep, "dos:readonly", false, NOFOLLOW_LINKS)
            discardIsolatedBookRun(root, root)
        }
    }
}
