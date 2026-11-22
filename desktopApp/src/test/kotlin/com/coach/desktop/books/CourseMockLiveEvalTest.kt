package com.coach.desktop.books

import com.coach.client.CourseHttp
import com.coach.desktop.books.exam.LocalExamPaper
import com.coach.desktop.books.exam.LocalExamStore
import com.coach.domain.num
import com.coach.domain.str
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer

/**
 * Opt-in real end-to-end mock exam generation & grading evaluation (`COACH_AI4_LIVE_EVAL=1`)
 * running against local Coach Server -> isolated Coach Bridge (`1x Sonnet 5.5` planner + `Haiku 5.5`
 * item writer, `COACH_API_ONLY=1`) on real `ECNM10119` course materials.
 *
 * Audits zero past-paper bilingual overlap (`en 8-gram == 0 && zh 12-char == 0`), exact rational
 * math accuracy, frozen L1/L2 source anchors, `BookTokenLedger` cost, and `2.0-AI-2` point grading.
 * Emits ONLY sanitized metrics (zero copyrighted text or past-paper snippets) to
 * `~/Coach/state/v2-impl/2.0-AI-4/mock-eval-report.json`.
 */
class CourseMockLiveEvalTest {

    private val enWordRe = Regex("""[a-z0-9]+""")

    private fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        .replace('\u2212', '-')
        .replace('\u2013', '-')
        .replace('\u2014', '-')
        .replace('\u2018', '\'')
        .replace('\u2019', '\'')
        .replace('\u201c', '"')
        .replace('\u201d', '"')
        .replace('\u00a0', ' ')
        .replace("\ufb01", "fi")
        .replace("\ufb02", "fl")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun countOverlaps(generated: List<String>, exclusions: List<String>): Pair<Int, Int> {
        val enSet = HashSet<String>()
        val zhSet = HashSet<String>()
        for (src in exclusions) {
            val words = enWordRe.findAll(normalize(src).lowercase()).map { it.value }.toList()
            if (words.size >= 8) {
                for (i in 0..(words.size - 8)) enSet += words.subList(i, i + 8).joinToString(" ")
            }
            val zh = src.filter { it in '\u4E00'..'\u9FFF' }
            if (zh.length >= 12) {
                for (i in 0..(zh.length - 12)) zhSet += zh.substring(i, i + 12)
            }
        }
        var enHits = 0
        var zhHits = 0
        for (gen in generated) {
            val words = enWordRe.findAll(normalize(gen).lowercase()).map { it.value }.toList()
            if (words.size >= 8) {
                for (i in 0..(words.size - 8)) {
                    if (words.subList(i, i + 8).joinToString(" ") in enSet) enHits++
                }
            }
            val zh = gen.filter { it in '\u4E00'..'\u9FFF' }
            if (zh.length >= 12) {
                for (i in 0..(zh.length - 12)) {
                    if (zh.substring(i, i + 12) in zhSet) zhHits++
                }
            }
        }
        return enHits to zhHits
    }

    @Test
    fun realCourseMockGenerationAuditFreezeAndPointGrading() = runBlocking {
        assumeTrue("Opt-in live eval requires COACH_AI4_LIVE_EVAL=1", System.getenv("COACH_AI4_LIVE_EVAL") == "1")
        val serverUrl = requireNotNull(System.getenv("COACH_AI4_SERVER_URL")).trim()
        val deviceId = requireNotNull(System.getenv("COACH_AI4_DEVICE_ID")).trim()
        val token = requireNotNull(System.getenv("COACH_AI4_TOKEN")).trim()

        val matRoot = Path.of(System.getProperty("user.home"), "Coach/materials/ECNM10119-mcq-src")
        assumeTrue("Course materials directory must exist", Files.isDirectory(matRoot))

        val relPaths = listOf(
            "参考资料/ECNM10119-Module-Handbook-updated.pdf",
            "参考资料/Week1/ECNM10119-Week1-Lecture-Slides.pdf",
            "参考资料/Week2/ECNM10119-Week2-Lecture-Slides.pdf",
            "参考资料/Week4/ECNM10119-Week4-Lecture-Slides.pdf",
            "参考资料/Week4/ECNM10119-Tutorial-Week4.pdf",
            "参考资料/Week5/ECNM10119-Week5-Lecture-Slides.pdf",
            "参考资料/Week5/ECNM10119-Tutorial-Week5-KEY.pdf",
            "参考资料/Week6/ECNM10119-Week6-Lecture-Slides.pdf",
            "参考资料/Bhattacharya-Hyde-Tu-2014-Health-Economics.pdf",
            "课堂录音/Week4-16Oct-转写.md",
        )

        val tmpRoot = Files.createTempDirectory("coach-ai4-live-eval-")
        val http = CourseHttp(serverUrl, deviceId, token, "library")
        try {
            val lib = TextbookLibrary(tmpRoot.resolve("library"), ocr = BookOcr { _, _ -> "" }, lazyPdfExtras = true)
            val importedBooks = relPaths.associateWith { rel ->
                val p = matRoot.resolve(rel)
                require(Files.isRegularFile(p)) { "Missing material: $p" }
                lib.importBook(p)
            }

            val profile = CourseExamProfile(
                examDate = "2026-12-12",
                examDateTentative = true,
                examFormat = "Part I Short Essays (40%) + Part II Analytical & Graphical Problems (60%)",
                examFormatTentative = true,
                durationMinutes = 120,
                closedBook = true,
                calculatorAllowed = "University-approved non-programmable calculator",
                weightingNote = "Coursework 20% + Final Exam 80%",
            )

            val courseId = lib.withCourseDatabase { db ->
                val (cid, rejected) = CourseStore.createCourse(
                    db = db,
                    title = "ECNM10119 Health Economics",
                    examProfile = profile,
                    materialBookIds = importedBooks.values.map { it.id },
                )
                assertEquals(0, rejected.size)
                CourseAutoAligner.alignLocally(db, cid)
                cid
            }

            val detail = lib.courseDetail(courseId)
            val excludedTopicTitle = detail.excludedTopics.firstOrNull()?.title ?: "Excluded Guest Seminar Topic"

            // Collect held-out reference texts strictly under `期末复习/` for anti-leakage overlap verification only.
            val exclusionDir = matRoot.resolve("期末复习")
            val exclusionTexts = if (Files.isDirectory(exclusionDir)) {
                Files.walk(exclusionDir).use { stream ->
                    stream
                        .filter { p -> Files.isRegularFile(p) && (p.toString().endsWith(".md") || p.toString().endsWith(".txt")) }
                        .map { Files.readString(it) }
                        .toList()
                }
            } else emptyList()

            val topicsJson = buildJsonArray {
                for (topic in detail.examinableTopics.take(5)) {
                    val pagesJson = buildJsonArray {
                        var added = 0
                        for (src in topic.sources.filter { !CourseClientGates.isPastExamMaterial(it.bookTitle, null) }.sortedBy { it.authorityRank }) {
                            if (added >= 3) break
                            val bk = lib.book(src.bookId)
                            for (pgNo in src.startPage..src.endPage) {
                                if (added >= 3) break
                                val pg = bk.page(pgNo) ?: continue
                                if (pg.text.isBlank()) continue
                                add(buildJsonObject {
                                    put("bookId", src.bookId)
                                    put("bookTitle", src.bookTitle)
                                    put("role", src.role.wire)
                                    put("authorityRank", src.authorityRank)
                                    put("pdfPage", pg.number)
                                    put("text", pg.text.take(3_600))
                                })
                                added++
                            }
                        }
                    }
                    if (pagesJson.isNotEmpty()) {
                        add(buildJsonObject {
                            put("topicId", topic.topicId)
                            put("weekLabel", topic.weekLabel)
                            put("title", topic.title)
                            put("examStatus", topic.examStatus.wire)
                            put("examWeight", topic.examWeight ?: 1.0)
                            put("pMastery", 0.30)
                            put("dominantErrorTags", buildJsonArray {
                                if (topic.weekNum == 2 || topic.weekNum == 5) add(JsonPrimitive("GRAPH_SHIFT_OR_ROTATION"))
                            })
                            put("sourcePages", pagesJson)
                        })
                    }
                }
            }
            assertTrue("Expected examinable topics with sourcePages", topicsJson.isNotEmpty())

            val genReq = buildJsonObject {
                put("courseId", courseId)
                put("courseTitle", detail.title)
                put("examProfile", buildJsonObject {
                    put("examDate", profile.examDate)
                    put("examFormat", profile.examFormat)
                    put("examFormatTentative", profile.examFormatTentative)
                    put("durationMinutes", profile.durationMinutes)
                    put("closedBook", profile.closedBook)
                    put("calculatorAllowed", profile.calculatorAllowed)
                    put("weightingNote", profile.weightingNote)
                })
                put("examinableTopics", topicsJson)
                put("excludedTopicTitles", buildJsonArray { add(JsonPrimitive(excludedTopicTitle)) })
                put("referenceExclusionTexts", buildJsonArray { exclusionTexts.forEach { add(JsonPrimitive(it.take(35_000))) } })
            }

            val genReply = http.generateCourseMock(genReq)
            assertTrue("Expected successful mock generation, got error=${genReply.error}", genReply.error == null && genReply.body != null)
            val paperBody = checkNotNull(genReply.body)
            val audit = paperBody["audit"]!!.jsonObject
            assertEquals(1, audit["plannerSonnetCalls"]?.jsonPrimitive?.intOrNull)
            assertEquals("true", paperBody["isTentativeFormat"]?.jsonPrimitive?.content)

            val calcChecked = audit["calcSubQuestionsChecked"]?.jsonPrimitive?.intOrNull ?: 0
            val calcAccurate = audit["calcSubQuestionsAccurate"]?.jsonPrimitive?.intOrNull ?: 0
            assertTrue("Expected at least 1 calculation sub-question verified", calcChecked >= 1)
            assertEquals("All calculation sub-questions must pass ExactRationalEvaluator", calcChecked, calcAccurate)

            // Freeze paper locally against imported ECNM10119 PDFs (verifies every docId, pdfPage, and quote!)
            val frozenPaper = LocalExamPaper(paperBody).freeze(lib)
            assertTrue(frozenPaper.isCourseMock)
            assertTrue(frozenPaper.isTentativeFormat)

            val generatedStrings = buildList {
                for (it in frozenPaper.items) {
                    add(it.stem)
                    for (sq in it.subQuestions) add(sq.prompt)
                }
            }
            val (clientEnOverlap, clientZhOverlap) = countOverlaps(generatedStrings, exclusionTexts)
            assertEquals("Client English 8-gram overlap must be 0", 0, clientEnOverlap)
            assertEquals("Client Chinese 12-char overlap must be 0", 0, clientZhOverlap)
            assertFalse("Excluded topic must never appear", generatedStrings.any { excludedTopicTitle in it })

            // Save & start attempt, then exercise live 2.0-AI-2 point grading on 1 item
            val store = LocalExamStore(lib)
            store.register(frozenPaper)
            val attempt = store.start(frozenPaper.book, frozenPaper.id, durationMinutes = profile.durationMinutes)
            val firstItem = frozenPaper.items.first()
            val firstBook = lib.book(firstItem.source.str("docId"))
            val firstPage = firstBook.page(firstItem.source.num("pdfPage").toInt())
            val syntheticAnswer = "Based on the lecture framework, higher returns and marginal trade-offs determine the optimal threshold."
            val draftPayload = if (firstItem.subQuestions.isNotEmpty()) {
                JsonObject(mapOf(firstItem.key to buildJsonObject {
                    put("parts", buildJsonObject {
                        firstItem.subQuestions.forEach { sq -> put(sq.subId, syntheticAnswer) }
                    })
                }))
            } else {
                JsonObject(mapOf(firstItem.key to buildJsonObject { put("text", syntheticAnswer) }))
            }
            store.save(frozenPaper.book, attempt.str("mockId"), draftPayload, 1L)
            store.submit(frozenPaper.book, attempt.str("mockId"))

            val gradeReq = buildJsonObject {
                put("mode", "GRADE_AND_DIAGNOSE")
                put("bookId", firstItem.source.str("docId"))
                put("chapterId", firstItem.topicId.ifBlank { "mock" })
                put("sectionId", firstItem.weekLabel.ifBlank { firstItem.key })
                put("conceptKey", firstItem.conceptKey)
                put("questionId", firstItem.key)
                put("questionStem", firstItem.stem)
                put("studentAnswer", syntheticAnswer)
                put("rubricCriteria", JsonArray(firstItem.rubricCriteria))
                put("sourcePages", buildJsonArray {
                    if (firstPage != null) {
                        add(buildJsonObject {
                            put("pdfPage", firstPage.number)
                            put("text", firstPage.text.take(4_000))
                            put("authorityRank", 1)
                        })
                    }
                })
                put("generateVariant", true)
            }
            val gradeReply = http.gradeAndDiagnosePractice(gradeReq)
            assertTrue("Expected live point grading success, got error=${gradeReply.error}", gradeReply.error == null && gradeReply.body != null)
            val gradeObj = checkNotNull(gradeReply.body)
            assertEquals(0, gradeObj["rewrittenParagraphsEmitted"]?.jsonPrimitive?.intOrNull)

            // Emit sanitized report ONLY (zero raw source or past-paper text)
            val itemsMeta = buildJsonArray {
                for (it in frozenPaper.items) {
                    add(buildJsonObject {
                        put("itemId", it.key)
                        put("type", it.type)
                        put("topicId", it.topicId)
                        put("weekLabel", it.weekLabel)
                        put("marks", it.max / 100.0)
                        put("subQuestionCount", it.subQuestions.size)
                        put("rubricCriteriaCount", it.rubricCriteria.size)
                        put("sourcePage", it.source.num("pdfPage"))
                    })
                }
            }
            val reportDir = Path.of(System.getProperty("user.home"), "Coach/state/v2-impl/2.0-AI-4")
            Files.createDirectories(reportDir)
            val report = buildJsonObject {
                put("paperId", frozenPaper.id)
                put("courseId", courseId)
                put("itemCount", frozenPaper.items.size)
                put("isTentativeFormat", frozenPaper.isTentativeFormat)
                put("plannerSonnetCalls", audit["plannerSonnetCalls"]?.jsonPrimitive?.intOrNull ?: 0)
                put("plannerModel", audit["plannerModel"]?.jsonPrimitive?.content.orEmpty())
                put("itemGenerationCalls", audit["itemGenerationCalls"]?.jsonPrimitive?.intOrNull ?: 0)
                put("calcSubQuestionsChecked", calcChecked)
                put("calcSubQuestionsAccurate", calcAccurate)
                put("calcAccuracy", if (calcChecked > 0) calcAccurate.toDouble() / calcChecked else 0.0)
                put("en8GramOverlaps", clientEnOverlap)
                put("zh12CharOverlaps", clientZhOverlap)
                put("allFrozenAnchorsVerified", true)
                put("liveGradingRewrittenParagraphs", gradeObj["rewrittenParagraphsEmitted"]?.jsonPrimitive?.intOrNull ?: -1)
                put("ledgerCalls", audit["ledgerCalls"]?.jsonPrimitive?.intOrNull ?: 0)
                put("ledgerCostMicrodollars", audit["ledgerCostMicrodollars"]?.jsonPrimitive?.content.orEmpty().toLongOrNull() ?: 0L)
                put("itemsMeta", itemsMeta)
            }
            Files.writeString(reportDir.resolve("mock-eval-report.json"), report.toString())
            assertNotNull(filesWrittenCheck(reportDir.resolve("mock-eval-report.json")))
        } finally {
            http.close()
            tmpRoot.toFile().deleteRecursively()
        }
    }

    private fun filesWrittenCheck(path: Path): Path? = if (Files.isRegularFile(path)) path else null
}
