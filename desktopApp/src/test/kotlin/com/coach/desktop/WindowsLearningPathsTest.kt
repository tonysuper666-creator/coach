package com.coach.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WindowsLearningPathsTest {
    /**
     * 同一个 Windows 宿主里两本书各走自己的路（看书 / 边看边学 / 先做题学），换一个新宿主后每本书回到自己的路、页、节和作答。
     *
     * 按当前规格改写的入口；核对的东西不变：路按书存、看书绕路不改路、原文只在点了才出现、草稿和作答不丢。
     * - TON-1784（#570）：导入直接进阅读器，没有 IMPORTED / START 提示（learning-path-*、question-mode-*、enable-learning）；
     *   选路只剩控制器 learn(path)，题型只剩 mode()。阅读器里的「选段提问」（ask-book-passage）也去掉了，
     *   选段弹窗本身由 WindowsPassageQuestionLimitTest 核对。先做题学的选择题改由已配对的服务按本节正文生成，作答只留在
     *   本窗口（AnswerExplain.answers）；测试用 generatedChoiceGate 顶替服务，不联网、不借机器上的配对。
     * - TON-1790：书架首页只有焦点卡「继续」（home-read，回到读到的页）加学法选择，没有 home-learn / home-guided，
     *   也没有「今天 / 课程」两页的读、学按钮；看书绕路改走书架「继续」。书架有书时导入走顶栏「＋」→「导入教材」。
     * - 学习页「题型：…」按钮点了目前不出题型面板（#570 删了 BookPrompt.QUESTIONS 的面板），见 TON-1862；这里换题型走 mode()。
     */
    @Test fun threeWalksThroughTheActualHostRestorePerBookWithoutLosingAnswers() {
        val root = Files.createTempDirectory("coach-three-walks-host-")
        val aFile = learningPathTextbook(root, "Synthetic astronomy")
        val bFile = learningPathTextbook(root, "Synthetic chemistry", chemistry = true)
        val directory = root.resolve("library")
        val library = TextbookLibrary(directory)
        BookHudPreferenceStore(directory).setPinned(true)
        var nextFile = aFile
        var controller by mutableStateOf(TextbookController(library))
        // 出题通道给一个确定不联网的实现：这台机器的配对与 Mac 都不参与这条测试。
        val gate = generatedChoiceGate(root)
        // 讲解 / 选段问答有独立的配对通道：隔离它的默认凭据目录，绝不借用机器上的配对。
        assertTrue(System.getenv("COACH_CLIENT_DIR").isNullOrBlank())
        assertTrue(System.getenv("COACH_TOKEN_FILE").isNullOrBlank())
        val previousHome = System.getProperty("user.home")
        System.setProperty("user.home", root.resolve("unpaired-home").toString())
        try {
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            setContent { key(controller) { WindowsTextbookHost(controller, answerExplain = gate) { nextFile } } }
            ready(controller) { it.catalog.isEmpty() }
            onNodeWithTag("upload-textbook").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.prompt == null }
            val a = controller.state.value.book!!
            assertNull(library.learningPath(a.id), "Landing in the reader after import must not record a walk")
            runBlocking { controller.learn(LearningPath.READ) }
            ready(controller) { it.screen == BookScreen.READER && it.learningPath == LearningPath.READ }
            assertEquals("READ", library.learningPath(a.id))
            onNodeWithTag("choice-0").assertDoesNotExist()
            onNodeWithTag("book-lesson-explanation").assertDoesNotExist()
            jumpToPage(controller, 2)
            capture("read-route")
            assertEquals(PracticeAnswer(), library.answer(a.id, a.lessons[0].id, QuestionMode.CHOICE))
            assertEquals(PracticeAnswer(), gate.answerFor(a, a.lessons[0]))
            // 关掉时停在阅读器，新宿主就回到同一本书的同一页（load() 按 open session 恢复）；回书架再点「继续」也是这一页。
            runOnIdle { controller = TextbookController(TextbookLibrary(directory)) }
            ready(controller) { it.book?.id == a.id && it.screen == BookScreen.READER }
            assertEquals(2, controller.state.value.page)
            assertEquals(LearningPath.READ, controller.state.value.learningPath)
            onNodeWithTag("immersive-back").assertTextContains("书架", substring = true).performClick()
            ready(controller) { it.book?.id == a.id && it.screen == BookScreen.HOME }
            onNodeWithTag("home-learn").assertDoesNotExist()
            onNodeWithTag("home-read").assertTextEquals("继续").performClick()
            ready(controller) { it.screen == BookScreen.READER }
            assertEquals(2, controller.state.value.page)
            assertEquals(LearningPath.READ, controller.state.value.learningPath)
            onNodeWithTag("question-settings").assertDoesNotExist()

            onNodeWithTag("immersive-back").assertTextContains("书架", substring = true).performClick()
            ready(controller) { it.screen == BookScreen.HOME }
            runBlocking { controller.learn(LearningPath.READ_AND_LEARN) }
            ready(controller) { it.screen == BookScreen.LEARNING && it.learningPath == LearningPath.READ_AND_LEARN }
            onNodeWithTag("choice-0").assertDoesNotExist()
            onNodeWithTag("book-lesson-explanation").assertExists()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-unavailable").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(a.lessons[0].text).assertDoesNotExist()
            capture("guided-before-questions")
            onNodeWithTag("guided-start-questions").performClick()
            ready(controller) { it.questionsOpen }
            // 边看边学的选择题只由已配对的服务出（TON-1784）；这里只指路，不拿本地模板顶。
            onNodeWithTag("choice-0").assertDoesNotExist()
            onNodeWithText("本节选择题需登录 Coach 服务生成", substring = true).assertExists()
            runBlocking { controller.mode(QuestionMode.SHORT_ANSWER) }
            ready(controller) { it.book?.questionMode == QuestionMode.SHORT_ANSWER }
            // 学习页只写题型本身，不再露「出题：简答」这种半截词。
            onNodeWithText("题型：简答题").assertExists()
            onNodeWithTag("short-answer").performScrollTo().performTextInput("My retained guided draft")
            ready(controller) { it.answer.text == "My retained guided draft" }
            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.READER && it.source != null }
            onNodeWithTag("immersive-back").assertTextContains("返回学习", substring = true).performClick()
            ready(controller) { it.screen == BookScreen.LEARNING && it.questionsOpen }
            onNodeWithTag("short-answer").assertTextContains("My retained guided draft")
            onNodeWithTag("open-practice-source").assertIsFocused()
            assertEquals(2, library.book(a.id).readingPage)

            // The ordinary read button is a detour, not a new walk selection.
            onNodeWithText("返回书架").performClick()
            ready(controller) { it.screen == BookScreen.HOME }
            onNodeWithTag("home-read").assertTextEquals("继续").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.source == null && it.page == 2 }
            assertEquals("READ_AND_LEARN", library.learningPath(a.id))
            onNodeWithTag("immersive-back").assertTextContains("书架", substring = true).performClick()
            ready(controller) { it.screen == BookScreen.HOME }
            runBlocking { controller.learn() }
            ready(controller) { it.screen == BookScreen.LEARNING && it.answer.text == "My retained guided draft" }
            assertEquals(LearningPath.READ_AND_LEARN, controller.state.value.learningPath)
            val resumed = controller.state.value.book!!
            assertEquals(a.id, resumed.id)
            assertEquals(0, resumed.lessonIndex)
            assertEquals(a.lessons[0].id, resumed.lessons[resumed.lessonIndex].id)
            onNodeWithTag("short-answer").performScrollTo().assertTextContains("My retained guided draft")
            capture("shelf-learning-after-read")
            onNodeWithText("返回书架").performClick()
            ready(controller) { it.screen == BookScreen.HOME }

            nextFile = bFile
            onNodeWithTag("host-add").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("host-add-import").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("host-add-import").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.prompt == null && it.book?.id != a.id }
            runBlocking { controller.mode(QuestionMode.NONE) }
            ready(controller) { it.book?.questionMode == QuestionMode.NONE }
            runBlocking { controller.learn(LearningPath.QUESTION_FIRST) }
            ready(controller) { it.screen == BookScreen.LEARNING }
            assertEquals(LearningPath.QUESTION_FIRST, controller.state.value.learningPath)
            onNodeWithTag("book-questions-off").assertExists()
            onNodeWithText("题型：关闭").assertExists()
            onNodeWithTag("choice-0").assertDoesNotExist()
            val b = controller.state.value.book!!
            onNodeWithText(b.lessons[0].text).assertDoesNotExist()
            runBlocking { controller.mode(QuestionMode.CHOICE) }
            ready(controller) { it.book?.questionMode == QuestionMode.CHOICE }
            assertEquals(LearningPath.QUESTION_FIRST, controller.state.value.learningPath)
            onNodeWithTag("book-lesson-explanation").assertDoesNotExist()
            waitUntil(timeoutMillis = 20_000) { gate.practices["${b.id}:${b.lessons[0].id}"] != null }
            val q0 = gate.practices.getValue("${b.id}:${b.lessons[0].id}").practice
            assertEquals(generatedChoiceBlock(b.lessons[0]).text, q0.reference)
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("choice-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("choice-${q0.options.indexOf(q0.answer)}").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { gate.answerFor(b, b.lessons[0]).revealed }
            // 答完就讲，题下给小结卡接着走；原文仍只在点了才出现。
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("与本题答案一致。").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-lesson-explanation").assertExists()
            onNodeWithTag("book-summary-card").assertExists()
            onNodeWithText("原文：${q0.reference}").assertDoesNotExist()
            onNodeWithText(b.lessons[0].text).assertDoesNotExist()
            capture("question-route-after-answer")
            onNodeWithTag("next-lesson").performScrollTo().performClick()
            ready(controller) { it.book?.lessonIndex == 1 }
            val currentB = controller.state.value.book!!
            waitUntil(timeoutMillis = 20_000) { gate.practices["${b.id}:${currentB.lessons[1].id}"] != null }
            val q1 = gate.practices.getValue("${b.id}:${currentB.lessons[1].id}").practice
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("choice-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("choice-${q1.options.indexOf(q1.answer)}").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { gate.answerFor(currentB, currentB.lessons[1]).revealed }
            assertEquals(PracticeAnswer(q1.answer, true), gate.answerFor(currentB, currentB.lessons[1]))

            runOnIdle { controller = TextbookController(TextbookLibrary(directory)) }
            // 关掉时停在学习页，新宿主直接回到这本书的学习页（load() 按 open session 恢复），不用再选路。
            ready(controller) { it.book?.id == b.id && it.screen == BookScreen.LEARNING }
            assertEquals(LearningPath.QUESTION_FIRST, controller.state.value.learningPath)
            assertEquals(1, controller.state.value.book!!.lessonIndex)
            // 换宿主不换窗口：生成题的作答还留在本窗口，答后讲解照旧显示。
            assertEquals(PracticeAnswer(q1.answer, true), gate.answerFor(currentB, currentB.lessons[1]))
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("与本题答案一致。").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(currentB.lessons[1].text).assertDoesNotExist()
            capture("restored-question-route")
            onNodeWithText("返回书架").performClick()
            ready(controller) { it.screen == BookScreen.HOME }
            // TON-1790：书架上点一本书就打开它（进阅读器）；学习页仍由 learn() 按这本书存下的路恢复。
            onNodeWithTag("book-${a.id}").performScrollTo().performClick()
            ready(controller) { it.book?.id == a.id && it.screen == BookScreen.READER }
            runBlocking { controller.learn() }
            ready(controller) { it.screen == BookScreen.LEARNING }
            assertEquals(LearningPath.READ_AND_LEARN, controller.state.value.learningPath)
            assertEquals(QuestionMode.SHORT_ANSWER, controller.state.value.book!!.questionMode)
            onNodeWithTag("short-answer").assertTextContains("My retained guided draft")
            assertEquals(2, controller.state.value.book!!.readingPage)
            capture("restored-guided-route")
            println("LEARNING_PATH_TRACE two-books: READ/page2/reopen -> GUIDED/explanation-unavailable/explicit-questions/retained-draft -> shelf-detour -> QUESTION_FIRST/NONE/generated-CHOICE/lesson2-answer -> new-host:each-book-own-walk/page/lesson/answer; raw-source-only-after-click")
        }
        } finally {
            if (previousHome == null) System.clearProperty("user.home") else System.setProperty("user.home", previousHome)
        }
    }

    @Test fun rawSourceEchoIsNotDisplayedAsAnExplanation() = runBlocking {
        val root = Files.createTempDirectory("coach-source-not-explanation-")
        val c = TextbookController(TextbookLibrary(root.resolve("library")))
        c.importBook(learningPathTextbook(root, "Synthetic echo boundary"))
        c.chooseLearningPath(LearningPath.READ_AND_LEARN); c.enableLearning()
        val book = c.state.value.book!!
        val body = book.lessons[0].text
        val echo = object : BookLearningSupport {
            override suspend fun explain(request: BookExplanationRequest) = BookLearningReply.Text(body)
            override suspend fun ask(request: BookPassageQuestion) = BookLearningReply.Unavailable("No answer provider")
        }
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            setContent { TextbookWorkspace(c, support = echo) { null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-unavailable").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-explanation-text").assertDoesNotExist()
            onNodeWithText(body).assertDoesNotExist()
            onNodeWithTag("choice-0").assertDoesNotExist()
        }
    }

    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.capture(name: String) {
        val dir = Path.of("build/screens/learning-paths").toFile().also { it.mkdirs() }
        val file = dir.resolve("$name.png")
        assertTrue(ImageIO.write(onNodeWithTag("windows-host").captureToImage().toAwtImage(), "png", file))
        val image = assertNotNull(ImageIO.read(file))
        assertEquals(1100, image.width); assertEquals(800, image.height)
    }
}
