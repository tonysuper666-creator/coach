package com.coach.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import coach.design.tokens.CoachV7
import com.coach.desktop.books.BookPrompt
import com.coach.desktop.books.BookScreen
import com.coach.desktop.books.LibraryUi
import com.coach.desktop.books.ShellErrorKind
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.WorkspaceBusyBar
import com.coach.desktop.books.classifyShellError
import com.coach.desktop.books.shellErrorMessage
import com.coach.desktop.books.exam.LocalExamController
import com.coach.desktop.books.exam.LocalExamUi
import com.coach.ui.CoachTheme
import com.coach.ui.theme.CoachColors
import com.coach.ui.v7.LocalV7ReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class WindowShellPolishTest {
    private fun sampleChineseBook(root: Path): Path {
        Files.createDirectories(root)
        val file = root.resolve("普通生物学导论.md")
        Files.writeString(
            file,
            """
            # 普通生物学导论

            ## 第一节 细胞的结构

            细胞是生物体结构和功能的基本单位。细胞膜把细胞与外界环境分隔开来，同时控制物质有选择地进出细胞。
            """.trimIndent(),
        )
        return file
    }

    @Suppress("UNCHECKED_CAST")
    private fun setControllerError(controller: TextbookController, error: String?) {
        val field = TextbookController::class.java.getDeclaredField("mutable")
        field.isAccessible = true
        val flow = field.get(controller) as MutableStateFlow<LibraryUi>
        flow.update { it.copy(error = error, busy = false) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setExamError(controller: LocalExamController, error: String?) {
        val field = LocalExamController::class.java.getDeclaredField("mutable")
        field.isAccessible = true
        val flow = field.get(controller) as MutableStateFlow<LocalExamUi>
        flow.update { it.copy(book = null, error = error, busy = false) }
    }

    private fun textColorOf(node: SemanticsNode): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single().layoutInput.style.color
    }

    private fun colorsClose(a: Color, b: Color, tolerance: Float = 0.02f): Boolean =
        abs(a.red - b.red) <= tolerance &&
            abs(a.green - b.green) <= tolerance &&
            abs(a.blue - b.blue) <= tolerance

    @Test
    fun workspaceBusyBarOverlaysWithoutMovingContentAndRespectsReducedMotion() {
        val previousTheme = System.getProperty("coach.windows.theme")
        val previousReduced = System.getProperty("coach.reducedMotion")
        System.clearProperty("coach.windows.theme")
        System.setProperty("coach.reducedMotion", "false")
        try {
            val outDir = Path.of("build/screens/ton1734/after")
            Files.createDirectories(outDir)
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                val root = Files.createTempDirectory("coach-ton1734-busy-$theme-")
                val library = TextbookLibrary(root.resolve("library"))
                val book = library.importBook(sampleChineseBook(root))
                val controller = TextbookController(library)
                runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(1.5f)) {
                    setContent {
                        CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) {
                            WindowsTextbookHost(controller) { null }
                        }
                    }
                    waitUntil(timeoutMillis = 20_000) {
                        !controller.state.value.busy && controller.state.value.book?.id == book.id &&
                            onAllNodesWithTag("shelf-sheet").fetchSemanticsNodes().isNotEmpty()
                    }
                    waitForIdle()
                    val beforeBounds = onNodeWithTag("shelf-sheet").fetchSemanticsNode().boundsInRoot
                    onAllNodesWithTag("workspace-busy-bar").assertCountEquals(0)

                    mainClock.autoAdvance = false
                    DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { blocker ->
                        blocker.createStatement().use { it.execute("BEGIN EXCLUSIVE") }
                        try {
                            val job = CoroutineScope(Dispatchers.Default).launch { controller.select(book.id) }
                            waitUntil(timeoutMillis = 5_000) {
                                mainClock.advanceTimeBy(16)
                                controller.state.value.busy &&
                                    onAllNodesWithTag("workspace-busy-bar").fetchSemanticsNodes().isNotEmpty()
                            }
                            mainClock.advanceTimeBy(360)
                            val barNode = onNodeWithTag("workspace-busy-bar").fetchSemanticsNode()
                            assertTrue(barNode.boundsInRoot.height in 1f..4f, "Busy hairline height must stay ~2dp (${barNode.boundsInRoot.height})")
                            val duringBounds = onNodeWithTag("shelf-sheet").fetchSemanticsNode().boundsInRoot
                            assertEquals(beforeBounds, duringBounds, "Busy hairline must not push shelf-sheet down")
                            val loadingImg = onNodeWithTag("windows-host").captureToImage().toAwtImage()
                            assertTrue(ImageIO.write(loadingImg, "png", outDir.resolve("loading-$theme.png").toFile()))
                            blocker.createStatement().use { it.execute("ROLLBACK") }
                            runBlocking { job.join() }
                        } finally {
                            runCatching { blocker.createStatement().use { it.execute("ROLLBACK") } }
                        }
                    }
                    mainClock.autoAdvance = true
                    waitUntil(timeoutMillis = 5_000) {
                        !controller.state.value.busy &&
                            onAllNodesWithTag("workspace-busy-bar").fetchSemanticsNodes().isEmpty()
                    }
                }
            }

            // Direct reduced-motion vs animated pixel verification on WorkspaceBusyBar in both light and dark.
            for (dark in listOf(false, true)) {
                val expectedPine = if (dark) CoachV7.Dark.pine else CoachV7.Light.pine
                val expectedTrack = if (dark) CoachV7.Dark.line else CoachV7.Light.line
                for (reduced in listOf(false, true)) {
                    runSkikoComposeUiTest(size = Size(600f, 20f), density = Density(1f)) {
                        mainClock.autoAdvance = false
                        setContent {
                            CoachTheme(dark) {
                                CompositionLocalProvider(LocalV7ReducedMotion provides reduced) {
                                    Box(Modifier.fillMaxSize().background(com.coach.ui.theme.CoachTheme.colors.bgCanvas)) {
                                        WorkspaceBusyBar(Modifier.testTag("busy-bar"))
                                    }
                                }
                            }
                        }
                        mainClock.advanceTimeBy(32)
                        val desc = onNodeWithTag("busy-bar").fetchSemanticsNode()
                            .config.getOrNull(SemanticsProperties.StateDescription)
                        assertEquals(if (reduced) "static" else "animated", desc)
                        val frame0 = onNodeWithTag("busy-bar").captureToImage().toPixelMap()
                        mainClock.advanceTimeBy(450)
                        val frame1 = onNodeWithTag("busy-bar").captureToImage().toPixelMap()

                        var sawPine = false
                        var sawTrack = false
                        var diffPixels = 0
                        for (x in 0 until frame0.width) {
                            val p0 = frame0[x, 1]
                            val p1 = frame1[x, 1]
                            if (colorsClose(p0, expectedPine)) sawPine = true
                            if (colorsClose(p0, expectedTrack)) sawTrack = true
                            if (!colorsClose(p0, p1, tolerance = 0.01f)) diffPixels++
                        }
                        assertTrue(sawPine, "Busy bar must draw V7 pine token (dark=$dark reduced=$reduced)")
                        assertTrue(sawTrack, "Busy bar must draw V7 borderSubtle track token (dark=$dark reduced=$reduced)")
                        if (reduced) {
                            assertEquals(0, diffPixels, "Reduced motion busy bar must stay static (dark=$dark)")
                        } else {
                            assertTrue(diffPixels > 0, "Normal motion busy bar must animate over 450ms (dark=$dark)")
                        }
                    }
                }
            }
        } finally {
            if (previousTheme == null) System.clearProperty("coach.windows.theme")
            else System.setProperty("coach.windows.theme", previousTheme)
            if (previousReduced == null) System.clearProperty("coach.reducedMotion")
            else System.setProperty("coach.reducedMotion", previousReduced)
        }
    }

    @Test
    fun shellErrorsDistinguishRecoverableGreyWithRetryFromUserActionZhu() {
        // Pure classifier & plain-language copy checks
        assertEquals(ShellErrorKind.RECOVERABLE, classifyShellError("讲解服务暂时连不上，读书和做题照常。"))
        assertEquals(ShellErrorKind.RECOVERABLE, classifyShellError("暂时无法读取或保存教材，请确认文件完整、未加密，然后重试。"))
        assertEquals(ShellErrorKind.RECOVERABLE, classifyShellError("这本书还在后台识别文字，请等识别出部分内容后再开始学，或先翻看 PDF 原页。"))
        assertEquals(ShellErrorKind.USER_ACTION, classifyShellError("今天的讲解额度用完了，明天再来。"))
        assertEquals(ShellErrorKind.USER_ACTION, classifyShellError("这台电脑的连接已停用。"))
        assertEquals(ShellErrorKind.USER_ACTION, classifyShellError("请使用 PDF、电子书、Word、幻灯片、网页或文本教材。"))
        assertEquals(ShellErrorKind.NOTICE, classifyShellError("已取消导入，书架没有新增教材。"))
        assertTrue(
            shellErrorMessage("今天的讲解额度用完了，明天再来。", ShellErrorKind.USER_ACTION).contains("配对码"),
            "Quota error must tell the reader who to contact",
        )
        assertTrue(
            shellErrorMessage("这台电脑的连接已停用。", ShellErrorKind.USER_ACTION).contains("配对码"),
            "Revoked error must tell the reader next step and who to contact",
        )
        assertTrue(
            shellErrorMessage("SQLITE_BUSY", ShellErrorKind.RECOVERABLE).contains("再试"),
            "Non-Chinese internal messages must be replaced with plain Chinese",
        )

        val previousTheme = System.getProperty("coach.windows.theme")
        System.clearProperty("coach.windows.theme")
        try {
            val outDir = Path.of("build/screens/ton1734/after")
            Files.createDirectories(outDir)
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                val v7 = if (dark) CoachColors.dark() else CoachColors.light()
                val zhu = if (dark) CoachV7.Dark.zhu else CoachV7.Light.zhu
                val root = Files.createTempDirectory("coach-ton1734-err-$theme-")
                val library = TextbookLibrary(root.resolve("library"))
                val book = library.importBook(sampleChineseBook(root))
                val controller = TextbookController(library)
                lateinit var exams: LocalExamController

                runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(1.5f)) {
                    setContent {
                        exams = com.coach.desktop.books.exam.rememberLocalExamController(library)
                        CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) {
                            WindowsTextbookHost(controller, exams = exams) { null }
                        }
                    }
                    waitUntil(timeoutMillis = 20_000) {
                        !controller.state.value.busy && controller.state.value.book?.id == book.id
                    }

                    // 1. Recoverable disconnect error -> fgSecondary grey + quiet 再试一次 button -> clicking retry clears error
                    setControllerError(controller, "讲解服务暂时连不上，读书和做题照常。")
                    waitForIdle()
                    val offlineNode = onNodeWithTag("book-error").fetchSemanticsNode()
                    assertTrue(colorsClose(v7.fgSecondary, textColorOf(offlineNode)), "Recoverable error must use fgSecondary ($theme)")
                    onNodeWithTag("book-error-retry").assertIsDisplayed()
                    onNode(hasTestTag("book-error-retry") and hasText("再试一次")).assertExists()
                    val offlineImg = onNodeWithTag("windows-host").captureToImage().toAwtImage()
                    assertTrue(ImageIO.write(offlineImg, "png", outDir.resolve("offline-$theme.png").toFile()))

                    onNodeWithTag("book-error-retry").performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        !controller.state.value.busy && controller.state.value.error == null &&
                            onAllNodesWithTag("book-error").fetchSemanticsNodes().isEmpty()
                    }

                    // 2. Quota / user-action error -> zhu alert + who to contact + NO retry button
                    setControllerError(controller, "今天的讲解额度用完了，明天再来。")
                    waitForIdle()
                    val quotaNode = onNodeWithTag("book-error").fetchSemanticsNode()
                    assertTrue(colorsClose(zhu, textColorOf(quotaNode)), "Quota error must use V7 zhu ($theme)")
                    onNodeWithTag("book-error").assertTextContains("配对码", substring = true)
                    onNodeWithTag("book-error-retry").assertDoesNotExist()
                    val quotaImg = onNodeWithTag("windows-host").captureToImage().toAwtImage()
                    assertTrue(ImageIO.write(quotaImg, "png", outDir.resolve("quota-$theme.png").toFile()))
                    setControllerError(controller, null)
                    waitForIdle()

                    // 3. Real TextbookController error paths (no reflection): SQLITE_BUSY (recoverable) vs missing/unsupported files (user action)
                    DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { blocker ->
                        blocker.createStatement().use { it.execute("BEGIN EXCLUSIVE") }
                        try {
                            runBlocking { controller.select(book.id) }
                        } finally {
                            runCatching { blocker.createStatement().use { it.execute("ROLLBACK") } }
                        }
                    }
                    waitForIdle()
                    assertTrue(colorsClose(v7.fgSecondary, textColorOf(onNodeWithTag("book-error").fetchSemanticsNode())))
                    onNodeWithTag("book-error-retry").assertIsDisplayed()
                    onNodeWithTag("book-error-retry").performClick()
                    waitUntil(timeoutMillis = 10_000) { !controller.state.value.busy && controller.state.value.error == null }

                    val missing = root.resolve("missing.txt")
                    runBlocking { controller.importBook(missing) }
                    waitForIdle()
                    assertTrue(colorsClose(zhu, textColorOf(onNodeWithTag("book-error").fetchSemanticsNode())))
                    onNodeWithTag("book-error-retry").assertDoesNotExist()

                    val corruptPdf = root.resolve("corrupt.pdf").also { Files.writeString(it, "not a pdf") }
                    runBlocking { controller.importBook(corruptPdf) }
                    waitForIdle()
                    assertTrue(colorsClose(zhu, textColorOf(onNodeWithTag("book-error").fetchSemanticsNode())))
                    onNodeWithTag("book-error-retry").assertDoesNotExist()
                    setControllerError(controller, null)
                    waitForIdle()

                    // 4. WindowsTextbookHost course exam error slot (host-exam-error / host-exam-retry)
                    onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText("课程")).performClick()
                    waitUntil(timeoutMillis = 20_000) {
                        onAllNodesWithTag("course-exams-summary").fetchSemanticsNodes().isNotEmpty()
                    }
                    setExamError(exams, "作答还没保存，请稍后重试。当前内容仍在本页。")
                    waitForIdle()
                    assertTrue(colorsClose(v7.fgSecondary, textColorOf(onNodeWithTag("host-exam-error").fetchSemanticsNode())))
                    onNodeWithTag("host-exam-retry").assertIsDisplayed()
                    onNodeWithText("正在读取本书练习…").assertDoesNotExist()
                    onNodeWithTag("host-exam-retry").performClick()
                    waitUntil(timeoutMillis = 20_000) {
                        onAllNodesWithTag("course-exams-summary").fetchSemanticsNodes().isNotEmpty()
                    }

                    setExamError(exams, "今天的讲解额度用完了，明天再来。")
                    waitForIdle()
                    assertTrue(colorsClose(zhu, textColorOf(onNodeWithTag("host-exam-error").fetchSemanticsNode())))
                    onNodeWithTag("host-exam-retry").assertDoesNotExist()
                }
            }
        } finally {
            if (previousTheme == null) System.clearProperty("coach.windows.theme")
            else System.setProperty("coach.windows.theme", previousTheme)
        }
    }

    @Test
    fun recoverableImportFailureRetriesOriginalPickedFileWithoutRepicking() {
        val previousTheme = System.getProperty("coach.windows.theme")
        System.clearProperty("coach.windows.theme")
        val root = Files.createTempDirectory("coach-ton1734-import-retry-")
        try {
            val v7 = CoachColors.light()
            val zhu = CoachV7.Light.zhu
            val library = TextbookLibrary(root.resolve("library"))
            val existingFile = Files.createDirectories(root.resolve("existing")).resolve("已有教材.md")
            Files.writeString(
                existingFile,
                """
                # 已有教材

                ## 第一节 序言

                书架上已有的一本教材。
                """.trimIndent(),
            )
            val existingBook = library.importBook(existingFile)

            val pickedDir = Files.createDirectories(root.resolve("picked"))
            val pendingFile = pickedDir.resolve("待导入教材.md")
            Files.writeString(
                pendingFile,
                """
                # 待导入教材

                ## 第一节 新知识

                这里是等待重试后才能导入的新教材内容。
                """.trimIndent(),
            )
            val corruptPdf = pickedDir.resolve("corrupt.pdf").also { Files.writeString(it, "not a pdf") }

            val controller = TextbookController(library)
            var nextPick: Path? = pendingFile
            var chooseCalls = 0
            val released = mutableListOf<Path>()

            runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(1.5f)) {
                setContent {
                    CompositionLocalProvider(LocalSystemTheme provides SystemTheme.Light) {
                        WindowsTextbookHost(
                            controller,
                            releasePickedBook = { released.add(it) },
                            chooseBook = { chooseCalls++; nextPick },
                        )
                    }
                }
                waitUntil(timeoutMillis = 20_000) {
                    !controller.state.value.busy && controller.state.value.book?.id == existingBook.id
                }
                waitForIdle()

                // 1. Recoverable SQLITE_BUSY during picked import -> keep picked file and retry without re-picking
                DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { blocker ->
                    blocker.createStatement().use { it.execute("BEGIN EXCLUSIVE") }
                    try {
                        onNodeWithTag("upload-textbook").performClick()
                        waitUntil(timeoutMillis = 20_000) {
                            controller.state.value.error != null && controller.state.value.importing == null
                        }
                    } finally {
                        runCatching { blocker.createStatement().use { it.execute("ROLLBACK") } }
                    }
                }
                waitForIdle()

                assertEquals(1, chooseCalls, "Picker should open once for initial upload")
                assertEquals(1, library.catalog().size, "Blocked import must not add partial book")
                assertTrue(released.isEmpty(), "Retained picked file must stay alive until retry completes")
                assertTrue(
                    colorsClose(v7.fgSecondary, textColorOf(onNodeWithTag("book-error").fetchSemanticsNode())),
                    "Recoverable import error must use fgSecondary",
                )
                onNodeWithTag("book-error-retry").assertIsDisplayed()

                // Click "再试一次": must import pendingFile directly instead of controller.load() or reopening picker
                onNodeWithTag("book-error-retry").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    controller.state.value.error == null &&
                        !controller.state.value.busy &&
                        controller.state.value.importing == null &&
                        controller.state.value.catalog.size == 2 &&
                        controller.state.value.book?.title == "待导入教材" &&
                        controller.state.value.prompt == BookPrompt.IMPORTED
                }
                waitUntil(timeoutMillis = 5_000) { released.isNotEmpty() }
                waitForIdle()

                assertEquals(1, chooseCalls, "Retry must import original picked file without reopening picker")
                assertEquals(listOf(pendingFile), released, "Retained picked file must release once retry succeeds")
                assertTrue(library.catalog().any { it.title == "待导入教材" })
                assertTrue(onAllNodesWithTag("book-error").fetchSemanticsNodes().isEmpty())

                // 2. Non-recoverable import failure -> V7 zhu alert, no retry button, and immediate release
                runBlocking { controller.dismissPrompt() }
                waitUntil(timeoutMillis = 10_000) {
                    !controller.state.value.busy && controller.state.value.prompt == null
                }
                waitForIdle()

                nextPick = corruptPdf
                onNodeWithTag("upload-textbook").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    controller.state.value.error != null && controller.state.value.importing == null
                }
                waitUntil(timeoutMillis = 5_000) { released.size == 2 }
                waitForIdle()

                assertEquals(2, chooseCalls)
                assertTrue(
                    colorsClose(zhu, textColorOf(onNodeWithTag("book-error").fetchSemanticsNode())),
                    "Non-recoverable import error must use V7 zhu",
                )
                onNodeWithTag("book-error-retry").assertDoesNotExist()
                assertEquals(
                    listOf(pendingFile, corruptPdf),
                    released,
                    "Non-recoverable import error must release picked file immediately",
                )
            }
        } finally {
            if (previousTheme == null) System.clearProperty("coach.windows.theme")
            else System.setProperty("coach.windows.theme", previousTheme)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun hostFollowsRuntimeSystemThemeAndInAppPaperToggleWithoutRestart() {
        val previousTheme = System.getProperty("coach.windows.theme")
        System.clearProperty("coach.windows.theme")
        try {
            val root = Files.createTempDirectory("coach-ton1734-w6-offscreen-")
            val library = TextbookLibrary(root.resolve("library"))
            library.importBook(sampleChineseBook(root))
            val controller = TextbookController(library)
            var systemTheme by mutableStateOf(SystemTheme.Light)

            runSkikoComposeUiTest(size = Size(1600f, 1000f), density = Density(1.5f)) {
                setContent {
                    CompositionLocalProvider(LocalSystemTheme provides systemTheme) {
                        WindowsTextbookHost(controller) { null }
                    }
                }
                waitUntil(timeoutMillis = 20_000) {
                    !controller.state.value.busy &&
                        onAllNodesWithTag("shelf-sheet").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()

                fun deskColor(): Color = onNodeWithTag("windows-host").captureToImage().toPixelMap()[5, 5]
                fun sheetColor(): Color {
                    val sheet = onNodeWithTag("shelf-sheet")
                    return sheet.captureToImage().toPixelMap()[20, 20]
                }

                assertEquals(CoachV7.Light.desk, deskColor())
                assertEquals(CoachV7.Light.sheet, sheetColor())

                // Flip runtime system theme Light -> Dark without recreating composition
                systemTheme = SystemTheme.Dark
                waitForIdle()
                assertEquals(CoachV7.Dark.desk, deskColor())
                assertEquals(CoachV7.Dark.sheet, sheetColor())

                // Flip back Dark -> Light without recreating composition
                systemTheme = SystemTheme.Light
                waitForIdle()
                assertEquals(CoachV7.Light.desk, deskColor())
                assertEquals(CoachV7.Light.sheet, sheetColor())

                // Open reader and toggle in-app Aa paper setting (深 -> 跟随系统) and re-test system switch
                onNodeWithTag("home-read").performClick()
                waitUntil(timeoutMillis = 20_000) {
                    !controller.state.value.busy && controller.state.value.screen == BookScreen.READER
                }
                if (controller.state.value.prompt == BookPrompt.AFTER_READING) {
                    onNodeWithTag("read-only").performClick()
                    waitUntil(timeoutMillis = 10_000) { controller.state.value.prompt == null }
                }
                waitForIdle()

                onNodeWithTag("v7-type-menu").performClick()
                waitForIdle()
                onNodeWithTag("reading-paper-DARK").performClick()
                waitForIdle()
                assertEquals(CoachV7.Dark.desk, deskColor(), "In-app DARK paper must override light system immediately")

                onNodeWithTag("reading-paper-SYSTEM").performClick()
                waitForIdle()
                assertEquals(CoachV7.Light.desk, deskColor(), "Switching back to 跟随系统 must snap immediately to light system")
                onNodeWithTag("v7-type-menu").performMouseInput { click() }
                onNodeWithTag("reading-settings-panel").assertDoesNotExist()

                systemTheme = SystemTheme.Dark
                waitForIdle()
                assertEquals(CoachV7.Dark.desk, deskColor(), "With 跟随系统 active, runtime Dark switch must apply immediately")
            }
        } finally {
            if (previousTheme == null) System.clearProperty("coach.windows.theme")
            else System.setProperty("coach.windows.theme", previousTheme)
        }
    }

    @Test
    fun realWindowFollowsRuntimeSystemThemeToggleWithoutRestart() {
        assumeTrue("Real ComposeWindow theme switch evidence runs on Windows", System.getProperty("os.name").startsWith("Windows"))
        val previousTheme = System.getProperty("coach.windows.theme")
        System.clearProperty("coach.windows.theme")
        val root = Files.createTempDirectory("coach-ton1734-w6-window-")
        val outDir = Path.of("build/screens/ton1734/w6")
        Files.createDirectories(outDir)
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(sampleChineseBook(root))
        val controller = TextbookController(library)
        val systemTheme = mutableStateOf(SystemTheme.Light)
        var window: ComposeWindow? = null
        try {
            NativeWindowForeground.awaitOtherWindowsClosed(keep = null)
            EventQueue.invokeAndWait {
                window = ComposeWindow().apply {
                    title = "LearningCoach · W6 深浅色跟随真窗口"
                    setBounds(120, 80, 1280, 800)
                    setContent {
                        CompositionLocalProvider(LocalSystemTheme provides systemTheme.value) {
                            WindowsTextbookHost(controller) { null }
                        }
                    }
                    isVisible = true
                    toFront()
                }
            }
            val live = checkNotNull(window)
            val deadline = System.nanoTime() + 30_000_000_000L
            while (controller.state.value.busy || controller.state.value.book?.id != book.id) {
                check(System.nanoTime() < deadline) { "Timed out waiting for host book load in real window" }
                Thread.sleep(50)
            }
            Thread.sleep(400)

            fun captureAndDeskRgb(filename: String): Int {
                val file = outDir.resolve(filename).toFile()
                EventQueue.invokeAndWait {
                    live.renderImmediately()
                    VerifyHooks.capture(live, file)
                }
                val img: BufferedImage = ImageIO.read(file)
                return img.getRGB(5, 5)
            }

            fun assertRgbMatches(expected: Color, argb: Int, step: String) {
                val r = ((argb shr 16) and 0xFF) / 255f
                val g = ((argb shr 8) and 0xFF) / 255f
                val b = (argb and 0xFF) / 255f
                assertTrue(colorsClose(expected, Color(r, g, b), tolerance = 0.03f), "$step expected=$expected actual=#%06X".format(argb and 0xFFFFFF))
            }

            val lightRgb = captureAndDeskRgb("window-01-light.png")
            assertRgbMatches(CoachV7.Light.desk, lightRgb, "initial Light")

            EventQueue.invokeAndWait { systemTheme.value = SystemTheme.Dark }
            Thread.sleep(300)
            val darkRgb = captureAndDeskRgb("window-02-dark.png")
            assertRgbMatches(CoachV7.Dark.desk, darkRgb, "runtime Dark without restart")
            assertNotEquals(lightRgb and 0xFFFFFF, darkRgb and 0xFFFFFF)

            EventQueue.invokeAndWait { systemTheme.value = SystemTheme.Light }
            Thread.sleep(300)
            val lightBackRgb = captureAndDeskRgb("window-03-light-back.png")
            assertRgbMatches(CoachV7.Light.desk, lightBackRgb, "runtime Light back without restart")
        } finally {
            window?.let { w -> EventQueue.invokeAndWait { w.dispose() } }
            if (previousTheme == null) System.clearProperty("coach.windows.theme")
            else System.setProperty("coach.windows.theme", previousTheme)
            root.toFile().deleteRecursively()
        }
    }
}
