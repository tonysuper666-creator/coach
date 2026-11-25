package com.coach.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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

    private fun fixture(root: Path): Path {
        Files.createDirectories(root)
        return root.resolve("reader.md").also { path ->
            Files.writeString(path, """<!-- PDF 第 1 页 · 书第 1 页 -->
Light gives a plant the energy it needs to grow in a synthetic garden.
<!-- PDF 第 2 页 · 书第 2 页 -->
An isolated specimen uses the same process on the second page.
""")
        }
    }

    private fun ComposeUiTest.waitTag(tag: String) =
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.openCompanion() {
        if (onAllNodesWithTag("immersive-companion-drawer").fetchSemanticsNodes().isEmpty()) {
            onNodeWithTag("immersive-companion-toggle").performClick()
        }
        onNodeWithTag("companion-tab-explain").performClick()
        waitTag("single-book-companion")
    }

    @Test fun uploadReadSelectAndReopenAtTheCorrectOriginalPassage() {
        val root = Files.createTempDirectory("window-source-")
        val source = fixture(root)
        val library = TextbookLibrary(root.resolve("library"))
        val controller = TextbookController(library)
        val quote = "isolated specimen"
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent { WindowsTextbookHost(controller, learningSupport = UnconnectedBookLearningSupport) { source } }
            waitTag("upload-textbook")
            onNodeWithTag("upload-textbook").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.prompt == null }
            onNodeWithTag("reader-page-number").performClick()
            onNodeWithTag("page-jump").performTextReplacement("2")
            onNodeWithTag("page-jump").performKeyInput { pressKey(Key.Enter) }
            ready(controller) { it.page == 2 }
            val text = onNode(hasTestTag("reader-text") and hasText(quote, substring = true))
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val start = layout.layoutInput.text.text.indexOf(quote)
            val left = layout.getBoundingBox(start)
            val right = layout.getBoundingBox(start + quote.lastIndex)
            text.performMouseInput {
                moveTo(androidx.compose.ui.geometry.Offset(left.left + 0.1f, left.center.y))
                press()
                moveTo(androidx.compose.ui.geometry.Offset(right.right - 0.1f, right.center.y))
                release()
            }
            waitTag("selection-action-note")
            onNodeWithTag("selection-action-note").performClick()
            waitTag("note-input")
            onNodeWithTag("note-input").performTextInput("合成选段批注")
            onNodeWithTag("books-note-save").performScrollTo().performClick()
            val id = controller.state.value.book!!.id
            waitUntil(timeoutMillis = 20_000) { library.allNotes(id).size == 1 }
        }
        val reopened = TextbookController(TextbookLibrary(library.directory))
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent { WindowsTextbookHost(reopened, learningSupport = UnconnectedBookLearningSupport) { null } }
            ready(reopened) { it.book != null }
            if (reopened.state.value.screen == BookScreen.HOME) onNodeWithTag("home-read").performClick()
            ready(reopened) { it.screen == BookScreen.READER && it.page == 2 }
            val book = reopened.state.value.book!!
            val note = reopened.library.allNotes(book.id).single()
            assertEquals(quote, note.quote)
            assertEquals(2, note.pdfPage)
            assertEquals(quote, book.page(note.pdfPage)!!.text.substring(note.start, note.end))
            assertEquals("合成选段批注", note.note)
            onNode(hasTestTag("reader-text") and hasText(quote, substring = true)).assertIsDisplayed()
        }
    }

    @Test fun directlyLearningWithNoQuestionsCourseOrPairingFromHome() {
        val root = Files.createTempDirectory("window-unpaired-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(fixture(root))
        StudyModeStore.save(library, BookStudyPreference(book.id, BookStudyMode.BOOK_MAP))
        val controller = TextbookController(library)
        val local = BookExplainer(DesktopConnectionSettings(root, { null }, mac = false, property = { null }), library)
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent { WindowsTextbookHost(controller, bookExplainer = local, learningSupport = UnconnectedBookLearningSupport) { null } }
            ready(controller) { it.book != null }
            assertTrue(controller.state.value.collections.none { it.record.kind == CollectionKind.COURSE })
            onNodeWithTag("home-read").performClick()
            ready(controller) { it.screen == BookScreen.READER }
            openCompanion()
            waitTag("explain-fallback-page-0")
            assertNull(local.identity)
            onNodeWithTag("explain-fallback-page-0").assertTextEquals("第 1 页").performClick()
            onNode(hasTestTag("reader-text") and hasText("synthetic garden", substring = true)).assertIsDisplayed()
        }
    }

    @Test fun homeContinuesTheBookThatTheBookshelfAlreadyHas() {
        val root = Files.createTempDirectory("window-home-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(fixture(root))
        library.save(book.copy(readingPage = 2, promptSeen = true))
        repeat(2) {
            val controller = TextbookController(TextbookLibrary(library.directory))
            runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
                setContent { WindowsTextbookHost(controller) { null } }
                ready(controller) { it.book != null }
                onNodeWithTag("home-focus-title").assertTextEquals(book.title)
                onNodeWithTag("home-read").performClick()
                ready(controller) { it.screen == BookScreen.READER }
                assertEquals(book.id, controller.state.value.book!!.id)
                assertEquals(2, controller.state.value.page)
                assertEquals(listOf(book.id), library.catalog().map { it.id })
                onNodeWithTag("immersive-back").performClick()
                ready(controller) { it.screen == BookScreen.HOME }
            }
        }
    }

    @Test fun returnFollowsWhereTheBookWasOpenedFromAndEachPageHasOneBar() {
        val root = Files.createTempDirectory("window-back-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(fixture(root))
        library.upsertCollection(MaterialCollectionRecord("fixture-list", CollectionKind.BOOKLIST, "合成书单"))
        library.attachMaterial(CollectionMaterialRecord("fixture-list", book.id))
        val controller = TextbookController(library)
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent { WindowsTextbookHost(controller) { null } }
            fun nav(only: String) {
                for (tag in listOf("host-bar", "booklist-back", "immersive-back")) {
                    onAllNodesWithTag(tag).assertCountEquals(if (tag == only) 1 else 0)
                }
            }
            ready(controller) { it.book != null }
            nav("host-bar")
            onNodeWithTag("home-read").performClick()
            ready(controller) { it.screen == BookScreen.READER }
            nav("immersive-back")
            onNodeWithTag("immersive-back").performClick()
            ready(controller) { it.screen == BookScreen.HOME }
            nav("host-bar")
            onNodeWithTag("collection-fixture-list").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.BOOKLIST }
            nav("booklist-back")
            onNodeWithTag("booklist-book-${book.id}").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.READER }
            nav("immersive-back")
            onNodeWithTag("immersive-back").performClick()
            ready(controller) { it.screen == BookScreen.BOOKLIST && it.activeBooklistId == "fixture-list" }
            nav("booklist-back")
            onNodeWithTag("booklist-back").performClick()
            ready(controller) { it.screen == BookScreen.HOME }
            nav("host-bar")
        }
    }

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
        val stored = library.importBook(fixture(root))
        StudyModeStore.save(library, BookStudyPreference(stored.id, BookStudyMode.BOOK_MAP))
        val settings = DesktopConnectionSettings(root, { null }, mac = false,
            property = { if (it == "jpackage.app-path") "Synthetic.exe" else null })
        val controller = TextbookController(library)
        runSkikoComposeUiTest(size = Size(1440f, 900f), density = Density(1f)) {
            setContent {
                WindowsTextbookGate(settings) {
                    val client = androidx.compose.runtime.remember { BookExplainer(settings, library) }
                    WindowsTextbookHost(controller, bookExplainer = client, learningSupport = UnconnectedBookLearningSupport) { null }
                }
            }
            fun login(code: String) {
                waitTag("textbook-login-screen")
                if (onAllNodesWithTag("login-code").fetchSemanticsNodes().isEmpty()) {
                    onNodeWithTag("login-pairing-toggle").performClick()
                }
                onNodeWithTag("login-address").performTextReplacement(mac.url)
                onNodeWithTag("login-code").performTextInput(code)
                onNodeWithTag("login-pair").performClick()
                waitTag("windows-host")
            }
            fun read() {
                ready(controller) { it.book != null }
                if (controller.state.value.screen == BookScreen.HOME) onNodeWithTag("home-read").performClick()
                ready(controller) { it.screen == BookScreen.READER }
                openCompanion()
            }
            fun shell() = listOf("textbook-reader", "immersive-top-hud", "immersive-back").map {
                onNodeWithTag(it).getBoundsInRoot()
            }
            waitTag("textbook-login-screen")
            capture("ton1698-login-$theme", "textbook-login-screen")
            mac.mode = ExplainShellMac.Mode.OFFLINE
            login("246810")
            read()
            val reader = shell()
            fun checkShell(state: String) {
                assertEquals(stored.id, controller.state.value.book!!.id)
                assertEquals(reader, shell(), "$theme $state")
                onNodeWithTag("host-bar").assertDoesNotExist()
                capture("ton1698-$state-$theme", "windows-host")
            }
            waitTag("page-explanation-error")
            onNodeWithTag("page-explanation-error").assertTextEquals(ExplainTrouble.UNREACHABLE)
            checkShell("offline")
            mac.mode = ExplainShellMac.Mode.QUOTA
            onNode(hasText("重试") and hasAnySibling(hasTestTag("page-explanation-error"))).performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(ExplainShellMac.SERVICE_BUSY).fetchSemanticsNodes().isNotEmpty() }
            checkShell("busy")
            mac.mode = ExplainShellMac.Mode.REVOKED
            onNode(hasText("重试") and hasAnySibling(hasTestTag("page-explanation-error"))).performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("重新登录").fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithTag("textbook-login-screen").fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithText("登录后讲解这一页").fetchSemanticsNodes().isNotEmpty() }
            if (onAllNodesWithTag("textbook-login-screen").fetchSemanticsNodes().isEmpty()) {
                checkShell("revoked")
                onNode(hasText("重新登录") or hasText("登录后讲解这一页")).performClick()
            }
            waitTag("textbook-login-screen")
            capture("ton1698-relogin-$theme", "textbook-login-screen")
            mac.mode = ExplainShellMac.Mode.OK
            login("135791")
            read()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("This passage says", substring = true).fetchSemanticsNodes().isNotEmpty() }
            checkShell("connected")
            assertEquals(2, mac.pairs.get())
            onNodeWithTag("immersive-back").performClick()
            ready(controller) { it.screen == BookScreen.HOME }
            onNodeWithTag("host-bar").assertIsDisplayed()
        }
    }

    /**
     * TON-1698, native-window half: the same walk as above, in a real [androidx.compose.ui.awt.ComposeWindow] on the
     * Windows desktop, with the private real textbook kept outside Git on winbox (never a sample or synthetic book).
     * Opt in with `-Pcoach.explainLiveWindow=true` on Windows. Explain requests go only to a loopback stub on a random
     * port. Screenshots keep the rail, settings, the top bar and the panel's own status lines; everything else in the
     * window (book pages, the passages listed in the panel, citation snippets) is mosaicked before the file is written,
     * so no original text leaves the machine. Logs carry bounds and states only.
     */
    @Test fun realTextbookKeepsTheReaderShellInEveryExplainStateInANativeWindowInBothThemes() {
        org.junit.Assume.assumeTrue(System.getProperty("coach.explainLiveWindow") == "true")
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val book = Path.of("D:/agent/friend-path-books/health-economics.pdf")
        check(Files.isRegularFile(book)) { "the private real textbook is missing on this machine" }
        for (name in listOf("COACH_URL", "COACH_CLIENT_DIR", "COACH_TOKEN_FILE", "COACH_ACCEPTANCE_REPLAY", "COACH_AGENT_RUN_ID"))
            check(System.getenv(name).isNullOrBlank()) { "$name must be unset for this isolated run" }
        val saved = listOf("user.home", "jpackage.app-path", "coach.windows.theme").associateWith { System.getProperty(it) }
        val root = Files.createTempDirectory("coach-explain-shell-native-")
        try {
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                val home = Files.createDirectories(root.resolve("$theme-home"))
                System.setProperty("user.home", home.toString())
                System.setProperty("jpackage.app-path", home.resolve("LearningCoach.exe").toString())
                System.setProperty("coach.windows.theme", theme)
                assertEquals(home.resolve("Coach/client"), DesktopConnectionSettings().clientDirectory)
                ExplainShellMac(if (dark) 'd' else 'l').use { mac -> NativeShellWalk(root.resolve(theme), theme, book, mac).run() }
            }
        } finally {
            for ((key, value) in saved) if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            root.toFile().deleteRecursively()
        }
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class,
        androidx.compose.runtime.tooling.ComposeToolingApi::class)
    private class NativeShellWalk(val root: Path, val theme: String, val source: Path, val mac: ExplainShellMac) {
        private lateinit var window: androidx.compose.ui.awt.ComposeWindow
        private val out = File("build/screens/ton1698-native/$theme").apply { mkdirs() }

        fun run() {
            val library = TextbookLibrary(root.resolve("library"))
            val stored = library.importBook(source)
            check(stored.pages.size > 100) { "use the private real textbook" }
            // Consent is per book and asked once; it is covered elsewhere. Granting it here sends passages only to the stub.
            ExplainConsent(library.directory).allow(stored.id)
            val controller = TextbookController(library)
            kotlinx.coroutines.runBlocking { controller.load() }
            window = edt { androidx.compose.ui.awt.ComposeWindow().apply {
                title = "LearningCoach · TON-1698 讲解外壳真窗口 · 真实教材"
                val screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                setBounds(screen.x + 20, screen.y + 20, minOf(1440, screen.width - 40), minOf(900, screen.height - 60))
                setContent { WindowsTextbookHost(controller) { null } }
                isVisible = true
            } }
            try { walk(controller) } catch (failure: Throwable) { shot("failure"); throw failure } finally { edt { window.dispose() } }
        }

        private fun walk(controller: TextbookController) {
            waitFor("host") { controller.state.value.screen == BookScreen.HOME && node("host-navigation") != null }
            clickText("今天")
            click("host-today-read")
            waitFor("reader") { idle(controller) && controller.state.value.screen == BookScreen.READER && node("textbook-reader") != null }
            if (controller.state.value.prompt != null) kotlinx.coroutines.runBlocking { controller.dismissPrompt() }
            val book = controller.state.value.book!!
            // A page in the body of the book with a real paragraph, so the panel has something to explain.
            val block = StudyCoachCourse.blocks(book.id, book.pages).first { it.page >= 20 && !it.heading && it.text.length >= 120 }
            kotlinx.coroutines.runBlocking { controller.page(block.page) }
            waitFor("page ${block.page}") { idle(controller) && controller.state.value.page == block.page }
            assertHighlighted("书架")
            val reader = shell()
            log("reader", reader, "pages=${book.pages.size} page=${block.page}")
            shot("reader")
            fun same(state: String) {
                Thread.sleep(400)
                assertEquals(reader, shell(), "explain panel $state ($theme) moved the shell")
                assertHighlighted("书架")
                log(state, reader)
            }
            fun inBlock(tag: String) = edt { nodes().firstOrNull { n ->
                n.config.getOrNull(SemanticsProperties.TestTag) == tag && generateSequence(n.parent) { it.parent }
                    .any { it.config.getOrNull(SemanticsProperties.TestTag) == "explain-block-${block.id}" } } }
            fun clickInBlock(tag: String) {
                waitFor(tag) { inBlock(tag) != null }
                edt { assertTrue(inBlock(tag)!!.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!.invoke(), tag) }
                Thread.sleep(250)
            }
            fun pair(code: String) {
                waitFor("pairing form") { node("explain-pairing") != null }
                type("explain-address", mac.url)
                type("explain-code", code)
                click("explain-pair")
                waitFor("paired") { node("explain-pairing") == null }
            }

            // 连上
            click("book-explain-toggle")
            same("pairing")
            pair("246810")
            clickInBlock("explain-button")
            waitFor("answer") { inBlock("explain-text") != null }
            same("explain-connected")
            shot("explain-connected")

            // 连不上
            mac.mode = ExplainShellMac.Mode.OFFLINE
            clickInBlock("explain-button")
            waitFor("offline") { inBlock("explain-retry") != null }
            assertEquals(ExplainTrouble.UNREACHABLE, text(inBlock("explain-failed")))
            same("explain-offline")
            shot("explain-offline")

            // 被停用
            mac.mode = ExplainShellMac.Mode.REVOKED
            clickInBlock("explain-retry")
            waitFor("revoked") { node("explain-revoked") != null }
            same("explain-revoked")
            shot("explain-revoked")

            // 旧服务 429
            mac.mode = ExplainShellMac.Mode.QUOTA
            pair("135791")
            clickInBlock("explain-button")
            waitFor("quota") { inBlock("explain-failed") != null }
            assertEquals(ExplainShellMac.SERVICE_BUSY, text(inBlock("explain-failed")))
            assertNotNull(inBlock("explain-retry"))
            same("explain-busy")
            shot("explain-busy")
            assertEquals(2, mac.pairs.get())

            // 关面板，再「返回书架」
            click("book-explain-toggle")
            waitFor("panel closed") { node("book-explain") == null }
            same("closed")
            clickText("返回书架", inDesk = true)
            waitFor("shelf") { idle(controller) && controller.state.value.screen == BookScreen.HOME }
            assertHighlighted("书架")
            assertNotNull(node("host-bar"))
            log("back-on-shelf", shell(desk = false))
            shot("back-on-shelf")

            // Reading opened from 课程 is the shelf's page too.
            clickText("课程")
            click("host-course-read")
            waitFor("course reader") { idle(controller) && controller.state.value.screen == BookScreen.READER }
            if (controller.state.value.prompt != null) kotlinx.coroutines.runBlocking { controller.dismissPrompt() }
            Thread.sleep(400)
            assertHighlighted("书架")
            assertNull(node("host-bar"))
            log("course-reader", shell())
        }

        private fun idle(controller: TextbookController): Boolean {
            check(controller.state.value.error == null) { "controller error ($theme)" }
            return !controller.state.value.busy
        }

        private fun nodes(): List<androidx.compose.ui.semantics.SemanticsNode> {
            fun walk(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
                listOf(node) + node.children.flatMap(::walk)
            return window.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        }
        private fun node(tag: String) = edt { nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag } }
        private fun inDesk(node: androidx.compose.ui.semantics.SemanticsNode) =
            generateSequence(node.parent) { it.parent }.any { it.config.getOrNull(SemanticsProperties.TestTag) == "v7-desk" }
        private fun textNode(value: String, desk: Boolean) = edt { nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == value } == true && (!desk || inDesk(it)) } }
        private fun text(node: androidx.compose.ui.semantics.SemanticsNode?): String = edt {
            val target = checkNotNull(node)
            (listOf(target) + target.children).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString("") { it.text }
        }
        private fun assertHighlighted(label: String) =
            assertEquals(label, edt { node("host-navigation")!!.config.getOrNull(SemanticsProperties.StateDescription) }, "rail ($theme)")

        /** Rail, settings and, on the reader, the desk and its top bar (Coach, 目录, 返回书架), in window pixels. */
        private fun shell(desk: Boolean = true): Map<String, androidx.compose.ui.geometry.Rect> = edt {
            buildMap {
                put("rail", node("host-navigation")!!.boundsInRoot)
                put("settings", node("open-settings")!!.boundsInRoot)
                if (desk) {
                    put("desk", node("v7-desk")!!.boundsInRoot)
                    put("Coach", textNode("Coach", desk = true)!!.boundsInRoot)
                    put("目录", node("v7-directory-toggle")!!.boundsInRoot)
                    put("返回书架", textNode("返回书架", desk = true)!!.boundsInRoot)
                }
            }
        }

        private fun click(tag: String) {
            waitFor(tag) { node(tag) != null }
            edt { assertTrue(node(tag)!!.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!.invoke(), tag) }
            Thread.sleep(250)
        }
        private fun clickText(value: String, inDesk: Boolean = false) {
            waitFor(value) { textNode(value, inDesk) != null }
            edt {
                val button = generateSequence(textNode(value, inDesk)) { it.parent }
                    .first { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) != null }
                assertTrue(button.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!.invoke(), value)
            }
            Thread.sleep(250)
        }
        private fun type(tag: String, value: String) {
            waitFor(tag) { node(tag) != null }
            edt {
                val field = node(tag)!!
                val editable = (listOf(field) + field.children).first { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.SetText) != null }
                assertTrue(editable.config[androidx.compose.ui.semantics.SemanticsActions.SetText].action!!
                    .invoke(androidx.compose.ui.text.AnnotatedString(value)), tag)
            }
            Thread.sleep(250)
        }

        private fun log(step: String, shell: Map<String, androidx.compose.ui.geometry.Rect>, extra: String = "") = println(
            "TON1698_NATIVE theme=$theme step=$step rail=${node("host-navigation")!!.config.getOrNull(SemanticsProperties.StateDescription)} " +
                "hostBar=${node("host-bar") != null} " + shell.entries.joinToString(" ") { (k, r) ->
                    "$k=${r.left.toInt()},${r.top.toInt()},${r.width.toInt()}x${r.height.toInt()}" } + " $extra".trimEnd())

        /**
         * Capture the native window, then mosaic every pixel outside the shell and the panel's status lines.
         * The raw frame never leaves the run's temp directory, which is deleted afterwards.
         */
        private fun shot(name: String) {
            Thread.sleep(500)
            val raw = root.resolve("raw-$name.png").toFile()
            val keep = edt {
                window.renderImmediately()
                VerifyHooks.capture(window, raw)
                val tags = setOf("host-navigation", "open-settings", "host-bar", "book-explain-toggle", "explain-pairing",
                    "explain-button", "explain-retry", "explain-repair", "explain-failed", "explain-text")
                val all = nodes()
                val boxes = all.filter { it.config.getOrNull(SemanticsProperties.TestTag) in tags }.map { it.boundsInRoot }.toMutableList()
                // The reader's top bar: from the desk's top edge to the bottom of Coach / 目录 / 返回书架, full desk width.
                val desk = all.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "v7-desk" }?.boundsInRoot
                val bar = listOfNotNull(all.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "v7-directory-toggle" },
                    textNode("Coach", desk = true), textNode("返回书架", desk = true)).map { it.boundsInRoot }
                if (desk != null && bar.isNotEmpty()) boxes += androidx.compose.ui.geometry.Rect(desk.left, desk.top, desk.right, bar.maxOf { it.bottom } + 8f)
                boxes.map { java.awt.Rectangle(it.left.toInt(), it.top.toInt(), it.width.toInt() + 1, it.height.toInt() + 1) }
            }
            val image = ImageIO.read(raw)
            raw.delete()
            val masked = java.awt.image.BufferedImage(image.width, image.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val cell = 28
            for (y in 0 until image.height step cell) for (x in 0 until image.width step cell) {
                val w = minOf(cell, image.width - x); val h = minOf(cell, image.height - y)
                var r = 0L; var g = 0L; var b = 0L
                for (yy in y until y + h) for (xx in x until x + w) image.getRGB(xx, yy).let { r += it shr 16 and 255; g += it shr 8 and 255; b += it and 255 }
                val n = w * h
                val rgb = ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
                for (yy in y until y + h) for (xx in x until x + w) masked.setRGB(xx, yy, rgb)
            }
            val g = masked.createGraphics()
            for (box in keep) box.intersection(java.awt.Rectangle(0, 0, image.width, image.height)).takeIf { !it.isEmpty }?.let {
                g.drawImage(image.getSubimage(it.x, it.y, it.width, it.height), it.x, it.y, null)
            }
            g.dispose()
            ImageIO.write(masked, "png", File(out, "$name.png"))
        }

        private fun waitFor(label: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 90_000_000_000L
            while (!condition()) { check(System.nanoTime() < deadline) { "Timed out: $label ($theme)" }; Thread.sleep(100) }
        }
        private fun <T> edt(block: () -> T): T {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) return block()
            var result: Result<T>? = null
            javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
            return checkNotNull(result).getOrThrow()
        }
    }

    /** Loopback stand-in for the Mac: pairing hands out credentials, explain answers by [mode]. */
    private class ExplainShellMac(prefix: Char) : AutoCloseable {
        enum class Mode { OK, OFFLINE, REVOKED, QUOTA }
        companion object {
            private const val QUOTA = "今天的讲解额度用完了，明天再来。"
            const val SERVICE_BUSY = "讲解服务暂时不可用，请稍后再试。"
        }
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
