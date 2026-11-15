package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Synthetic fixtures only; the outside hardlink belongs to the same temporary test tree. */
class WindowsReadonlyBookLongPathTest {
    private fun deep(root: Path): Path {
        var path = root
        repeat(4) { path = Files.createDirectory(path.resolve("segment${it}_" + "a".repeat(70))) }
        assertTrue(path.toString().length > 260)
        return path
    }

    @Test fun longReadonlyHardlinkKeepsOutsideFileAndRemovesRun() {
        val root = Files.createTempDirectory("synthetic-longpath-").toRealPath()
        val run = Files.createDirectory(root.resolve("run"))
        val victim = Files.writeString(root.resolve("outside.txt"), "SYNTHETIC_KEEP")
        val link = deep(run).resolve("readonly-link.txt")
        Files.createLink(link, victim)
        Files.setAttribute(victim, "dos:readonly", true, NOFOLLOW_LINKS)
        try {
            assertFailsWith<IOException> { WindowsReadonlyBookEntry.delete(link, link.resolveSibling("wrong.txt"), false) }
            assertFailsWith<IOException> { WindowsReadonlyBookEntry.delete(link, link, true) }
            try { WindowsReadonlyBookEntry.delete(link, link, false) }
            catch (e: Exception) { println("LONG_FILE_RED path_length=${link.toString().length} error=${e.message}"); throw e }
            assertEquals("SYNTHETIC_KEEP", Files.readString(victim))
            assertEquals(true, Files.getAttribute(victim, "dos:readonly", NOFOLLOW_LINKS))
            discardIsolatedBookRun(run, run)
            assertFalse(Files.exists(run, NOFOLLOW_LINKS))
            println("LONG_FILE_GREEN path_length=${link.toString().length} run_root_left=false outside_content_preserved=true outside_readonly=true")
        } finally {
            Files.setAttribute(victim, "dos:readonly", false, NOFOLLOW_LINKS)
            if (Files.exists(run, NOFOLLOW_LINKS)) discardIsolatedBookRun(run, run)
            Files.delete(victim)
            Files.delete(root)
        }
    }

    @Test fun longReadonlyDirectoryRemovesRun() {
        val root = Files.createTempDirectory("synthetic-longpath-").toRealPath()
        val run = Files.createDirectory(root.resolve("run"))
        val dir = deep(run)
        Files.setAttribute(dir, "dos:readonly", true, NOFOLLOW_LINKS)
        try {
            try { WindowsReadonlyBookEntry.delete(dir, dir, true) }
            catch (e: Exception) { println("LONG_DIR_RED path_length=${dir.toString().length} error=${e.message}"); throw e }
            discardIsolatedBookRun(run, run)
            assertFalse(Files.exists(run, NOFOLLOW_LINKS))
            println("LONG_DIR_GREEN path_length=${dir.toString().length} run_root_left=false")
        } finally {
            if (Files.exists(dir, NOFOLLOW_LINKS)) Files.setAttribute(dir, "dos:readonly", false, NOFOLLOW_LINKS)
            if (Files.exists(run, NOFOLLOW_LINKS)) discardIsolatedBookRun(run, run)
            Files.delete(root)
        }
    }

    @Test fun extendedPathSyntaxHandlesLocalAndUncWithoutNetworkAccess() {
        assertEquals("\\\\?\\C:\\long\\file", extendedWindowsBookPath("C:\\long\\file"))
        assertEquals("\\\\?\\UNC\\server\\share\\file", extendedWindowsBookPath("\\\\server\\share\\file"))
        assertEquals("\\\\?\\C:\\long\\file", extendedWindowsBookPath("\\\\?\\C:\\long\\file"))
        assertEquals("\\\\?\\UNC\\server\\share\\file", extendedWindowsBookPath("\\\\?\\UNC\\server\\share\\file"))
        assertFailsWith<IOException> { extendedWindowsBookPath("\\\\.\\device") }
        assertFailsWith<IOException> { extendedWindowsBookPath("relative\\file") }
    }

    @Test fun reparseObjectIsRefusedWithoutTouchingItsTarget() {
        val root = Files.createTempDirectory("synthetic-longpath-reparse-").toRealPath()
        val target = Files.createDirectory(root.resolve("target"))
        val marker = Files.writeString(target.resolve("outside.txt"), "SYNTHETIC_KEEP")
        val link = root.resolve("junction")
        val command = listOf(Path.of(System.getenv("SystemRoot"), "System32/cmd.exe").toString(),
            "/d", "/c", "mklink", "/J", link.toString(), target.toString())
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertFailsWith<IOException> { WindowsReadonlyBookEntry.delete(link, link, true) }
            assertTrue(Files.exists(link, NOFOLLOW_LINKS))
            assertEquals("SYNTHETIC_KEEP", Files.readString(marker))
            println("LONG_SPECIAL reparse_refused=true target_content_preserved=true")
        } finally {
            if (Files.exists(link, NOFOLLOW_LINKS)) Files.delete(link)
            Files.delete(marker)
            Files.delete(target)
            Files.delete(root)
        }
    }
}
