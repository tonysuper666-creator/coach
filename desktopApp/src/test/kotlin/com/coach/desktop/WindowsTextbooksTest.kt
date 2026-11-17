package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
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
            onNodeWithText("要开始学吗？").assertExists()
            onNodeWithTag("read-only").performClick()
            ready(controller) { it.prompt == null && it.book?.promptSeen == true }
            assertFalse(controller.state.value.book!!.learning)
            onNodeWithTag("next-page").performClick()
            ready(controller) { it.page == 2 }
            capture("reader")
            onNodeWithText("返回书架").performClick()
            ready(controller) { it.screen == BookScreen.HOME }
            capture("home")
            // The shelf sits on V7's sheet and says everything in Chinese: the catalog row and prompts used to mix in "learning".
            onNodeWithTag("shelf-sheet").assertExists()
            onAllNodesWithText("learning", substring = true, ignoreCase = true).assertCountEquals(0)
            onNodeWithTag("home-question").performClick()
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
            onNodeWithTag("question-mode-NONE").performClick()
            ready(controller) { it.book?.questionMode == QuestionMode.NONE }
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

            // 设置在导航栏底部，内容是本机的书架位置。
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

            // 版心落在实际宿主：学习页没有页边内容，不留空的页边栏，纸面在桌面里居中；目录只在抽屉里。
            onNodeWithTag("v7-paper").assertIsDisplayed()
            onNodeWithTag("v7-margin").assertDoesNotExist()
            onNodeWithTag("v7-margin-toggle").assertDoesNotExist()
            val desk = onNodeWithTag("v7-desk").getBoundsInRoot()
            val paper = onNodeWithTag("v7-paper").getBoundsInRoot()
            assertTrue(abs(((paper.left - desk.left) - (desk.right - paper.right)).value) <= 1f,
                "paper is centred in the desk: desk=$desk paper=$paper")
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

    /**
     * TON-1650 D4 / D9: a page has one top bar, and learning points where it was opened from.
     * 今天 / 课程 open their book inside 书架's workspace; before, the reader showed the host's "Coach" row above
     * the desk's own one. TON-1698: reading itself is the shelf's page, so the reader always lights 书架.
     */
    @Test fun railFollowsWhereTheBookWasOpenedFromAndEachPageHasOneBar() {
        val root = Files.createTempDirectory("coach-windows-host-origin-")
        val library = TextbookLibrary(root.resolve("library"))
        val stored = library.importBook(syntheticTextbook(root.resolve("friend")))
        val controller = TextbookController(library)
        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(controller) { null } }
            ready(controller) { it.book?.id == stored.id }
            fun nav(label: String) = onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText(label))
            fun highlighted(label: String) = onNodeWithTag("host-navigation")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label))
            fun coachMarks() = onAllNodes(hasText("Coach")).fetchSemanticsNodes().size
            fun readFrom(button: String) {
                onNodeWithTag(button).performClick()
                ready(controller) { it.screen == BookScreen.READER }
                if (controller.state.value.prompt == BookPrompt.AFTER_READING) {
                    onNodeWithTag("read-only").performClick()
                    ready(controller) { it.prompt == null }
                }
            }

            highlighted("书架")
            onNodeWithTag("host-crumb").assertTextEquals("书架")
            assertEquals(1, coachMarks())

            nav("今天").performClick()
            highlighted("今天")
            onNodeWithTag("host-crumb").assertTextEquals("今天")
            // TON-1698: reading is the shelf's page, so the rail says 书架 even when 今天 opened the book.
            readFrom("host-today-read")
            highlighted("书架")
            onAllNodesWithTag("host-bar").assertCountEquals(0)
            assertEquals(1, coachMarks())
            onNodeWithTag("open-settings").assertIsDisplayed()

            // Back on the shelf the rail is on 书架 again, and a book opened from the shelf keeps it there.
            onNodeWithText("返回书架").performClick()
            ready(controller) { it.screen == BookScreen.HOME }
            highlighted("书架")
            onNodeWithTag("host-bar").assertIsDisplayed()
            readFrom("home-read")
            highlighted("书架")

            nav("课程").performClick()
            highlighted("课程")
            readFrom("host-course-read")
            highlighted("书架")
            nav("书架").performClick()
            highlighted("书架")
            onNodeWithTag("textbook-reader").assertIsDisplayed()
        }
    }

    /**
     * TON-1698: the explain panel opens inside the reader's own shell. In every state it reaches — connected,
     * the Mac gone, the connection stopped, the day's quota used — the rail, the desk bar (Coach · 目录 · 返回书架)
     * and the desk stay where the plain reader had them, the rail lights 书架, and 返回书架 leads back to the shelf.
     *
     * Same trick as [ExplainTroubleLiveWindowTest]: the host keeps its default explainer and pairing files; only this
     * process's `user.home` points at a temp directory, with `jpackage.app-path` set the way an installed copy has it.
     * The Mac is a loopback stub on a random port, never a production one; the book is the synthetic sample.
     */
    @Test fun explainPanelKeepsTheReaderShellInEveryStateInBothThemes() {
        // These would point the client directory, server or credentials elsewhere; with them set the run is no longer isolated.
        for (name in listOf("COACH_URL", "COACH_CLIENT_DIR", "COACH_TOKEN_FILE", "COACH_ACCEPTANCE_REPLAY", "COACH_AGENT_RUN_ID"))
            org.junit.Assume.assumeTrue("$name is set", System.getenv(name).isNullOrBlank())
        val saved = listOf("user.home", "jpackage.app-path", "coach.windows.theme").associateWith { System.getProperty(it) }
        val root = Files.createTempDirectory("coach-explain-shell-")
        try {
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                val home = Files.createDirectories(root.resolve("$theme-home"))
                System.setProperty("user.home", home.toString())
                System.setProperty("jpackage.app-path", home.resolve("LearningCoach.exe").toString())
                System.setProperty("coach.windows.theme", theme)
                assertEquals(home.resolve("Coach/client"), DesktopConnectionSettings().clientDirectory)
                ExplainShellMac(if (dark) 'd' else 'l').use { mac -> explainShellWalk(root.resolve(theme), theme, mac) }
            }
        } finally {
            for ((key, value) in saved) if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            root.toFile().deleteRecursively()
        }
    }

    private fun explainShellWalk(root: Path, theme: String, mac: ExplainShellMac) {
        val library = TextbookLibrary(root.resolve("library"))
        val stored = library.importBook(syntheticTextbook(root.resolve("friend")))
        ExplainConsent(library.directory).allow(stored.id)
        val controller = TextbookController(library)
        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(controller) { null } }
            ready(controller) { it.book?.id == stored.id }
            fun nav(label: String) = onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText(label))
            fun highlighted(label: String) = onNodeWithTag("host-navigation")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label))
            fun waitTag(tag: String) = waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            fun shell() = mapOf(
                "rail" to onNodeWithTag("host-navigation").getBoundsInRoot(),
                "settings" to onNodeWithTag("open-settings").getBoundsInRoot(),
                "desk" to onNodeWithTag("v7-desk").getBoundsInRoot(),
                "Coach" to onNode(hasText("Coach") and hasAnyAncestor(hasTestTag("v7-desk"))).getBoundsInRoot(),
                "目录" to onNodeWithTag("v7-directory-toggle").getBoundsInRoot(),
                "返回书架" to onNode(hasText("返回书架") and hasAnyAncestor(hasTestTag("v7-desk"))).getBoundsInRoot(),
            )
            fun shot(name: String) = capture("ton1698-$name-$theme", "windows-host")

            // Read from 今天: the reader is the shelf's page, so the rail says 书架.
            nav("今天").performClick()
            onNodeWithTag("host-today-read").performClick()
            ready(controller) { it.screen == BookScreen.READER }
            if (controller.state.value.prompt == BookPrompt.AFTER_READING) {
                onNodeWithTag("read-only").performClick()
                ready(controller) { it.prompt == null }
            }
            highlighted("书架")
            onAllNodesWithTag("host-bar").assertCountEquals(0)
            val reader = shell()
            shot("reader")
            fun sameShell(state: String) {
                waitForIdle()
                assertEquals(reader, shell(), "explain panel $state ($theme) moved the shell")
                highlighted("书架")
                onAllNodesWithTag("host-bar").assertCountEquals(0)
            }

            // 连上: pair from the panel, then one passage explained.
            onNodeWithTag("book-explain-toggle").performClick()
            waitTag("explain-pairing")
            sameShell("pairing")
            onNodeWithTag("explain-address").performTextClearance()
            onNodeWithTag("explain-address").performTextInput(mac.url)
            onNodeWithTag("explain-code").performTextInput("246810")
            onNodeWithTag("explain-pair").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-pairing").fetchSemanticsNodes().isEmpty() }
            onAllNodesWithTag("explain-button")[0].performScrollTo().performClick()
            waitTag("explain-answer")
            sameShell("connected")
            shot("explain-connected")

            // 连不上: the Mac is gone; the same passage says so and offers 再试一次 in place of its button.
            mac.mode = ExplainShellMac.Mode.OFFLINE
            onAllNodesWithTag("explain-button")[0].performScrollTo().performClick()
            waitTag("explain-retry")
            onAllNodesWithTag("explain-failed")[0].assertTextEquals(ExplainTrouble.UNREACHABLE)
            sameShell("offline")
            shot("explain-offline")

            // 被停用: the connection was stopped on the Mac; the panel goes back to pairing, shell unchanged.
            mac.mode = ExplainShellMac.Mode.REVOKED
            onAllNodesWithTag("explain-retry")[0].performScrollTo().performClick()
            waitTag("explain-revoked")
            sameShell("revoked")
            shot("explain-revoked")

            // 额度用完: re-pair, then the day's quota is used; the button closes and there is no retry.
            mac.mode = ExplainShellMac.Mode.QUOTA
            onNodeWithTag("explain-address").performTextClearance()
            onNodeWithTag("explain-address").performTextInput(mac.url)
            onNodeWithTag("explain-code").performTextInput("135791")
            onNodeWithTag("explain-pair").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-pairing").fetchSemanticsNodes().isEmpty() }
            onAllNodesWithTag("explain-button")[0].performScrollTo().performClick()
            waitTag("explain-failed")
            onAllNodesWithTag("explain-failed")[0].assertTextEquals(ExplainShellMac.QUOTA)
            onAllNodesWithTag("explain-retry").assertCountEquals(0)
            sameShell("quota")
            shot("explain-quota")
            assertEquals(2, mac.pairs.get())

            // Leaving the panel does not move anything either, and 返回书架 is the way back to the shelf.
            onNodeWithTag("book-explain-toggle").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explain").fetchSemanticsNodes().isEmpty() }
            sameShell("closed")
            onNode(hasText("返回书架") and hasAnyAncestor(hasTestTag("v7-desk"))).performClick()
            ready(controller) { it.screen == BookScreen.HOME }
            highlighted("书架")
            onNodeWithTag("host-bar").assertIsDisplayed()
            shot("back-on-shelf")
        }
    }

    /** Loopback stand-in for the Mac: pairing hands out credentials, explain answers by [mode]. */
    private class ExplainShellMac(prefix: Char) : AutoCloseable {
        enum class Mode { OK, OFFLINE, REVOKED, QUOTA }
        companion object { const val QUOTA = "今天的讲解额度用完了，明天再来。" }
        @Volatile var mode = Mode.OK
        val pairs = java.util.concurrent.atomic.AtomicInteger()
        private val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val url: String

        init {
            server.createContext("/") { exchange ->
                val sent = exchange.requestBody.use { it.readBytes() }.decodeToString()
                var type = "application/json; charset=utf-8"
                val (status, body) = when (exchange.requestURI.path) {
                    "/v1/pair" -> 200 to """{"deviceId":"friend-pc","token":"$prefix${pairs.incrementAndGet()}${"x".repeat(41)}"}"""
                    "/v1/hello" -> 200 to """{"epoch":"synthetic","lastSeq":0,"backedUpThroughSeq":0,"lastSeqBefore":0}"""
                    "/v1/library/explain" -> when (mode) {
                        // What a tunnel or proxy sends when the Mac behind it is asleep: not the Mac's own words.
                        Mode.OFFLINE -> { type = "text/html"; 502 to "<html><body>502 Bad Gateway</body></html>" }
                        Mode.REVOKED -> 401 to """{"error":"unauthorized"}"""
                        Mode.QUOTA -> 429 to """{"error":"$QUOTA","code":"daily_quota"}"""
                        Mode.OK -> {
                            val id = Json.parseToJsonElement(sent).jsonObject.getValue("blocks").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content
                            val page = Regex(":p(\\d+):").find(id)!!.groupValues[1]
                            200 to """{"text":"This passage says light gives the plant its energy.【S1】","sources":[{"docId":"$id","pdfPage":$page,"anchor":"S1"}]}"""
                        }
                    }
                    else -> 404 to "{}"
                }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", type)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            check(server.address.port !in productionPorts) { "never a production port" }
            url = "http://127.0.0.1:${server.address.port}"
        }

        override fun close() = server.stop(0)
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
