package com.coach.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

/**
 * End-to-end verification for TON-1792 (2.0-W2-B):
 * - 5 modular single-book companion panels in the right split-pane drawer:
 *   本章要点 / 读前自测 / 推导看图模板 / 选做练习 / 原书章末习题三级提示
 * - Real-time draft persistence into `book_chapter_progress.practice_draft_json`
 * - Mid-practice interruption -> Home focus card shows `练习草稿已保存` -> full restart ->
 *   clicking Home's single PrimaryButton (`继续`) restores exact page, drawer, module, typed draft, and hint tier.
 */
@OptIn(ExperimentalTestApi::class)
class SingleBookCompanionFlowTest {

    private fun samplePack(book: Textbook, group: ChapterSectionGroup): BookCompanionPack {
        val sec1 = group.sections.first()
        val blk1 = sec1.blocks.firstOrNull { !it.heading } ?: sec1.blocks.first()
        val quote1 = blk1.text.take(24)
        val anchor1 = BookCompanionAnchor(
            pdfPage = blk1.page,
            blockId = blk1.id,
            startUtf16 = blk1.start,
            endUtf16 = blk1.start + quote1.length,
            quoteAnchor = quote1,
        )
        return BookCompanionPack(
            bookId = book.id,
            chapterId = group.chapterId,
            chapterTitle = group.chapterTitle,
            coreQuestion = "信息不对称如何在保险与医疗市场引发逆向选择？",
            keyPoints = listOf(
                BookCompanionKeyPoint("kp-1", sec1.id, "投保人比保险公司更了解自身风险，统一费率会挤出低风险群体。", anchor1),
                BookCompanionKeyPoint("kp-2", sec1.id, "保费随高风险占比上升而不断抬高，最终导致市场萎缩。", anchor1),
            ),
            jargonGlosses = listOf(
                BookCompanionJargon("unravel", "市场逐层瓦解，低风险人群相继退出。"),
            ),
            pretest = listOf(
                BookCompanionQuiz(
                    id = "pre-1",
                    sectionId = sec1.id,
                    stem = "当保险公司无法区分高低风险人群并按均价收费时，最先退出的通常是哪类人群？",
                    options = listOf("低风险人群", "高风险人群", "所有人群同时退出", "风险偏好最高者"),
                    correctOption = "低风险人群",
                    explanation = "对低风险者而言，混合保费高于其预期赔付，因此最先退出。",
                    revealHint = "答案依据在第 ${blk1.page} 页。",
                    kind = "MCQ",
                    anchor = anchor1,
                ),
            ),
            derivations = listOf(
                BookCompanionDerivation(
                    id = "drv-1",
                    sectionId = sec1.id,
                    title = "混合保费盈亏平衡条件",
                    setup = "设高风险占比为 q，高低风险赔付概率分别为 pH 与 pL。",
                    steps = listOf("第一步：写出期望赔付 E[L] = (q·pH + (1-q)·pL)·L。", "第二步：比较低风险愿付保费与 E[L]。"),
                    graphCheck = "核对横轴为参保覆盖率，纵轴为保费与边际成本。",
                    pitfall = "不要把平均成本曲线误画成单调上升。",
                    anchor = anchor1,
                ),
            ),
            optionalPractice = listOf(
                BookCompanionQuiz(
                    id = "prac-mcq-1",
                    sectionId = sec1.id,
                    stem = "强制全员参保如何缓解逆向选择死亡螺旋？",
                    options = listOf("把低风险人群留在风险池以压低平均保费", "提高医疗服务边际成本"),
                    correctOption = "把低风险人群留在风险池以压低平均保费",
                    explanation = "强制参保阻止低风险退出，从而稳住混合费率。",
                    kind = "MCQ",
                    anchor = anchor1,
                ),
                BookCompanionQuiz(
                    id = "prac-calc-2",
                    sectionId = sec1.id,
                    stem = "写出两类风险混合均衡下计算盈亏平衡保费的推导步骤。",
                    options = emptyList(),
                    correctOption = "E[L] = q·pH·L + (1-q)·pL·L",
                    explanation = "按人口比例加权期望损失即可得到精算公平保费。",
                    kind = "SHORT_CALC",
                    anchor = anchor1,
                ),
            ),
            exerciseHints = listOf(
                BookCompanionExerciseHint(
                    id = "ex-1",
                    sectionId = sec1.id,
                    problemLabel = "习题 1",
                    questionSummary = "分析两类健康状态下的逆向选择均衡是否存在。",
                    hint1Direction = "提示 1：先分别算出两类人群的精算公平费率。",
                    hint2Method = "提示 2：检验低风险群体是否愿意接受混合费率。",
                    hint3Check = "核对结论：若低风险最高愿付低于混合费率，则不存在混合均衡。",
                    anchor = anchor1,
                ),
            ),
        )
    }

    private fun ComposeUiTest.maybeSaveShot(name: String) {
        val dir = (System.getProperty("coach.w2b.shotsDir") ?: System.getenv("COACH_W2B_SHOTS_DIR"))
            ?.takeIf { it.isNotBlank() } ?: return
        val out = Path.of(dir).also { Files.createDirectories(it) }.resolve("$name.png")
        val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap())
            .encodeToData(EncodedImageFormat.PNG)?.bytes ?: return
        Files.write(out, png)
    }

    @Test
    fun fiveModulesWorkAndMidPracticeInterruptionResumesFromHomeContinueAfterRestart() {
        val root = Files.createTempDirectory("coach-w2b-companion-")
        try {
            val md = root.resolve("health-econ-sample.md").also {
                Files.writeString(
                    it,
                    """
                    # Chapter 1 Asymmetric Information

                    When buyers hold private risk information, pooled insurance premiums exceed low-risk willingness to pay.

                    As low-risk buyers exit the pool, average claims rise and the market unravels toward higher prices.

                    # Chapter 2 Risk Pooling

                    Compulsory participation stabilizes average claim costs across heterogeneous households.
                    """.trimIndent(),
                )
            }
            val libDir = root.resolve("library")
            val library = TextbookLibrary(libDir)
            val book = library.importBook(md)
            val group = assertNotNull(ChapterCompanionGrouping.groupForSection(book, 0))
            library.saveChapterCompanion(book.id, group.chapterId, samplePack(book, group))

            // 1. Open reader, open right companion drawer, interact across all 5 modules, leave mid-draft
            val controller1 = TextbookController(library)
            runBlocking {
                controller1.load()
                controller1.read()
            }
            runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
                setContent {
                    WindowsTextbookHost(controller = controller1, chooseBook = { null })
                }
                waitForIdle()
                // Open companion drawer with L key
                onNodeWithTag("textbook-reader").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.L) }
                waitForIdle()
                onNodeWithTag("immersive-companion-drawer").assertExists()

                // Module 1: 本章要点
                onNodeWithTag("companion-mod-keypoints").assertExists()
                onNodeWithTag("companion-core-question").assertExists()
                onNodeWithTag("companion-kp-0").assertExists()
                onNodeWithTag("companion-jargon-card").assertExists()

                // Module 2: 读前自测 -> select option, mark guessed, submit -> enters FSRS review queue
                onNodeWithTag("companion-mod-pretest").performClick()
                waitForIdle()
                onNodeWithTag("pretest-opt-0-0").performClick()
                onNodeWithTag("pretest-guess-0").performClick()
                onNodeWithTag("pretest-submit-0").performClick()
                waitForIdle()
                onNodeWithTag("pretest-feedback-0").assertTextContains("已记入今日复习巩固", substring = true)

                // Module 3: 推导看图模板
                onNodeWithTag("companion-mod-derivation").performClick()
                waitForIdle()
                onNodeWithTag("derivation-card-0").assertExists()

                // Module 5: 原书章末习题三级提示 -> reveal Tier 1 & Tier 2
                onNodeWithTag("companion-mod-exercises").performClick()
                waitForIdle()
                onNodeWithTag("exercise-reveal-0").performClick()
                waitForIdle()
                onNodeWithTag("exercise-hint1-0").assertExists()
                onNodeWithTag("exercise-reveal-0").performClick()
                waitForIdle()
                onNodeWithTag("exercise-hint2-0").assertExists()
                onNodeWithTag("exercise-hint3-0").assertDoesNotExist()

                // Module 4: 选做练习 -> type mid-calculation draft and exit WITHOUT submitting!
                onNodeWithTag("companion-mod-practice").performClick()
                waitForIdle()
                onNodeWithTag("practice-opt-0-0").performClick()
                onNodeWithTag("practice-draft-input-1").performClick()
                onNodeWithTag("practice-draft-input-1").performTextInput("Step 1: E[L] = 0.2*200 + 0.8*50")
                waitForIdle()
                maybeSaveShot("01-mid-practice-before-exit")

                // Return to Home (interruption)
                runBlocking {
                    controller1.flushPendingSaves()
                    controller1.back()
                }
                waitForIdle()
                onNodeWithTag("home-focus-card").assertExists()
                onNodeWithTag("home-focus-meta").assertTextContains("练习草稿已保存", substring = true)
                maybeSaveShot("02-home-continue-draft-saved")
            }

            runBlocking { controller1.flushPendingSaves() }

            // Verify persisted in SQLite `book_chapter_progress.practice_draft_json` + FSRS review created
            val persistedDraft = assertNotNull(library.latestChapterPracticeDraft(book.id))
            assertEquals("PRACTICE", persistedDraft.activeModule)
            assertEquals("Step 1: E[L] = 0.2*200 + 0.8*50", persistedDraft.practiceTextDrafts["prac-calc-2"])
            assertEquals(2, persistedDraft.exerciseHintLevels["ex-1"])
            assertTrue(library.reviews().isNotEmpty(), "Guessed pretest must enqueue into FSRS review store")

            // 2. Cold restart: brand new TextbookLibrary + TextbookController on same SQLite directory
            val relaunchedLibrary = TextbookLibrary(libDir)
            val relaunchedController = TextbookController(relaunchedLibrary)
            runBlocking { relaunchedController.load() }
            assertEquals(BookScreen.HOME, relaunchedController.state.value.screen)
            assertNotNull(relaunchedController.state.value.activePracticeDraft)

            runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
                setContent {
                    WindowsTextbookHost(controller = relaunchedController, chooseBook = { null })
                }
                waitForIdle()
                // Home shows saved draft indicator and single PrimaryButton("继续")
                onNodeWithTag("home-focus-meta").assertTextContains("练习草稿已保存", substring = true)
                onNodeWithTag("home-read").assertTextContains("继续").performClick()
                waitUntil(timeoutMillis = 10_000) { relaunchedController.state.value.screen == BookScreen.READER }
                waitForIdle()

                // Exact breakpoint restored: companion drawer open, on 选做练习, typed draft intact!
                onNodeWithTag("immersive-companion-drawer").assertExists()
                onNodeWithTag("practice-draft-input-1").assertTextContains("Step 1: E[L] = 0.2*200 + 0.8*50", substring = true)
                maybeSaveShot("03-resumed-after-cold-restart")

                // Switch to 习题 -> Tier 1 & Tier 2 hints still revealed, Tier 3 still hidden
                onNodeWithTag("companion-mod-exercises").performClick()
                waitForIdle()
                onNodeWithTag("exercise-hint1-0").assertExists()
                onNodeWithTag("exercise-hint2-0").assertExists()
                onNodeWithTag("exercise-hint3-0").assertDoesNotExist()
                maybeSaveShot("04-resumed-exercise-hints-tier2")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun compactBottomSheetDrawerMergesModeBarAndKeepsBodyScrollable() {
        val root = Files.createTempDirectory("coach-ton1776-compact-drawer-")
        try {
            val md = root.resolve("health-econ-sample.md").also {
                Files.writeString(
                    it,
                    """
                    # Chapter 1 Asymmetric Information

                    When buyers hold private risk information, pooled insurance premiums exceed low-risk willingness to pay.

                    As low-risk buyers exit the pool, average claims rise and the market unravels toward higher prices.

                    # Chapter 2 Risk Pooling

                    Compulsory participation stabilizes average claim costs across heterogeneous households.
                    """.trimIndent(),
                )
            }

            // 1. Wide bottom-sheet drawer at 752x552 merges mode trigger + module strip on a single row
            // so companion-module-body stays tall and encloses mouse-wheel point (0.65 * winW, 0.72 * winH).
            val libCompact = TextbookLibrary(root.resolve("lib-compact"))
            val bookCompact = libCompact.importBook(md)
            val groupCompact = assertNotNull(ChapterCompanionGrouping.groupForSection(bookCompact, 0))
            libCompact.saveChapterCompanion(bookCompact.id, groupCompact.chapterId, samplePack(bookCompact, groupCompact))
            val controllerCompact = TextbookController(libCompact)
            runBlocking {
                controllerCompact.load()
                controllerCompact.read()
            }

            runSkikoComposeUiTest(size = Size(752f, 552f), density = Density(1f)) {
                setContent {
                    WindowsTextbookHost(controller = controllerCompact, chooseBook = { null })
                }
                waitForIdle()
                onNodeWithTag("textbook-reader").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.L) }
                waitForIdle()

                onNodeWithTag("companion-mode-bar").assertIsDisplayed()
                onNodeWithTag("companion-mode-trigger").assertIsDisplayed()
                onNodeWithTag("companion-module-strip").assertIsDisplayed()
                onNodeWithTag("companion-module-body").assertIsDisplayed()

                val triggerBounds = onNodeWithTag("companion-mode-trigger").fetchSemanticsNode().boundsInRoot
                val stripBounds = onNodeWithTag("companion-module-strip").fetchSemanticsNode().boundsInRoot
                val bodyBounds = onNodeWithTag("companion-module-body").fetchSemanticsNode().boundsInRoot

                assertTrue(
                    abs(triggerBounds.top - stripBounds.top) <= 12f,
                    "Expected merged header row on 752x552 bottom sheet, but triggerTop=${triggerBounds.top}, stripTop=${stripBounds.top}",
                )
                val scrollTarget = Offset(752f * 0.65f, 552f * 0.72f)
                assertTrue(
                    bodyBounds.top < scrollTarget.y,
                    "Expected companion-module-body.top (${bodyBounds.top}) < smoke scroll Y (${scrollTarget.y})",
                )
                assertTrue(
                    bodyBounds.height >= 150f,
                    "Expected companion-module-body.height >= 150f on 752x552, actual=${bodyBounds.height}",
                )

                val kpTopBefore = onNodeWithTag("companion-kp-0").fetchSemanticsNode().boundsInRoot.top
                onRoot().performMouseInput {
                    moveTo(scrollTarget)
                    scroll(6f)
                }
                waitForIdle()
                val kpTopAfter = onNodeWithTag("companion-kp-0").fetchSemanticsNode().boundsInRoot.top
                assertTrue(
                    kpTopAfter < kpTopBefore,
                    "Expected scroll at $scrollTarget to move keypoints down (top $kpTopBefore -> $kpTopAfter)",
                )
                // Scroll back to top before switching tabs so pretest options start in view
                onRoot().performMouseInput {
                    moveTo(scrollTarget)
                    scroll(-6f)
                }
                waitForIdle()

                // Switch to pretest module, select option 0 while visible, then scroll at the exact smoke coordinate to reveal 提交
                onNodeWithTag("companion-mod-pretest").performClick()
                waitForIdle()
                onNodeWithTag("pretest-opt-0-0").assertIsDisplayed().performClick()
                waitForIdle()
                repeat(8) {
                    val submitBounds = onNodeWithTag("pretest-submit-0").fetchSemanticsNode().boundsInRoot
                    if (submitBounds.top >= bodyBounds.top && submitBounds.bottom <= 552f) return@repeat
                    onRoot().performMouseInput {
                        moveTo(scrollTarget)
                        scroll(2f)
                    }
                    waitForIdle()
                }
                val finalSubmitBounds = onNodeWithTag("pretest-submit-0").fetchSemanticsNode().boundsInRoot
                assertTrue(
                    finalSubmitBounds.top >= bodyBounds.top && finalSubmitBounds.bottom <= 552f,
                    "Expected pretest-submit-0 to scroll into visible body [${bodyBounds.top}, 552f], actual=$finalSubmitBounds",
                )
                onNodeWithTag("pretest-submit-0").assertIsDisplayed().performClick()
                waitForIdle()
                onNodeWithTag("pretest-feedback-0").assertExists()

                // Mode switcher popover still opens cleanly from the merged header trigger
                onNodeWithTag("companion-mode-trigger").performClick()
                waitForIdle()
                onNodeWithTag("mode-option-BOOK_MAP").assertIsDisplayed()
            }

            // 2. Narrow docked side drawer at 1280x800 (< 520dp drawer width) keeps stacked 2-row header
            val libSide = TextbookLibrary(root.resolve("lib-side"))
            val bookSide = libSide.importBook(md)
            val groupSide = assertNotNull(ChapterCompanionGrouping.groupForSection(bookSide, 0))
            libSide.saveChapterCompanion(bookSide.id, groupSide.chapterId, samplePack(bookSide, groupSide))
            val controllerSide = TextbookController(libSide)
            runBlocking {
                controllerSide.load()
                controllerSide.read()
            }

            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                setContent {
                    WindowsTextbookHost(controller = controllerSide, chooseBook = { null })
                }
                waitForIdle()
                onNodeWithTag("textbook-reader").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.L) }
                waitForIdle()

                val triggerBounds = onNodeWithTag("companion-mode-trigger").fetchSemanticsNode().boundsInRoot
                val stripBounds = onNodeWithTag("companion-module-strip").fetchSemanticsNode().boundsInRoot
                assertTrue(
                    stripBounds.top >= triggerBounds.bottom - 1f,
                    "Expected stacked 2-row header on narrow docked drawer, but stripTop=${stripBounds.top}, triggerBottom=${triggerBounds.bottom}",
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
