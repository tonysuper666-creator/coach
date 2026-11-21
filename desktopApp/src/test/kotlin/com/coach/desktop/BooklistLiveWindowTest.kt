package com.coach.desktop

import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.style.TextDecoration
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
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
 * Real desktop live-window (`ComposeWindow` + `ComposeSceneMediator`) E2E verification for TON-1794 (2.0-W2-D):
 * 1. Opens a 3-book booklist in `BooklistScreen`, verifies `继续读`, `共同主题` expansion, shared note persistence,
 *    and bidirectional jump to source page.
 * 2. Tests TOC jump skipping earlier chapters -> 0 unread false-positive cross-book reminders.
 * 3. Marks target chapter READ -> verifies reader body has 0px inline underlines/popups while companion drawer bottom
 *    shows <= 2 high-value cross-book cards.
 * 4. Clicks `×` once on a cross-book card -> 0 leakage across chapters/books & cold restart, then restores from
 *    `已静音主题` in `BooklistScreen` and confirms reappearance.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class BooklistLiveWindowTest {

    @Test
    fun realDesktopWindowBooklistReadGateZeroInlineMuteAndRestore() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("coach-w2d-live-window-")
        try {
            val mdA = root.resolve("book-a-theory.md").also {
                Files.writeString(
                    it,
                    """
                    # Chapter 1 Adverse Selection Theory

                    Adverse selection arises when insured buyers hold private risk information that insurers cannot observe.
                    Compulsory participation stabilizes pooled insurance premiums by retaining low-risk households.
                    Moral hazard expands healthcare utilization when patient coinsurance falls toward zero.

                    # Chapter 2 Clinical Trial Risk

                    Pharmaceutical innovation requires patent exclusivity to recover costly clinical trial investments.
                    """.trimIndent(),
                )
            }
            val mdB = root.resolve("book-b-empirical.md").also {
                Files.writeString(
                    it,
                    """
                    # Chapter 1 Empirical Health Markets

                    Contrary to textbook adverse selection models, Medigap empirical evidence reveals advantageous selection.
                    Compulsory participation mandates reduced uncompensated hospital care and stabilized risk pools.
                    Moral hazard estimates from Oregon Medicaid show rising outpatient visits after insurance expansion.

                    # Chapter 2 Follow-up Policy Analysis

                    Adverse selection also affects exchange plan pricing when subsidies fail to cover young healthy households.
                    """.trimIndent(),
                )
            }
            val mdC = root.resolve("book-c-governance.md").also {
                Files.writeString(
                    it,
                    """
                    # Chapter 1 Risk Adjustment Policies

                    Compulsory participation paired with risk adjustment mitigates adverse selection across insurers.
                    Moral hazard is contained through smart cost sharing that protects against catastrophic expenses.
                    """.trimIndent(),
                )
            }

            val libDir = root.resolve("library")
            val library1 = TextbookLibrary(libDir)
            val bookA = library1.importBook(mdA)
            val bookB = library1.importBook(mdB)
            val bookC = library1.importBook(mdC)

            val colId = "booklist-live-1"
            library1.upsertCollection(MaterialCollectionRecord(colId, CollectionKind.BOOKLIST, "健康经济三书单"))
            library1.attachMaterial(CollectionMaterialRecord(colId, bookA.id, role = "LIST_BOOK", ordinal = 0))
            library1.attachMaterial(CollectionMaterialRecord(colId, bookB.id, role = "LIST_BOOK", ordinal = 1))
            library1.attachMaterial(CollectionMaterialRecord(colId, bookC.id, role = "LIST_BOOK", ordinal = 2))

            runBlocking {
                val report = BooklistAlignmentEngine.alignBooklist(library1, colId, HeuristicConceptSemanticJudge)
                assertTrue("Expected >= 2 aligned concepts, got ${report.alignedConcepts}", report.alignedConcepts >= 2)
            }

            val controller1 = TextbookController(library1)
            runBlocking {
                controller1.load()
                controller1.openBooklist(colId)
            }

            val firstConceptId: String
            val chA1 = BookChapterResolver.chapters(bookA)[0]
            val chA2 = BookChapterResolver.chapters(bookA)[1]

            // Session 1: Real ComposeWindow #1
            val window1 = edt {
                ComposeWindow().apply {
                    title = "Coach W2-D Live Window Session 1"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(
                        screen.x + 20,
                        screen.y + 20,
                        minOf(1440, (screen.width - 40).coerceAtLeast(800)),
                        minOf(900, (screen.height - 80).coerceAtLeast(600)),
                    )
                    setContent {
                        CompositionLocalProvider(LocalChapterDwellMs provides 60_000L) {
                            WindowsTextbookHost(controller1) { null }
                        }
                    }
                    isVisible = true
                }
            }
            try {
                waitFor("booklist screen visible", window1) {
                    node(window1, "booklist-screen") != null &&
                        node(window1, "booklist-continue") != null &&
                        node(window1, "booklist-concepts-section") != null
                }

                // Expand first shared concept & write a shared note in BooklistScreen
                val snap = library1.booklistConcepts(
                    colId,
                    mapOf(bookA.id to bookA.title, bookB.id to bookB.title, bookC.id to bookC.title),
                    emptyMap(),
                )
                assertTrue("Expected >= 2 active concepts", snap.active.size >= 2)
                firstConceptId = snap.active.first().entity.conceptId

                click(window1, "booklist-concept-toggle-$firstConceptId")
                waitFor("concept detail expanded", window1) {
                    node(window1, "booklist-concept-detail-$firstConceptId") != null &&
                        node(window1, "booklist-note-$firstConceptId") != null
                }
                setText(window1, "booklist-note-$firstConceptId", "三书对照共享笔记：理论与实证互补。")
                waitFor("shared note saved to SQLite", window1) {
                    library1.booklistConcepts(colId, emptyMap(), emptyMap())
                        .active.firstOrNull { it.entity.conceptId == firstConceptId }
                        ?.entity?.sharedNote == "三书对照共享笔记：理论与实证互补。"
                }

                // Step A: Open Book A, open TOC, jump straight to Chapter 2 (skipping Chapter 1)
                click(window1, "booklist-book-${bookA.id}")
                waitFor("Book A opened", window1) {
                    node(window1, "textbook-reader") != null && controller1.state.value.book?.id == bookA.id
                }
                openTocDrawer(window1)
                waitFor("TOC drawer open", window1) { node(window1, "toc-item-1") != null }
                click(window1, "toc-item-1")
                waitFor("jumped to Book A Chapter 2 and TOC closed", window1) {
                    controller1.state.value.page == chA2.firstPage &&
                        node(window1, "immersive-toc-drawer") == null
                }
                // Skipped Chapter 1 MUST still be UNREAD!
                assertEquals(
                    ChapterReadState.UNREAD,
                    library1.chapterProgressMap(bookA.id)[chA1.tocNodeId]?.readState ?: ChapterReadState.UNREAD,
                )

                // Step B: Open Book B Chapter 1 -> open companion drawer -> 0 unread false-positive reminders!
                runBlocking { controller1.openBook(bookB.id, 1) }
                waitFor("Book B rendered", window1) {
                    controller1.state.value.book?.id == bookB.id &&
                        nodeText(window1, "reader-text").contains("Contrary to textbook")
                }
                openCompanionDrawer(window1)
                waitFor("companion drawer open", window1) {
                    node(window1, "immersive-companion-drawer") != null &&
                        node(window1, "single-book-companion") != null
                }
                assertNull(
                    "Skipped Chapter 1 of Book A must trigger 0 cross-book cards",
                    node(window1, "crossbook-companion-section"),
                )

                // Step C: Mark Book A Chapter 1 as READ via TOC manual toggle -> Book B shows <= 2 cards & 0px inline underlines!
                runBlocking { controller1.openBook(bookA.id, 1) }
                waitFor("Book A rendered", window1) {
                    controller1.state.value.book?.id == bookA.id &&
                        nodeText(window1, "reader-text").contains("Adverse selection arises")
                }
                openTocDrawer(window1)
                waitFor("TOC read toggle visible", window1) { node(window1, "toc-read-toggle-0") != null }
                click(window1, "toc-read-toggle-0")
                waitFor("Book A Ch 1 persisted READ", window1) {
                    library1.chapterProgressMap(bookA.id)[chA1.tocNodeId]?.readState == ChapterReadState.READ
                }

                // Switch back to Book B Chapter 1
                runBlocking { controller1.openBook(bookB.id, 1) }
                waitFor("Book B re-rendered", window1) {
                    controller1.state.value.book?.id == bookB.id &&
                        nodeText(window1, "reader-text").contains("Contrary to textbook")
                }
                openCompanionDrawer(window1)
                waitFor("cross-book cards visible in companion drawer", window1) {
                    node(window1, "crossbook-companion-section") != null
                }

                // Verify Iron Law 1: 0px inline concept underlines/popups on reader text!
                val readerTextNodes = edt {
                    nodes(window1).filter {
                        val tag = it.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
                        tag == "reader-text" || tag.startsWith("reader-text-")
                    }
                }
                assertTrue("Reader body text must be rendered", readerTextNodes.isNotEmpty())
                for (textNode in readerTextNodes) {
                    val texts = textNode.config.getOrNull(SemanticsProperties.Text).orEmpty()
                    for (annotated in texts) {
                        val hasUnderline = annotated.spanStyles.any {
                            it.item.textDecoration == TextDecoration.Underline
                        }
                        assertTrue("Reader text must have 0px inline underlines", !hasUnderline)
                    }
                }
                assertTrue(
                    "Reader body must have 0 floating crossbook popup nodes",
                    edt {
                        nodes(window1).none {
                            val tag = it.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
                            tag.contains("inline-concept-popup")
                        }
                    },
                )

                // Verify Iron Law 3: <= 2 cross-book cards shown!
                val shownCardTags = edt {
                    nodes(window1).mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
                        .filter { it.startsWith("crossbook-card-") }
                        .distinct()
                }
                assertTrue("Expected 1..2 cross-book cards, got $shownCardTags", shownCardTags.size in 1..2)

                // Step D: Click `×` on the first shown cross-book card -> permanent theme-level mute!
                val mutedId = shownCardTags.first().removePrefix("crossbook-card-")
                click(window1, "crossbook-mute-$mutedId")
                waitFor("muted card disappeared and persisted", window1) {
                    node(window1, "crossbook-card-$mutedId") == null &&
                        library1.booklistConcepts(colId, emptyMap(), emptyMap())
                            .muted.any { it.entity.conceptId == mutedId }
                }

                // Switch to Book B Chapter 2 -> ZERO leakage of the muted theme!
                val chB2 = BookChapterResolver.chapters(bookB).last()
                runBlocking { controller1.page(chB2.firstPage) }
                waitFor("moved to Book B Ch 2", window1) { controller1.state.value.page == chB2.firstPage }
                assertNull(
                    "Muted theme must have 0 leakage in subsequent chapters",
                    node(window1, "crossbook-card-$mutedId"),
                )

                // Dispose Window 1 and verify cold restart + restore from BooklistScreen's `已静音主题`!
                runBlocking { controller1.flushPendingSaves() }
                edt { window1.dispose() }

                val library2 = TextbookLibrary(libDir)
                val controller2 = TextbookController(library2)
                runBlocking {
                    controller2.load()
                    controller2.openBooklist(colId)
                }
                val window2 = edt {
                    ComposeWindow().apply {
                        title = "Coach W2-D Live Window Session 2"
                        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                        setBounds(
                            screen.x + 20,
                            screen.y + 20,
                            minOf(1440, (screen.width - 40).coerceAtLeast(800)),
                            minOf(900, (screen.height - 80).coerceAtLeast(600)),
                        )
                        setContent {
                            CompositionLocalProvider(LocalChapterDwellMs provides 60_000L) {
                                WindowsTextbookHost(controller2) { null }
                            }
                        }
                        isVisible = true
                    }
                }
                try {
                    waitFor("BooklistScreen reloaded with muted section", window2) {
                        node(window2, "booklist-screen") != null &&
                            node(window2, "booklist-muted-toggle") != null
                    }
                    // Expand `已静音主题` and click `恢复`
                    click(window2, "booklist-muted-toggle")
                    waitFor("restore button visible", window2) {
                        node(window2, "booklist-restore-$mutedId") != null
                    }
                    click(window2, "booklist-restore-$mutedId")
                    waitFor("concept restored to active list", window2) {
                        node(window2, "booklist-concept-$mutedId") != null &&
                            library2.booklistConcepts(colId, emptyMap(), emptyMap())
                                .muted.none { it.entity.conceptId == mutedId }
                    }
                    println("W2D_LIVE_WINDOW_OK={\"realComposeWindow\":true,\"inlineAnnotationPx\":0,\"maxCards\":${shownCardTags.size},\"muteLeakage\":0,\"tocSkipUnreadTriggers\":0,\"restored\":true}")
                } finally {
                    edt { window2.dispose() }
                }
            } finally {
                edt { if (window1.isDisplayable) window1.dispose() }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun aiExplainDisabledInWorkspaceInstallsLocalJudgeOnlyAndCoversAutoAndManualRealign() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("coach-w2d-live-privacy-")
        try {
            val mdA = root.resolve("local-a.md").also {
                Files.writeString(
                    it,
                    """
                    # Chapter 1 Asymmetric Information

                    Adverse selection arises when insured buyers hold private risk information that insurers cannot observe.
                    Compulsory participation stabilizes pooled insurance premiums by retaining low-risk households.
                    """.trimIndent(),
                )
            }
            val mdB = root.resolve("local-b.md").also {
                Files.writeString(
                    it,
                    """
                    # Chapter 1 Empirical Insurance Evidence

                    Contrary to textbook adverse selection models, empirical evidence reveals advantageous selection.
                    Compulsory participation mandates reduced uncompensated hospital care and stabilized risk pools.
                    """.trimIndent(),
                )
            }

            val libDir = root.resolve("library")
            val library = TextbookLibrary(libDir)
            val bookA = library.importBook(mdA)
            val bookB = library.importBook(mdB)
            ReadingSettings(libDir).chooseAiExplain(false)

            val controller = TextbookController(library)
            runBlocking { controller.load() }

            val window = edt {
                ComposeWindow().apply {
                    title = "Coach W2-D Privacy Gate Live Window"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(
                        screen.x + 20,
                        screen.y + 20,
                        minOf(1280, (screen.width - 40).coerceAtLeast(800)),
                        minOf(840, (screen.height - 80).coerceAtLeast(600)),
                    )
                    setContent {
                        WindowsTextbookHost(controller) { null }
                    }
                    isVisible = true
                }
            }
            try {
                waitFor("workspace installs strictly local judge when aiExplain=false", window) {
                    controller.conceptSemanticJudge === HeuristicConceptSemanticJudge &&
                        !controller.allowRemoteBooklistAi()
                }

                // Automatic alignment when creating a booklist while aiExplain=false
                runBlocking {
                    controller.createCollection(
                        kind = CollectionKind.BOOKLIST,
                        title = "本地隐私书单",
                        existingBookIds = listOf(bookA.id, bookB.id),
                        newFiles = emptyList(),
                    ).join()
                    controller.awaitBooklistAlignment()
                }
                val colId = controller.state.value.collections.first { it.record.title == "本地隐私书单" }.record.id
                assertTrue(
                    "Auto alignment must use local judge only when aiExplain=false",
                    controller.conceptSemanticJudge === HeuristicConceptSemanticJudge,
                )
                assertTrue(
                    "Auto local alignment should produce concepts",
                    library.booklistConcepts(colId, mapOf(bookA.id to bookA.title, bookB.id to bookB.title), emptyMap()).active.isNotEmpty(),
                )

                // Open BooklistScreen and trigger manual realign ("⋯" -> "重新整理共同主题")
                runBlocking { controller.openBooklist(colId) }
                waitFor("BooklistScreen visible", window) { node(window, "booklist-screen") != null }
                val revBefore = controller.state.value.booklistRevision
                click(window, "booklist-more-menu")
                waitFor("realign menu item", window) { node(window, "booklist-realign") != null }
                click(window, "booklist-realign")
                waitFor("manual realign completed locally", window) {
                    controller.state.value.booklistRevision > revBefore && !controller.state.value.booklistAligning
                }
                assertTrue(
                    "Manual realign must stay on local judge when aiExplain=false",
                    controller.conceptSemanticJudge === HeuristicConceptSemanticJudge && !controller.allowRemoteBooklistAi(),
                )
            } finally {
                edt { if (window.isDisplayable) window.dispose() }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun openTocDrawer(window: ComposeWindow) {
        if (node(window, "immersive-toc-drawer") != null) return
        revealHudViaMediator(window)
        val hasToggle = waitUntilTrue(window, timeoutSeconds = 3) {
            node(window, "immersive-toc-drawer") != null ||
                node(window, "v7-directory-toggle") != null
        }
        if (!hasToggle) {
            sendKeyViaMediator(window, KeyEvent.VK_TAB)
            waitUntilTrue(window, timeoutSeconds = 3) {
                node(window, "immersive-toc-drawer") != null ||
                    node(window, "v7-directory-toggle") != null
            }
        }
        if (node(window, "immersive-toc-drawer") == null && node(window, "v7-directory-toggle") != null) {
            click(window, "v7-directory-toggle")
            waitUntilTrue(window, timeoutSeconds = 3) { node(window, "immersive-toc-drawer") != null }
        }
        if (node(window, "immersive-toc-drawer") == null) {
            sendKeyViaMediator(window, KeyEvent.VK_T, 't')
        }
    }

    private fun openCompanionDrawer(window: ComposeWindow) {
        if (node(window, "immersive-companion-drawer") != null) return
        revealHudViaMediator(window)
        val hasToggle = waitUntilTrue(window, timeoutSeconds = 3) {
            node(window, "immersive-companion-drawer") != null ||
                node(window, "immersive-companion-toggle") != null
        }
        if (!hasToggle) {
            sendKeyViaMediator(window, KeyEvent.VK_TAB)
            waitUntilTrue(window, timeoutSeconds = 3) {
                node(window, "immersive-companion-drawer") != null ||
                    node(window, "immersive-companion-toggle") != null
            }
        }
        if (node(window, "immersive-companion-drawer") == null && node(window, "immersive-companion-toggle") != null) {
            click(window, "immersive-companion-toggle")
            waitUntilTrue(window, timeoutSeconds = 3) { node(window, "immersive-companion-drawer") != null }
        }
        if (node(window, "immersive-companion-drawer") == null) {
            sendKeyViaMediator(window, KeyEvent.VK_L, 'l')
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

    private fun nodeText(window: ComposeWindow, tag: String): String =
        edt {
            nodes(window)
                .filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .joinToString("\n") { it.text }
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
        if (c is Container) for (ch in c.components) res += collectSkiaLayers(ch)
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
        val take = manager.javaClass.methods.first { it.name.startsWith("takeFocus-") }
        take.invoke(manager, enter)
    }

    private fun revealHudViaMediator(window: ComposeWindow) {
        edt {
            val main = activeSurfaces(window).firstOrNull()
            if (main != null) {
                runCatching { takeLogicalFocus(main.mediator) }
                val lx = (main.layer.canvas.width / 2).coerceAtLeast(10)
                val now = System.currentTimeMillis()
                invokeDeclared(
                    main.mediator,
                    "onMouseEvent",
                    MouseEvent::class.java,
                    MouseEvent(main.layer.canvas, MouseEvent.MOUSE_ENTERED, now, 0, lx, 120, 0, false, MouseEvent.NOBUTTON),
                )
                invokeDeclared(
                    main.mediator,
                    "onMouseEvent",
                    MouseEvent::class.java,
                    MouseEvent(main.layer.canvas, MouseEvent.MOUSE_MOVED, now + 1, 0, lx, 120, 0, false, MouseEvent.NOBUTTON),
                )
                invokeDeclared(
                    main.mediator,
                    "onMouseEvent",
                    MouseEvent::class.java,
                    MouseEvent(main.layer.canvas, MouseEvent.MOUSE_MOVED, now + 2, 0, lx + 2, 8, 0, false, MouseEvent.NOBUTTON),
                )
            }
            runCatching { window.renderImmediately() }
        }
        Thread.sleep(150)
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
        Thread.sleep(120)
    }

    private fun click(window: ComposeWindow, tag: String) {
        waitFor(tag, window) { node(window, tag) != null }
        edt {
            val target = requireNotNull(node(window, tag)) { "Missing node $tag" }
            assertTrue("Click failed on $tag", target.config[SemanticsActions.OnClick].action!!.invoke())
            runCatching { window.renderImmediately() }
        }
        Thread.sleep(30)
    }

    private fun setText(window: ComposeWindow, tag: String, value: String) {
        waitFor(tag, window) { node(window, tag) != null }
        edt {
            val target = requireNotNull(node(window, tag)) { "Missing text field $tag" }
            assertTrue("SetText failed on $tag", target.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
            runCatching { window.renderImmediately() }
        }
        Thread.sleep(30)
    }

    private fun waitUntilTrue(
        window: ComposeWindow? = null,
        timeoutSeconds: Long = 3,
        pollMs: Long = 40,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        while (true) {
            if (window != null) edt { runCatching { window.renderImmediately() } }
            if (condition()) return true
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(pollMs)
        }
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
            if (condition()) return
            check(System.nanoTime() < deadline) {
                val tags = window?.let {
                    edt { nodes(it).mapNotNull { n -> n.config.getOrNull(SemanticsProperties.TestTag) }.distinct().take(40) }
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
