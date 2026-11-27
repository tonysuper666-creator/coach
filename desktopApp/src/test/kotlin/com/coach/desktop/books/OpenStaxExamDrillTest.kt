package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import org.junit.Assume.assumeTrue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Optional public-corpus acceptance. Only the live test calls the explicitly paired QA service. */
class OpenStaxExamDrillTest {
    @Test
    fun fiveStandaloneBooksHavePageBackedPoints() = checkBooks(live = false)

    @Test
    fun fiveCheckedQuestionsFromEachStandaloneBook() = checkBooks(live = true)

    private fun checkBooks(live: Boolean) = runBlocking {
        val corpus = System.getenv("COACH_OPENSTAX_CORPUS")
        assumeTrue(!corpus.isNullOrBlank())
        val explainer = if (live) {
            assumeTrue(System.getenv("COACH_OPENSTAX_QA_LIVE") == "1")
            require(!System.getenv("COACH_CLIENT_DIR").isNullOrBlank() && !System.getenv("COACH_URL").isNullOrBlank())
            BookExplainer().also { it.load(); check(it.identity != null) { "QA pairing required" } }
        } else null
        val titles = listOf("sociology-2e", "psychology", "intro-statistics", "economics-2e", "college-physics")
        for (slug in titles) {
            val directory = Files.createTempDirectory("openstax-exam-")
            try {
                val source = Files.newDirectoryStream(Path.of(corpus, "textbooks", "openstax-$slug"), "*.pdf")
                    .use { it.single() }
                val library = TextbookLibrary(directory, lazyPdfExtras = true)
                val book = library.indexBookTier1(library.stageBookImportTier0(source).book.id)
                val detail = library.withCourseDatabase { db ->
                    val (course, _) = CourseStore.createCourse(db, slug, CourseExamProfile(examEnabled = true), listOf(book.id))
                    CourseAutoAligner.alignLocally(db, course)
                }
                val points = detail.examinableTopics.filter { it.weekLabel == CourseExamPoints.LABEL }
                assertTrue(points.size >= 5, "$slug has ${points.size} exam points")
                val pages = mapOf(book.id to book.pages)
                var checked = 0
                for (point in points.shuffled(Random(1961)).take(5)) {
                    val request = CourseAutoAligner.buildTopicPackRequest(detail, point, pages)
                    assertTrue(request.getValue("blocks").jsonArray.isNotEmpty(), "$slug has a point without evidence")
                    if (explainer != null && checked < 5) {
                        val reply = explainer.examPointDrill(request)
                        val drill = ExamPointDrillLocal.decode(checkNotNull(reply.body) { "$slug drill request failed" })
                        val verified = ExamPointDrillLocal.verify(checkNotNull(drill), pages)
                        assertTrue(verified.choices.isNotEmpty(), "$slug returned zero verified choices")
                        assertTrue(verified.checkerModel.startsWith("gpt-") && verified.generatorModel.startsWith("claude-haiku"))
                        for (choice in verified.choices.take(5 - checked)) {
                            assertTrue(choice.options.size == 4 && choice.options.count { it.correct } == 1)
                            assertTrue(choice.options.all { it.citation.bookId == book.id && it.reason.isNotBlank() })
                            checked++
                        }
                    }
                }
                println("$slug points=${points.size}, sampled=5, liveChecked=$checked")
                if (live) assertTrue(checked == 5, "$slug has $checked verified choices")
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }
}
