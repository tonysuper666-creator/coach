package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.model.MultipleFailureException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Optional public-corpus acceptance. Each book reports independently, even if another book fails. */
@RunWith(Parameterized::class)
class OpenStaxExamDrillTest(private val slug: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun books() = listOf("sociology-2e", "psychology", "intro-statistics", "economics-2e", "college-physics")
    }

    @Test
    fun fiveStandaloneBooksHavePageBackedPoints() = checkBook(live = false)

    @Test
    fun fiveCheckedQuestionsFromEachStandaloneBook() = checkBook(live = true)

    private fun checkBook(live: Boolean): Unit = runBlocking {
        val corpus = System.getenv("COACH_OPENSTAX_CORPUS")
        assumeTrue(!corpus.isNullOrBlank())
        val explainer = if (live) {
            assumeTrue(System.getenv("COACH_OPENSTAX_QA_LIVE") == "1")
            require(!System.getenv("COACH_CLIENT_DIR").isNullOrBlank() && !System.getenv("COACH_URL").isNullOrBlank())
            BookExplainer().also { it.load(); check(it.identity != null) { "QA pairing required" } }
        } else null
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
            val failures = mutableListOf<Throwable>()
            val sample = points.shuffled(Random(1961)).take(5)
            var checked = 0
            for ((index, point) in sample.withIndex()) {
                val label = "$slug sample ${index + 1} (${point.title})"
                try {
                    val request = CourseAutoAligner.buildTopicPackRequest(detail, point, pages)
                    assertTrue(request.getValue("blocks").jsonArray.isNotEmpty(), "$label has no evidence")
                    if (explainer == null) continue
                    val reply = explainer.examPointDrill(request)
                    val body = assertNotNull(reply.body, "$label request failed (${reply.trouble}, ${reply.code})")
                    val drill = assertNotNull(ExamPointDrillLocal.decode(body), "$label returned a malformed drill")
                    val verified = ExamPointDrillLocal.verify(drill, pages)
                    assertTrue(verified.choices.isNotEmpty(), "$label returned zero verified choices (${verified.discards})")
                    assertTrue(verified.checkerModel.startsWith("gpt-") && verified.generatorModel.startsWith("claude-haiku"))
                    for (choice in verified.choices) {
                        assertTrue(choice.options.size == 4 && choice.options.count { it.correct } == 1)
                        assertTrue(choice.options.all { it.citation.bookId == book.id && it.reason.isNotBlank() })
                    }
                    checked += verified.choices.size
                } catch (failure: AssertionError) {
                    failures += failure
                }
            }
            println("$slug points=${points.size}, sampled=${sample.size}, failed=${failures.size}, liveChecked=$checked")
            MultipleFailureException.assertEmpty(failures)
            if (live) assertTrue(checked >= sample.size, "$slug has $checked verified choices")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
