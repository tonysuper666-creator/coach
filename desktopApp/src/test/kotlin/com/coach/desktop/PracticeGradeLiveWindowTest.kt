package com.coach.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.jetbrains.skiko.SkiaLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * Real desktop live-window (`ComposeWindow`) end-to-end verification for TON-1801 (`2.0-AI-2`):
 * 1. Opens a real desktop window, writes half of a subjective answer in companion practice, disposes window (cold restart).
 * 2. Reopens a second real desktop window, clicks Home "继续", verifies the half-written draft is restored verbatim.
 * 3. Finishes typing and clicks "提交批改" -> renders compact in-place `RubricAnnotationSheet` (single `CoachCardMeta`,
 *    highlighted awarded quote, concise zero-ghostwriting notes, clickable page pills) + `MisconceptionFeedbackCard`.
 * 4. Clicks the page pill (`课本第 2 页`) -> asserts the left reader pane flips directly to page 2!
 * 5. Verifies `concept_mastery_ledger` recorded `dominant_error_tag = "GRAPH_SHIFT_OR_ROTATION"`.
 * 6. Clicks `练一道同类变式`, answers the targeted isomorphic variant correctly -> asserts `dominant_error_tag`
 *    is cleared back to `null` in `concept_mastery_ledger`!
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class PracticeGradeLiveWindowTest {

    // TON-1821（#603）起按书的目录分章：每个一级标题是一章，伴读跟着当前章走。
    // 第 2 页不另起标题，课本第 2 页仍在同一章里，点页码跳过去后批改卡和变式按钮还在原地。
    private fun buildSyntheticBook(dir: Path): Path = dir.resolve("synthetic-econ-grading.md").also { file ->
        Files.writeString(
            file,
            """
            # 1.1 Health Capital Overview

            In the multi-period health capital framework, optimal health balances marginal efficiency against user cost.
            Equilibrium occurs where MEC(H*) equals r plus gamma, combining the market interest rate and depreciation.

            ${"\u000C"}
            Aging and depreciation shift.

            When an individual ages, the depreciation rate gamma increases over time.
            Because gamma raises the user cost of health capital, the horizontal line r + gamma shifts upward while the MEC curve stays fixed.
            Consequently, the equilibrium optimal health stock H* declines along the unchanged MEC curve.
            """.trimIndent(),
        )
    }

    private fun samplePack(book: Textbook, group: ChapterSectionGroup): BookCompanionPack {
        val sec = group.sections.first()
        val p2 = book.pages.last()
        val quote = "horizontal line r + gamma shifts upward"
        val startIdx = p2.text.indexOf(quote).coerceAtLeast(0)
        val anchor = BookCompanionAnchor(
            pdfPage = p2.number,
            blockId = "${book.id}:p${p2.number}:$startIdx",
            startUtf16 = startIdx,
            endUtf16 = startIdx + quote.length,
            quoteAnchor = quote,
        )
        return BookCompanionPack(
            bookId = book.id,
            chapterId = group.chapterId,
            chapterTitle = group.chapterTitle,
            coreQuestion = "老龄化如何通过折旧率影响最优健康存量 H*？",
            keyPoints = listOf(
                BookCompanionKeyPoint("kp-1", sec.id, "最优健康满足 MEC(H*) = r + γ。", anchor),
            ),
            optionalPractice = listOf(
                BookCompanionQuiz(
                    id = "prac-subj-1",
                    sectionId = sec.id,
                    stem = "写出最优健康均衡条件，并说明年龄增长提高折旧率 γ 时，图上哪条曲线如何移动？",
                    options = emptyList(),
                    correctOption = "MEC(H*) = r + γ；水平线 r + γ 上移，MEC 曲线不动。",
                    explanation = "折旧率上升提高使用成本线，不改变教育决定的 MEC 曲线。",
                    kind = "SHORT_CALC",
                    anchor = anchor,
                ),
            ),
        )
    }

    @Test
    fun realWindowHalfDraftRestartSubmitRubricJumpPageAndClearErrorTagWithVariant() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val reportOut = System.getProperty("coach.ai2.reportOut")?.takeIf { it.isNotBlank() }?.let(Path::of)

        val root = Files.createTempDirectory("coach-ai2-live-window-")
        try {
            val md = buildSyntheticBook(root)
            val libDir = root.resolve("library")
            val library1 = TextbookLibrary(libDir)
            val book = library1.importBook(md)
            assertTrue("Expected >= 2 pages, got ${book.pages.size}", book.pages.size >= 2)
            val page2Num = book.pages.last().number
            val group = ChapterCompanionGrouping.groupForSection(book, 0)
            assertNotNull(group)
            val sec1 = group!!.sections.first()
            library1.saveChapterCompanion(book.id, group.chapterId, samplePack(book, group))

            val partialDraft = "At optimum MEC(H*) = r + gamma."
            val fullDraftWithError = "$partialDraft When aging raises gamma, the MEC curve shifts downward while r + gamma stays fixed."
            val expectedConceptKey = MasteryEngine.conceptKeyFor(book.id, sec1.id)

            val gradingHost = PracticeGradingHost(
                library = library1,
                gradeAndDiagnose = { req ->
                    val ans = (req["studentAnswer"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    assertTrue("Submitted answer must contain partial draft", partialDraft in ans)
                    PracticeGradeDiagnoseResult.Ready(
                        PracticeGradeDiagnoseResponse(
                            mode = "GRADE_AND_DIAGNOSE",
                            rubricItems = listOf(
                                PracticeRubricItemGrade(
                                    cid = "C1",
                                    verdict = "FULL",
                                    awardedMarks = 2.0,
                                    maxMarks = 2.0,
                                    studentQuote = "MEC(H*) = r + gamma",
                                    studentQuoteVerified = true,
                                    feedbackNoteZh = "均衡条件写对，得分。",
                                    pdfPage = 1,
                                    quoteAnchor = "MEC(H*) equals r plus gamma",
                                    anchorVerified = true,
                                ),
                                PracticeRubricItemGrade(
                                    cid = "C2",
                                    verdict = "PARTIAL",
                                    awardedMarks = 1.0,
                                    maxMarks = 3.0,
                                    studentQuote = "the MEC curve shifts downward",
                                    studentQuoteVerified = true,
                                    feedbackNoteZh = "移错曲线：γ上升应使r+γ线上移，MEC不动。",
                                    pdfPage = page2Num,
                                    quoteAnchor = "horizontal line r + gamma shifts upward",
                                    anchorVerified = true,
                                ),
                            ),
                            totalAwarded = 3.0,
                            maxMarks = 5.0,
                            summaryMetaLine = "已核对 2 个采分要点 · 得分 3 / 5 · 漏了 1 个关键步骤。",
                            diagnosis = PracticeMisconceptionDiagnosis(
                                isEquivalentValid = false,
                                errorClass = MisconceptionErrorClass.GRAPH_SHIFT_OR_ROTATION,
                                studentErrorSpan = "the MEC curve shifts downward",
                                diagnosisZh = "折旧率提高只抬高水平成本线 r + γ，MEC 曲线本身不动。",
                            ),
                            variant = PracticeIsomorphicVariant(
                                variantId = "var-graph-shift-1",
                                stem = "若利率 r = 3% 不变，健康折旧率 γ 从 4% 升至 6%，在 (H, 回报率) 图上如何变化？",
                                options = listOf(
                                    "A. 水平线 r + γ 向上平移，MEC 曲线不动，H* 下降",
                                    "B. 水平线 r + γ 不动，MEC 曲线向下平移",
                                    "C. 两条线同时下移，H* 上升",
                                    "D. 两条线均不移动",
                                ),
                                correctOption = "A. 水平线 r + γ 向上平移，MEC 曲线不动，H* 下降",
                                targetedTrapExplanation = "γ 属于使用成本项，上升只会让水平线 r + γ 上移，不移动由教育效率决定的 MEC 曲线。",
                                pdfPage = page2Num,
                                quoteAnchor = "horizontal line r + gamma shifts upward",
                                conceptKey = expectedConceptKey,
                                targetErrorClass = MisconceptionErrorClass.GRAPH_SHIFT_OR_ROTATION,
                                anchorVerified = true,
                            ),
                            rewrittenParagraphsEmitted = 0,
                        ),
                    )
                },
            )

            // === Session 1: Open real window, type half subjective draft, close window (cold restart) ===
            val controller1 = TextbookController(library1)
            runBlocking {
                controller1.load()
                controller1.read()
            }
            val window1 = openWindow("Coach AI-2 Session 1") {
                CompositionLocalProvider(LocalPracticeGradingHost provides gradingHost) {
                    WindowsTextbookHost(controller1) { null }
                }
            }
            try {
                waitFor("reader ready session 1", window1) {
                    node(window1, "textbook-reader") != null &&
                        edt {
                            activeSurfaces(window1).firstOrNull()
                                ?.let { it.layer.canvas.width > 0 && it.layer.canvas.height > 0 } == true
                        }
                }
                openCompanionDrawer(window1)
                waitFor("companion module strip", window1) { node(window1, "companion-mod-practice") != null }
                click(window1, "companion-mod-practice")
                waitFor("subjective draft input", window1) { node(window1, "practice-draft-input-0") != null }
                setText(window1, "practice-draft-input-0", partialDraft)
                waitFor("draft persisted in sqlite", window1) {
                    library1.latestChapterPracticeDraft(book.id)?.practiceTextDrafts?.get("prac-subj-1") == partialDraft
                }
            } finally {
                edt { window1.dispose() }
            }

            // === Session 2: Cold restart window -> Home "继续" -> draft restored -> Submit -> Jump Page -> Variant clears error tag ===
            val library2 = TextbookLibrary(libDir)
            val gradingHost2 = gradingHost.copy(library = library2)
            val controller2 = TextbookController(library2)
            runBlocking { controller2.load() }

            val window2 = openWindow("Coach AI-2 Session 2") {
                CompositionLocalProvider(LocalPracticeGradingHost provides gradingHost2) {
                    WindowsTextbookHost(controller2) { null }
                }
            }
            try {
                waitFor("home or resumed practice ready", window2) {
                    node(window2, "home-read") != null || node(window2, "practice-draft-input-0") != null
                }
                if (node(window2, "home-read") != null) {
                    click(window2, "home-read")
                }
                waitFor("subjective draft restored after cold restart", window2) {
                    val input = node(window2, "practice-draft-input-0")
                    input != null && editableText(input) == partialDraft
                }

                // Finish writing full subjective answer & submit
                setText(window2, "practice-draft-input-0", fullDraftWithError)
                waitFor("submit button enabled", window2) { node(window2, "practice-submit-0") != null }
                click(window2, "practice-submit-0")

                // Assert compact in-place annotation sheet & misconception card rendered
                waitFor("rubric annotation sheet rendered", window2) {
                    node(window2, "rubric-annotation-sheet") != null &&
                        node(window2, "rubric-annotation-meta") != null &&
                        node(window2, "rubric-highlighted-draft") != null &&
                        node(window2, "rubric-page-pill-$page2Num") != null &&
                        node(window2, "misconception-feedback-card") != null &&
                        node(window2, "practice-variant-btn") != null
                }
                val metaText = nodeText(node(window2, "rubric-annotation-meta"))
                assertTrue("Meta should show rubric count & score: $metaText", "已核对 2 个采分要点" in metaText)

                // Verify dominant_error_tag was written to concept_mastery_ledger
                waitFor("dominant_error_tag recorded in SQLite", window2) {
                    library2.conceptMasteryForBook(book.id).any { it.dominantErrorTag == "GRAPH_SHIFT_OR_ROTATION" }
                }

                // Click page pill (`课本第 2 页`) -> left reader pane jumps directly to page 2!
                assertEquals(1, controller2.state.value.book?.readingPage)
                click(window2, "rubric-page-pill-$page2Num")
                waitFor("left reader jumped to page $page2Num", window2) {
                    controller2.state.value.book?.readingPage == page2Num
                }

                // Click `练一道同类变式`, pick correct option A, submit -> clears dominant_error_tag!
                click(window2, "practice-variant-btn")
                waitFor("variant card opened", window2) {
                    node(window2, "practice-variant-card") != null && node(window2, "practice-variant-opt-0") != null
                }
                click(window2, "practice-variant-opt-0")
                click(window2, "practice-variant-submit")
                waitFor("variant mastered confirmation", window2) {
                    node(window2, "practice-variant-cleared") != null
                }
                waitFor("dominant_error_tag cleared in SQLite", window2) {
                    val rec = library2.conceptMastery(expectedConceptKey)
                    rec != null && rec.dominantErrorTag == null && rec.hasActiveTest
                }

                val finalConcept = library2.conceptMastery(expectedConceptKey)
                assertNotNull(finalConcept)
                assertNull("dominant_error_tag must be cleared after solving variant", finalConcept!!.dominantErrorTag)

                reportOut?.let { out ->
                    Files.createDirectories(out.parent)
                    Files.writeString(
                        out,
                        buildJsonObject {
                            put("status", "PASS")
                            put("draftRestoredAfterColdRestart", true)
                            put("rubricAnnotationRendered", true)
                            put("pagePillJumpedToPage", controller2.state.value.book?.readingPage ?: -1)
                            put("variantMasteredClearedErrorTag", finalConcept.dominantErrorTag == null)
                            put("finalMastery", finalConcept.pMastery)
                        }.toString(),
                    )
                }
            } finally {
                edt { window2.dispose() }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun openWindow(windowTitle: String, content: @androidx.compose.runtime.Composable () -> Unit): ComposeWindow = edt {
        ComposeWindow().apply {
            title = windowTitle
            val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            setBounds(
                screen.x + 24,
                screen.y + 24,
                minOf(1400, (screen.width - 48).coerceAtLeast(800)),
                minOf(880, (screen.height - 64).coerceAtLeast(600)),
            )
            setContent { content() }
            isVisible = true
        }
    }

    private fun <T> edt(block: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                result = block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private data class LiveSurface(val layer: SkiaLayer, val mediator: Any, val focusable: Boolean)

    private fun reflectField(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                return f.get(target)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException("${target.javaClass.name}.$name")
    }

    private fun invokeDeclared(target: Any, name: String, paramType: Class<*>, arg: Any): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val m = cls.getDeclaredMethod(name, paramType)
                m.isAccessible = true
                return m.invoke(target, arg)
            } catch (_: NoSuchMethodException) {
                cls = cls.superclass
            }
        }
        throw NoSuchMethodException("${target.javaClass.name}.$name(${paramType.name})")
    }

    private fun collectSkiaLayers(c: Component): List<SkiaLayer> {
        val res = mutableListOf<SkiaLayer>()
        if (c is SkiaLayer && c.isShowing) res += c
        if (c is Container) c.components.forEach { res += collectSkiaLayers(it) }
        return res
    }

    private fun activeSurfaces(window: ComposeWindow): List<LiveSurface> {
        val panel = reflectField(window, "composePanel") ?: return emptyList()
        val getContainer = panel.javaClass.getDeclaredMethod("getComposeContainer").apply { isAccessible = true }
        val container = getContainer.invoke(panel) ?: return emptyList()
        val out = mutableListOf<LiveSurface>()
        fun addFor(mediator: Any?, focusable: Boolean) {
            if (mediator == null) return
            val comp = mediator.javaClass.getMethod("getContentComponent").invoke(mediator) as? Component ?: return
            for (layer in collectSkiaLayers(comp)) out += LiveSurface(layer, mediator, focusable)
        }
        addFor(reflectField(container, "mediator"), true)
        val layers = (reflectField(container, "layers") as? List<*>).orEmpty()
        for (layer in layers) {
            if (layer == null) continue
            val mediator = layer.javaClass.getDeclaredMethod("getMediator").apply { isAccessible = true }.invoke(layer)
            val focusable = layer.javaClass.getMethod("getFocusable").invoke(layer) as Boolean
            addFor(mediator, focusable)
        }
        return out
    }

    private fun takeLogicalFocus(mediator: Any) {
        val manager = mediator.javaClass.getMethod("getFocusManager").invoke(mediator)
        val hasFocus = manager.javaClass.getMethod("getHasFocus").invoke(manager) as Boolean
        if (hasFocus) return
        val companion = Class.forName("androidx.compose.ui.focus.FocusDirection").getField("Companion").get(null)
        val enter = companion.javaClass.methods.first { it.name.startsWith("getEnter-") }.invoke(companion)
        manager.javaClass.methods.first { it.name.startsWith("takeFocus-") }.invoke(manager, enter)
    }

    private fun openCompanionDrawer(window: ComposeWindow) {
        edt {
            val main = activeSurfaces(window).firstOrNull()
            if (main != null) {
                runCatching { takeLogicalFocus(main.mediator) }
                val lx = (main.layer.canvas.width / 2).coerceAtLeast(10)
                val now = System.currentTimeMillis()
                invokeDeclared(main.mediator, "onMouseEvent", MouseEvent::class.java, MouseEvent(main.layer.canvas, MouseEvent.MOUSE_ENTERED, now, 0, lx, 8, 0, false, MouseEvent.NOBUTTON))
                invokeDeclared(main.mediator, "onMouseEvent", MouseEvent::class.java, MouseEvent(main.layer.canvas, MouseEvent.MOUSE_MOVED, now + 1, 0, lx, 8, 0, false, MouseEvent.NOBUTTON))
            }
            runCatching { window.renderImmediately() }
        }
        Thread.sleep(150)
        if (node(window, "immersive-companion-drawer") == null && node(window, "immersive-companion-toggle") != null) {
            click(window, "immersive-companion-toggle")
        }
        if (node(window, "immersive-companion-drawer") == null) {
            edt {
                val target = activeSurfaces(window).lastOrNull { it.focusable } ?: activeSurfaces(window).firstOrNull()
                if (target != null) {
                    runCatching { takeLogicalFocus(target.mediator) }
                    val now = System.currentTimeMillis()
                    invokeDeclared(target.mediator, "onKeyEvent", KeyEvent::class.java, KeyEvent(target.layer.canvas, KeyEvent.KEY_PRESSED, now, 0, KeyEvent.VK_L, 'l'))
                    invokeDeclared(target.mediator, "onKeyEvent", KeyEvent::class.java, KeyEvent(target.layer.canvas, KeyEvent.KEY_RELEASED, now + 1, 0, KeyEvent.VK_L, 'l'))
                }
                runCatching { window.renderImmediately() }
            }
            Thread.sleep(120)
        }
        waitFor("companion drawer visible", window) { node(window, "immersive-companion-drawer") != null }
    }

    private fun allNodes(window: ComposeWindow): List<SemanticsNode> = edt {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        val owners = window.semanticsOwners
        owners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }

    private fun node(window: ComposeWindow, tag: String): SemanticsNode? =
        allNodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun nodeText(n: SemanticsNode?): String =
        n?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun editableText(n: SemanticsNode?): String =
        n?.config?.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun click(window: ComposeWindow, tag: String) {
        val target = node(window, tag) ?: error("Node with tag '$tag' not found")
        edt {
            val action = target.config.getOrNull(SemanticsActions.OnClick) ?: error("Node '$tag' is not clickable")
            action.action?.invoke()
            runCatching { window.renderImmediately() }
        }
        Thread.sleep(30)
    }

    private fun setText(window: ComposeWindow, tag: String, value: String) {
        val target = node(window, tag) ?: error("Text node '$tag' not found")
        edt {
            val action = target.config.getOrNull(SemanticsActions.SetText) ?: error("Node '$tag' has no SetText action")
            action.action?.invoke(AnnotatedString(value))
            runCatching { window.renderImmediately() }
        }
        Thread.sleep(30)
    }

    private fun waitFor(label: String, window: ComposeWindow, timeoutMs: Long = 15_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            edt { runCatching { window.renderImmediately() } }
            if (runCatching { predicate() }.getOrDefault(false)) return
            Thread.sleep(50)
        }
        val tags = runCatching { allNodes(window).mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.distinct().take(40) }.getOrDefault(emptyList())
        error("Timed out waiting for $label. Visible tags: $tags")
    }
}
