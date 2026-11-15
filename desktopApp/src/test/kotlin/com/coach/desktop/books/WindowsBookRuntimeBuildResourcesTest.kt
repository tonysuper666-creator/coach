package com.coach.desktop.books

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Exercises the actual main classpath after Gradle's absent/withdrawn-input build. No test trust root. */
class WindowsBookRuntimeBuildResourcesTest {
    @Test fun absentBuildResourcesStayUnavailableBeforeAuthenticationOrLaunch() = checkUnavailable(false)

    @Test fun untrustedResourceDirectoryCannotEnableTheOrdinaryLoader() = checkUnavailable(true)

    private fun checkUnavailable(untrustedDirectory: Boolean) = runBlocking {
        assertNull(WindowsBookRuntime::class.java.getResource("/windows-book-runtime-trust.der"),
            "Run this check without approved build inputs; no test key belongs in the main classpath")
        val previous = System.getProperty("compose.application.resources.dir")
        val directory = Files.createTempDirectory("coach-build-resources-test-")
        try {
            if (untrustedDirectory) {
                val runtime = Files.createDirectories(directory.resolve("resources/windows-book-runtime"))
                Files.writeString(runtime.resolve("manifest.json"), "{}")
                Files.write(runtime.resolve("manifest.sig"), ByteArray(64))
                System.setProperty("compose.application.resources.dir", runtime.parent.toString())
            } else System.clearProperty("compose.application.resources.dir")
            // Use installed(), exactly as the ordinary host does. Never pass an injected runtime/key.
            val runtime = WindowsBookRuntime.installed()
            assertNull(runtime)
            val authReads = AtomicInteger()
            val launches = AtomicInteger()
            val support = isolatedWindowsBookLearningSupport(TextbookLibrary(directory.resolve("empty-library")), runtime,
                authSource = { authReads.incrementAndGet(); error("Authentication must not be resolved") },
                evidence = WindowsBookRuntimeEvidence(receipt = { _, _ -> launches.incrementAndGet() }))
            assertIs<BookLearningReply.Unavailable>(support.explain(BookExplanationRequest("SYNTHETIC", "SYNTHETIC", emptyList())))
            assertIs<BookLearningReply.Unavailable>(support.ask(BookPassageQuestion(
                BookPassage("SYNTHETIC", 1, 0, 1, "X"), "SYNTHETIC?")))
            assertEquals(0, authReads.get())
            assertEquals(0, launches.get())
        } finally {
            if (previous == null) System.clearProperty("compose.application.resources.dir")
            else System.setProperty("compose.application.resources.dir", previous)
            // Only the isolated directory created above; no user library was opened.
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
