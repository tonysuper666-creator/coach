package com.coach.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.skiko.SkiaLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JDialog
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.min

/**
 * Real desktop live-window (`ComposeWindow`) verification for TON-1800 (2.0-AI-1):
 * - Verifies Home focus card `NeedScore` recommendation line and single `PrimaryButton("继续")`
 * - Verifies adaptive companion drawer folding of basic jargon when `p_mastery >= 0.85`
 * - Injects page turns + backtrack rereads + section-end traversal into a real desktop window,
 *   measuring:
 *     * `modalDialogs == 0`
 *     * `focusSteals == 0` (keyboard `→` paging keeps working uninterrupted across whisper & checkpoint)
 *     * `pageOverlapPx == 0` (`MarginWhisperStrip` & `EndOfSectionCheckpoint` never overlap `reader-page`)
 *     * Every triggered margin whisper exports non-empty `trigger_evidence_json` and page anchor
 * - Supports `-Pcoach.ai1.soakSeconds=1800` + `-Pcoach.ai1.reportOut=<path>` for the full 30-minute
 *   winbox real-window soak verification in an isolated temp data directory.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class QuietProactivityLiveWindowTest {

    private data class LiveSurface(val layer: SkiaLayer, val mediator: Any, val focusable: Boolean)

    @Test
    fun realWindowQuietProactivityZeroModalZeroFocusStealZeroOverlapAndEvidenceAudit() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val soakSeconds = System.getProperty("coach.ai1.soakSeconds")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val reportOut = System.getProperty("coach.ai1.reportOut")?.takeIf { it.isNotBlank() }?.let(Path::of)

        val root = Files.createTempDirectory("coach-ai1-live-window-")
        try {
            val syntheticBook = root.resolve("synthetic-econ-study.md").also { file ->
                val content = buildString {
                    // 第一个一级标题会被当成书名；TON-1821 起章节按目录走，书名那一节不归任何章节。
                    // 先给书名，1.1 才是目录里的第一节，sec1 才有伴读章节可放缓存包。
                    append("# Synthetic Econ Study\n\n")
                    for (sec in 1..6) {
                        for (p in 1..3) {
                            append("# $sec.$p Core Mechanism $sec.$p\n\n")
                            append("Section $sec.$p explains equilibrium dynamics, marginal user cost relationships, and comparative statics in clear analytical detail. ")
                            append("When depreciation rises, the horizontal user cost curve shifts upward while the marginal efficiency schedule stays fixed. ")
                            append("Separating stock variables from flow variables prevents conceptual confusion when reading multi-period models.\n\n")
                            append("Graph shift analysis for topic $sec.$p illustrates how parameter changes move equilibrium points along the curve versus shifting the entire schedule. ")
                            append("Careful inspection of axis labels, slopes, and boundary conditions makes every derivation transparent.\n\n")
                        }
                    }
                }
                Files.writeString(file, content)
            }
            val libDir = root.resolve("library")
            val library = TextbookLibrary(libDir)
            val book = library.importBook(syntheticBook)
            assertTrue("expected multi-page synthetic book (got ${book.pages.size})", book.pages.size >= 18)
            assertTrue("expected multiple sections (got ${book.lessons.size})", book.lessons.size >= 3)

            // 1. Seed a mastered section (>= 0.85) for companion jargon folding check and a weak section with error tag
            val sec1 = book.lessons[0]
            val sec2 = book.lessons[1]
            val sec3 = book.lessons[2]
            repeat(4) {
                library.recordMasteryEvidence(
                    bookId = book.id,
                    tocNodeId = sec1.id,
                    page = sec1.firstPage,
                    canonicalName = sec1.title,
                    signal = LearnerSignalType.POST_QUIZ_T1,
                    scoreRatio = 1.0,
                    guessed = false,
                )
            }
            library.recordMasteryEvidence(
                bookId = book.id,
                tocNodeId = sec2.id,
                page = sec2.firstPage,
                canonicalName = sec2.title,
                signal = LearnerSignalType.MISCONCEPTION_DIAG,
                errorClass = MisconceptionErrorClass.GRAPH_SHIFT_OR_ROTATION,
                syllabusWeight = 1.6,
            )
            val group1 = ChapterCompanionGrouping.groupForSection(book, 0)
            if (group1 != null) {
                library.saveChapterCompanion(
                    book.id,
                    group1.chapterId,
                    BookCompanionPack(
                        bookId = book.id,
                        chapterId = group1.chapterId,
                        chapterTitle = group1.chapterTitle,
                        coreQuestion = "核心均衡机制如何传导？",
                        keyPoints = listOf(BookCompanionKeyPoint("kp-1", sec1.id, "先分清存量与流量，再看边际条件。")),
                        jargonGlosses = listOf(BookCompanionJargon("marginal cost", "多增加一单位投入对应的边际成本。")),
                        optionalPractice = listOf(
                            BookCompanionQuiz(
                                id = "cp-q1",
                                sectionId = sec1.id,
                                stem = "下列哪一项属于存量变量？",
                                options = listOf("健康资本存量", "单期医疗消费"),
                                correctOption = "健康资本存量",
                                explanation = "存量在时点上度量，流量在时期内度量。",
                            ),
                        ),
                    ),
                )
            }

            val controller = TextbookController(library)
            runBlocking { controller.load() }

            val dwellMs = if (soakSeconds >= 60L) 1_000L else 80L
            val window = edt {
                ComposeWindow().apply {
                    title = "Coach 2.0-AI-1 Quiet Proactivity Verification"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(
                        screen.x + 30,
                        screen.y + 30,
                        minOf(1360, (screen.width - 60).coerceAtLeast(800)),
                        minOf(860, (screen.height - 80).coerceAtLeast(600)),
                    )
                    setContent {
                        CompositionLocalProvider(LocalChapterDwellMs provides dwellMs) {
                            WindowsTextbookHost(controller) { null }
                        }
                    }
                    isVisible = true
                }
            }

            var modalDialogs = 0
            var focusSteals = 0
            var maxPageOverlapPx = 0
            var totalPageTurns = 0
            var backtrackCycles = 0
            var checkpointSeen = false
            var jargonCollapsedSeen = false
            val startedAtMs = System.currentTimeMillis()

            try {
                // Step A: Verify Home focus card displays plain-Chinese NeedScore recommendation & single PrimaryButton
                waitFor("home focus card", window) {
                    node(window, "home-focus-card") != null && node(window, "home-read") != null
                }
                val homeMeta = nodeText(node(window, "home-focus-meta"))
                assertTrue("Home meta must show NeedScore basis, was: $homeMeta", "图形移动方向" in homeMeta)

                // Open book at sec1.firstPage to begin reading trajectory
                runBlocking { controller.openBook(book.id, sec1.firstPage) }
                waitFor("reader ready with non-zero canvas", window) {
                    node(window, "reader-page") != null &&
                        edt {
                            activeSurfaces(window).firstOrNull()
                                ?.let { it.layer.canvas.width > 0 && it.layer.canvas.height > 0 } == true
                        }
                }
                Thread.sleep(250L)

                // Check companion adaptive jargon folding on mastered sec1
                revealHudViaMediator(window)
                if (node(window, "immersive-companion-drawer") == null &&
                    waitUntilTrue(window, timeoutSeconds = 3) { node(window, "immersive-companion-toggle") != null }
                ) {
                    click(window, "immersive-companion-toggle")
                }
                if (node(window, "immersive-companion-drawer") == null) {
                    sendKeyViaMediator(window, KeyEvent.VK_L, 'l')
                }
                waitFor("companion drawer open", window) { node(window, "single-book-companion") != null }
                jargonCollapsedSeen = waitUntilTrue(window, timeoutSeconds = 4) {
                    node(window, "companion-jargon-collapsed") != null
                }
                val companionTags = nodes(window).mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.distinct()
                assertTrue(
                    "Mastered section (>=0.85) must default-collapse basic jargon; sec1=${sec1.id} p=${sec1.firstPage} mastery=${library.conceptMasteryForBook(book.id)} tags=$companionTags",
                    jargonCollapsedSeen,
                )
                if (node(window, "companion-close") != null) {
                    click(window, "companion-close")
                } else {
                    sendKeyViaMediator(window, KeyEvent.VK_L, 'l')
                }
                waitUntilTrue(window, timeoutSeconds = 3) { node(window, "immersive-companion-drawer") == null }
                Thread.sleep(250L)

                fun checkWindowInvariants() {
                    modalDialogs += countModalDialogs(window)
                    val readerRect = node(window, "reader-page")?.boundsInRoot
                    val whisperRect = node(window, "margin-whisper-strip")?.boundsInRoot
                    val checkpointRect = node(window, "end-of-section-checkpoint")?.boundsInRoot
                    if (readerRect != null && whisperRect != null) {
                        maxPageOverlapPx = max(maxPageOverlapPx, overlapAreaPx(readerRect, whisperRect))
                    }
                    if (readerRect != null && checkpointRect != null) {
                        maxPageOverlapPx = max(maxPageOverlapPx, overlapAreaPx(readerRect, checkpointRect))
                    }
                    if (checkpointRect != null) checkpointSeen = true
                }

                fun turnRightExpectingNoFocusSteal() {
                    val before = controller.state.value.page
                    val hasNext = book.pages.any { it.number > before }
                    if (!hasNext) return
                    sendKeyViaMediator(window, KeyEvent.VK_RIGHT)
                    val moved = waitUntilTrue(window, timeoutSeconds = 3) { controller.state.value.page > before }
                    if (!moved) {
                        focusSteals += 1
                        runBlocking { controller.page(before + 1) }
                    } else {
                        totalPageTurns += 1
                    }
                    checkWindowInvariants()
                }

                fun turnLeftExpectingNoFocusSteal() {
                    val before = controller.state.value.page
                    if (before <= 1) return
                    sendKeyViaMediator(window, KeyEvent.VK_LEFT)
                    val moved = waitUntilTrue(window, timeoutSeconds = 3) { controller.state.value.page < before }
                    if (!moved) {
                        focusSteals += 1
                        runBlocking { controller.page(before - 1) }
                    } else {
                        totalPageTurns += 1
                    }
                    checkWindowInvariants()
                }

                // Step B: Walk through sec1 (genuine dwell on pages up to sec1.lastPage) and cross into sec2
                // -> triggers EndOfSectionCheckpoint (non-blocking) + MarginWhisperStrip on sec2 (prior error guard)
                while (controller.state.value.page <= sec1.lastPage) {
                    Thread.sleep(dwellMs + 140L)
                    checkWindowInvariants()
                    turnRightExpectingNoFocusSteal()
                }
                Thread.sleep(dwellMs + 180L)
                checkWindowInvariants()
                assertTrue("sec2 prior-error whisper should appear", waitUntilTrue(window, timeoutSeconds = 4) {
                    node(window, "margin-whisper-strip") != null
                })
                checkWindowInvariants()
                // Pressing → while whisper & checkpoint are visible must NOT steal focus!
                turnRightExpectingNoFocusSteal()

                // Step C: Walk into sec3 and perform genuine backtrack reread trajectory (p -> p+1 -> p -> p+1 -> p)
                val targetBacktrackPage = sec3.firstPage
                while (controller.state.value.page < targetBacktrackPage) {
                    Thread.sleep(dwellMs + 100L)
                    turnRightExpectingNoFocusSteal()
                }
                Thread.sleep(dwellMs + 140L)
                turnRightExpectingNoFocusSteal()
                Thread.sleep(dwellMs + 140L)
                turnLeftExpectingNoFocusSteal()
                Thread.sleep(dwellMs * 2 + 140L)
                turnRightExpectingNoFocusSteal()
                Thread.sleep(dwellMs + 140L)
                turnLeftExpectingNoFocusSteal()
                Thread.sleep(dwellMs * 3 + 200L)
                backtrackCycles += 1
                checkWindowInvariants()

                // Step D: Optional continuous wall-clock soak (1800s on winbox)
                if (soakSeconds > 0L) {
                    val deadlineMs = startedAtMs + soakSeconds * 1000L
                    var cycle = 0
                    while (System.currentTimeMillis() < deadlineMs) {
                        val cur = controller.state.value.page
                        if (cur >= book.pages.last().number) {
                            runBlocking { controller.page(1) }
                            Thread.sleep(300L)
                        } else if (cycle % 5 == 3 && cur > 1) {
                            turnLeftExpectingNoFocusSteal()
                            Thread.sleep(min(2_500L, max(200L, deadlineMs - System.currentTimeMillis())))
                            checkWindowInvariants()
                            turnRightExpectingNoFocusSteal()
                            backtrackCycles += 1
                        } else {
                            turnRightExpectingNoFocusSteal()
                        }
                        val remaining = deadlineMs - System.currentTimeMillis()
                        if (remaining > 0L) {
                            Thread.sleep(min(3_000L, remaining))
                        }
                        checkWindowInvariants()
                        cycle += 1
                    }
                }
            } finally {
                edt { window.dispose() }
            }

            val elapsedSec = ((System.currentTimeMillis() - startedAtMs) / 1000.0)
            val audits = library.marginWhisperAudits(book.id)

            val reportJson = buildJsonObject {
                put("elapsedSec", elapsedSec)
                put("soakTargetSec", soakSeconds)
                put("totalPageTurns", totalPageTurns)
                put("backtrackCycles", backtrackCycles)
                put("modalDialogs", modalDialogs)
                put("focusSteals", focusSteals)
                put("pageOverlapPx", maxPageOverlapPx)
                put("checkpointSeen", checkpointSeen)
                put("jargonCollapsedSeen", jargonCollapsedSeen)
                put("whispersCount", audits.size)
                put(
                    "whispers",
                    buildJsonArray {
                        for (w in audits) {
                            add(
                                buildJsonObject {
                                    put("whisperId", w.whisperId)
                                    put("rule", w.rule.name)
                                    put("page", w.page)
                                    put("pdfPageAnchor", w.pdfPageAnchor)
                                    put("quoteAnchor", w.quoteAnchor)
                                    put("collapsedText", w.collapsedText)
                                    put("triggerEvidenceJson", w.triggerEvidenceJson)
                                },
                            )
                        }
                    },
                )
            }.toString()

            if (reportOut != null) {
                reportOut.parent?.let { Files.createDirectories(it) }
                Files.writeString(reportOut, reportJson)
            }
            println("AI1_LIVE_REPORT=$reportJson")

            assertEquals("modalDialogs must be 0", 0, modalDialogs)
            assertEquals("focusSteals must be 0", 0, focusSteals)
            assertEquals("pageOverlapPx must be 0", 0, maxPageOverlapPx)
            assertTrue("expected at least 1 persisted margin whisper audit", audits.isNotEmpty())
            for (audit in audits) {
                assertTrue("trigger_evidence_json must be non-empty", audit.triggerEvidenceJson.isNotBlank())
                assertTrue("pdfPageAnchor must be valid page", audit.pdfPageAnchor in 1..book.pages.size)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun overlapAreaPx(a: Rect, b: Rect): Int {
        val left = max(a.left, b.left)
        val right = min(a.right, b.right)
        val top = max(a.top, b.top)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0
        return ((right - left) * (bottom - top)).toInt()
    }

    private fun countModalDialogs(owner: ComposeWindow): Int = edt {
        Window.getWindows().count { w ->
            w.isShowing && w !== owner && (w is JDialog && w.isModal || w is java.awt.Dialog && w.isModal)
        }
    }

    private fun reflectField(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            runCatching {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(target)
            }
            c = c.superclass
        }
        return null
    }

    private fun invokeDeclared(target: Any, name: String, paramType: Class<*>, arg: Any): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            runCatching {
                val m = c.getDeclaredMethod(name, paramType)
                m.isAccessible = true
                return m.invoke(target, arg)
            }
            c = c.superclass
        }
        throw NoSuchMethodException("${target.javaClass.name}.$name(${paramType.name})")
    }

    private fun collectSkiaLayers(c: Component): List<SkiaLayer> {
        val res = mutableListOf<SkiaLayer>()
        if (c is SkiaLayer && c.isShowing) res += c
        if (c is Container) {
            for (ch in c.components) res += collectSkiaLayers(ch)
        }
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
            for (layer in collectSkiaLayers(comp)) {
                out += LiveSurface(layer, mediator, focusable)
            }
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
        val take = manager.javaClass.methods.first { it.name.startsWith("takeFocus-") }
        take.invoke(manager, enter)
    }

    private fun nodes(window: ComposeWindow): List<SemanticsNode> = edt {
        val owners = mutableListOf<SemanticsOwner>()
        val panel = reflectField(window, "composePanel")
        if (panel != null) {
            val getContainer = panel.javaClass.getDeclaredMethod("getComposeContainer").apply { isAccessible = true }
            val container = getContainer.invoke(panel)
            if (container != null) {
                val getOwners = container.javaClass.methods.firstOrNull { it.name == "getSemanticsOwners" }
                if (getOwners != null) {
                    @Suppress("UNCHECKED_CAST")
                    owners += (getOwners.invoke(container) as? Collection<SemanticsOwner>).orEmpty()
                }
            }
        }
        val out = mutableListOf<SemanticsNode>()
        fun visit(n: SemanticsNode) {
            out += n
            n.children.forEach(::visit)
        }
        owners.forEach { visit(it.unmergedRootSemanticsNode) }
        out
    }

    private fun node(window: ComposeWindow, tag: String): SemanticsNode? =
        nodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun nodeText(n: SemanticsNode?): String =
        n?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun revealHudViaMediator(window: ComposeWindow) {
        edt {
            val main = activeSurfaces(window).firstOrNull()
            if (main != null) {
                runCatching { takeLogicalFocus(main.mediator) }
                val lx = (main.layer.canvas.width / 2).coerceAtLeast(10)
                val ly = 8
                val now = System.currentTimeMillis()
                invokeDeclared(
                    main.mediator,
                    "onMouseEvent",
                    MouseEvent::class.java,
                    MouseEvent(main.layer.canvas, MouseEvent.MOUSE_ENTERED, now, 0, lx, ly, 0, false, MouseEvent.NOBUTTON),
                )
                invokeDeclared(
                    main.mediator,
                    "onMouseEvent",
                    MouseEvent::class.java,
                    MouseEvent(main.layer.canvas, MouseEvent.MOUSE_MOVED, now + 1, 0, lx, ly, 0, false, MouseEvent.NOBUTTON),
                )
            }
            runCatching { window.renderImmediately() }
        }
        Thread.sleep(150)
    }

    private fun click(window: ComposeWindow, tag: String) {
        waitFor(tag, window) { node(window, tag) != null }
        edt {
            val target = requireNotNull(node(window, tag)) { "Missing node $tag" }
            assertTrue("Click failed on $tag", target.config[SemanticsActions.OnClick].action!!.invoke())
            runCatching { window.renderImmediately() }
        }
        Thread.sleep(40)
    }

    private fun sendKeyViaMediator(window: ComposeWindow, keyCode: Int, keyChar: Char = KeyEvent.CHAR_UNDEFINED) {
        edt {
            val surfaces = activeSurfaces(window)
            val target = surfaces.lastOrNull { it.focusable } ?: surfaces.firstOrNull()
            val now = System.currentTimeMillis()
            if (target != null) {
                runCatching { takeLogicalFocus(target.mediator) }
                invokeDeclared(
                    target.mediator,
                    "onKeyEvent",
                    KeyEvent::class.java,
                    KeyEvent(target.layer.canvas, KeyEvent.KEY_PRESSED, now, 0, keyCode, keyChar),
                )
                invokeDeclared(
                    target.mediator,
                    "onKeyEvent",
                    KeyEvent::class.java,
                    KeyEvent(target.layer.canvas, KeyEvent.KEY_RELEASED, now + 1, 0, keyCode, keyChar),
                )
            }
            runCatching { window.renderImmediately() }
        }
        Thread.sleep(100)
    }

    private fun waitUntilTrue(
        window: ComposeWindow,
        timeoutSeconds: Long = 3,
        pollMs: Long = 40,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        while (true) {
            edt { runCatching { window.renderImmediately() } }
            if (runCatching(condition).getOrDefault(false)) return true
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(pollMs)
        }
    }

    private fun waitFor(label: String, window: ComposeWindow, timeoutSeconds: Long = 15, condition: () -> Boolean) {
        val ok = waitUntilTrue(window, timeoutSeconds = timeoutSeconds, condition = condition)
        assertTrue("Timed out waiting for: $label", ok)
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
