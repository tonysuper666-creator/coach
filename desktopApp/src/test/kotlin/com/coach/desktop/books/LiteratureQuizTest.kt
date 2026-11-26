package com.coach.desktop.books

import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class LiteratureQuizTest {
    private val questions = listOf(
        "样地植物生长有何记录？",
        "遮阴是否让样本长得更快？",
        "不同样地的开花植物如何被记录？",
        "样本观察说明了什么生长差异？",
        "光照条件与生长速度有何关系？",
    )

    @Test fun repairSucceedsPersistsAndEntersWrongAnswerReview() {
        val root = Files.createTempDirectory("coach-quiz-repair-ok-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val imported = (1..3).map { library.importBook(LiteratureGuideFixture.pdf(root, it)) }
            val course = library.withCourseDatabase { db ->
                CourseStore.createCourse(db, "选择题通过", CourseExamProfile(), imported.map { it.id }).first
            }
            val input = LiteratureInput(imported.map { library.literatureBook(it.id) }, questions)
            val ocr = CachingOcrVerifier { book, page -> library.ocrPdfPageForGuide(book.id, page) }
            val initial = LiteratureQuiz.request(input)
            assertTrue(initial.json.toString().toByteArray().size <= 79_000)
            assertEquals("literatureQuiz", initial.json["kind"]?.jsonPrimitive?.content)
            val firstReply = quizJson(questions.size, List(4) { 2 } + 1)
            val first = LiteratureQuiz.verify(initial.pool, firstReply, ocr)
            assertNull(LiteratureQuiz.resolve(input, first, null).saved)
            assertTrue(first.ocrChecks.isNotEmpty() && first.ocrChecks.all { it.passed })
            val repair = assertNotNull(LiteratureQuiz.repairRequest(input, first))
            assertEquals(setOf(4), repair.slots)
            val second = LiteratureQuiz.verify(repair.pool, quizJson(questions.size, listOf(0, 0, 0, 0, 1), nthOffset = 1), ocr, repair.slots)
            assertTrue(second.failures.isEmpty(), second.failures.joinToString { "${it.rule}:${it.detail}" })
            val saved = assertNotNull(LiteratureQuiz.resolve(input, first, second).saved)
            assertTrue(saved.items.size in 10..15)
            questions.indices.forEach { index -> assertTrue(saved.items.count { it.questionIndex == index } >= 2) }
            val quotes = (initial.pool + repair.pool).map { it.anchor.quoteAnchor }.toSet()
            assertTrue(saved.items.flatMap { it.options }.flatMap { it.sources }.all { it.anchor.quoteAnchor in quotes })
            library.withCourseDatabase { LiteratureQuizStore.save(it, course, saved) }
            val restored = LiteratureQuiz.restore(
                library.withCourseDatabase { LiteratureQuizStore.cached(it, course, saved.digest) }!!,
                input.books, questions, saved.digest,
            )
            assertEquals(saved.items.size, assertNotNull(restored).items.size)
            LiteratureQuizBank.commit(library, saved, 0, saved.items[0].options.first { !it.correct }.text, guessed = false)
            assertTrue(library.reviews().any { it.lesson.endsWith("-0") && !it.completed && it.practice.prompt == saved.items[0].stem })
            LiteratureQuizBank.commit(library, saved, 1, saved.items[1].options.first { it.correct }.text, guessed = false)
            assertTrue(library.reviews().none { it.lesson.endsWith("-1") && !it.completed })
            LiteratureQuizBank.commit(library, saved, 2, null, guessed = true)
            assertTrue(library.reviews().any { it.originalAnswer == "不确定" })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun repairFailureDoesNotPersistAndNamesTheShortQuestion() {
        val root = Files.createTempDirectory("coach-quiz-repair-fail-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val imported = (1..3).map { library.importBook(LiteratureGuideFixture.pdf(root, it)) }
            val course = library.withCourseDatabase { db ->
                CourseStore.createCourse(db, "选择题不足", CourseExamProfile(), imported.map { it.id }).first
            }
            val input = LiteratureInput(imported.map { library.literatureBook(it.id) }, questions)
            val key = LiteratureGuideStore.digest(input.request())
            val ocr = CachingOcrVerifier { book, page -> library.ocrPdfPageForGuide(book.id, page) }
            val initial = LiteratureQuiz.request(input)
            val first = LiteratureQuiz.verify(initial.pool, quizJson(questions.size, List(4) { 2 } + 1), ocr)
            val repair = assertNotNull(LiteratureQuiz.repairRequest(input, first))
            val second = LiteratureQuiz.verify(repair.pool, buildJsonObject { put("items", JsonArray(emptyList())) }, ocr, repair.slots)
            val resolution = LiteratureQuiz.resolve(input, first, second)
            assertNull(resolution.saved)
            val gaps = resolution.gaps.joinToString("\n")
            assertTrue("思考题 5" in gaps && "还缺" in gaps && "没有保存" in gaps, gaps)
            assertNull(library.withCourseDatabase { LiteratureQuizStore.cached(it, course, key) })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun rejectsCopiedQuoteLeakedRefMetadataStemAndNonBodyCitation() {
        val root = Files.createTempDirectory("coach-quiz-reject-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val imported = (1..3).map { library.importBook(LiteratureGuideFixture.pdf(root, it)) }
            val input = LiteratureInput(imported.map { library.literatureBook(it.id) }, questions)
            val request = LiteratureQuiz.request(input)
            val ocr = CachingOcrVerifier { book, page -> book.page(page)?.text.orEmpty() }
            val quote = request.pool.first().anchor.quoteAnchor
            val copied = LiteratureQuiz.verify(request.pool, oneItem(0, "请判断这句之后说明了什么：$quote"), ocr)
            assertEquals("copied_quote", copied.failures.single().rule)
            val leaked = LiteratureQuiz.verify(request.pool, oneItem(0, "见片段 2 之后，哪一项更贴近正文记录？"), ocr)
            assertEquals("leaked_ref", leaked.failures.single().rule)
            val metadata = LiteratureQuiz.verify(request.pool, oneItem(0, "这篇文献的参考文献列在什么位置？"), ocr)
            assertEquals("metadata_stem", metadata.failures.single().rule)
            val good = LiteratureQuiz.verify(request.pool, oneItem(0, "关于思考题1的第1个判断，哪一项更贴近正文记录？"), ocr)
            assertTrue(good.failures.isEmpty(), good.failures.joinToString { it.rule })

            val bibliography = "Smith, Plants, (London, 1990), pp. 12-14."
            val book = Textbook("bib", "Bib", "pdf", listOf(BookPage(1, bibliography)))
            val excerpt = GuideExcerpt(book, BookCompanionAnchor(1, "bib", 0, bibliography.length, bibliography), GuideRegion(bibliography))
            val cited = LiteratureQuiz.verify(listOf(excerpt), oneItem(0, "关于这条脚注，哪一项更贴近正文记录？"), ocr)
            assertEquals("not_body_sentence", cited.failures.single().rule)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun quizJson(questionCount: Int, counts: List<Int>, nthOffset: Int = 0): JsonObject = buildJsonObject {
        put("items", buildJsonArray {
            for (question in 0 until questionCount) repeat(counts.getOrElse(question) { 0 }) { nth ->
                add(quizItem(question, nth + nthOffset))
            }
        })
    }

    private fun oneItem(question: Int, stem: String): JsonObject = buildJsonObject {
        put("items", buildJsonArray { add(quizItem(question, 0, stem)) })
    }

    private fun quizItem(question: Int, nth: Int, stem: String = "关于思考题${question + 1}的第${nth + 1}个判断，哪一项更贴近正文记录？") =
        buildJsonObject {
            put("questionIndex", question)
            put("stem", stem)
            put("options", buildJsonArray {
                listOf(
                    Triple("生长记录支持这一判断。", true, "正确项对应正文里的观察记录。"),
                    Triple("样本观察与这一判断相反。", false, "此项把观察说成了相反结论。"),
                    Triple("保管流程解释了这一判断。", false, "此项改写成保管流程。"),
                    Triple("目录登记解释了这一判断。", false, "此项改写成目录登记。"),
                ).forEach { (text, correct, why) ->
                    add(buildJsonObject {
                        put("text", text)
                        put("correct", correct)
                        put("why", why)
                        put("refs", buildJsonArray { add(JsonPrimitive(0)) })
                    })
                }
            })
        }
}
