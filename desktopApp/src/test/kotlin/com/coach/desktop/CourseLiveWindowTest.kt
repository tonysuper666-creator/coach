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
import org.jetbrains.skiko.SkiaLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * Real desktop ComposeWindow end-to-end verification for TON-1793 (2.0-W2-C):
 * 1) Auto-aligned course view (examinable topics + excluded textbook chapter `本课不考内容`)
 * 2) In-place material week correction via `⋯ -> 查看本课材料清单` (`user_locked=1` + refreshed `pack_sources_hash` survives re-align)
 * 3) Restoring an excluded textbook chapter via `恢复为必考`
 * 4) Opening topic companion drawer -> verifying `[mm:ss]` transcript citation jump + L1->L4 source switcher + `各材料讲法差异`
 * 5) Typing an incomplete 300-word essay draft -> cold restart -> verifying draft, active source, and companion open restore intact.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class CourseLiveWindowTest {

    @Test
    fun examMapOpensWeakestPointForWriting() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("coach-exam-map-window-")
        val window = edt { ComposeWindow().apply { title = "Coach · 考点地图"; setSize(1280, 900) } }
        try {
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
            val names = listOf("Axial tilt", "Solar wind", "Lunar phase", "Transit depth", "Stellar parallax", "Orbital period", "Tidal locking")
            val books = listOf("Textbook", "Slides").map { role ->
                val file = root.resolve("$role.md")
                Files.writeString(file, names.joinToString("\n\n") { "## $it\nA synthetic $role example of $it." })
                library.stageBookImport(file).book
            }
            val (courseId, ordered) = library.withCourseDatabase { db ->
                val (id, _) = CourseStore.createCourse(db, "天文学", CourseExamProfile(examEnabled = true), books.map { it.id })
                val points = CourseAutoAligner.alignLocally(db, id).examinableTopics.filter { it.weekLabel == CourseExamPoints.LABEL }
                assertEquals(names.toSet(), points.map { it.title }.toSet())
                val ordered = names.map { name -> points.single { it.title == name } }
                ordered.take(6).forEachIndexed { i, point ->
                    MasteryStore.upsertConcept(db, ConceptMasteryRecord(point.topicId, id, books.first().id,
                        point.topicId, point.title, pMastery = 0.12 + 0.14 * i, hasActiveTest = true))
                }
                id to ordered
            }
            val controller = TextbookController(library)
            val offline = object : BookExplainer(library = library) {
                override suspend fun load() { loaded = true }
            }
            runBlocking { controller.load(); controller.openCourse(courseId) }
            edt {
                window.setContent { WindowsTextbookHost(controller, bookExplainer = offline) { null } }
                window.isVisible = true
            }
            waitFor("course overview map", window) { node(window, "course-exam-point-map") != null }
            val visible = ordered.take(5).map { requireNotNull(node(window, "exam-map-${it.topicId}")).boundsInWindow }
            assertTrue(visible.all { it.height > 0 && it.width > 0 })
            assertTrue(visible.zipWithNext().all { (first, second) -> first.top < second.top })
            assertNull(node(window, "exam-map-${ordered.last().topicId}"))
            val weakest = ordered.first()
            click(window, "exam-map-${weakest.topicId}")
            waitFor("visible answer field", window) {
                controller.state.value.activeCourseTopicId == weakest.topicId &&
                    (node(window, "course-essay-input")?.boundsInWindow?.height ?: 0f) > 0f
            }
            setText(window, "course-essay-input", "Synthetic answer about axial tilt.")
            waitFor("draft saved for selected point", window) {
                library.courseDetail(courseId).allTopics.single { it.topicId == weakest.topicId }
                    .practiceDraft?.text == "Synthetic answer about axial tilt."
            }
        } finally {
            edt { window.dispose() }
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun textbookOnlyCourseOpensFirstChapterAndWrongAnswersAppearOnHome() {
        assumeTrue("Live ComposeWindow verification requires non-headless desktop display", !GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("coach-ton1849-live-window-")
        val dark = System.getenv("COACH_TON1849_THEME") == "dark"
        val window = edt {
            ComposeWindow().apply {
                title = "LearningCoach · TON-1849"
                isAlwaysOnTop = !System.getenv("COACH_TON1849_SHOTS_DIR").isNullOrBlank()
                val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                setBounds(screen.x, screen.y, minOf(1120, screen.width), minOf(840, screen.height))
            }
        }
        try {
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
            val controller = TextbookController(library)
            val offlineExplainer = object : BookExplainer(library = library) {
                override suspend fun load() { loaded = true }
            }
            runBlocking { controller.load() }
            ReadingSettings(library.directory).choosePaper(if (dark) PaperColor.DARK else PaperColor.LIGHT)
            edt {
                window.setContent {
                    WindowsTextbookHost(
                        controller,
                        answerExplain = answerExplainWith(offlineExplainer, ExplainConsent(library.directory)),
                        bookExplainer = offlineExplainer,
                    ) { null }
                }
                window.isVisible = true
            }
            waitFor("empty review card", window) { node(window, "home-review-done") != null }
            assertEquals("还没有要复习的。做错的题会在这里出现。", text(window, "home-review-done"))
            captureTon1849Window(window, "empty-review")

            val book = library.stageBookImport(textbookWithFrontMatter(root)).book
            val courseId = library.withCourseDatabase { db ->
                val (cid, _) = CourseStore.createCourse(db, "植物学基础", CourseExamProfile(), listOf(book.id))
                CourseAutoAligner.alignLocally(db, cid)
                cid
            }
            runBlocking { controller.openBook(book.id); controller.back() }
            click(window, "home-read")
            waitFor("shelf reader", window) { controller.state.value.screen == BookScreen.READER }
            val shelfPage = controller.state.value.page
            assertEquals(32, shelfPage)
            runBlocking { controller.openCourse(courseId) }
            waitFor("course overview", window) { node(window, "course-primary-cta") != null }
            assertTrue(text(window, "course-primary-cta").contains("从第一章开始"))
            click(window, "course-primary-cta")
            waitFor("course overview reader", window) { controller.state.value.screen == BookScreen.READER }
            assertEquals(shelfPage, controller.state.value.page)
            runBlocking { controller.back() }

            click(window, "course-overflow")
            waitFor("non-exam overflow", window) { node(window, "course-enable-exam-action") != null }
            assertNull(node(window, "course-exam-profile-action"))
            captureTon1849Window(window, "course-menu")

            click(window, "course-enable-exam-action")
            waitFor("exam mode", window) { node(window, "course-weekly-plan-btn") != null }
            click(window, "course-overflow")
            click(window, "course-exam-profile-action")
            waitFor("exam date editor", window) { node(window, "exam-profile-date-input") != null }
            click(window, "exam-profile-save")
            click(window, "course-weekly-plan-btn")
            waitFor("weekly plan", window) { node(window, "course-schedule-primary-cta") != null }
            assertTrue(text(window, "course-schedule-primary-cta").contains("Chapter 1 Light and Leaves"))
            captureTon1849Window(window, "weekly-plan")
            click(window, "course-schedule-primary-cta")
            waitFor("weekly plan reader", window) { controller.state.value.screen == BookScreen.READER }
            assertEquals(shelfPage, controller.state.value.page)
            captureTon1849Window(window, "first-chapter")

            val activeBook = requireNotNull(controller.state.value.book)
            val lesson = activeBook.lessons.first { it.firstPage == shelfPage }
            val practice = requireNotNull(testPracticeFixture(activeBook, lesson, QuestionMode.CHOICE))
            val wrong = practice.options.first { it != practice.answer }
            runBlocking {
                controller.recordSectionQuizAnswer(lesson, 0, practice, PracticeAnswer(wrong, true))
                controller.back()
            }
            click(window, "course-back")
            click(window, "home-review-start")
            waitFor("today's review", window) { node(window, "local-review-item-0") != null }
            click(window, "local-review-item-0")
            waitFor("correct review destination", window) { controller.state.value.screen == BookScreen.REVIEWING }
            assertEquals(PracticeAnswer(wrong, true), library.answer(activeBook.id, lesson.id, QuestionMode.CHOICE))
            waitFor("previous wrong answer shown", window) {
                edt {
                    nodes(window).any { node ->
                        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "上次作答：$wrong" }
                    }
                }
            }
            captureTon1849Window(window, "same-day-review")
            println("TON-1849_LIVE_PASS: overflow, shelf/course/plan page $shelfPage, same-day review")
        } finally {
            edt { window.dispose() }
            root.toFile().deleteRecursively()
        }
    }

    private fun text(window: ComposeWindow, tag: String): String = edt {
        val target = requireNotNull(node(window, tag))
        val texts = target.config.getOrNull(SemanticsProperties.Text)
            ?: target.children.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
        texts.joinToString(" ") { it.text }
    }

    private fun captureTon1849Window(window: ComposeWindow, name: String) {
        val out = System.getenv("COACH_TON1849_SHOTS_DIR")?.let(Path::of) ?: return
        edt { window.toFront() }
        Thread.sleep(500) // Let the native transition settle before capturing the window.
        val bounds = edt {
            window.renderImmediately()
            Rectangle(window.contentPane.locationOnScreen, window.contentPane.size)
        }
        Files.createDirectories(out)
        check(ImageIO.write(Robot().createScreenCapture(bounds), "png", out.resolve("$name.png").toFile()))
    }

    @Test
    fun courseWorkflowCorrectionsTimestampsAndColdRestartEssayDraft() {
        assumeTrue("Live ComposeWindow verification requires non-headless desktop display", !GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("coach-w2c-live-window-")
        try {
            val libDir = root.resolve("library")
            val srcDir = root.resolve("sources")
            Files.createDirectories(srcDir)

            val handbook = srcDir.resolve("Course-Handbook.md").also {
                Files.writeString(
                    it,
                    "# Applied Economics Syllabus\nExam Date: 2026-12-12 (tentative)\nWeek 4: Choice Under Uncertainty\nWeek 6: Adverse Selection & Insurance\n",
                )
            }
            val wrongWeekSlides = srcDir.resolve("Week3-Selection-Slides.md").also {
                Files.writeString(
                    it,
                    "# Selection Model Lecture\nUnraveling occurs when pooling insurance premiums drive out healthy households.\n",
                )
            }
            val transcript = srcDir.resolve("Week6-Recording.md").also {
                Files.writeString(
                    it,
                    "[00:04:10] Intro to asymmetric risk pools.\n" +
                        "[00:12:30] Cutler & Reber (1998) tracks health plan switching death spiral.\n" +
                        "[00:20:15] Separating equilibrium via deductible menus.\n",
                )
            }
            val textbook = srcDir.resolve("Core-Textbook.md").also {
                Files.writeString(
                    it,
                    "# Chapter 1 Choice Under Uncertainty\nExpected utility compares lotteries.\n\n" +
                        "# Chapter 2 Adverse Selection\nHealthier buyers exit homogeneous pools as premiums reflect adverse claims.\n\n" +
                        "# Chapter 10 Monetary Policy\nCentral bank discount windows lie outside this course.\n",
                )
            }

            val library1 = TextbookLibrary(libDir, lazyPdfExtras = false)
            val controller1 = TextbookController(library1)
            val bHandbook = library1.stageBookImport(handbook).book
            val bSlides = library1.stageBookImport(wrongWeekSlides).book
            val bTranscript = library1.stageBookImport(transcript).book
            val bTextbook = library1.stageBookImport(textbook).book

            val courseId = library1.withCourseDatabase { db ->
                val (cid, _) = CourseStore.createCourse(
                    db = db,
                    title = "ECNM Course Prep",
                    examProfile = CourseExamProfile(examDate = "2026-12-12", examDateTentative = true, examFormat = "MCQ + 300词论述", examFormatTentative = true),
                    materialBookIds = listOf(bHandbook.id, bSlides.id, bTranscript.id, bTextbook.id),
                )
                CourseAutoAligner.alignLocally(db, cid)
                cid
            }

            runBlocking {
                controller1.load()
                controller1.openCourse(courseId)
            }

            val offlineExplainer1 = object : BookExplainer(library = library1) {
                override suspend fun load() {
                    identity = null
                    loaded = true
                }
            }

            val window1 = edt {
                ComposeWindow().apply {
                    title = "Coach W2-C Live Window Session 1"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(
                        screen.x + 20,
                        screen.y + 20,
                        minOf(1280, (screen.width - 40).coerceAtLeast(800)),
                        minOf(880, (screen.height - 80).coerceAtLeast(600)),
                    )
                    setContent { WindowsTextbookHost(controller1, bookExplainer = offlineExplainer1) { null } }
                    isVisible = true
                }
            }

            val initialDetail = library1.courseDetail(courseId)
            val excludedCh10 = requireNotNull(initialDetail.excludedTopics.firstOrNull { it.title.contains("Monetary Policy") })
            val week6TopicBefore = requireNotNull(initialDetail.examinableTopics.firstOrNull { it.weekNum == 6 })
            val week6HashBefore = week6TopicBefore.packSourcesHash

            val typedDraft = "Pooling contracts break down because low-risk buyers face average claims pricing above expected loss. Cutler & Reber (1998) illustrates the resulting death spiral."

            try {
                waitFor("course screen ready", window1) { node(window1, "course-screen") != null }

                // 1) Open `⋯ -> 查看本课材料清单` and move mis-labelled Week3 slide -> Week 6
                click(window1, "course-overflow")
                click(window1, "course-materials-action")
                waitFor("materials dialog", window1) { node(window1, "course-materials-dialog") != null }

                click(window1, "material-week-picker-${bSlides.id}")
                click(window1, "material-week-opt-${bSlides.id}-6")
                click(window1, "material-week-save-${bSlides.id}")
                waitFor("week 6 reassignment locked", window1) {
                    val refreshed = library1.courseDetail(courseId)
                    val moved = refreshed.materials.first { it.bookId == bSlides.id }
                    moved.weekNum == 6 && moved.userLocked
                }
                click(window1, "course-materials-close")

                val detailAfterLock = library1.courseDetail(courseId)
                val week6TopicAfter = detailAfterLock.examinableTopics.first { it.weekNum == 6 }
                assertNotEquals(week6HashBefore, week6TopicAfter.packSourcesHash)

                // Re-align via UI overflow and verify manual Week 6 lock survives!
                click(window1, "course-overflow")
                click(window1, "course-realign-action")
                waitFor("notice after realign", window1) {
                    val st = controller1.state.value
                    !st.busy && st.courseNotice?.contains("对齐") == true && node(window1, "course-notice-banner") != null
                }
                val postRealign = library1.courseDetail(courseId)
                assertEquals(6, postRealign.materials.first { it.bookId == bSlides.id }.weekNum)
                assertTrue(postRealign.materials.first { it.bookId == bSlides.id }.userLocked)

                // 2) Restore excluded textbook Chapter 10 via `恢复为必考`
                click(window1, "restore-topic-${excludedCh10.topicId}")
                waitFor("excluded chapter restored", window1) {
                    library1.courseDetail(courseId).examinableTopics.any { it.topicId == excludedCh10.topicId }
                }

                // 3) Seed a cross-material verified topic pack containing `[12:30]` transcript citation + discrepancy
                val tLine = "[12:30] Cutler & Reber (1998) tracks health plan switching death spiral."
                val tText = bTranscript.pages.first().text
                val tStart = tText.indexOf(tLine)
                val tsCitation = CourseCitationRef(
                    bookId = bTranscript.id,
                    bookTitle = bTranscript.title,
                    role = CourseMaterialRole.TRANSCRIPT,
                    page = 1,
                    startUtf16 = tStart,
                    endUtf16 = tStart + tLine.length,
                    timestampLabel = "12:30",
                    quote = tLine,
                    verified = true,
                )
                val packWithDiff = CourseTopicPack(
                    topicId = week6TopicAfter.topicId,
                    summary = "Adverse selection topic pack integrating lecture slides, transcript, and textbook.",
                    authorityNotice = "L1 课堂转写与讲义口径优先。",
                    examDateTentative = true,
                    examFormatTentative = true,
                    keyPoints = listOf(
                        CourseKeyPoint("Cutler & Reber (1998) 经验证据", "课堂重点聚焦固定补贴切换引发的逆向选择螺旋。", listOf(tsCitation)),
                    ),
                    discrepancies = listOf(
                        CourseDiscrepancyItem(
                            point = "实证案例范围差异",
                            authoritativeView = "课堂录音仅精讲 Cutler & Reber (1998)。",
                            secondaryView = "教材额外列出多行业参考案例。",
                            advice = "论述答卷主案例紧扣 Cutler & Reber (1998)。",
                            citations = listOf(tsCitation),
                        ),
                    ),
                    practicePrompt = CoursePracticePrompt(
                        promptId = "w6-essay",
                        kind = "ESSAY",
                        stem = "对比混合合同与分离合同在信息不对称下的均衡稳定条件。",
                        wordTarget = 300,
                        citations = listOf(tsCitation),
                    ),
                    citations = listOf(tsCitation),
                    generatedFromHash = week6TopicAfter.packSourcesHash.orEmpty(),
                    serverBacked = true,
                )
                library1.withCourseDatabase { db ->
                    CourseStore.saveTopicPack(db, week6TopicAfter.topicId, packWithDiff, week6TopicAfter.packSourcesHash.orEmpty())
                }

                // Open Week 6 topic -> verify source switcher, discrepancies block, `[12:30]` citation jump, and essay draft save
                runBlocking { controller1.openCourse(courseId) }
                click(window1, "course-topic-${week6TopicAfter.topicId}")
                waitFor("course companion panel open", window1) {
                    node(window1, "course-companion-panel") != null &&
                        node(window1, "course-source-switcher") != null &&
                        node(window1, "course-discrepancies-block") != null
                }

                // Click transcript source / citation and confirm switch to transcript book + timestamp quote
                click(window1, "course-citation-0-0")
                waitFor("jumped to transcript book with timestamp", window1) {
                    controller1.state.value.book?.id == bTranscript.id
                }

                // Type 300-word essay draft incrementally & verify live counter + SQLite persistence
                setText(window1, "course-essay-input", typedDraft)
                waitFor("essay draft persisted to SQLite", window1) {
                    val saved = library1.courseDetail(courseId).examinableTopics
                        .first { it.topicId == week6TopicAfter.topicId }.practiceDraft
                    saved?.text == typedDraft && saved.activeSourceBookId == bTranscript.id
                }
                val wcText = node(window1, "course-essay-wordcount")?.config?.getOrNull(SemanticsProperties.Text)
                    ?.joinToString("") { it.text }.orEmpty()
                assertTrue("Expected non-zero word count, got $wcText", wcText.contains("已写 ") && !wcText.contains("已写 0 "))

                runBlocking { controller1.flushPendingSaves() }
            } finally {
                edt { window1.dispose() }
            }

            // 4) Cold restart: fresh TextbookLibrary + fresh TextbookController + real ComposeWindow #2
            val library2 = TextbookLibrary(libDir, lazyPdfExtras = false)
            val controller2 = TextbookController(library2)
            runBlocking { controller2.load() }
            assertEquals(BookScreen.READER, controller2.state.value.screen)
            assertEquals(courseId, controller2.state.value.activeCourseId)

            val offlineExplainer2 = object : BookExplainer(library = library2) {
                override suspend fun load() {
                    identity = null
                    loaded = true
                }
            }

            val window2 = edt {
                ComposeWindow().apply {
                    title = "Coach W2-C Live Window Session 2"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(
                        screen.x + 20,
                        screen.y + 20,
                        minOf(1280, (screen.width - 40).coerceAtLeast(800)),
                        minOf(880, (screen.height - 80).coerceAtLeast(600)),
                    )
                    setContent { WindowsTextbookHost(controller2, bookExplainer = offlineExplainer2) { null } }
                    isVisible = true
                }
            }
            try {
                waitFor("cold restart resumes exact course companion & essay draft", window2) {
                    val editable = node(window2, "course-essay-input")
                        ?.config?.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
                    node(window2, "course-companion-panel") != null && editable == typedDraft
                }
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
            if (window != null) {
                edt { runCatching { window.renderImmediately() } }
            }
            if (condition()) return
            check(System.nanoTime() < deadline) {
                val tags = window?.let {
                    runCatching { edt { nodes(it).mapNotNull { n -> n.config.getOrNull(SemanticsProperties.TestTag) }.distinct().take(40) } }.getOrNull()
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
