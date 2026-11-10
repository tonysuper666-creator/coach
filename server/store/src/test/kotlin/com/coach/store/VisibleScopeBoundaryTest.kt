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
    private fun outline(dir: java.nio.file.Path) = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("coach.sqlite")).use { c ->
        for ((kp, ord, page) in listOf(Triple("k1", 1, 5), Triple("k2", 2, 50)))
            c.prepareStatement("INSERT INTO outline_kp(kp_id,course_id,module_id,ord,title,kind,exam_formats,prereqs,anchors,retired,legacy_id) VALUES(?,?,?,?,?,?,?,?,?,0,NULL)").use {
                it.setString(1, kp); it.setString(2, "c"); it.setString(3, "m"); it.setInt(4, ord); it.setString(5, kp); it.setString(6, "x")
                it.setString(7, "[]"); it.setString(8, "[]"); it.setString(9, """[{"docId":"book","pdfPage":$page}]"""); it.executeUpdate()
            }
    }

    /** Entry-level: the scope each entry builds from the learning record refuses unread chapters. */
    @Test fun learningRecordWindowRefusesUnreadChapter() {
        val dir = Files.createTempDirectory("scope-lr"); val s = LedgerStore(dir); seed(s); outline(dir)
        val atK1 = s.learnedThrough("c", VisibleScope.learner(), "k1")
        assertEquals(setOf("book:5"), docs(s.searchSourcePages("c", "alpha tradeoff", atK1)))        // book:50 (unread) refused
        assertEquals(setOf("book:5", "book:50"), docs(s.searchSourcePages("c", "alpha tradeoff", s.learnedThrough("c", VisibleScope.learner(), "k2"))))
        assertEquals(setOf("book:5"), docs(s.searchSourcePages("c", "alpha tradeoff", s.learnedScope("c", VisibleScope.learner(), setOf("k1")))))
        // forged widening cannot bring the unread page back
        val forged = atK1.narrow(VisibleScope.tutorInternal().narrowToPages("book", 1..999))
        assertEquals(setOf("book:5"), docs(s.searchSourcePages("c", "alpha tradeoff", forged)))
        // fail-closed: no current point / unknown point / nothing learned
        assertTrue(s.searchSourcePages("c", "alpha tradeoff", s.learnedThrough("c", VisibleScope.learner(), null)).isEmpty())
        assertTrue(s.searchSourcePages("c", "alpha tradeoff", s.learnedThrough("c", VisibleScope.learner(), "nope")).isEmpty())
        assertTrue(s.searchSourcePages("c", "alpha tradeoff", s.learnedScope("c", VisibleScope.learner(), emptySet())).isEmpty())
        // answer keys are not learner-visible; the tutor ceiling keeps the pre-existing internal-reference behaviour (no new protection claimed)
        assertTrue(s.searchSourcePages("c", "correct choice", s.learnedThrough("c", VisibleScope.learner(), "k2")).isEmpty())
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
