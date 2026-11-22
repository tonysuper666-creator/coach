package com.coach.desktop

import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.coach.client.PairedCredentials
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.swing.SwingUtilities

/**
 * Real desktop live-window (`ComposeWindow`) end-to-end verification for TON-1803 (`2.0-AI-4`):
 * 1. `CourseScreen` countdown header & sprint focus CTA (`D=3` -> `FINAL_MOCK`, zero red overdue alerts).
 * 2. Overflow `⋯` -> `进入考前限时综合练习` -> tentative format badge -> generate & start timed mock.
 * 3. Multi-subquestion `(a)`/`(b)` & essay real-time draft persistence verified after `CourseScreen` active-mock banner
 *    and across a fresh Window 2 cold restart (`exams.sqlite`).
 * 4. Post-submit `2.0-AI-2` point-by-point grading (`pending == 0`, zero ghostwriting), `RubricAnnotationSheet`,
 *    `MisconceptionFeedbackCard`, clickable `RubricPagePill` jump to original textbook page, and return to `CourseScreen`
 *    with updated `"模考失分 · 回看第 X 页"` sprint focus.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class CourseMockLiveWindowTest {

    @Test
    fun realWindowCourseCountdownMockDraftColdRestartGradingAndPageJump() {
        assumeTrue("Live ComposeWindow verification requires non-headless display", !GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("coach-ai4-live-window-")
        try {
            val fixedClock = Clock.fixed(Instant.parse("2026-11-28T09:00:00Z"), ZoneId.of("UTC"))
            val libDir = root.resolve("library")
            val srcDir = root.resolve("sources")
            Files.createDirectories(srcDir)

            val w1Slides = srcDir.resolve("Week1-Lecture-Slides.md").also {
                Files.writeString(
                    it,
                    "# Week 1 Demand & Elasticity\nHigher wage increases monetary return to healthy time in optimal health investment.\n",
                )
            }
            val w4Slides = srcDir.resolve("Week4-Lecture-Slides.md").also {
                Files.writeString(
                    it,
                    "# Week 4 Adverse Selection & Insurance\nUnder competitive insurance markets, actuarially fair pricing sets premium equal to expected claim.\n",
                )
            }
            val filler = (1..75).joinToString("\n\n") { idx ->
                "Paragraph $idx: This core textbook chapter derives competitive equilibrium under asymmetric risk and expected utility across insurance pools."
            }
            val coreBook = srcDir.resolve("Core-Textbook.md").also {
                Files.writeString(
                    it,
                    "# Chapter 10 Insurance Equilibrium\n\n$filler\n\n## Section 10.2 Full Coverage\nTheorem on page bottom: Competitive actuarially fair premium equals expected loss for full coverage.\n",
                )
            }

            val library1 = TextbookLibrary(libDir, clock = fixedClock, lazyPdfExtras = false)
            val bW1 = library1.importBook(w1Slides)
            val bW4 = library1.importBook(w4Slides)
            val bCore = library1.importBook(coreBook)
            val corePage = bCore.pages.first { "actuarially fair premium equals expected loss" in it.text }.number
            assertTrue("Expected multi-page textbook quote on page >= 2, got $corePage", corePage >= 2)

            val courseId = library1.withCourseDatabase { db ->
                val (cid, rejected) = CourseStore.createCourse(
                    db = db,
                    title = "ECNM10119 Health Economics",
                    examProfile = CourseExamProfile(
                        examDate = "2026-12-01",
                        examDateTentative = false,
                        examFormat = "Part I Short Essays + Part II Analytical Calculations",
                        examFormatTentative = true,
                        durationMinutes = 120,
                        closedBook = true,
                        calculatorAllowed = "非编程计算器",
                    ),
                    materialBookIds = listOf(bW1.id, bW4.id, bCore.id),
                )
                assertEquals(0, rejected.size)
                CourseAutoAligner.alignLocally(db, cid)
                cid
            }

            val initialDetail = library1.courseDetail(courseId)
            val topicE1 = initialDetail.examinableTopics.first()
            val topicA1 = initialDetail.examinableTopics.lastOrNull { it.topicId != topicE1.topicId } ?: topicE1
            val plan0 = checkNotNull(initialDetail.countdownPlan)
            assertEquals(CountdownPhase.FINAL_MOCK, plan0.phase)
            assertEquals(30, plan0.fsrsDailyCap)
            assertEquals(0, plan0.overdueAlertCount)

            val draftE1 = "Higher wage rate increases monetary return to healthy time."
            val draftA1a = "fair premium equals 16.00 under pi=0.25 and loss=64"
            val draftA1b = "zero-profit ray slope flips with wrong sign on diagram"

            fun createStubExplainer(lib: TextbookLibrary): BookExplainer = object : BookExplainer(library = lib) {
                override suspend fun load() {
                    loaded = true
                    identity = DesktopIdentity(
                        serverUrl = "http://127.0.0.1:1",
                        credentials = PairedCredentials("desktop", "tok", "owner"),
                    )
                }

                override suspend fun generateCourseMock(input: JsonObject): CourseServerCallResult {
                    val paper = buildJsonObject {
                        put("schemaVersion", 1)
                        put("id", "course-mock-e2e-v1")
                        put("bookId", "course:$courseId")
                        put("courseId", courseId)
                        put("title", "ECNM10119 · 全真限时模考")
                        put("kind", "paper")
                        put("origin", "AUTHORED")
                        put("key_state", "UNREVIEWED")
                        put("isTentativeFormat", true)
                        put("basis", "ECNM10119 · 按课程手册暂定题型组卷")
                        put("durationMinutes", 120)
                        put("closedBook", true)
                        put("calculatorAllowed", "非编程计算器")
                        put("sections", buildJsonArray {
                            add(buildJsonObject {
                                put("title", "Part I · Short Essays")
                                put("instructions", "写清核心机制与适用条件。")
                                put("items", buildJsonArray {
                                    add(buildJsonObject {
                                        put("id", "E1")
                                        put("type", "essay")
                                        put("stem", "Explain how wage differences affect optimal health capital accumulation.")
                                        put("marks", 20.0)
                                        put("key_state", "UNREVIEWED")
                                        put("topicId", topicE1.topicId)
                                        put("weekLabel", topicE1.weekLabel)
                                        put("conceptKey", "${topicE1.topicId}:e1")
                                        put("source", buildJsonObject {
                                            put("docId", bW1.id)
                                            put("lessonId", "course-page")
                                            put("pdfPage", 1)
                                            put("quote", "monetary return to healthy time")
                                        })
                                        put("rubricCriteria", buildJsonArray {
                                            add(buildJsonObject {
                                                put("cid", "E1-C1")
                                                put("slotLabel", "健康时间货币回报提高")
                                                put("maxMarks", 20.0)
                                                put("sourceBookId", bW1.id)
                                                put("pdfPage", 1)
                                                put("quoteAnchor", "monetary return to healthy time")
                                            })
                                        })
                                    })
                                })
                            })
                            add(buildJsonObject {
                                put("title", "Part II · Analytical & Graphical Problems")
                                put("instructions", "分小问写明公式、数值（保留两位小数）与图形方向。")
                                put("items", buildJsonArray {
                                    add(buildJsonObject {
                                        put("id", "A1")
                                        put("type", "multi_part_calc")
                                        put("stem", "Wealth W=100 faces loss L=64 with probability 0.25.")
                                        put("marks", 30.0)
                                        put("key_state", "UNREVIEWED")
                                        put("topicId", topicA1.topicId)
                                        put("weekLabel", topicA1.weekLabel)
                                        put("conceptKey", "${topicA1.topicId}:a1")
                                        put("targetErrorTag", "SIGN_OR_FORMULA")
                                        put("source", buildJsonObject {
                                            put("docId", bCore.id)
                                            put("lessonId", "course-page")
                                            put("pdfPage", corePage)
                                            put("quote", "actuarially fair premium equals expected loss")
                                        })
                                        put("subQuestions", buildJsonArray {
                                            add(buildJsonObject {
                                                put("subId", "a")
                                                put("label", "(a)")
                                                put("prompt", "Calculate the actuarially fair full-insurance premium.")
                                                put("kind", "calculation")
                                                put("marks", 15.0)
                                                put("mathSpec", buildJsonObject {
                                                    put("expression", "0.25 * 64")
                                                    put("expected", "16.00")
                                                    put("exactRational", "16/1")
                                                    put("decimalPlaces", 2)
                                                    put("absTolerance", "0.015")
                                                })
                                            })
                                            add(buildJsonObject {
                                                put("subId", "b")
                                                put("label", "(b)")
                                                put("prompt", "Identify slope sign of zero-profit line.")
                                                put("kind", "graph_or_explanation")
                                                put("marks", 15.0)
                                            })
                                        })
                                        put("rubricCriteria", buildJsonArray {
                                            add(buildJsonObject {
                                                put("cid", "A1-a")
                                                put("subId", "a")
                                                put("slotLabel", "公平保费数值 16.00")
                                                put("maxMarks", 15.0)
                                                put("sourceBookId", bCore.id)
                                                put("pdfPage", corePage)
                                                put("quoteAnchor", "actuarially fair premium equals expected loss")
                                            })
                                            add(buildJsonObject {
                                                put("cid", "A1-b")
                                                put("subId", "b")
                                                put("slotLabel", "零利润线斜率方向判断")
                                                put("maxMarks", 15.0)
                                                put("sourceBookId", bCore.id)
                                                put("pdfPage", corePage)
                                                put("quoteAnchor", "actuarially fair premium equals expected loss")
                                            })
                                        })
                                    })
                                })
                            })
                        })
                    }
                    return CourseServerCallResult(paper, null)
                }

                override suspend fun gradeAndDiagnosePractice(input: JsonObject): PracticeGradeDiagnoseResult {
                    val qid = (input["questionId"] as? JsonPrimitive)?.content.orEmpty()
                    val respObj = if (qid == "E1") {
                        buildJsonObject {
                            put("mode", "GRADE_AND_DIAGNOSE")
                            put("totalAwarded", 20.0)
                            put("maxMarks", 20.0)
                            put("summaryMetaLine", "已核对 1 个要点：全中（20 / 20 分）。")
                            put("rewrittenParagraphsEmitted", 0)
                            put("rubricItems", buildJsonArray {
                                add(buildJsonObject {
                                    put("cid", "E1-C1")
                                    put("verdict", "FULL")
                                    put("awardedMarks", 20.0)
                                    put("maxMarks", 20.0)
                                    put("studentQuote", "monetary return to healthy time")
                                    put("studentQuoteVerified", true)
                                    put("feedbackNoteZh", "准确写出了健康时间的货币回报变化。")
                                    put("pdfPage", 1)
                                    put("quoteAnchor", "monetary return to healthy time")
                                    put("anchorVerified", true)
                                })
                            })
                        }
                    } else {
                        buildJsonObject {
                            put("mode", "GRADE_AND_DIAGNOSE")
                            put("totalAwarded", 15.0)
                            put("maxMarks", 30.0)
                            put("summaryMetaLine", "第(a)小问数值 16.00 核对正确，第(b)小问斜率符号需修正。")
                            put("rewrittenParagraphsEmitted", 0)
                            put("rubricItems", buildJsonArray {
                                add(buildJsonObject {
                                    put("cid", "A1-a")
                                    put("verdict", "FULL")
                                    put("awardedMarks", 15.0)
                                    put("maxMarks", 15.0)
                                    put("studentQuote", "fair premium equals 16.00")
                                    put("studentQuoteVerified", true)
                                    put("feedbackNoteZh", "期望损失算式与数值 16.00 精确匹配。")
                                    put("pdfPage", corePage)
                                    put("quoteAnchor", "actuarially fair premium equals expected loss")
                                    put("anchorVerified", true)
                                })
                                add(buildJsonObject {
                                    put("cid", "A1-b")
                                    put("verdict", "ZERO")
                                    put("awardedMarks", 0.0)
                                    put("maxMarks", 15.0)
                                    put("studentQuote", "")
                                    put("studentQuoteVerified", false)
                                    put("feedbackNoteZh", "零利润线斜率方向写反，建议对照原书第 $corePage 页重审。")
                                    put("pdfPage", corePage)
                                    put("quoteAnchor", "actuarially fair premium equals expected loss")
                                    put("anchorVerified", true)
                                })
                            })
                            put("diagnosis", buildJsonObject {
                                put("isEquivalentValid", false)
                                put("errorClass", "SIGN_OR_FORMULA")
                                put("studentErrorSpan", "wrong sign")
                                put("diagnosisZh", "零利润边界比值斜率符号混淆。")
                            })
                            put("variant", buildJsonObject {
                                put("variantId", "var-a1b")
                                put("stem", "When claim probability p=0.2, what determines zero-profit fairness?")
                                put("options", buildJsonArray {
                                    add(JsonPrimitive("A. Premium equals expected loss 0.2 * L"))
                                    add(JsonPrimitive("B. Premium doubles expected loss"))
                                })
                                put("correctOption", "A. Premium equals expected loss 0.2 * L")
                                put("targetedTrapExplanation", "公平保费严格等于赔付概率与损失之积。")
                                put("pdfPage", corePage)
                                put("quoteAnchor", "actuarially fair premium equals expected loss")
                                put("conceptKey", "${topicA1.topicId}:a1")
                                put("targetErrorClass", "SIGN_OR_FORMULA")
                                put("anchorVerified", true)
                            })
                        }
                    }
                    return PracticeGradeDiagnoseResult.Ready(PracticeGradingJson.decodeResponse(respObj))
                }
            }

            // Session 1: CourseScreen -> ⋯ -> Mock Hub -> Generate -> Multi-subquestion draft -> Active banner
            val controller1 = TextbookController(library1)
            runBlocking {
                controller1.load()
                controller1.openCourse(courseId)
            }
            val window1 = openWindow {
                WindowsTextbookHost(
                    controller = controller1,
                    bookExplainer = createStubExplainer(library1),
                ) { null }
            }
            try {
                waitFor("course-screen session 1", window1) { node(window1, "course-screen") != null }
                assertTrue(
                    "Expected countdown sprint CTA on CourseScreen",
                    nodeText(node(window1, "course-primary-cta")).contains("继续今日冲刺"),
                )

                click(window1, "course-overflow")
                click(window1, "course-mock-exam-action")
                waitFor("course-mock-hub", window1) { node(window1, "course-mock-hub") != null }
                assertNotNull("Expected tentative badge on mock hub", node(window1, "course-mock-tentative-badge"))

                click(window1, "course-mock-generate-btn")
                waitFor("mock exam attempt screen", window1) {
                    node(window1, "local-exam-attempt") != null &&
                        node(window1, "local-countdown") != null &&
                        node(window1, "local-subtext-A1-a") != null
                }
                assertNotNull("Expected tentative badge on attempt page", node(window1, "local-exam-tentative-badge"))

                setText(window1, "local-text-E1", draftE1)
                setText(window1, "local-subtext-A1-a", draftA1a)
                setText(window1, "local-subtext-A1-b", draftA1b)
                waitFor("drafts saved to exams.sqlite", window1) {
                    nodeText(node(window1, "local-save-status")).contains("作答已保存")
                }

                // Navigate back to CourseScreen and confirm active banner is shown without losing timer/drafts
                click(window1, "local-exam-back")
                waitFor("mock hub active card", window1) { node(window1, "course-mock-active-card") != null }
                click(window1, "course-mock-hub-back")
                waitFor("course active mock banner", window1) { node(window1, "course-active-mock-banner") != null }
            } finally {
                edt { window1.dispose() }
            }

            // Session 2 (cold restart): new TextbookLibrary + new controller -> drafts & banner intact -> submit -> point grading -> page jump!
            val library2 = TextbookLibrary(libDir, clock = fixedClock, lazyPdfExtras = false)
            val controller2 = TextbookController(library2)
            runBlocking {
                controller2.load()
                controller2.openCourse(courseId)
            }
            val window2 = openWindow {
                WindowsTextbookHost(
                    controller = controller2,
                    bookExplainer = createStubExplainer(library2),
                ) { null }
            }
            try {
                waitFor("cold restart shows active mock banner", window2) { node(window2, "course-active-mock-banner") != null }
                click(window2, "course-resume-mock-btn")
                waitFor("mock hub resume button", window2) { node(window2, "course-mock-resume-btn") != null }
                click(window2, "course-mock-resume-btn")

                waitFor("cold restart restored multi-subquestion drafts", window2) {
                    editableText(node(window2, "local-subtext-A1-a")) == draftA1a &&
                        editableText(node(window2, "local-subtext-A1-b")) == draftA1b &&
                        editableText(node(window2, "local-text-E1")) == draftE1
                }

                click(window2, "local-submit")
                click(window2, "local-confirm-submit")

                waitFor("graded result + rubric + misconception card", window2) {
                    node(window2, "local-exam-result") != null &&
                        node(window2, "local-rubric-sheet-A1") != null &&
                        node(window2, "local-misconception-card-A1") != null &&
                        node(window2, "rubric-page-pill-$corePage") != null
                }

                val scoreBannerText = nodeText(node(window2, "local-exam-total-score"))
                assertTrue("Expected graded course mock score banner, got: $scoreBannerText", scoreBannerText.contains("模考核对得分"))
                assertFalse("Course mock must NOT stay in pending review", scoreBannerText.contains("总分待评"))

                // Click missed point's page pill -> jump straight to original textbook page!
                click(window2, "rubric-page-pill-$corePage")
                waitFor("reader opened missed textbook page $corePage", window2) {
                    controller2.state.value.screen == BookScreen.READER &&
                        controller2.state.value.page == corePage &&
                        node(window2, "textbook-reader") != null
                }

                // Return to CourseScreen -> verify mastery write-back updated topic plan meta with missed page!
                runBlocking { controller2.back() }
                waitFor("course screen updated sprint meta", window2) {
                    val meta = node(window2, "topic-plan-meta-${topicA1.topicId}")
                    meta != null && nodeText(meta).contains("回看第 $corePage 页")
                }
            } finally {
                edt { window2.dispose() }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun openWindow(content: @androidx.compose.runtime.Composable () -> Unit): ComposeWindow = edt {
        ComposeWindow().apply {
            val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            setBounds(
                screen.x + 20,
                screen.y + 20,
                minOf(1280, (screen.width - 40).coerceAtLeast(920)),
                minOf(880, (screen.height - 80).coerceAtLeast(640)),
            )
            setContent { content() }
            isVisible = true
        }
    }

    private fun nodes(window: ComposeWindow): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        val owners = window.semanticsOwners + ownedDialogs(window).flatMap { it.semanticsOwners }
        return owners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }

    private fun ownedDialogs(window: java.awt.Window): List<ComposeDialog> =
        window.ownedWindows.filter { it.isVisible }.flatMap { listOfNotNull(it as? ComposeDialog) + ownedDialogs(it) }

    private fun node(window: ComposeWindow, tag: String): SemanticsNode? =
        edt { nodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag } }

    private fun nodeText(n: SemanticsNode?): String {
        if (n == null) return ""
        fun collect(cur: SemanticsNode): String =
            cur.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty() +
                cur.children.joinToString("") { collect(it) }
        return collect(n)
    }

    private fun editableText(n: SemanticsNode?): String =
        n?.config?.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun click(window: ComposeWindow, tag: String) {
        waitFor(tag, window) { node(window, tag) != null }
        edt {
            val target = requireNotNull(node(window, tag)) { "Missing node $tag" }
            assertTrue("Click failed on $tag", target.config[SemanticsActions.OnClick].action!!.invoke())
            runCatching { window.renderImmediately() }
        }
        Thread.sleep(40)
    }

    private fun setText(window: ComposeWindow, tag: String, value: String) {
        waitFor(tag, window) { node(window, tag) != null }
        edt {
            val target = requireNotNull(node(window, tag)) { "Missing text field $tag" }
            assertTrue("SetText failed on $tag", target.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
            runCatching { window.renderImmediately() }
        }
        Thread.sleep(40)
    }

    private fun waitFor(
        label: String,
        window: ComposeWindow? = null,
        timeoutSeconds: Long = 20,
        pollMs: Long = 40,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        while (true) {
            if (window != null) edt { runCatching { window.renderImmediately() } }
            if (runCatching { condition() }.getOrDefault(false)) return
            check(System.nanoTime() < deadline) {
                val tags = window?.let {
                    runCatching { edt { nodes(it).mapNotNull { n -> n.config.getOrNull(SemanticsProperties.TestTag) }.distinct().take(45) } }.getOrNull()
                }
                "Timed out waiting for: $label; tags=$tags"
            }
            Thread.sleep(pollMs)
        }
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
