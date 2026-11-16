package com.coach.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.coach.client.PairedCredentials
import com.coach.desktop.books.*
import com.coach.desktop.books.exam.*
import com.coach.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * TON-1650：Windows 桌面每个主要界面按 V7「案头」对照拍一遍。
 *
 * 两种 Windows 常见屏（1920×1080、2560×1440）都按 150% 缩放拍，浅色、深色各一遍；
 * 截图放 `build/screens/v7-desktop/<屏>/<明暗>/<界面>.png`。设计包参考图在场时（winbox 上的
 * `D:/agent/design/ui-v7/shots`，或 `-Dcoach.v7.refs=` 指定），再拼一张左右对照图放 `compare/`：
 * 左边是桌面原图不缩放，右边是最接近的 V7 参考（Fold8 内屏 2448×1848）等比缩到同高。
 * 设计包没有桌面专用参考，对照只看颜色、字体、纸面与留白这类体系问题，不比像素位置。
 *
 * 教材是测试里自造的六节中文短文（[knowledgeRecapTextbook]），讲解走本地假通道，不联网、不碰 Mac。
 * 这条测试只负责拍全、拍对尺寸；偏差判断写在 docs/acceptance/TON-1650-desktop-v7.md。
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class DesktopV7ScreenshotTest {
    private data class Screen(val name: String, val width: Int, val height: Int)

    private val screens = listOf(Screen("1920x1080", 1920, 1080), Screen("2560x1440", 2560, 1440))
    private val scale = 1.5f
    private val out: Path = Path.of("build/screens/v7-desktop")
    private val refs: Path = Path.of(System.getProperty("coach.v7.refs") ?: "D:/agent/design/ui-v7/shots")

    /** 每个桌面界面对照设计包里最接近的那一屏：周览对首页三处，读书对阅读器，做题对做题后的一串卡。 */
    private val reference = linkedMapOf(
        "bookshelf" to "inner-week", "today" to "inner-week", "course" to "inner-week",
        "reader" to "inner-read", "quiz" to "inner-quiz", "explain" to "inner-quiz",
        "summary" to "inner-quiz", "recap" to "inner-quiz", "reviews" to "inner-quiz", "review-question" to "inner-quiz",
        // 第 8 刀：阅读器里的「选段提问」弹窗和「讲解」面板，都对读书那一屏。
        "ask-pick" to "inner-read", "ask-unavailable" to "inner-read", "ask-too-long" to "inner-read",
        "explain-pairing" to "inner-read", "explain-consent" to "inner-read", "explain-answer" to "inner-read",
        "explain-source" to "inner-read", "explain-failed" to "inner-read",
        // 第 9 刀：课程里的本地练习——入口、作答、保存冲突、读取确认、交卷确认、结果、冻结题源、历次结果，都对做题那一屏。
        "exam-entry" to "inner-quiz", "exam-attempt" to "inner-quiz", "exam-conflict" to "inner-quiz", "exam-reload" to "inner-quiz",
        "exam-submit" to "inner-quiz", "exam-result" to "inner-quiz", "exam-source" to "inner-quiz", "exam-history" to "inner-quiz",
        // 第 10 刀：书架顶栏的「设置」弹窗；课程窗口的「导出复习提纲」——初始、按知识点选、导出成功、导出失败。都对周览那一屏。
        "settings" to "inner-week", "notes-export" to "inner-week", "notes-export-point" to "inner-week",
        "notes-export-done" to "inner-week", "notes-export-failed" to "inner-week",
        // 第 13 刀：简答作答——写到一半、离开再回来接着写、对照原文并自评。都对做题那一屏。
        "short-answer" to "inner-quiz", "short-answer-restored" to "inner-quiz", "short-answer-revealed" to "inner-quiz",
    )

    @Test fun everyDesktopSceneInLightAndDarkAtWindowsScaling() {
        val previous = System.getProperty("coach.windows.theme")
        val shots = mutableListOf<Shot>()
        try {
            for (screen in screens) for (dark in listOf(false, true)) {
                System.setProperty("coach.windows.theme", if (dark) "dark" else "light")
                shots += walk(screen, dark)
                shots += explainWalk(screen, dark)
                shots += examWalk(screen, dark)
                shots += notesWalk(screen, dark)
                shots += shortAnswerWalk(screen, dark)
            }
        } finally {
            if (previous == null) System.clearProperty("coach.windows.theme") else System.setProperty("coach.windows.theme", previous)
        }
        assertEquals(reference.size * screens.size * 2, shots.size)
        assertEquals(reference.keys, shots.map { it.scene }.toSet())
        if (Files.isDirectory(refs)) {
            val composed = shots.count { compose(it) }
            assertEquals(shots.size, composed, "每张桌面截图都要有一张对照图")
            println("V7_DESKTOP compare=${out.resolve("compare").toAbsolutePath()} images=$composed")
        } else {
            println("V7_DESKTOP 参考图不在 $refs，这次只出桌面截图，不拼对照")
        }
    }

    private class Shot(val scene: String, val screen: String, val dark: Boolean, val file: Path)

    private fun walk(screen: Screen, dark: Boolean): List<Shot> {
        val root = Files.createTempDirectory("coach-v7-desktop-")
        val directory = root.resolve("library")
        val library = TextbookLibrary(directory)
        val consent = ExplainConsent(directory)
        val controller = TextbookController(library)
        // 先用控制器把书走到第六节之前：五节答过、其中第三节答错进了复习，第六节等着做。
        runBlocking {
            controller.importBook(knowledgeRecapTextbook(root))
            controller.chooseLearningPath(LearningPath.QUESTION_FIRST); controller.enableLearning()
            for (index in 0 until 5) {
                controller.lesson(index)
                val book = controller.state.value.book!!
                val practice = StudyCoachCourse.practice(book, book.lessons[index], QuestionMode.CHOICE)!!
                val pick = if (index == 2) practice.options.first { it != practice.answer } else practice.answer
                controller.answer(PracticeAnswer(pick, revealed = true))
            }
            controller.lesson(5)
            controller.back()
        }
        val book = controller.state.value.book!!
        consent.allow(book.id)
        val gate = AnswerExplain(consent, load = {}, ready = { true }, paired = { true },
            explain = { chosen, _ ->
                ExplainResult.Done(Explanation(
                    "这一段只讲一件事：核糖体是细胞里合成蛋白质的地方。题目问「在哪儿做」，答案就落在「场所」两个字上；" +
                        "后半句说它常附着在内质网上，是在告诉你去哪儿找它。【c1】",
                    listOf(Citation("c1", chosen.id, chosen.page)), 12))
            })
        val shots = mutableListOf<Shot>()
        runSkikoComposeUiTest(size = Size(screen.width.toFloat(), screen.height.toFloat()), density = Density(scale)) {
            setContent {
                CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) {
                    WindowsTextbookHost(controller, answerExplain = gate) { null }
                }
            }
            fun nav(label: String) = onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText(label))
            fun shot(scene: String) { shots += capture(scene, screen, dark) }

            ready(controller) { it.screen == BookScreen.HOME }
            onNodeWithTag("library-title").assertExists()
            shot("bookshelf")

            // 设置弹窗（第 10 刀）：弹窗在另一层，整幅画面一起拍。
            onNodeWithTag("open-settings").performClick(); waitForIdle()
            onNodeWithTag("settings-library-directory").assertTextEquals(directory.toString())
            shots += capture("settings", screen, dark, whole = true)
            onNodeWithTag("close-settings").performClick(); waitForIdle()
            onNodeWithTag("settings-library-directory").assertDoesNotExist()

            nav("今天").performClick(); waitForIdle()
            onNodeWithTag("host-today-book").assertExists()
            shot("today")

            nav("课程").performClick(); waitForIdle()
            onNodeWithTag("host-course-book").assertExists()
            shot("course")

            nav("今天").performClick(); waitForIdle()
            onNodeWithTag("host-today-read").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.prompt == null }
            onNodeWithTag("textbook-reader").assertExists()
            // 阅读器（D5、D6）：按钮不夹英文；Markdown 标题的井号隐去，但原文字符一个不少，出处偏移照旧对得上。
            onAllNodesWithText("learning", substring = true, ignoreCase = true).assertCountEquals(0)
            val readerText = onNodeWithTag("reader-text").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
            val hashes = readerText.text.indices.filter { readerText.text[it] == '#' }
            assertTrue(hashes.isNotEmpty(), "这本示例书的正文带 Markdown 标题")
            assertTrue(hashes.all { i -> readerText.spanStyles.any {
                it.item.color == androidx.compose.ui.graphics.Color.Transparent && i in it.start until it.end } }, "标题井号要隐去")
            shot("reader")
            // 标题字号走 V7 typography：中号时等于 section token，Aa 选小、大时跟着缩放。
            fun headingSize(): Float = onNodeWithTag("reader-text").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().spanStyles
                .filter { it.item.fontFamily != null && it.item.color != androidx.compose.ui.graphics.Color.Transparent }
                .also { assertTrue(it.isNotEmpty(), "正文里有标题") }.maxOf { it.item.fontSize.value }
            fun typeSize(label: String) { onNodeWithTag("v7-type-menu").performClick(); waitForIdle(); onNodeWithTag("v7-type-$label").performClick(); waitForIdle() }
            // 正文（第 11 刀）：字号、行距取 V7 内屏 token（18 × 1.85），和标题一起随 Aa 缩放；原文字符和标记偏移不因缩放改变。
            fun bodyStyle(): androidx.compose.ui.text.TextStyle {
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                onNodeWithTag("reader-text").fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                return layouts.single().layoutInput.style
            }
            fun marks() = onNodeWithTag("reader-text").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().let { t ->
                    t.text to t.spanStyles.map { Triple(it.start, it.end, it.item.color) } }
            fun assertBody(scale: Float, label: String) {
                val style = bodyStyle()
                assertEquals(coach.design.tokens.CoachV7.Size.body.value * scale, style.fontSize.value, 0.01f, "$label：正文字号")
                assertEquals(coach.design.tokens.CoachV7.Size.body.value * coach.design.tokens.CoachV7.LineHeight.body * scale, style.lineHeight.value, 0.01f, "$label：正文行距")
            }
            val normalHeading = headingSize()
            val normalMarks = marks()
            assertEquals(coach.design.tokens.ReadingSizes.section.value, normalHeading, 0.01f, "二级标题用 section 字号")
            assertBody(1f, "Aa 中")
            typeSize("大"); assertEquals(normalHeading * 1.15f, headingSize(), 0.01f, "Aa 大：标题跟着放大")
            assertBody(1.15f, "Aa 大"); assertEquals(normalMarks, marks(), "Aa 大：原文和偏移不变")
            typeSize("小"); assertEquals(normalHeading * .9f, headingSize(), 0.01f, "Aa 小：标题跟着缩小")
            assertBody(.9f, "Aa 小"); assertEquals(normalMarks, marks(), "Aa 小：原文和偏移不变")
            typeSize("中"); assertEquals(normalHeading, headingSize(), 0.01f)
            assertBody(1f, "回到 Aa 中"); assertEquals(normalMarks, marks())

            // 选段提问（第 8 刀）：在真阅读器里点开。宿主没接问答方案商，发出去一定落到「暂时不能回答」，所以每次拍到的都一样。
            onNodeWithTag("ask-book-passage").performClick(); waitForIdle()
            onNodeWithTag("ask-paragraph-0").assertExists()
            shot("ask-pick")
            onNodeWithTag("ask-paragraph-0").performClick(); waitForIdle()
            onNodeWithTag("book-selected-passage").assertExists()
            onNodeWithTag("book-passage-question").performTextInput("这里说的「基本单位」是什么意思？")
            onNodeWithTag("book-passage-send").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-unavailable").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            shot("ask-unavailable")
            onNodeWithTag("book-passage-question").performTextReplacement("问".repeat(BookPassageQuestion.MAX_QUESTION_LENGTH + 1))
            onNodeWithTag("book-passage-length").assertTextContains("已超过", substring = true)
            onNodeWithTag("book-passage-send").assertIsNotEnabled()
            waitForIdle()
            shot("ask-too-long")
            onNodeWithTag("book-passage-close").performClick(); waitForIdle()
            onNodeWithTag("book-passage-ask").assertDoesNotExist()

            nav("课程").performClick(); waitForIdle()
            onNodeWithTag("host-course-learn").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING && it.book?.lessonIndex == 5 && it.questionsOpen }
            val current = controller.state.value.book!!
            val practice = StudyCoachCourse.practice(current, current.lessons[5], QuestionMode.CHOICE)!!
            onNodeWithTag("choice-0").assertExists()
            shot("quiz")

            onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
            ready(controller) { it.answer.revealed }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-lesson-explanation").performScrollTo()
            waitForIdle()
            shot("explain")

            onNodeWithTag("book-summary-card").performScrollTo(); waitForIdle()
            shot("summary")

            ready(controller) { it.ladder.recapOpen }
            onNodeWithTag("book-knowledge-recap").performScrollTo(); waitForIdle()
            shot("recap")

            nav("今天").performClick(); waitForIdle()
            onNodeWithTag("host-today-review").performClick()
            ready(controller) { it.screen == BookScreen.REVIEWS && it.reviews.isNotEmpty() }
            onNodeWithTag("local-review-item-0").assertExists()
            shot("reviews")

            onNodeWithTag("local-review-item-0").performClick()
            ready(controller) { it.screen == BookScreen.REVIEWING }
            onNodeWithTag("local-review-question").assertExists()
            shot("review-question")
        }
        return shots
    }

    /**
     * 讲解面板在阅读器里用的是本机真实的配对状态，拍出来会随机器变。这里单独把面板放进同一套 V7 主题和纸面，
     * 用 [FakeMac] 顶替 Mac：配对码 000000 配不上，别的码配上；第一段讲解给一条带出处的回答，之后一律拒绝。
     * 不联网，不写真实凭据；逐书同意存在临时目录里，每一遍都会弹一次。
     */
    private fun explainWalk(screen: Screen, dark: Boolean): List<Shot> {
        val root = Files.createTempDirectory("coach-v7-explain-")
        val directory = root.resolve("library")
        val controller = TextbookController(TextbookLibrary(directory))
        runBlocking { controller.importBook(knowledgeRecapTextbook(root)) }
        val book = controller.state.value.book!!
        val blocks = StudyCoachCourse.blocks(book.id, book.pages).filterNot { it.heading }
        val page = blocks.groupBy { it.page }.maxBy { it.value.size }.key
        val consent = ExplainConsent(directory)
        val mac = FakeMac(root.resolve("home"))
        val shots = mutableListOf<Shot>()
        runSkikoComposeUiTest(size = Size(screen.width.toFloat(), screen.height.toFloat()), density = Density(scale)) {
            setContent {
                com.coach.ui.CoachTheme(dark) {
                    val colors = com.coach.ui.theme.CoachTheme.colors
                    Surface(Modifier.fillMaxSize().testTag("windows-host"), color = colors.bgCanvas) {
                        com.coach.ui.v7.Desk(crumb = book.title, showNavigation = false, hasMargin = false, status = "第 $page 页 · 讲解") {
                            Box(Modifier.fillMaxSize().padding(20.dp).background(colors.surface1)) {
                                BookExplainPanel(book, page, consent, mac)
                            }
                        }
                    }
                }
            }
            fun shot(scene: String) { shots += capture(scene, screen, dark) }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-pairing").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("explain-code").performTextInput("000000")
            onNodeWithTag("explain-pair").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-pair-error").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            shot("explain-pairing")

            onNodeWithTag("explain-code").performTextInput("246810")
            onNodeWithTag("explain-pair").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-pairing").fetchSemanticsNodes().isEmpty() }
            onAllNodesWithTag("explain-button")[0].performClick(); waitForIdle()
            onNodeWithTag("explain-consent").assertExists()
            shot("explain-consent")

            onNodeWithTag("explain-consent-allow").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-answer").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            shot("explain-answer")

            onNodeWithTag("explain-citation").performClick(); waitForIdle()
            onNodeWithTag("explain-source-text").assertTextEquals(blocks.first { it.page == page }.text)
            shot("explain-source")
            onNodeWithTag("explain-source-close").performClick(); waitForIdle()
            onNodeWithTag("explain-source-card").assertDoesNotExist()

            val buttons = onAllNodesWithTag("explain-button")
            buttons[buttons.fetchSemanticsNodes().size - 1].performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-failed").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("explain-failed").performScrollTo(); waitForIdle()
            shot("explain-failed")
        }
        assertEquals(2, mac.explained)
        return shots
    }

    /**
     * 课程页底部的「本地练习与记录」：一份自编题卷（一道选择、一道论述），出处指向样例书第一节。
     * 计时打开、30 分钟；另一窗口抢先写入一次，制造「读取已保存作答」冲突，再读回、交卷、看题源和历次结果。
     */
    private fun examWalk(screen: Screen, dark: Boolean): List<Shot> {
        val root = Files.createTempDirectory("coach-v7-exam-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(knowledgeRecapTextbook(root))
        val lesson = book.lessons.first()
        val source = json("docId" to book.id, "lessonId" to lesson.id, "pdfPage" to 1, "quote" to lesson.blocks.first { !it.heading }.text)
        val store = LocalExamStore(library)
        store.register(LocalExamPaper(json("id" to "v7-authored", "bookId" to book.id, "title" to "细胞结构小测",
            "kind" to "paper", "origin" to "AUTHORED", "key_state" to "UNREVIEWED",
            "sections" to JsonArray(listOf(json("title" to "细胞", "items" to JsonArray(listOf(
                json("id" to "choice", "type" to "mcq", "stem" to "生物体结构和功能的基本单位是什么？", "marks" to 1,
                    "options" to JsonArray(listOf("细胞", "细胞膜", "染色体").map(::JsonPrimitive)), "answer" to 0, "source" to source),
                json("id" to "essay", "type" to "essay", "stem" to "用自己的话说说，为什么把细胞叫作「基本单位」。", "marks" to 3, "source" to source),
            ))))))))
        val controller = TextbookController(library)
        val shots = mutableListOf<Shot>()
        runSkikoComposeUiTest(size = Size(screen.width.toFloat(), screen.height.toFloat()), density = Density(scale)) {
            setContent {
                CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) {
                    WindowsTextbookHost(controller) { null }
                }
            }
            fun nav(label: String) = onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText(label))
            fun shot(scene: String) { waitForIdle(); shots += capture(scene, screen, dark) }
            fun has(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

            ready(controller) { it.screen == BookScreen.HOME }
            nav("课程").performClick()
            waitUntil(timeoutMillis = 20_000) { has("local-paper-start-0") }
            onNodeWithTag("local-timed-0").performScrollTo().performClick()
            onNodeWithTag("local-paper-start-0").performScrollTo()
            shot("exam-entry")

            onNodeWithTag("local-paper-start-0").performClick()
            waitUntil(timeoutMillis = 20_000) { has("local-exam-attempt") }
            val id = store.active(book.id)!!.str("mockId")
            onNodeWithTag("local-choice-choice-1").performScrollTo().performClick()
            onNodeWithTag("local-text-essay").performScrollTo().performTextReplacement("细胞里装着完成生命活动要用的结构。")
            waitUntil(timeoutMillis = 20_000) { store.get(book.id, id)["answers"]!!.jsonObject["essay"]?.jsonObject?.str("text") == "细胞里装着完成生命活动要用的结构。" }
            onNodeWithTag("local-exam-back").performScrollTo()
            shot("exam-attempt")

            // 另一个窗口先存了一版（序号远大于本页），本页下一次保存就会被拒，出现「读取已保存作答」。
            store.save(book.id, id, json("choice" to json("choice" to 0), "essay" to json("text" to "细胞能独立完成生命活动。")), 100)
            onNodeWithTag("local-choice-choice-2").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { has("local-reload-saved") }
            onNodeWithTag("local-reload-saved").performScrollTo()
            shot("exam-conflict")

            onNodeWithTag("local-reload-saved").performClick()
            waitUntil(timeoutMillis = 20_000) { has("local-confirm-reload") }
            shot("exam-reload")

            onNodeWithTag("local-confirm-reload").performClick()
            waitUntil(timeoutMillis = 20_000) { !has("local-reload-saved") }
            onNodeWithTag("local-submit").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { has("local-confirm-submit") }
            shot("exam-submit")

            onNodeWithTag("local-confirm-submit").performClick()
            waitUntil(timeoutMillis = 20_000) { has("local-exam-result") }
            onNodeWithText("总分待评").assertExists()
            shot("exam-result")

            onNodeWithTag("local-source-essay").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { has("local-frozen-source") }
            shot("exam-source")

            onNodeWithTag("local-source-back").performClick()
            waitUntil(timeoutMillis = 20_000) { has("local-exam-back") }
            onNodeWithTag("local-exam-back").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { has("local-history-0") }
            onNodeWithTag("local-history-0").performScrollTo()
            shot("exam-history")
        }
        return shots
    }

    /**
     * 导出复习提纲是课程窗口里另开的一扇窗，测试里开不了真窗口：把窗口里的面板放在同一张桌面上，大小与窗口一致（600×610）。
     * 周次、知识点是自造的；「成功」「失败」两种状态直接写进状态，不真的导出，不碰磁盘以外的东西。
     */
    private fun notesWalk(screen: Screen, dark: Boolean): List<Shot> {
        val points = listOf(WeekPoint("kp-cell", "细胞是基本单位", setOf(1, 2)), WeekPoint("kp-membrane", "细胞膜控制物质进出", setOf(2)),
            WeekPoint("kp-nucleus", "细胞核里有遗传信息", setOf(3)))
        val state = NotesExportState(listOf(1, 2, 3), points, 1, null)
        val shots = mutableListOf<Shot>()
        runSkikoComposeUiTest(size = Size(screen.width.toFloat(), screen.height.toFloat()), density = Density(scale)) {
            setContent {
                com.coach.ui.CoachTheme(dark) {
                    val colors = com.coach.ui.theme.CoachTheme.colors
                    Surface(Modifier.fillMaxSize().testTag("windows-host"), color = colors.bgCanvas) {
                        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Box(Modifier.size(600.dp, 610.dp).border(1.dp, colors.borderSubtle)) {
                                NotesExportPanel(state, onPickDirectory = { state.directory = Path.of("D:/学习/复习提纲") },
                                    onExport = {}, onClose = {}, onOpen = {})
                            }
                        }
                    }
                }
            }
            fun shot(scene: String) { waitForIdle(); shots += capture(scene, screen, dark, whole = true) }
            onNodeWithText("尚未选择保存位置").assertExists()
            shot("notes-export")

            onNodeWithText("按知识点", substring = true).performClick(); waitForIdle()
            onNodeWithText("细胞是基本单位", substring = true).performClick(); waitForIdle()
            onNodeWithText("细胞膜控制物质进出").assertExists()
            shot("notes-export-point")
            onNodeWithText("细胞膜控制物质进出").performClick(); waitForIdle()

            onNodeWithText("选择本机文件夹…").performClick(); waitForIdle()
            state.saved = Path.of("D:/学习/复习提纲/coach-notes-1")
            state.status = "已导出：5 条待练错题，账本序号 128。"
            shot("notes-export-done")

            state.saved = null; state.failed = true
            state.status = "目标文件夹已存在同名导出，未覆盖。请换一个文件夹后重试。"
            shot("notes-export-failed")
        }
        return shots
    }

    /**
     * 简答作答（第 13 刀）：做题学、出简答，第一节写一半；去「今天」再回到课程，草稿从本地读回；
     * 对照原文自检后选「我已理解」。教材同样是自造的六节短文，不联网。
     */
    private fun shortAnswerWalk(screen: Screen, dark: Boolean): List<Shot> {
        val root = Files.createTempDirectory("coach-v7-short-")
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        runBlocking {
            controller.importBook(knowledgeRecapTextbook(root))
            controller.chooseLearningPath(LearningPath.QUESTION_FIRST)
            controller.mode(QuestionMode.SHORT_ANSWER); controller.enableLearning()
            controller.back()
        }
        val draft = "细胞是生物体结构和功能的基本单位：身体由细胞搭起来，生命活动也在细胞里完成。"
        val shots = mutableListOf<Shot>()
        runSkikoComposeUiTest(size = Size(screen.width.toFloat(), screen.height.toFloat()), density = Density(scale)) {
            setContent {
                CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) {
                    WindowsTextbookHost(controller) { null }
                }
            }
            fun nav(label: String) = onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText(label))
            fun shot(scene: String) { waitForIdle(); shots += capture(scene, screen, dark) }
            fun learn() {
                nav("课程").performClick(); waitForIdle()
                onNodeWithTag("host-course-learn").performClick()
                ready(controller) { it.screen == BookScreen.LEARNING && it.book?.questionMode == QuestionMode.SHORT_ANSWER }
            }

            ready(controller) { it.screen == BookScreen.HOME }
            learn()
            onNodeWithTag("short-answer").performScrollTo().performTextInput(draft)
            ready(controller) { it.answer.text == draft }
            onNodeWithTag("reveal-reference").assertIsEnabled()
            // 字段名在框上方，不压在描边上。
            val label = onNodeWithText("写下你的理解").fetchSemanticsNode().boundsInRoot
            val field = onNodeWithTag("short-answer").fetchSemanticsNode().boundsInRoot
            assertTrue(label.bottom <= field.top, "字段名要在作答框上方：$label / $field")
            shot("short-answer")

            nav("今天").performClick(); waitForIdle()
            onNodeWithTag("host-today-book").assertExists()
            learn()
            onNodeWithTag("short-answer").performScrollTo().assertTextContains(draft)
            assertFalse(controller.state.value.answer.revealed)
            shot("short-answer-restored")

            onNodeWithTag("reveal-reference").performScrollTo().performClick()
            ready(controller) { it.answer.revealed && it.answer.text == draft }
            onNodeWithText("我已理解").performScrollTo().performClick()
            ready(controller) { it.answer.understood == true }
            onNodeWithText("我已理解").performScrollTo()
            shot("short-answer-revealed")
        }
        return shots
    }

    private class FakeMac(home: Path) : BookExplainer(DesktopConnectionSettings(home = home, environment = { null })) {
        var explained = 0
        override suspend fun load() { defaultAddress = "mac.local"; loaded = true }
        override suspend fun pair(address: String, code: String): Boolean {
            if (code == "000000") return false
            identity = DesktopIdentity("http://mac.test:18791", PairedCredentials("test-device", "test-token"))
            return true
        }
        override suspend fun explain(chosen: SourceBlock, blocks: List<SourceBlock>, requestId: String): ExplainResult =
            if (explained++ == 0) ExplainResult.Done(Explanation(
                "这一段讲的是细胞在生物体里的位置：结构从它搭起，功能也由它完成，所以叫「基本单位」。后半句提到细胞核，是给下一节留的线头。【c1】",
                listOf(Citation("c1", chosen.id, chosen.page)), 12))
            else ExplainResult.Refused("暂时没连上 Mac，可继续阅读或稍后重试。")
    }

    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.capture(scene: String, screen: Screen, dark: Boolean, whole: Boolean = false): Shot {
        val theme = if (dark) "dark" else "light"
        val file = out.resolve(screen.name).resolve(theme).resolve("$scene.png")
        Files.createDirectories(file.parent)
        // 弹窗、下拉菜单在另一层，只拍 windows-host 会漏掉；whole 拍整个场景。
        val image = (if (whole) (this as SkikoComposeUiTest).captureToImage() else onNodeWithTag("windows-host").captureToImage()).toAwtImage()
        assertTrue(ImageIO.write(image, "png", file.toFile()))
        val saved = assertNotNull(ImageIO.read(file.toFile()))
        assertEquals(screen.width, saved.width, "$scene 宽度要等于 ${screen.name} 的物理像素")
        assertEquals(screen.height, saved.height, "$scene 高度要等于 ${screen.name} 的物理像素")
        // 角落两处底色记下来给偏差清单对 token：右下是正文区底，左上是顶栏。
        println("V7_DESKTOP ${screen.name} $theme $scene canvas=${hex(saved.getRGB(saved.width - 4, saved.height - 4))} " +
            "header=${hex(saved.getRGB(4, 4))}")
        return Shot(scene, screen.name, dark, file)
    }

    /** 左：桌面原图不缩放；右：参考图等比缩到同高。上方一行写清两边是什么，免得对照时认错。 */
    private fun compose(shot: Shot): Boolean {
        val theme = if (shot.dark) "dark" else "light"
        val name = "${reference.getValue(shot.scene)}-$theme.png"
        val refFile = refs.resolve(name).toFile()
        if (!refFile.isFile) { println("V7_DESKTOP 缺参考图 $name"); return false }
        val left = ImageIO.read(shot.file.toFile())
        val ref = ImageIO.read(refFile)
        val h = left.height
        val w = (ref.width.toDouble() * h / ref.height).toInt()
        val bar = (40 * scale).toInt()
        val gap = (16 * scale).toInt()
        val canvas = BufferedImage(left.width + gap + w, h + bar, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color(0x808080); g.fillRect(0, 0, canvas.width, canvas.height)
            g.drawImage(left, 0, bar, null)
            g.drawImage(ref, left.width + gap, bar, w, h, null)
            g.color = Color.WHITE
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, (16 * scale).toInt())
            val y = (bar * 0.68).toInt()
            g.drawString("Windows ${shot.scene} · $theme · ${shot.screen} @150% (1:1)", gap, y)
            g.drawString("V7 ${name.removeSuffix(".png")} · Fold8 inner ${ref.width}x${ref.height} → height $h", left.width + gap * 2, y)
        } finally {
            g.dispose()
        }
        val target = out.resolve("compare").resolve("${shot.screen}-$theme-${shot.scene}.png")
        Files.createDirectories(target.parent)
        return ImageIO.write(canvas, "png", target.toFile())
    }

    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
}
