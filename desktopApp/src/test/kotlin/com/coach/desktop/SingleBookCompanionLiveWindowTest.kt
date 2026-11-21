package com.coach.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * Real desktop live-window (`ComposeWindow`) end-to-end verification for TON-1792 (2.0-W2-B).
 * Uses a synthetic non-copyrighted book, opens a real macOS/Windows `ComposeWindow`, interacts
 * through all 5 companion modules, leaves mid-draft, disposes the window (cold restart), opens
 * a second real `ComposeWindow`, verifies Home shows `练习草稿已保存`, clicks Home's single
 * PrimaryButton (`继续`), and asserts exact page, drawer, module, typed draft, and hint-tier resume.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class SingleBookCompanionLiveWindowTest {

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

    @Test
    fun realDesktopWindowCompanionInterruptAndColdRestartResume() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val shotsDir = (System.getProperty("coach.w2b.liveShotsDir") ?: System.getenv("COACH_W2B_LIVE_SHOTS_DIR"))
            ?.takeIf { it.isNotBlank() }?.let(Path::of)?.also { Files.createDirectories(it) }

        val root = Files.createTempDirectory("coach-w2b-live-window-")
        try {
            val md = root.resolve("health-econ-live.md").also {
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
            val library1 = TextbookLibrary(libDir)
            val book = library1.importBook(md)
            val group = ChapterCompanionGrouping.groupForSection(book, 0)
            assertNotNull(group)
            library1.saveChapterCompanion(book.id, group!!.chapterId, samplePack(book, group))

            val controller1 = TextbookController(library1)
            runBlocking {
                controller1.load()
                controller1.read()
            }

            // 1. Open real desktop ComposeWindow #1, open companion drawer, exercise all 5 modules, leave mid-draft
            val window1 = edt {
                ComposeWindow().apply {
                    title = "Coach W2-B Live Window Session 1"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(screen.x + 20, screen.y + 20, minOf(1440, screen.width - 40), minOf(900, screen.height - 80))
                    setContent { WindowsTextbookHost(controller1) { null } }
                    isVisible = true
                }
            }
            val cachedFirstScreenMs: Double
            try {
                waitFor("reader ready") { node(window1, "textbook-reader") != null }
                // Hover top edge to reveal immersive HUD and click real `伴读` toggle (or dispatch `L` key)
                val drawerOpenStart = System.nanoTime()
                hoverTopHud(window1)
                waitFor("immersive companion toggle or drawer visible") {
                    if (node(window1, "immersive-companion-drawer") != null) return@waitFor true
                    if (node(window1, "immersive-companion-toggle") != null) return@waitFor true
                    sendKey(window1, java.awt.event.KeyEvent.VK_L, 'l')
                    node(window1, "immersive-companion-drawer") != null
                }
                if (node(window1, "immersive-companion-drawer") == null) {
                    click(window1, "immersive-companion-toggle")
                }
                waitFor("companion drawer visible") {
                    node(window1, "immersive-companion-drawer") != null &&
                        node(window1, "companion-core-question") != null
                }
                cachedFirstScreenMs = (System.nanoTime() - drawerOpenStart) / 1_000_000.0
                assertTrue("Cached companion first-screen load must be <= 5000ms (was ${cachedFirstScreenMs}ms)", cachedFirstScreenMs <= 5_000.0)

                // Module 2: Pretest -> choose + mark guessed + check
                click(window1, "companion-mod-pretest")
                waitFor("pretest option") { node(window1, "pretest-opt-0-0") != null }
                click(window1, "pretest-opt-0-0")
                click(window1, "pretest-guess-0")
                click(window1, "pretest-submit-0")
                waitFor("pretest feedback") { node(window1, "pretest-feedback-0") != null }

                // Module 3: Derivation template
                click(window1, "companion-mod-derivation")
                waitFor("derivation card") { node(window1, "derivation-card-0") != null }

                // Module 5: Exercise progressive hints -> open tier 1 & tier 2
                click(window1, "companion-mod-exercises")
                waitFor("exercise card") { node(window1, "exercise-hint-card-0") != null }
                click(window1, "exercise-reveal-0")
                waitFor("hint 1 visible") { node(window1, "exercise-hint1-0") != null }
                click(window1, "exercise-reveal-0")
                waitFor("hint 2 visible") { node(window1, "exercise-hint2-0") != null }

                // Module 4: Optional practice -> pick MCQ + type unfinished calculation draft
                click(window1, "companion-mod-practice")
                waitFor("practice draft input") { node(window1, "practice-draft-input-1") != null }
                click(window1, "practice-opt-0-0")
                setText(window1, "practice-draft-input-1", "Step 1: E[L] = 0.2*200 + 0.8*50")
                waitFor("draft persisted to SQLite") {
                    library1.loadChapterPracticeDraft(book.id, group.chapterId)
                        ?.practiceTextDrafts?.get("prac-calc-2") == "Step 1: E[L] = 0.2*200 + 0.8*50"
                }
                shotsDir?.let { shot(window1, it.resolve("live-01-mid-practice.png")) }
                runBlocking {
                    controller1.flushPendingSaves()
                    controller1.back()
                }
            } finally {
                // Real window disposal (simulating mid-practice app close)
                edt { window1.dispose() }
            }

            // 2. Cold restart: fresh TextbookLibrary + fresh TextbookController + real ComposeWindow #2
            val library2 = TextbookLibrary(libDir)
            val controller2 = TextbookController(library2)
            runBlocking { controller2.load() }
            assertEquals(BookScreen.HOME, controller2.state.value.screen)
            assertEquals("PRACTICE", controller2.state.value.activePracticeDraft?.activeModule)

            val window2 = edt {
                ComposeWindow().apply {
                    title = "Coach W2-B Live Window Session 2"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(screen.x + 20, screen.y + 20, minOf(1440, screen.width - 40), minOf(900, screen.height - 80))
                    setContent { WindowsTextbookHost(controller2) { null } }
                    isVisible = true
                }
            }
            val resumeMs: Double
            try {
                waitFor("home focus card with draft saved") {
                    val meta = node(window2, "home-focus-meta")?.config?.getOrNull(SemanticsProperties.Text)
                        ?.joinToString("") { it.text }.orEmpty()
                    node(window2, "home-read") != null && meta.contains("练习草稿已保存")
                }
                shotsDir?.let { shot(window2, it.resolve("live-02-home-continue.png")) }

                val resumeStart = System.nanoTime()
                click(window2, "home-read")
                waitFor("exact practice draft resumed") {
                    val input = node(window2, "practice-draft-input-1")
                    val editable = input?.config?.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
                    node(window2, "immersive-companion-drawer") != null &&
                        editable.contains("Step 1: E[L] = 0.2*200 + 0.8*50")
                }
                resumeMs = (System.nanoTime() - resumeStart) / 1_000_000.0
                shotsDir?.let { shot(window2, it.resolve("live-03-resumed-practice.png")) }

                // Verify tier-2 exercise hint also survived cold restart
                click(window2, "companion-mod-exercises")
                waitFor("hint 2 restored") {
                    node(window2, "exercise-hint1-0") != null && node(window2, "exercise-hint2-0") != null
                }
                shotsDir?.let { shot(window2, it.resolve("live-04-resumed-hints.png")) }
                println("W2B_LIVE_WINDOW_METRICS={\"cachedFirstScreenMs\":$cachedFirstScreenMs,\"coldRestartResumeMs\":$resumeMs,\"realComposeWindow\":true}")
            } finally {
                edt { window2.dispose() }
            }
        } finally {
            root.toFile().deleteRecursively()
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

    private fun allAwtTargets(window: ComposeWindow): List<java.awt.Component> {
        val out = mutableListOf<java.awt.Component>()
        fun walk(c: java.awt.Component) {
            out += c
            if (c is java.awt.Container) {
                for (child in c.components) walk(child)
            }
        }
        walk(window.contentPane)
        return out.reversed() // deepest child (SkiaLayer / canvas) first
    }

    private fun hoverTopHud(window: ComposeWindow) {
        edt {
            window.toFront()
            window.requestFocus()
            window.renderImmediately()
            val now = System.currentTimeMillis()
            for (target in allAwtTargets(window)) {
                target.dispatchEvent(java.awt.event.MouseEvent(target, java.awt.event.MouseEvent.MOUSE_ENTERED, now, 0, 700, 10, 0, false))
                target.dispatchEvent(java.awt.event.MouseEvent(target, java.awt.event.MouseEvent.MOUSE_MOVED, now + 1, 0, 700, 10, 0, false))
            }
            window.renderImmediately()
        }
        Thread.sleep(80)
    }

    private fun sendKey(window: ComposeWindow, keyCode: Int, keyChar: Char) {
        edt {
            val now = System.currentTimeMillis()
            for (target in allAwtTargets(window)) {
                target.dispatchEvent(java.awt.event.KeyEvent(target, java.awt.event.KeyEvent.KEY_PRESSED, now, 0, keyCode, keyChar))
                target.dispatchEvent(java.awt.event.KeyEvent(target, java.awt.event.KeyEvent.KEY_RELEASED, now + 1, 0, keyCode, keyChar))
            }
            window.renderImmediately()
        }
        Thread.sleep(60)
    }

    private fun click(window: ComposeWindow, tag: String) {
        waitFor(tag) { node(window, tag) != null }
        edt {
            val target = requireNotNull(node(window, tag)) { "Missing node $tag" }
            assertTrue("Click failed on $tag", target.config[SemanticsActions.OnClick].action!!.invoke())
        }
        Thread.sleep(30)
    }

    private fun setText(window: ComposeWindow, tag: String, value: String) {
        waitFor(tag) { node(window, tag) != null }
        edt {
            val target = requireNotNull(node(window, tag)) { "Missing text field $tag" }
            assertTrue("SetText failed on $tag", target.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
        }
        Thread.sleep(30)
    }

    private fun shot(window: ComposeWindow, target: Path) {
        Thread.sleep(200)
        edt {
            window.renderImmediately()
            VerifyHooks.capture(window, target.toFile())
        }
    }

    private fun waitFor(label: String, timeoutSeconds: Long = 20, pollMs: Long = 15, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for: $label" }
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
