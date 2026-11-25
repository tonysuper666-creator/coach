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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.skiko.SkiaLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import javax.swing.SwingUtilities

/**
 * Real desktop live-window (`ComposeWindow`) end-to-end verification for TON-1802 (2.0-AI-3):
 * 1. CompanionAskPane scope switcher (`本节 | 整门课`) inside `TextbookReader` asks a multi-material conflict question,
 *    renders verified citation capsules + `各材料说法不一` conflict card, and strips unverified citations.
 * 2. Clicking a citation capsule targeting another book (`核心教材` or `第4周录音 [42:41]`) switches the left reader
 *    to that book/page/timestamp in 1 click while keeping the Q&A thread open!
 * 3. `HostSearchDialog` (`搜索与跨材料提问`) triggers collection Q&A and jumps straight to the cited source page in 1 click.
 * 4. Booklist `已读书目` scope strictly filters out unread chapters (`0` spoilers).
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class CollectionQaLiveWindowTest {

    @Test
    fun realWindowCourseAskCapsulesConflictJumpHostSearchAndBooklistAntiSpoiler() {
        assumeTrue("Live ComposeWindow verification requires non-headless display", !GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("coach-ai3-live-window-")
        try {
            val libDir = root.resolve("library")
            val srcDir = root.resolve("sources")
            Files.createDirectories(srcDir)

            val slides = srcDir.resolve("Week5-Lecture-Slides.md").also {
                Files.writeString(
                    it,
                    "# Week 5 Rothschild-Stiglitz Model\nInsurers lose money on contracts northeast of the zero-profit line.\n",
                )
            }
            val textbookFiller = "This textbook section introduces competitive insurance equilibrium assumptions and consumer utility schedules. ".repeat(22)
            val textbook = srcDir.resolve("Core-Textbook.md").also {
                Files.writeString(
                    it,
                    "# Chapter 7 Insurance Markets\n$textbookFiller\nBottom page theorem: Any contract strictly to the right of the zero-profit line incurs losses.\n",
                )
            }
            val transcript = srcDir.resolve("Week4-Recording.md").also {
                Files.writeString(
                    it,
                    "[42:25] First suppose healthy buyers satisfy weak inequality >= 700.\n" +
                        "[42:41] Wait, correction: it must be a strict inequality > 700 so no insurance is bought.\n",
                )
            }
            val booklistUnread = srcDir.resolve("Booklist-Second-Book.md").also {
                Files.writeString(
                    it,
                    "# Chapter 1 Hidden Second Book\nSpoiler secret formula alpha=999 lies in this unread book.\n",
                )
            }

            val library = TextbookLibrary(libDir, lazyPdfExtras = false)
            val bSlides = library.importBook(slides)
            val bTextbook = library.importBook(textbook)
            val bTranscript = library.importBook(transcript)
            val bUnread = library.importBook(booklistUnread)

            val courseId = library.withCourseDatabase { db ->
                val (cid, _) = CourseStore.createCourse(
                    db,
                    title = "Health Economics 2.0",
                    materialBookIds = listOf(bSlides.id, bTextbook.id, bTranscript.id),
                )
                CourseAutoAligner.alignLocally(db, cid)
                val detail = CourseStore.loadCourseDetail(db, cid)
                val topic = detail.allTopics.first()
                val textPage = bTextbook.pages.first { "to the right of the zero-profit line" in it.text }.number
                CourseStore.saveTopicPack(
                    db,
                    topic.topicId,
                    CourseTopicPack(
                        topicId = topic.topicId,
                        summary = "Zero-profit boundary across slides and textbook",
                        authorityNotice = "L1 slides precede L2 textbook.",
                        discrepancies = listOf(
                            CourseDiscrepancyItem(
                                point = "零利润线亏损方位表述",
                                authoritativeView = "讲义标明零利润线右上方（northeast）亏损",
                                secondaryView = "课本第 $textPage 页标为右侧（to the right）亏损",
                                advice = "方向等价，答题以讲义图示坐标系为准",
                                citations = listOf(
                                    CourseCitationRef(
                                        bookId = bSlides.id,
                                        bookTitle = bSlides.title,
                                        role = CourseMaterialRole.LECTURE_SLIDES,
                                        page = 1,
                                        startUtf16 = 0,
                                        endUtf16 = 10,
                                        quote = "northeast of the zero-profit line",
                                    ),
                                    CourseCitationRef(
                                        bookId = bTextbook.id,
                                        bookTitle = bTextbook.title,
                                        role = CourseMaterialRole.CORE_TEXTBOOK,
                                        page = textPage,
                                        startUtf16 = 0,
                                        endUtf16 = 10,
                                        quote = "to the right of the zero-profit line",
                                    ),
                                ),
                            ),
                        ),
                    ),
                    topic.packSourcesHash.orEmpty(),
                )
                cid
            }

            val booklistId = "booklist-live-1"
            library.upsertCollection(
                MaterialCollectionRecord(
                    id = booklistId,
                    kind = CollectionKind.BOOKLIST,
                    title = "测试书单",
                ),
            )
            library.attachMaterial(CollectionMaterialRecord(booklistId, bSlides.id, ordinal = 0))
            library.attachMaterial(CollectionMaterialRecord(booklistId, bUnread.id, ordinal = 1))
            val slidesCh1 = BookChapterResolver.chapters(bSlides).first()
            library.setChapterManualReadState(bSlides.id, slidesCh1.tocNodeId, slidesCh1.pagesTotal, read = true)

            val seenPrompts = mutableListOf<String>()
            val stubExplainer = object : BookExplainer(library = library) {
                override suspend fun load() {
                    loaded = true
                    identity = DesktopIdentity(
                        serverUrl = "http://127.0.0.1:1",
                        credentials = PairedCredentials("desktop", "tok", "owner"),
                    )
                }

                override suspend fun askCollection(input: JsonObject): CourseServerCallResult {
                    val blocks = input["blocks"]!!.jsonArray.map { it.jsonObject }
                    val q = input["question"]!!.jsonPrimitive.content
                    seenPrompts += blocks.joinToString("\n") { it["text"]!!.jsonPrimitive.content }

                    val bSlidesBlk = blocks.firstOrNull { it["bookId"]!!.jsonPrimitive.content == bSlides.id }
                    val bTextBlk = blocks.firstOrNull { it["bookId"]!!.jsonPrimitive.content == bTextbook.id }
                    val bTransBlk = blocks.firstOrNull { it["bookId"]!!.jsonPrimitive.content == bTranscript.id }

                    val sents = buildJsonArray {
                        if (bSlidesBlk != null) {
                            add(buildJsonObject {
                                put("claim", "第5周讲义指出零利润线右上方合同会亏损。")
                                put("blockId", bSlidesBlk["blockId"]!!.jsonPrimitive.content)
                                put("bookId", bSlides.id)
                                put("docLabel", bSlidesBlk["docLabel"]!!.jsonPrimitive.content)
                                put("page", bSlidesBlk["page"]!!.jsonPrimitive.int)
                                put("quoteAnchor", "northeast of the zero-profit line")
                            })
                        }
                        if (bTextBlk != null) {
                            add(buildJsonObject {
                                put("claim", "核心教材补充说明严格位于零利润线右侧的合同亏损。")
                                put("blockId", bTextBlk["blockId"]!!.jsonPrimitive.content)
                                put("bookId", bTextbook.id)
                                put("docLabel", bTextBlk["docLabel"]!!.jsonPrimitive.content)
                                put("page", bTextBlk["page"]!!.jsonPrimitive.int)
                                put("quoteAnchor", "to the right of the zero-profit line")
                            })
                            // Deliberately inject one fabricated quote to prove UI strips it!
                            add(buildJsonObject {
                                put("claim", "这句假引文绝不能出现在界面上。")
                                put("blockId", bTextBlk["blockId"]!!.jsonPrimitive.content)
                                put("bookId", bTextbook.id)
                                put("docLabel", bTextBlk["docLabel"]!!.jsonPrimitive.content)
                                put("page", bTextBlk["page"]!!.jsonPrimitive.int)
                                put("quoteAnchor", "totally bogus quote absent from book")
                            })
                        }
                        if (bTransBlk != null) {
                            add(buildJsonObject {
                                put("claim", "第4周讨论课录音于 42:41 口误纠正为严格不等号。")
                                put("blockId", bTransBlk["blockId"]!!.jsonPrimitive.content)
                                put("bookId", bTranscript.id)
                                put("docLabel", bTransBlk["docLabel"]!!.jsonPrimitive.content)
                                put("page", bTransBlk["page"]!!.jsonPrimitive.int)
                                put("timestampLabel", "42:41")
                                put("quoteAnchor", "must be a strict inequality")
                            })
                        }
                    }

                    val isConflictQ = "零利润" in q || "斜率" in q
                    return CourseServerCallResult(
                        body = buildJsonObject {
                            put("sentences", sents)
                            put("hasMaterialConflict", isConflictQ)
                            put("conflictSummaryZh", if (isConflictQ) "讲义写右上方，课本写右侧。" else "")
                            put("droppedUnverifiedCount", 0)
                        },
                        error = null,
                    )
                }
            }

            val controller = TextbookController(library)
            runBlocking {
                controller.load()
                val detail = library.courseDetail(courseId)
                controller.openCourseTopic(courseId, detail.allTopics.first().topicId)
            }

            val window = edt {
                ComposeWindow().apply {
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(
                        screen.x + 20,
                        screen.y + 20,
                        minOf(1360, (screen.width - 40).coerceAtLeast(960)),
                        minOf(900, (screen.height - 80).coerceAtLeast(640)),
                    )
                    setContent {
                        WindowsTextbookHost(
                            controller = controller,
                            bookExplainer = stubExplainer,
                        ) { null }
                    }
                    isVisible = true
                }
            }

            try {
                // 1. Open ASK tab in companion drawer and switch to `整门课`
                click(window, "companion-tab-ask")
                click(window, "ask-scope-collection")

                setText(window, "inline-followup-input", "零利润线的斜率与亏损方位，以及讨论课录音不等号更正？")
                click(window, "inline-followup-send")

                waitFor("collection Q&A renders verified sentences & conflict card", window) {
                    node(window, "collection-qa-turn-0") != null &&
                        node(window, "collection-qa-conflict-card-0") != null
                }

                // Verify fabricated sentence was stripped (only verified citations appear)
                val allText = edt {
                    nodes(window).joinToString("\n") { nodeText(it) }
                }
                assertFalse("Fabricated quote claim must NOT appear on screen", "假引文" in allText)
                assertTrue(allText.contains("各材料说法不一"))

                // 2. Click textbook citation capsule -> verify 1-click switch to Core-Textbook + page jump!
                val citationTags = edt {
                    nodes(window)
                        .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
                        .filter { it.startsWith("collection-qa-citation-0-") }
                }
                assertTrue("Expected multi-material citation pills, got $citationTags", citationTags.size >= 2)
                val textPillTag = citationTags.first { tag ->
                    val txt = edt { nodeText(node(window, tag)) }
                    "课本" in txt
                }
                click(window, textPillTag)
                waitFor("1-click citation jump switches active book to Core-Textbook", window) {
                    controller.state.value.book?.id == bTextbook.id
                }
                // Verify ASK drawer stayed open across cross-book jump!
                waitFor("ASK pane remains visible after cross-book jump", window) {
                    node(window, "companion-ask-pane") != null
                }

                // If transcript block was included, verify 1-click transcript timestamp jump too
                val transPillTag = citationTags.firstOrNull { tag ->
                    val txt = edt { nodeText(node(window, tag)) }
                    "42:41" in txt
                }
                if (transPillTag != null) {
                    click(window, transPillTag)
                    waitFor("1-click citation jump opens transcript at [42:41]", window) {
                        controller.state.value.book?.id == bTranscript.id
                    }
                }

                // 3. Open HostSearchDialog via top bar after returning Home, ask course question, and 1-click jump!
                // TON-1859 C（5c88ea7e）：跟过跨书引用后，「返回」先逐次回到跳转前的那本书；回完再退出阅读器回课程、再回首页。
                runBlocking {
                    while (controller.state.value.canReturnToCitationOrigin) controller.back()
                    controller.back(); controller.back()
                }
                click(window, "host-search")
                waitFor("HostSearchDialog opens", window) { node(window, "host-search-dialog") != null }
                setText(window, "host-search-input", "零利润线亏损方位")
                click(window, "host-search-ask-collection")
                waitFor("HostSearchDialog renders Q&A citations", window) {
                    node(window, "host-search-qa-citation-0-0") != null
                }
                click(window, "host-search-qa-citation-0-0")
                waitFor("HostSearchDialog citation jumps to reader", window) {
                    controller.state.value.screen == BookScreen.READER && node(window, "host-search-dialog") == null
                }

                // 4. Switch to Booklist book and verify `已读书目` enforces 0 unread spoilers!
                runBlocking {
                    controller.openBooklist(booklistId)
                    controller.openBook(bSlides.id, 1)
                }
                waitFor("Booklist book opens in reader", window) {
                    controller.state.value.book?.id == bSlides.id &&
                        controller.state.value.activeBooklistId == booklistId
                }
                openCompanionDrawer(window)
                click(window, "companion-tab-ask")
                waitFor("Booklist shows 已读书目 toggle", window) {
                    val txt = edt { nodeText(node(window, "ask-scope-collection")) }
                    "已读书目" in txt
                }
                click(window, "ask-scope-collection")
                setText(window, "inline-followup-input", "Hidden Second Book alpha=999")
                click(window, "inline-followup-send")
                waitFor("Booklist ask finishes", window) {
                    node(window, "collection-qa-turn-0") != null
                }
                val lastPrompt = seenPrompts.last()
                assertFalse("Unread book spoiler must NEVER enter prompt", "alpha=999" in lastPrompt)
            } finally {
                edt { window.dispose() }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun openCompanionDrawer(window: ComposeWindow) {
        if (node(window, "companion-tab-ask") != null || node(window, "immersive-companion-drawer") != null) return
        revealHudViaMediator(window)
        val hasToggle = waitUntilTrue(window, timeoutSeconds = 3) {
            node(window, "companion-tab-ask") != null ||
                node(window, "immersive-companion-drawer") != null ||
                node(window, "immersive-companion-toggle") != null
        }
        if (!hasToggle) {
            sendKeyViaMediator(window, KeyEvent.VK_TAB)
            waitUntilTrue(window, timeoutSeconds = 3) {
                node(window, "companion-tab-ask") != null ||
                    node(window, "immersive-companion-drawer") != null ||
                    node(window, "immersive-companion-toggle") != null
            }
        }
        if (node(window, "companion-tab-ask") == null &&
            node(window, "immersive-companion-drawer") == null &&
            node(window, "immersive-companion-toggle") != null
        ) {
            click(window, "immersive-companion-toggle")
            waitUntilTrue(window, timeoutSeconds = 3) {
                node(window, "companion-tab-ask") != null || node(window, "immersive-companion-drawer") != null
            }
        }
        if (node(window, "companion-tab-ask") == null && node(window, "immersive-companion-drawer") == null) {
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

    private fun nodeText(n: SemanticsNode?): String {
        if (n == null) return ""
        val own = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        val childTexts = n.children.map(::nodeText).filter { it.isNotEmpty() }
        return (listOf(own).filter { it.isNotEmpty() } + childTexts).joinToString(" ")
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
        window: ComposeWindow,
        timeoutSeconds: Long = 20,
        pollMs: Long = 40,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        while (true) {
            edt { runCatching { window.renderImmediately() } }
            if (runCatching(condition).getOrDefault(false)) return
            check(System.nanoTime() < deadline) {
                val tags = runCatching {
                    edt { nodes(window).mapNotNull { n -> n.config.getOrNull(SemanticsProperties.TestTag) }.distinct().take(50) }
                }.getOrNull()
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
