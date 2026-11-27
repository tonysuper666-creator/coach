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

    @Test fun repairReplyMissingItemsIsInvalidJsonAndEmptyArrayIsNot() {
        val input = handInput()
        val request = LiteratureQuiz.request(input)
        val shape = request.json.getValue("replyShape").jsonPrimitive.content
        assertTrue("items" in shape && "stem" in shape, shape)
        val ocr = pageTextOcr()
        val repairs = buildJsonObject {
            put("repairs", buildJsonArray {
                add(buildJsonObject { put("question", "样地里记录了什么？") })
            })
        }
        val broken = LiteratureQuiz.verify(request.pool, repairs, ocr)
        assertEquals("invalid_json", broken.failures.single().rule)
        assertTrue(broken.items.isEmpty())
        val gaps = LiteratureQuiz.resolve(input, broken, null).gaps.joinToString("\n")
        assertNull(LiteratureQuiz.resolve(input, broken, null).saved)
        assertTrue("invalid_json" in gaps && "没有保存" in gaps, gaps)
        val empty = LiteratureQuiz.verify(
            request.pool,
            buildJsonObject { put("items", JsonArray(emptyList())) },
            ocr,
        )
        assertTrue(empty.failures.none { it.rule == "invalid_json" })
        val emptyGaps = LiteratureQuiz.resolve(input, empty, null).gaps.joinToString("\n")
        assertTrue("invalid_json" !in emptyGaps && "没有保存" in emptyGaps, emptyGaps)
        val repair = assertNotNull(LiteratureQuiz.repairRequest(input, empty))
        val repairShape = repair.json.getValue("replyShape").jsonPrimitive.content
        assertTrue("items" in repairShape && "stem" in repairShape, repairShape)
    }

    @Test fun evasiveWordingOnWrongOptionsIsAllowed() {
        val pool = listOf(excerpt("Plot one contained flowering plants during the survey."))
        val ocr = pageTextOcr()
        val stem = "关于样地植物，哪一项更贴近正文记录？"
        val allowed = LiteratureQuiz.verify(
            pool,
            choice(
                stem,
                wrongText = "材料只提到收藏策略。",
                wrongWhy = "正文记录的是样地开花植物。原文没有提及收藏策略。",
            ),
            ocr,
        )
        assertTrue(allowed.failures.isEmpty(), allowed.failures.joinToString { it.rule })
        val stemHit = LiteratureQuiz.verify(pool, choice("原文没有提及这类论述，哪一项更贴近？"), ocr)
        assertEquals("stem_substantive", stemHit.failures.single().rule)
        val correctHit = LiteratureQuiz.verify(
            pool,
            choice(stem, correctText = "原文没有提及后续标准。"),
            ocr,
        )
        assertEquals("option_substantive", correctHit.failures.single().rule)
    }

    @Test fun correctOptionComparisonMustAppearInTheSource() {
        val unsupported = LiteratureQuiz.verify(
            listOf(excerpt("Cataloguing the collection costs money and is an investment.")),
            choice(
                "登记和研究哪一件更要紧？",
                correctText = "登记与研究同样是一项投资。",
                correctWhy = "出处写了编目要花钱。",
            ),
            pageTextOcr(),
        )
        assertEquals("unsupported_claim", unsupported.failures.single().rule)
        val supported = LiteratureQuiz.verify(
            listOf(excerpt("登记与研究同样是一项投资，编目保存也要花钱。")),
            choice(
                "登记和研究哪一件更要紧？",
                correctText = "登记与研究同样是一项投资。",
                correctWhy = "与原文一致。",
            ),
            pageTextOcr(),
        )
        assertTrue(supported.failures.isEmpty(), supported.failures.joinToString { "${it.rule}:${it.detail}" })
        val paraphrase = LiteratureQuiz.verify(
            listOf(excerpt("Cataloguing the collection costs money and is an investment.")),
            choice(correctText = "编目保存需要花钱。", correctWhy = "出处写了这项花费。"),
            pageTextOcr(),
        )
        assertTrue(paraphrase.failures.isEmpty(), paraphrase.failures.joinToString { "${it.rule}:${it.detail}" })
    }

    @Test fun wrongOptionDenialMustNotContradictTheSource() {
        val stem = "这里说的衰落用的是什么标准？"
        val contradictory = LiteratureQuiz.verify(
            listOf(excerpt("生长记录支持这一判断，评价也按后来博物馆的标准。")),
            choice(
                stem,
                correctWhy = "记录相符。",
                wrongText = "按后来博物馆的标准。",
                wrongWhy = "原文没有这类论述。",
            ),
            pageTextOcr(),
        )
        assertEquals("contradicts_source", contradictory.failures.single().rule)
        val english = LiteratureQuiz.verify(
            listOf(excerpt("Plot one contained flowering plants during the survey.")),
            choice(stem, wrongText = "以后来博物馆的分类方式为标准。", wrongWhy = "原文没有以后来博物馆为标准的论述。"),
            pageTextOcr(),
        )
        assertEquals("contradicts_source", english.failures.single().rule)
        val bareWei = LiteratureQuiz.verify(
            listOf(excerpt("Plot one contained flowering plants during the survey.")),
            choice(stem, wrongWhy = "并未讨论后来的博物馆标准。"),
            pageTextOcr(),
        )
        assertEquals("contradicts_source", bareWei.failures.single().rule)
        val restated = LiteratureQuiz.verify(
            listOf(excerpt("Plot one contained flowering plants during the survey.")),
            choice(
                stem,
                wrongText = "按后来博物馆的标准。",
                wrongWhy = "正文记录的是样地里的开花植物。原文没有以后来博物馆为标准的论述。",
            ),
            pageTextOcr(),
        )
        assertTrue(restated.failures.isEmpty(), restated.failures.joinToString { "${it.rule}:${it.detail}" })
    }

    @Test fun repairBecauseNamesWhyThePreviousItemWasRejected() {
        val input = handInput()
        val request = LiteratureQuiz.request(input)
        val tooLong = LiteratureQuiz.verify(
            request.pool,
            choice(correctText = "甲".repeat(87)),
            pageTextOcr(),
        )
        assertEquals("option_length", tooLong.failures.single().rule)
        val repair = assertNotNull(LiteratureQuiz.repairRequest(input, tooLong))
        val slots = repair.json.getValue("repair").jsonObject.getValue("slots").jsonArray.map { it.jsonObject }
        val rejected = slots.first { it.getValue("questionIndex").jsonPrimitive.int == 0 }
            .getValue("because").jsonPrimitive.content
        assertTrue("option_length" in rejected && "80" in rejected && "上次被拒" in rejected, rejected)
        val other = slots.first { it.getValue("questionIndex").jsonPrimitive.int == 1 }
            .getValue("because").jsonPrimitive.content
        assertTrue("上次被拒" !in other, other)
    }

    @Test fun footerNotesStayOutOfThePoolAndFailVerification() {
        val englishNote = "Earlier catalogues recorded the same plants (Hunter, 1989, p. 21)."
        val chineseNote = "编目保存需要继续花钱，这是对将来的一项投资（亨特，1989 年第 21 页）。"
        val onWhomSee = "Several apprentices learned the same beds (on whom see Hunter, The Gardeners, 1989)."
        val seeAlso = "See also the later catalogue of the same plants."
        val seeLead = "See Hunter for the earlier list of the same beds."
        val seeDiscussion = "See the discussion in the earlier estate papers."
        val bibliographyFragment = "Hunter discusses those plants in the earlier catalogue."
        val body = "Plot one contained flowering plants during the survey."
        val books = listOf(
            regionBook("paper-a", listOf(
                body,
                "Sample four showed faster growth under the open sky.",
                "Plot five recorded flowering stems across the field.",
                "Sample six showed steady growth during the dry season.",
                englishNote,
                chineseNote,
                onWhomSee,
            )),
            regionBook("paper-b", listOf(seeAlso, seeLead, seeDiscussion, bibliographyFragment)),
            regionBook("paper-c", listOf("Plot three recorded flowering stems across the open field.")),
        )
        val extracted = books.flatMap { LiteraturePassages.extractCandidates(it) }.map { it.anchor.quoteAnchor }
        assertTrue(extracted.any { "(Hunter, 1989, p. 21)" in it }, extracted.joinToString("\n"))
        assertTrue(extracted.any { "第 21 页" in it }, extracted.joinToString("\n"))
        assertTrue(extracted.any { "on whom see" in it }, extracted.joinToString("\n"))
        assertTrue(extracted.any { "See also" in it }, extracted.joinToString("\n"))
        assertTrue(extracted.any { "earlier catalogue" in it }, extracted.joinToString("\n"))
        val input = LiteratureInput(books, questions)
        val pool = LiteratureQuiz.request(input).pool.map { it.anchor.quoteAnchor }
        assertTrue(pool.none { "(Hunter, 1989, p. 21)" in it || "第 21 页" in it || "on whom see" in it || "See also" in it || "earlier catalogue" in it }, pool.joinToString("\n"))
        assertTrue(pool.any { "flowering plants" in it }, pool.joinToString("\n"))
        val ocr = pageTextOcr()
        val cited = LiteratureQuiz.verify(listOf(excerpt(englishNote)), choice(), ocr)
        assertEquals("footer_note", cited.failures.single().rule)
        val citedChinese = LiteratureQuiz.verify(listOf(excerpt(chineseNote)), choice(), ocr)
        assertEquals("footer_note", citedChinese.failures.single().rule)
        val citedSee = LiteratureQuiz.verify(listOf(excerpt(onWhomSee)), choice(), ocr)
        assertEquals("footer_note", citedSee.failures.single().rule)
    }

    private fun handInput(): LiteratureInput = LiteratureInput(
        listOf(
            regionBook("paper-a", listOf("Plot one contained flowering plants during the survey.")),
            regionBook("paper-b", listOf("Sample two showed faster growth under the shade treatment.")),
            regionBook("paper-c", listOf("Plot three recorded flowering stems across the open field.")),
        ),
        questions,
    )

    private fun regionBook(id: String, sentences: List<String>): Textbook {
        val text = sentences.joinToString("\n")
        return Textbook(id, id, "pdf", listOf(BookPage(1, text, guideRegions = listOf(GuideRegion(text)))))
    }

    private fun excerpt(quote: String): GuideExcerpt {
        val book = Textbook("hand", "Hand", "pdf", listOf(BookPage(1, quote)))
        return GuideExcerpt(book, BookCompanionAnchor(1, "hand", 0, quote.length, quote), GuideRegion(quote))
    }

    private fun pageTextOcr() = CachingOcrVerifier { book, page -> book.page(page)?.text.orEmpty() }

    private fun choice(
        stem: String = "关于思考题1的第1个判断，哪一项更贴近正文记录？",
        correctText: String = "生长记录支持这一判断。",
        correctWhy: String = "正确项对应正文里的观察记录。",
        wrongText: String = "样本观察与这一判断相反。",
        wrongWhy: String = "此项把观察说成了相反结论。",
    ): JsonObject = buildJsonObject {
        put("items", buildJsonArray {
            add(buildJsonObject {
                put("questionIndex", 0)
                put("stem", stem)
                put("options", buildJsonArray {
                    listOf(
                        Triple(correctText, true, correctWhy),
                        Triple(wrongText, false, wrongWhy),
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
            })
        })
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
