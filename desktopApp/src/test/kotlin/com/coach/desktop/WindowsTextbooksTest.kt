package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WindowsTextbooksTest {
    @Test fun developmentLibraryIsExplicitAndIndependentOfProductionPairing() {
        val home = Files.createTempDirectory("coach-books-home-")
        assertFailsWith<IllegalArgumentException> { windowsLibraryDirectory({ null }, { null }, home) }
        val isolated = home.resolve("isolated")
        assertEquals(isolated, windowsLibraryDirectory({ if (it == "COACH_LIBRARY_DIR") isolated.toString() else null }, { null }, home))
        assertEquals(home.resolve("Coach/books"), windowsLibraryDirectory({ null }, { if (it == "jpackage.app-path") "Coach.exe" else null }, home))
        assertFailsWith<IllegalArgumentException> {
            windowsLibraryDirectory({ if (it == "COACH_LIBRARY_DIR") home.resolve("Coach/books").toString() else null }, { null }, home)
        }
    }

    /** Offscreen Compose actions only: no native file dialog, desktop input or existing user books. */
    @Test fun uploadReadDeclineLearningThenLearnAndOpenTheCorrectOriginalPage() {
        val root = Files.createTempDirectory("coach-windows-book-ui-")
        val source = syntheticTextbook(root.resolve("friend"))
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { source } }
            ready(controller) { it.catalog.isEmpty() }
            onNodeWithTag("upload-textbook").performClick()
            ready(controller) { it.prompt == BookPrompt.IMPORTED }
            onNodeWithTag("read-only").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.prompt == BookPrompt.AFTER_READING }
            onNodeWithText("要开启 learning 吗？").assertExists()
            onNodeWithTag("read-only").performClick()
            ready(controller) { it.prompt == null && it.book?.promptSeen == true }
            assertFalse(controller.state.value.book!!.learning)
            onNodeWithTag("next-page").performClick()
            ready(controller) { it.page == 2 }
            capture("reader")
            onNodeWithText("返回书架").performClick()
            ready(controller) { it.screen == BookScreen.HOME }
            capture("home")
            onNodeWithTag("home-learn").performClick()
            ready(controller) { it.prompt == BookPrompt.START }
            onNodeWithTag("question-mode-CHOICE").performClick()
            ready(controller) { it.book?.questionMode == QuestionMode.CHOICE }
            onNodeWithTag("enable-learning").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING && it.prompt == null }
            capture("learning")
            val learning = controller.state.value.book!!
            val practice = StudyCoachCourse.practice(learning, learning.lessons[0], QuestionMode.CHOICE)!!
            onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
            ready(controller) { it.answer.text == practice.answer && it.answer.revealed }
            onNodeWithText("与原文一致。").assertExists()
            onNodeWithTag("open-lesson-source").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.READER && it.source != null }
            assertEquals(1, controller.state.value.page)
            assertEquals(2, controller.state.value.book!!.readingPage)
            onNodeWithText("返回学习").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING && it.source == null }
            assertEquals(0, controller.state.value.book!!.lessonIndex)
            onNodeWithTag("question-settings").performClick()
            ready(controller) { it.prompt == BookPrompt.QUESTIONS }
            onNodeWithTag("question-mode-SHORT_ANSWER").performClick()
            ready(controller) { it.book?.questionMode == QuestionMode.SHORT_ANSWER }
            onNodeWithTag("close-question-settings").performClick()
            ready(controller) { it.prompt == null }
            onNodeWithTag("short-answer").performScrollTo().performTextInput("Plants use light to make sugar.")
            ready(controller) { it.answer.text == "Plants use light to make sugar." }
            onNodeWithTag("reveal-reference").performScrollTo().performClick()
            ready(controller) { it.answer.revealed }
            onNodeWithText("这是对照原文的自检，不会自动判定掌握。").assertExists()
            onNodeWithTag("next-lesson").performScrollTo().performClick()
            ready(controller) { it.book?.lessonIndex == 1 }
        }
        val reopened = TextbookLibrary(root.resolve("library")).book(controller.state.value.book!!.id)
        assertEquals(1, reopened.lessonIndex)
        assertEquals(2, reopened.readingPage)
        assertEquals(QuestionMode.SHORT_ANSWER, reopened.questionMode)
    }

    @Test fun directlyLearningWithNoQuestionsNeedsNeitherCourseConfigurationNorPairing() {
        val root = Files.createTempDirectory("coach-direct-book-ui-")
        val source = syntheticTextbook(root.resolve("friend"))
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { source } }
            ready(controller) { it.catalog.isEmpty() }
            onNodeWithTag("upload-textbook").performClick()
            ready(controller) { it.prompt == BookPrompt.IMPORTED }
            onNodeWithTag("enable-learning").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING }
            assertEquals(QuestionMode.NONE, controller.state.value.book!!.questionMode)
            onAllNodesWithTag("choice-0").assertCountEquals(0)
            onAllNodesWithTag("short-answer").assertCountEquals(0)
            onNodeWithTag("next-lesson").performScrollTo().performClick()
            ready(controller) { it.book?.lessonIndex == 1 }
            onNodeWithTag("open-lesson-source").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.READER }
            assertEquals(2, controller.state.value.page)
        }
    }

    /**
     * The live host path: 今天/课程/书架 plus 设置 in one chrome, and the textbook flow still runs inside 书架.
     * 1440×900 dp is the shipped window; the desk keeps at most two columns beside the rail.
     */
    @Test fun defaultHostKeepsThreeDestinationsAndTheTextbookFlowInTwoColumns() {
        val root = Files.createTempDirectory("coach-windows-host-ui-")
        val source = syntheticTextbook(root.resolve("friend"))
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(controller) { source } }
            ready(controller) { it.catalog.isEmpty() && it.book == null }
            fun nav(label: String) = onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText(label))

            // 旧缺口：Windows 入口直接落在书桌，没有三入口。新入口三处同在，书名与导航都叫书架。
            onNodeWithTag("host-navigation").assertIsDisplayed()
            nav("今天").assertIsDisplayed()
            nav("课程").assertIsDisplayed()
            nav("书架").assertIsDisplayed()
            onNodeWithTag("library-title").assertTextEquals("书架")
            capture("host-library-1440x900", "windows-host")

            // 设置右上角，内容是本机的书架位置。
            onNodeWithTag("open-settings").assertIsDisplayed().performClick()
            onNodeWithTag("settings-library-directory").assertTextEquals(root.resolve("library").toString())
            onNodeWithTag("close-settings").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("settings-library-directory").fetchSemanticsNodes().isEmpty() }

            // 今天和课程在这台没有课程数据的机器上是真实空态，不伪造进度。
            nav("今天").performClick()
            onNodeWithTag("host-today-empty").assertIsDisplayed()
            onAllNodesWithTag("upload-textbook").assertCountEquals(0)
            capture("host-today-1440x900", "windows-host")
            nav("课程").performClick()
            onNodeWithTag("host-course-empty").assertIsDisplayed()
            onAllNodesWithTag("upload-textbook").assertCountEquals(0)
            capture("host-course-1440x900", "windows-host")
            nav("书架").performClick()
            onNodeWithTag("upload-textbook").assertIsDisplayed()

            // 上传 → 出题选选择 → 直接开始学。
            onNodeWithTag("upload-textbook").performClick()
            ready(controller) { it.prompt == BookPrompt.IMPORTED }
            onNodeWithTag("question-mode-CHOICE").performClick()
            ready(controller) { it.book?.questionMode == QuestionMode.CHOICE }
            onNodeWithTag("enable-learning").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING }

            // 两栏落在实际宿主：正文与出处栏并排，目录只在抽屉里。
            onNodeWithTag("v7-paper").assertIsDisplayed()
            onNodeWithTag("v7-margin").assertIsDisplayed()
            onAllNodesWithTag("v7-directory-drawer").assertCountEquals(0)
            onNodeWithTag("v7-directory-toggle").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("v7-directory-drawer").fetchSemanticsNodes().isNotEmpty() }
            onNode(hasAnyAncestor(hasTestTag("v7-directory-drawer")) and hasText("学习路线")).assertIsDisplayed()
            capture("host-learning-directory-1440x900", "windows-host")
            onNodeWithText("收起目录").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("v7-directory-drawer").fetchSemanticsNodes().isEmpty() }
            waitForIdle()

            // 作答、出处和翻页照旧；去今天再回书架，学习位置和作答都还在。
            val learning = controller.state.value.book!!
            val practice = StudyCoachCourse.practice(learning, learning.lessons[0], QuestionMode.CHOICE)!!
            onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
            ready(controller) { it.answer.revealed }
            onNodeWithText("与原文一致。").assertExists()
            nav("今天").performClick()
            onNodeWithTag("host-today-book").assertIsDisplayed()
            nav("书架").performClick()
            onNodeWithTag("textbook-learning").assertIsDisplayed()
            onNodeWithText("与原文一致。").assertExists()
            onNodeWithTag("open-lesson-source").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.READER && it.source != null }
            onNodeWithTag("next-page").performClick()
            ready(controller) { it.page == 2 }
            // 学习时看原文只翻原文，不挪动书架上的阅读书签。
            assertEquals(1, controller.state.value.book!!.readingPage)
            capture("host-source-1440x900", "windows-host")
            onNodeWithText("返回学习").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING && it.source == null }
            onNodeWithTag("next-lesson").performScrollTo().performClick()
            ready(controller) { it.book?.lessonIndex == 1 }

            // 回书架再读：真页翻读把书签落在第 2 页。
            onNodeWithText("返回书架").performClick()
            ready(controller) { it.screen == BookScreen.HOME }
            onNodeWithTag("home-read").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.source == null }
            onNodeWithTag("next-page").performClick()
            ready(controller) { it.page == 2 && it.book?.readingPage == 2 }
        }
        val reopened = TextbookLibrary(root.resolve("library")).book(controller.state.value.book!!.id)
        assertEquals(1, reopened.lessonIndex)
        assertEquals(2, reopened.readingPage)
        assertEquals(QuestionMode.CHOICE, reopened.questionMode)
    }

    /**
     * 今天 and 课程 are not just labels: both read the controller 书架 owns. A book that is already on disk
     * is continuable before 书架 is opened, and continuing it lands in the same reader and lesson screens.
     */
    @Test fun todayAndCourseContinueTheBookThatTheBookshelfAlreadyHas() {
        val root = Files.createTempDirectory("coach-windows-today-course-")
        val library = TextbookLibrary(root.resolve("library"))
        val stored = library.importBook(syntheticTextbook(root.resolve("friend")))
        val controller = TextbookController(library)
        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(controller) { null } }
            ready(controller) { it.book?.id == stored.id }
            fun nav(label: String) = onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText(label))
            fun panel(tag: String, text: String) = onNode(hasAnyAncestor(hasTestTag(tag)) and hasText(text, substring = true))

            // 已有教材在盘上：不进书架，今天就有这本可接着读的书，位置来自同一条记录。
            nav("今天").performClick()
            onNodeWithTag("host-today-book").assertIsDisplayed()
            panel("host-today-book", stored.title).assertIsDisplayed()
            panel("host-today-book", "读到第 1 页").assertIsDisplayed()
            capture("host-today-existing-book-1440x900", "windows-host")

            // 课程就是这本教材按节分，未开启 learning 时用的是书架自己的开启流程。
            nav("课程").performClick()
            onNodeWithTag("host-course-book").assertIsDisplayed()
            panel("host-course-book", "共 3 节").assertIsDisplayed()
            capture("host-course-existing-book-1440x900", "windows-host")
            onNodeWithTag("host-course-learn").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("enable-learning").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("question-mode-CHOICE").performClick()
            ready(controller) { it.book?.questionMode == QuestionMode.CHOICE }
            onNodeWithTag("enable-learning").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING }

            // 作答后去今天：同一份进度，继续学回到同一节，作答还在。
            val learning = controller.state.value.book!!
            val practice = StudyCoachCourse.practice(learning, learning.lessons[0], QuestionMode.CHOICE)!!
            onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
            ready(controller) { it.answer.revealed }
            nav("今天").performClick()
            panel("host-today-book", "学习停在第 1 / 3 节").assertIsDisplayed()
            onNodeWithTag("host-today-learn").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING }
            onNodeWithText("与原文一致。").assertExists()

            // 看原文再返回，学习位置往后走一节；今天读到的是书架真实书签，不是学习出处页。
            onNodeWithTag("open-lesson-source").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.READER && it.source != null }
            onNodeWithTag("next-page").performClick()
            ready(controller) { it.page == 2 }
            assertEquals(1, controller.state.value.book!!.readingPage)
            onNodeWithText("返回学习").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING && it.source == null }
            onNodeWithTag("next-lesson").performScrollTo().performClick()
            ready(controller) { it.book?.lessonIndex == 1 }
            nav("今天").performClick()
            panel("host-today-book", "学习停在第 2 / 3 节").assertIsDisplayed()
            onNodeWithTag("host-today-read").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.source == null }
            onNodeWithTag("reader-page-number").assertTextEquals("1 / 3")
            onNodeWithTag("next-page").performClick()
            ready(controller) { it.page == 2 && it.book?.readingPage == 2 }

            // 三个入口来回切不重新读库：阅读器还停在原页，书签没被重置。
            nav("课程").performClick()
            panel("host-course-book", "读到第 2 页").assertIsDisplayed()
            nav("书架").performClick()
            onNodeWithTag("reader-page-number").assertTextEquals("2 / 3")
            assertEquals(BookScreen.READER, controller.state.value.screen)
            capture("host-return-keeps-page-1440x900", "windows-host")
        }
        val reopened = TextbookLibrary(root.resolve("library")).book(stored.id)
        assertEquals(1, reopened.lessonIndex)
        assertEquals(2, reopened.readingPage)
        assertEquals(QuestionMode.CHOICE, reopened.questionMode)
    }

    private fun ComposeUiTest.ready(controller: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && condition(controller.state.value) }
        assertNull(controller.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.capture(name: String, tag: String = "windows-textbooks") {
        val output = File("build/screens/windows-textbooks").apply { mkdirs() }
        ImageIO.write(onNodeWithTag(tag).captureToImage().toAwtImage(), "png", File(output, "$name.png"))
    }
}
