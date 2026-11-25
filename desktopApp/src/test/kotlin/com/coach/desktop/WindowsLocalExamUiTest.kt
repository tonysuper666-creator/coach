package com.coach.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.input.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.coach.desktop.books.*
import com.coach.desktop.books.exam.*
import com.coach.domain.*
import com.coach.ui.CoachTheme
import kotlinx.serialization.json.*
import java.sql.DriverManager
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class WindowsLocalExamUiTest {
    @Suppress("DEPRECATION", "DEPRECATION_ERROR", "OVERRIDE_DEPRECATION")
    @Test fun composingEssayTabKeepsFocusAndTextThenKeyboardCanSubmit() {
        val root = Files.createTempDirectory("coach-essay-ime-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val lesson = book.lessons.first()
        val source = json("docId" to book.id, "lessonId" to lesson.id, "pdfPage" to 1,
            "quote" to lesson.blocks.first { !it.heading }.text)
        val paper = LocalExamPaper(json("id" to "ime-paper", "bookId" to book.id, "title" to "Synthetic essay",
            "kind" to "paper", "origin" to "AUTHORED", "key_state" to "UNREVIEWED",
            "sections" to JsonArray(listOf(json("title" to "Synthetic", "items" to JsonArray(listOf(
                json("id" to "essay", "type" to "essay", "stem" to "请说明学习过程。", "marks" to 3, "source" to source)
            )))))))
        val store = LocalExamStore(library)
        store.register(paper)
        val draft = "学习需要观察和练习。".repeat(45)
        fun hash(s: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        var request: PlatformTextInputMethodRequest? = null
        val interceptor = PlatformTextInputInterceptor { input, next ->
            request = input
            try { next.startInputMethod(input) } finally { request = null }
        }
        runSkikoComposeUiTest(size = Size(1044f, 528f), density = Density(1f)) {
            setContent { InterceptPlatformTextInput(interceptor) {
                SavedExamAttempt(library, book, paper)
            } }
            waitUntil(timeoutMillis = 20_000) { store.active(book.id) != null }
            // The attempt is stored before the paper pane recomposes; wait for the essay field itself.
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-text-essay").fetchSemanticsNodes().isNotEmpty() }
            val id = store.active(book.id)!!.str("mockId")
            onNodeWithTag("local-text-essay").performScrollTo().performClick().performTextReplacement(draft)
            waitUntil(timeoutMillis = 20_000) { store.get(book.id, id)["answers"]!!.jsonObject["essay"]?.jsonObject?.str("text") == draft }
            runOnIdle { checkNotNull(request).onEditCommand(listOf(SetComposingTextCommand("候选", 1))) }
            waitForIdle()
            assertNotNull(request?.value?.invoke()?.composition, "IME must have an active composition in the actual text field")
            val composed = draft + "候选"
            onNodeWithTag("local-text-essay").assertTextContains(composed)
            onNodeWithTag("local-text-essay").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("local-text-essay").assertIsFocused()
            assertEquals(hash(composed), hash(checkNotNull(request).value().text))
            onNodeWithTag("local-text-essay").performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft) }
            onNodeWithTag("local-text-essay").assertIsFocused()
            assertEquals(hash(composed), hash(checkNotNull(request).value().text))
            onNodeWithTag("local-text-essay").assertTextContains(composed)
            assertNotNull(request?.value?.invoke()?.composition)
            runOnIdle { checkNotNull(request).onEditCommand(listOf(FinishComposingTextCommand())) }
            waitForIdle()
            assertNull(request?.value?.invoke()?.composition)
            onNodeWithTag("local-text-essay").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("local-clear-essay").assertIsFocused()
            onNodeWithTag("local-clear-essay").performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft) }
            onNodeWithTag("local-text-essay").assertIsFocused()
            waitUntil(timeoutMillis = 20_000) { store.get(book.id, id)["answers"]!!.jsonObject["essay"]?.jsonObject?.str("text") == composed }
            println("IME_ESSAY_HASH=${hash(composed)} attempt=$id")
            onNodeWithTag("local-text-essay").performKeyInput { pressKey(Key.Enter); pressKey(Key.Tab) }
            onNodeWithTag("local-clear-essay").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("local-source-essay").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("local-submit").assertIsFocused().assertIsDisplayed().performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("local-confirm-submit").performClick()
            waitUntil(timeoutMillis = 20_000) { store.get(book.id, id).str("status") == "submitted" }
            assertTrue(store.get(book.id, id)["answers"]!!.jsonObject["essay"]!!.jsonObject.str("text").contains('\n'))
            assertEquals(1, store.get(book.id, id)["result"]!!.jsonObject.num("pending"))
        }
    }
    @Test fun essayKeyboardTabPreservesDraftAndReachesSubmitInShortWindow() {
        val root = Files.createTempDirectory("coach-essay-keyboard-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val lesson = book.lessons.first()
        val source = json("docId" to book.id, "lessonId" to lesson.id, "pdfPage" to 1,
            "quote" to lesson.blocks.first { !it.heading }.text)
        val paper = LocalExamPaper(json("id" to "keyboard-paper", "bookId" to book.id, "title" to "Synthetic essay",
            "kind" to "paper", "origin" to "AUTHORED", "key_state" to "UNREVIEWED",
            "sections" to JsonArray(listOf(json("title" to "Synthetic", "items" to JsonArray(listOf(
                json("id" to "essay", "type" to "essay", "stem" to "请说明学习过程。", "marks" to 3, "source" to source)
            )))))))
        val store = LocalExamStore(library)
        store.register(paper)
        val draft = "学习需要观察和练习。".repeat(45)
        fun hash(s: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        runSkikoComposeUiTest(size = Size(1044f, 528f), density = Density(1f)) {
            setContent { SavedExamAttempt(library, book, paper) }
            waitUntil(timeoutMillis = 20_000) { store.active(book.id) != null }
            // The attempt is stored before the paper pane recomposes; wait for the essay field itself.
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-text-essay").fetchSemanticsNodes().isNotEmpty() }
            val id = store.active(book.id)!!.str("mockId")
            onNodeWithTag("local-text-essay").performScrollTo().performClick().performTextReplacement(draft)
            waitUntil(timeoutMillis = 20_000) { store.get(book.id, id)["answers"]!!.jsonObject["essay"]?.jsonObject?.str("text") == draft }
            onNodeWithTag("local-text-essay").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("local-clear-essay").assertIsFocused()
            onNodeWithTag("local-text-essay").assertTextContains(draft)
            assertEquals(hash(draft), hash(store.get(book.id, id)["answers"]!!.jsonObject["essay"]!!.jsonObject.str("text")))
            println("ESSAY_TAB_HASH=${hash(draft)} attempt=$id")
            onNodeWithTag("local-clear-essay").performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft) }
            onNodeWithTag("local-text-essay").assertIsFocused()
            onNodeWithTag("local-text-essay").performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft) }
            onNodeWithTag("local-exam-back").assertIsFocused()
            onNodeWithTag("local-exam-back").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("local-text-essay").assertIsFocused()
            onNodeWithTag("local-text-essay").performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 20_000) { store.get(book.id, id)["answers"]!!.jsonObject["essay"]?.jsonObject?.str("text")?.contains('\n') == true }
            onNodeWithTag("local-text-essay").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("local-clear-essay").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("local-source-essay").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("local-submit").assertIsFocused().assertIsDisplayed()
            val submitBounds = onNodeWithTag("local-submit").getUnclippedBoundsInRoot()
            assertTrue(submitBounds.top >= 0.dp && submitBounds.bottom <= 528.dp, "submit outside viewport: $submitBounds")
            capture("essay-keyboard-submit-focused", "exam-test-window")
            onNodeWithTag("local-submit").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("local-confirm-submit").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 20_000) { store.get(book.id, id).str("status") == "submitted" }
            assertEquals(1, store.get(book.id, id)["result"]!!.jsonObject.num("pending"))
        }
    }
    // Course mocks and saved authored papers share this production answer pane (TON-1803).
    @Composable
    private fun SavedExamAttempt(
        library: TextbookLibrary, book: Textbook, paper: LocalExamPaper,
        exams: LocalExamController = rememberLocalExamController(library), resumeId: String? = null,
    ) {
        val ui by exams.state.collectAsState()
        LaunchedEffect(exams, paper.id) {
            exams.load(book)
            if (resumeId == null) exams.start(paper.id) else exams.open(resumeId)
        }
        CoachTheme(false) {
            Box(Modifier.fillMaxSize().testTag("exam-test-window")) {
                if (ui.attempt != null) LocalExamPane(exams, library)
            }
        }
    }

    private fun ComposeUiTest.nav(label: String) =
        onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText(label))

    private fun ComposeUiTest.ready(c: TextbookController) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && c.state.value.book != null }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.capture(name: String, rootTag: String = "windows-host") {
        val dir = Path.of("build/screens/local-exam-ui")
        Files.createDirectories(dir)
        assertTrue(ImageIO.write(onNodeWithTag(rootTag).captureToImage().toAwtImage(),
            "png", dir.resolve("$name.png").toFile()))
    }

    @Test fun courseGeneratesRealPracticeReopensDraftAndShowsTraceableResult() {
        val root = Files.createTempDirectory("coach-course-exam-ui-")
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val book = imported.copy(readingPage = 3, lessonIndex = 1, learning = true, promptSeen = true)
        library.save(book)
        val c = TextbookController(library)
        val store = LocalExamStore(library)
        lateinit var attempt: String
        val first = book.lessons.first()
        val practice = com.coach.desktop.books.testPracticeFixture(book, first, QuestionMode.CHOICE)!!
        val correct = practice.options.indexOf(practice.answer)
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent { WindowsTextbookHost(c) { null } }
            ready(c)
            nav("课程").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("course-exams-summary").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("course-exams-summary").performScrollTo().assertIsDisplayed()
            onNodeWithText("教材参考练习，答案尚未独立核对，不计时。").assertExists()
            onNodeWithTag("local-generate-start").performScrollTo()
            capture("00-course-real-entry")
            onNodeWithTag("local-generate-start").performClick()
            waitUntil(timeoutMillis = 20_000) { store.active(book.id) != null }
            attempt = store.active(book.id)!!.str("mockId")
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-exam-attempt").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("local-choice-${first.id}-$correct").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { store.get(book.id, attempt)["answers"]!!.jsonObject[first.id]?.jsonObject?.num("choice", -1) == correct.toLong() }
            capture("01-course-practice-saved")
            nav("今天").performClick()
            nav("课程").performClick()
            onNodeWithTag("local-choice-${first.id}-$correct").performScrollTo().assertIsSelected()
        }

        val reopened = TextbookController(TextbookLibrary(library.directory))
        val reopenedStore = LocalExamStore(reopened.library)
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent { WindowsTextbookHost(reopened) { null } }
            ready(reopened)
            nav("课程").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-resume").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("local-resume").performScrollTo()
            capture("00b-course-resume-entry")
            onNodeWithTag("local-resume").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-exam-attempt").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("local-choice-${first.id}-$correct").performScrollTo().assertIsSelected()
            onNodeWithTag("local-attempt-id").assertTextContains("第 1 次", substring = true)
            onNodeWithText("local-", substring = true).assertDoesNotExist()
            capture("02-sqlite-reopened-draft")
            onNodeWithTag("local-submit").performScrollTo().performClick()
            onNodeWithTag("local-confirm-submit").performClick()
            waitUntil(timeoutMillis = 20_000) { reopenedStore.get(book.id, attempt).str("status") == "submitted" }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-exam-result").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("参考得分：1 / 3").assertIsDisplayed()
            onNodeWithText("参考答案尚未独立核对，结果仅供对照。").assertExists()
            onNodeWithTag("local-attempt-id").assertTextContains("第 1 次", substring = true)
            onNodeWithText("local-", substring = true).assertDoesNotExist()
            capture("03-submitted-result")
            onNodeWithTag("local-source-${first.id}").performScrollTo().performClick()
            onNodeWithTag("local-frozen-source").assertExists()
            onNodeWithText("本次题卷保存的教材片段 · 第 1 页").assertExists()
            onNodeWithTag("local-source-quote").assertTextEquals(practice.reference)
            capture("04-frozen-source")
            onNodeWithTag("local-source-back").performClick()
            onNodeWithTag("local-exam-back").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-history-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("local-history-0").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-exam-result").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("local-attempt-id").assertTextContains("第 1 次", substring = true)
            onNodeWithText("local-", substring = true).assertDoesNotExist()
            capture("05-history-original-attempt")
        }
        assertEquals(3, reopened.library.book(book.id).readingPage)
        assertEquals(1, reopened.library.book(book.id).lessonIndex)
        val result = reopenedStore.get(book.id, attempt)
        assertEquals(result, reopenedStore.history(book.id).single())
        val dir = Path.of("build/screens/local-exam-ui")
        Files.writeString(dir.resolve("sqlite-readback.json"), result.toString())
        println("LOCAL_EXAM_UI_PASS: course -> generate/start -> answer -> reopen/resume -> submit -> frozen source -> original history; bookmark=3 lessonIndex=1")
    }

    @Test fun authoredEssayClearsAsTextAndRemainsPendingWithFrozenSource() {
        val root = Files.createTempDirectory("coach-course-essay-ui-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val lesson = book.lessons.first()
        val quote = lesson.blocks.first { !it.heading }.text
        val source = json("docId" to book.id, "lessonId" to lesson.id, "pdfPage" to 1, "quote" to quote)
        val paper = LocalExamPaper(json("id" to "authored-ui-v1", "bookId" to book.id, "title" to "Self-authored biology",
            "kind" to "paper", "origin" to "AUTHORED", "key_state" to "UNREVIEWED",
            "sections" to JsonArray(listOf(json("title" to "Synthetic", "items" to JsonArray(listOf(
                json("id" to "choice", "type" to "mcq", "stem" to "Which resource supplies energy?", "marks" to 1,
                    "options" to JsonArray(listOf(JsonPrimitive("Light"), JsonPrimitive("Rock"))), "answer" to 0, "source" to source),
                json("id" to "essay", "type" to "essay", "stem" to "Explain photosynthesis.", "marks" to 3, "source" to source),
            )))))))
        val store = LocalExamStore(library)
        store.register(paper)
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent { SavedExamAttempt(library, book, paper) }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-exam-attempt").fetchSemanticsNodes().isNotEmpty() }
            val id = store.active(book.id)!!.str("mockId")
            onNodeWithTag("local-choice-choice-0").performScrollTo().performClick()
            onNodeWithTag("local-text-essay").performScrollTo().performTextReplacement("draft to clear")
            waitUntil(timeoutMillis = 20_000) { store.get(book.id, id)["answers"]!!.jsonObject["essay"]?.jsonObject?.str("text") == "draft to clear" }
            onNodeWithTag("local-clear-essay").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { store.get(book.id, id)["answers"]!!.jsonObject["essay"]?.jsonObject?.get("text") == JsonPrimitive("") }
            onNodeWithTag("local-text-essay").performScrollTo().assertTextContains("")
            onNodeWithTag("local-text-essay").performTextReplacement("Plants use light to make sugars.")
            waitUntil(timeoutMillis = 20_000) { store.get(book.id, id)["answers"]!!.jsonObject["essay"]?.jsonObject?.str("text") == "Plants use light to make sugars." }
            onNodeWithTag("local-submit").performScrollTo().performClick()
            onNodeWithTag("local-confirm-submit").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-exam-result").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("总分待评").assertExists()
            onNodeWithText("已计分题目：1 分 · 1 题待评").assertExists()
            onNodeWithText("参考得分：1 / 4").assertDoesNotExist()
            capture("06-essay-pending-result", "exam-test-window")
            onNodeWithTag("local-source-essay").performScrollTo().performClick()
            onNodeWithTag("local-source-quote").assertTextEquals(quote)
            val result = store.get(book.id, id)["result"]!!.jsonObject
            assertEquals(JsonNull, result["score"])
            assertEquals(1, result.num("pending"))
            assertEquals("UNREVIEWED", result.str("key_state"))
        }
    }

    @Test fun emptyExamCourseShowsMissingMaterialsAndNoStartButton() {
        val root = Files.createTempDirectory("coach-course-no-questions-")
        val library = TextbookLibrary(root.resolve("library"))
        val courseId = library.withCourseDatabase { db ->
            CourseStore.createCourse(db, "Empty exam course", CourseExamProfile(examEnabled = true)).first
        }
        val c = TextbookController(library)
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent { WindowsTextbookHost(c) { null } }
            waitUntil(timeoutMillis = 20_000) { c.state.value.initialized && !c.state.value.busy }
            assertNull(c.state.value.error)
            onNodeWithTag("collection-$courseId").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { c.state.value.screen == BookScreen.COURSE }
            onNodeWithTag("course-mock-exam-btn").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("course-mock-hub").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("当前还没有可用于组卷的课程内容").performScrollTo().assertIsDisplayed()
            onNodeWithTag("course-mock-generate-btn").assertDoesNotExist()
            onNodeWithTag("course-mock-resume-btn").assertDoesNotExist()
            assertTrue(LocalExamStore(library).papers(LocalExamController.courseMockScopeId(courseId)).isEmpty())
            capture("07-no-usable-question")
        }
    }

    @Test fun partialReferencePaperNamesTheChaptersItCouldNotUse() {
        val root = Files.createTempDirectory("coach-course-partial-gap-")
        val file = root.resolve("mixed.md")
        val sections = listOf(
            "细胞" to "细胞是生物体结构和功能的基本单位。细胞里有细胞核，并能通过分裂和分化维持生命的连续性。",
            "细胞膜" to "细胞膜是包围细胞的一层薄膜。它控制物质进出，同时保护内部环境并参与细胞之间的交流。",
            "细胞核" to "细胞核是细胞里储存遗传信息的结构。它里面有染色体，负责调控细胞代谢和生长繁殖。",
            "补充说明" to "略。",
        )
        Files.writeString(file, sections.mapIndexed { index, (title, body) -> "## 第${index + 1}节 $title\n\n$body" }.joinToString("\n\n"))
        val library = TextbookLibrary(root.resolve("library"))
        library.importBook(file)
        val c = TextbookController(library)
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent { WindowsTextbookHost(c) { null } }
            ready(c)
            nav("课程").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-generate-start").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("local-generate-start").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-exam-attempt").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("local-exam-back").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-exam-gaps").fetchSemanticsNodes().isNotEmpty() }
            // 缺题说清是哪一节、去哪儿补；不再出现「暂无」这类系统腔。
            onNodeWithTag("local-exam-gaps").performScrollTo().assertTextContains("补充说明", substring = true)
            onNodeWithTag("local-exam-gaps").assertTextContains("回教材", substring = true)
            onNodeWithText("暂无", substring = true).assertDoesNotExist()
            onNodeWithText("缺少可用选择题", substring = true).assertDoesNotExist()
            capture("08-partial-reference-gap")
        }
    }

    private fun savedChoicePaper(book: Textbook, id: String): LocalExamPaper {
        val lesson = book.lessons.first()
        val block = lesson.blocks.first { !it.heading }
        val source = json("docId" to book.id, "lessonId" to lesson.id, "pdfPage" to block.page, "quote" to block.text)
        val item = json("id" to lesson.id, "type" to "mcq", "marks" to 1,
            "stem" to "Which statement comes from the saved page?",
            "options" to JsonArray(listOf(JsonPrimitive(block.text), JsonPrimitive("Unrelated statement"))),
            "answer" to 0, "source" to source)
        return LocalExamPaper(json("id" to id, "bookId" to book.id, "title" to "Saved drill",
            "kind" to "drill", "origin" to "TEXTBOOK_REFERENCE", "sections" to JsonArray(listOf(
                json("title" to "Practice", "items" to JsonArray(listOf(item)))))))
    }

    @Test fun failedAcceptedAnswerBlocksCloseAndRetryPersistsIt() {
        val root = Files.createTempDirectory("coach-exam-close-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val c = TextbookController(library)
        val store = LocalExamStore(library)
        val paper = savedChoicePaper(book, "saved-choice")
        store.register(paper)
        val first = book.lessons.first()
        val correct = 0
        val choice = hasTestTag("local-choice-${first.id}-$correct")
        var windowOpen by mutableStateOf(true)
        lateinit var guard: TextbookCloseGuard
        lateinit var id: String
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent {
                if (windowOpen) {
                    val drafts = rememberAnswerDrafts(c)
                    val exams = rememberLocalExamController(library)
                    val scope = rememberCoroutineScope()
                    guard = remember { TextbookCloseGuard({ drafts.flush() && exams.flush() }, scope) { windowOpen = false } }
                    Box(Modifier.fillMaxSize()) {
                        SavedExamAttempt(library, book, paper, exams)
                        Button(guard::requestClose, Modifier.align(Alignment.BottomEnd).testTag("exam-request-close")) { Text("close") }
                    }
                    TextbookCloseWarning(guard)
                }
            }
            waitUntil(timeoutMillis = 20_000) { store.active(book.id) != null }
            id = store.active(book.id)!!.str("mockId")
            DriverManager.getConnection("jdbc:sqlite:${store.databasePath}").use { db ->
                db.createStatement().use { it.execute("""CREATE TRIGGER reject_save BEFORE UPDATE OF answers_json ON local_exam_attempts
                    BEGIN SELECT RAISE(ABORT,'synthetic save failure'); END""") }
            }
            // SQLite commits before refresh publishes the attempt and the option becomes interactive.
            waitUntil(timeoutMillis = 20_000) { onAllNodes(choice and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            onNode(choice).performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-exam-error").fetchSemanticsNodes().isNotEmpty() }
            onNode(choice).assertIsSelected()
            onNodeWithTag("exam-request-close").performClick()
            waitUntil(timeoutMillis = 20_000) { guard.failed }
            assertTrue(windowOpen)
            assertEquals(json(), store.get(book.id, id)["answers"])
            DriverManager.getConnection("jdbc:sqlite:${store.databasePath}").use { db ->
                db.createStatement().use { it.execute("DROP TRIGGER reject_save") }
            }
            onNodeWithTag("retry-close").performClick()
            waitUntil(timeoutMillis = 20_000) { !windowOpen }
            assertEquals(correct.toLong(), store.get(book.id, id)["answers"]!!.jsonObject[first.id]!!.jsonObject.num("choice"))
        }
        val reopened = TextbookLibrary(library.directory)
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent { SavedExamAttempt(reopened, reopened.book(book.id), paper, resumeId = id) }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-exam-attempt").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("local-attempt-id").assertTextContains("第 1 次", substring = true)
            onNodeWithText("local-", substring = true).assertDoesNotExist()
            onNode(choice).performScrollTo().assertIsSelected()
        }
        println("LOCAL_EXAM_CLOSE_PASS: accepted choice stayed visible after SQLite failure; window held; retry saved before close; reopened exact choice")
    }

    @Test fun anotherWindowUpdateRequiresExplicitReloadBeforeFurtherEditing() {
        val root = Files.createTempDirectory("coach-exam-window-conflict-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val store = LocalExamStore(library)
        val paper = savedChoicePaper(book, "saved-choice")
        store.register(paper)
        val first = book.lessons.first()
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent { SavedExamAttempt(library, book, paper) }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-exam-attempt").fetchSemanticsNodes().isNotEmpty() }
            val id = store.active(book.id)!!.str("mockId")
            val other = json(first.id to json("choice" to 1))
            store.save(book.id, id, other, 10)
            onNodeWithTag("local-choice-${first.id}-0").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-reload-saved").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("local-choice-${first.id}-0").assertIsSelected().assertIsNotEnabled()
            assertEquals(other, store.get(book.id, id)["answers"])
            onNodeWithTag("local-reload-saved").performScrollTo().performClick()
            onNodeWithText("保留当前内容").performClick()
            onNodeWithTag("local-choice-${first.id}-0").assertIsSelected()
            onNodeWithTag("local-reload-saved").performScrollTo().performClick()
            onNodeWithTag("local-confirm-reload").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("local-reload-saved").fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("local-choice-${first.id}-1").performScrollTo().assertIsSelected()
            onNodeWithTag("local-choice-${first.id}-0").performClick()
            waitUntil(timeoutMillis = 20_000) { store.get(book.id, id).num("answersSeq") == 11L }
            assertEquals(json(first.id to json("choice" to 0)), store.get(book.id, id)["answers"])
        }
    }

    @Test fun storedReferenceVersionsKeepLegacyPendingAttemptAcrossReopen() {
        val root = Files.createTempDirectory("coach-exam-reference-version-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val current = savedChoicePaper(book, "reference-${book.id}-v2")
        assertNull(textbookReferencePaper(book).paper)
        val legacy = LocalExamPaper(JsonObject(current.snapshot +
            ("id" to JsonPrimitive("reference-${book.id}-v1"))))
        assertNotEquals(legacy.id, current.id)
        val store = LocalExamStore(library)
        store.register(legacy)
        val oldAttempt = store.start(book.id, legacy.id)
        store.register(current)
        assertEquals(oldAttempt, store.get(book.id, oldAttempt.str("mockId")))
        assertEquals(legacy.id, store.active(book.id)!!.str("paperId"))
        val reopened = LocalExamStore(TextbookLibrary(library.directory))
        assertEquals(setOf(current.id, legacy.id), reopened.papers(book.id).map { it.str("id") }.toSet())
        assertEquals(2, reopened.papers(book.id).size)
    }
}
