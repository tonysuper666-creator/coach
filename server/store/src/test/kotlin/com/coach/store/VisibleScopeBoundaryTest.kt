package com.coach.store

import com.coach.domain.scope.VisibleScope
import java.nio.file.Files
import kotlin.test.*

/** Synthetic material only. Verifies out-of-scope retrieval is refused and that no retrieval call bypasses the scope type. */
class VisibleScopeBoundaryTest {
    private fun store() = LedgerStore(Files.createTempDirectory("scope"))
    private fun seed(s: LedgerStore) {
        s.registerSourcePage("c", "book", "Synthetic book", 5, "Alpha tradeoff lesson one", learnerVisible = true)
        s.registerSourcePage("c", "book", "Synthetic book", 50, "Alpha tradeoff lesson later chapter", learnerVisible = true)
        s.registerSourcePage("c", "quiz-answers", "Synthetic quiz answers", 1, "Alpha tradeoff the correct choice is B", learnerVisible = false)
        s.registerSourcePage("c", "heldout", "Synthetic sealed set", 1, "Alpha tradeoff held out item", learnerVisible = false)
    }
    private fun docs(l: List<SourcePage>) = l.map { it.docId + ":" + it.pdfPage }.toSet()

    @Test fun outOfScopeRefused() {
        val s = store(); seed(s)
        val learner = VisibleScope.learner().narrowToPages("book", 1..10)
        val hits = docs(s.searchSourcePages("c", "alpha tradeoff", learner))
        assertEquals(setOf("book:5"), hits)                         // legal content still works
        assertTrue(s.searchSourcePages("c", "alpha tradeoff", VisibleScope.NONE).isEmpty())
        assertTrue(s.searchSourcePages("c", "correct choice", VisibleScope.learner().narrowToDocs("quiz-answers")).isEmpty())
        assertTrue(s.searchSourcePages("c", "held out", VisibleScope.learner().narrowToDocs("heldout")).isEmpty())
    }
    @Test fun forgedWideningNarrowsAnyway() {
        val s = store(); seed(s)
        val forged = VisibleScope.learner().narrowToPages("book", 1..10).narrow(VisibleScope.tutorInternal().narrowToPages("book", 1..999))
        assertEquals(setOf("book:5"), docs(s.searchSourcePages("c", "alpha tradeoff", forged)))
    }
    @Test fun everyRetrievalCallSitePassesAScope() {
        var dir = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(dir.resolve("settings.gradle.kts"))) dir = dir.parent
        val files = Files.walk(dir.resolve("server")).use { w -> w.toList() }
            .filter { it.toString().endsWith(".kt") && it.toString().replace(java.io.File.separatorChar, '/').contains("/src/main/") }
        val calls = ArrayList<String>()
        for (f in files) for (line in Files.readAllLines(f)) {
            if (line.contains("searchSourcePages(") && !line.contains("fun searchSourcePages")) calls.add(f.fileName.toString() + " | " + line)
        }
        assertTrue(calls.size >= 4, "expected the known call sites, found " + calls.size)
        for (c in calls) assertTrue(c.contains("VisibleScope") || c.contains("scope"), "bypasses VisibleScope: " + c)
    }
}
