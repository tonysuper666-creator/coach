package com.coach.desktop

import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import com.coach.desktop.books.*
import com.coach.ui.theme.CoachColors
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.awt.GraphicsEnvironment
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.*

/**
 * 朋友那台 Windows 电脑上，讲解没讲成的几种情况在真窗口里走一遍（TON-1687）：
 * 逐书同意、断连后再试一次讲成、日额度用完、答后讲解被 401 停用 → 去原文页重新配对 → 回到这一节讲出来。
 *
 * 走 Windows 主机默认的那套讲解器和配对文件，不注入讲解器：只把这个测试进程的 `user.home` 换成临时目录，
 * 并按安装版的样子带上 `jpackage.app-path`，所以配对、凭据和逐书同意都落在临时目录里，不碰真实的 `~/Coach/client`。
 * Mac 换成本机回环上的假服务（随机端口，绝不是 18791）。教材是仓库里自造的两本样例书。
 * 只在 Windows 上、显式带 `-Pcoach.explainLiveWindow=true` 时运行。
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class ExplainTroubleLiveWindowTest {
    @Test fun friendWindowHandlesOfflineQuotaRevokedAndPerBookConsentInBothThemes() {
        assumeTrue(System.getProperty("coach.explainLiveWindow") == "true")
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        // 这些变量会把客户端目录、服务地址或凭据指到别处；带着它们跑就不再是隔离的临时目录。
        for (name in listOf("COACH_URL", "COACH_CLIENT_DIR", "COACH_TOKEN_FILE", "COACH_ACCEPTANCE_REPLAY", "COACH_AGENT_RUN_ID"))
            check(System.getenv(name).isNullOrBlank()) { "$name must be unset for this isolated run" }
        val saved = listOf("user.home", "jpackage.app-path", "coach.windows.theme").associateWith { System.getProperty(it) }
        val root = Files.createTempDirectory("coach-explain-live-")
        try {
            for (dark in listOf(false, true)) {
                val home = Files.createDirectories(root.resolve(if (dark) "dark-home" else "light-home"))
                System.setProperty("user.home", home.toString())
                System.setProperty("jpackage.app-path", home.resolve("LearningCoach.exe").toString())
                System.setProperty("coach.windows.theme", if (dark) "dark" else "light")
                assertEquals(home.resolve("Coach/client"), DesktopConnectionSettings().clientDirectory)
                FakeMac(if (dark) 'd' else 'l').use { mac -> walk(root.resolve(if (dark) "dark" else "light"), dark, mac) }
            }
        } finally {
            for ((key, value) in saved) if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            root.toFile().deleteRecursively()
        }
    }

    private fun walk(root: Path, dark: Boolean, mac: FakeMac) {
        val theme = if (dark) "dark" else "light"
        val library = TextbookLibrary(root.resolve("library"))
        val controller = TextbookController(library)
        runBlocking {
            controller.importBook(syntheticTextbook(root.resolve("other")))
            controller.importBook(knowledgeRecapTextbook(root.resolve("recap")))
            controller.chooseLearningPath(LearningPath.QUESTION_FIRST); controller.enableLearning()
            controller.back()
        }
        val book = controller.state.value.book!!
        val other = controller.state.value.catalog.first { it.id != book.id }
        val consent = ExplainConsent(library.directory)
        val window = edt { ComposeWindow().apply {
            title = "LearningCoach · 讲解异常真窗口验收 · 自造样例书"
            val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            setBounds(screen.x + 20, screen.y + 20, minOf(1440, screen.width - 40), minOf(900, screen.height - 60))
            setContent { WindowsTextbookHost(controller) { null } }
            isVisible = true
        } }
        fun step(name: String) = println("EXPLAIN_LIVE theme=$theme step=$name pairs=${mac.pairs.get()} explains=${mac.explains.get()}")
        try {
            waitFor("host") { controller.state.value.screen == BookScreen.HOME && node(window, "host-navigation") != null }
            clickText(window, "今天")
            click(window, "host-today-read")
            waitFor("reader") { controller.state.value.screen == BookScreen.READER && node(window, "textbook-reader") != null }
            if (controller.state.value.prompt != null) runBlocking { controller.dismissPrompt() }
            val page = controller.state.value.page
            val block = StudyCoachCourse.blocks(book.id, book.pages).first { it.page == page && !it.heading }
            fun inBlock(tag: String) = edt { nodes(window).firstOrNull { n ->
                n.config.getOrNull(SemanticsProperties.TestTag) == tag && generateSequence(n.parent) { it.parent }
                    .any { it.config.getOrNull(SemanticsProperties.TestTag) == "explain-block-${block.id}" } } }
            fun clickInBlock(tag: String) {
                waitFor(tag) { inBlock(tag) != null }
                edt { assertTrue(inBlock(tag)!!.config[SemanticsActions.OnClick].action!!.invoke(), tag) }
                Thread.sleep(250)
            }

            // 先连上 Mac：讲解页的配对表单，填假 Mac 的地址和配对码。
            click(window, "book-explain-toggle")
            waitFor("pairing form") { node(window, "explain-pairing") != null }
            type(window, "explain-address", mac.url)
            type(window, "explain-code", "246810")
            click(window, "explain-pair")
            waitFor("paired") { node(window, "explain-pairing") == null }
            assertEquals(1, mac.pairs.get())
            step("paired")

            // 第 3 项：平时「讲解这段」只是段落首行右边一行 faint 小字，和正文同一基线；
            // 鼠标扫到这一段才浮成纸面按钮，盒子不挪位。用真窗口和真鼠标核一遍，浅深各拍两张。
            val colors = if (dark) CoachColors.dark() else CoachColors.light()
            fun label() = assertNotNull(inBlock("explain-button"))
            reveal(window, "explain-paragraph-${block.id}")
            assertEquals(colors.fgTertiary, labelColor(label()), "平时应当是 faint 文字")
            assertEquals(firstBaseline(inBlock("explain-paragraph-${block.id}")!!), firstBaseline(label()), 1.5f,
                "和段落首行同一基线")
            val faintTop = edt { label().boundsInRoot.top }
            shot(window, dark, "00-faint")
            val origin = edt { window.contentPane.locationOnScreen }
            val transform = edt { window.graphicsConfiguration.defaultTransform }
            val band = edt { inBlock("explain-paragraph-${block.id}")!!.boundsInRoot }
            val robot = java.awt.Robot().apply { autoDelay = 40 }
            fun moveTo(x: Float, y: Float) =
                robot.mouseMove(origin.x + (x / transform.scaleX).toInt(), origin.y + (y / transform.scaleY).toInt())
            moveTo(band.center.x, band.center.y)
            Thread.sleep(800)
            assertEquals(colors.fgPrimary, labelColor(label()), "鼠标在这一段上时换成正常字色")
            assertEquals(faintTop, edt { label().boundsInRoot.top }, 1f, "悬停不能把这一行挪位")
            shot(window, dark, "00-faint-hover")
            moveTo(4f, 4f)
            Thread.sleep(800)
            assertEquals(colors.fgTertiary, labelColor(label()), "鼠标离开回到 faint")
            step("faint-control")

            // 失败那一行（再试一次 + 那句话）占的应当是平时就留好的位置：失败回来这一块的高度和顶上都不许变，
            // 否则同一页下面的段落会被推下去（QA 退回 PR #448 的那 47 像素）。
            fun blockBounds() = edt { nodes(window).first {
                it.config.getOrNull(SemanticsProperties.TestTag) == "explain-block-${block.id}" }.boundsInRoot }
            val restingBlock = blockBounds()

            // 逐书同意 + 断连：Mac 那边整个服务停掉。点「讲解这段」先问这本书能不能发原文；允许之后才去连，连不上。
            mac.down()
            clickInBlock("explain-button")
            waitFor("per-book consent") { node(window, "explain-consent") != null }
            assertFalse(consent.allowed(book.id), "同意之前什么都不发")
            assertEquals(0, mac.explains.get())
            shot(window, dark, "01-consent")
            click(window, "explain-consent-allow")
            assertTrue(consent.allowed(book.id))
            assertFalse(consent.allowed(other.id), "同意只算这一本书")
            waitFor("offline line") { inBlock("explain-failed") != null }
            assertEquals(ExplainTrouble.UNREACHABLE, text(inBlock("explain-failed")))
            assertNotNull(inBlock("explain-retry"), "连不上给「再试一次」")
            assertNull(node(window, "explain-answer"))
            shot(window, dark, "02-offline")
            assertEquals(restingBlock.top, blockBounds().top, 1f, "失败不能把这一块往下挪")
            assertEquals(restingBlock.height, blockBounds().height, 1f, "失败提示用的是预留的位置，块高度不变")
            step("offline")

            // Mac 回来：同一个按钮再试一次，讲出来，带出处。
            mac.up()
            clickInBlock("explain-retry")
            waitFor("retried answer") { inBlock("explain-answer") != null }
            assertNull(inBlock("explain-failed"))
            assertEquals(1, mac.explains.get())
            shot(window, dark, "03-retried")
            step("retried")

            // 日额度用完：服务端那句话原样显示，没有「再试一次」，按钮停用。
            mac.quota = true
            clickInBlock("explain-button")
            waitFor("quota line") { inBlock("explain-failed") != null }
            assertEquals(FakeMac.QUOTA, text(inBlock("explain-failed")))
            assertNull(inBlock("explain-retry"), "额度用完不给再试")
            assertFalse(enabled(inBlock("explain-button")!!), "额度用完按钮停用")
            assertEquals(2, mac.explains.get())
            shot(window, dark, "04-quota")
            step("quota")

            // 选段就问碰上额度用完：同一句话，「发送」停用，选段和问题留着。
            click(window, "book-explain-toggle")
            click(window, "ask-book-passage")
            click(window, "ask-paragraph-0")
            type(window, "book-passage-question", "这里的「基本单位」指什么？")
            click(window, "book-passage-send")
            waitFor("ask quota") { node(window, "book-passage-unavailable") != null }
            assertEquals(FakeMac.QUOTA, text(node(window, "explain-trouble")))
            assertNull(node(window, "explain-trouble-retry"))
            assertFalse(enabled(node(window, "book-passage-send")!!))
            assertEquals(3, mac.explains.get(), "额度用完只发了这一次")
            assertNotNull(node(window, "book-selected-passage"))
            reveal(window, "book-passage-unavailable")
            shot(window, dark, "05-ask-quota")
            click(window, "book-passage-close")
            step("ask-quota")

            // 第二天额度回来了，但这台电脑的连接在 Mac 那边被停用（401）。做题学答完，讲解那一块指回配对。
            mac.quota = false
            mac.revokeFirst = true
            clickText(window, "课程")
            click(window, "host-course-learn")
            waitFor("learning") { controller.state.value.screen == BookScreen.LEARNING && node(window, "choice-0") != null }
            val current = controller.state.value.book!!
            val practice = assertNotNull(StudyCoachCourse.practice(current, current.lessons[current.lessonIndex], QuestionMode.CHOICE))
            click(window, "choice-${practice.options.indexOf(practice.answer)}")
            waitFor("revealed") { controller.state.value.answer.revealed }
            waitFor("answer revoked") { node(window, "answer-explain-unpaired") != null }
            assertEquals(ExplainTrouble.REVOKED, text(node(window, "answer-explain-unpaired-text")))
            assertEquals(4, mac.explains.get())
            reveal(window, "answer-explain-unpaired")
            shot(window, dark, "06-answer-revoked")
            step("answer-revoked")

            // 去原文页配对：原文页直接摊开配对，标题是「已停用」；填新的配对码。
            click(window, "answer-explain-pair")
            waitFor("source page pairing") { controller.state.value.screen == BookScreen.READER && node(window, "explain-pairing") != null }
            assertNotNull(node(window, "explain-revoked"))
            shot(window, dark, "07-repair")
            type(window, "explain-address", mac.url)
            type(window, "explain-code", "135791")
            click(window, "explain-pair")
            waitFor("re-paired") { node(window, "explain-pairing") == null }
            assertEquals(2, mac.pairs.get())
            step("re-paired")

            // 回到这一节：讲解重新去取，讲出来，不再停在「已停用」。
            clickText(window, "返回学习")
            waitFor("answer explained") { controller.state.value.screen == BookScreen.LEARNING && node(window, "book-explanation-text") != null }
            assertTrue(FakeMac.ANSWER in text(node(window, "book-explanation-text")))
            assertNull(node(window, "book-explanation-unavailable"))
            assertNull(node(window, "answer-explain-unpaired"))
            assertEquals(5, mac.explains.get())
            reveal(window, "book-lesson-explanation")
            shot(window, dark, "08-answer-explained")
            step("answer-explained")

            // 另一本书：同意不跟着走，第一次讲解照样先问；选「不允许」就什么都不发。
            runBlocking { controller.select(other.id); controller.read() }
            waitFor("other reader") { controller.state.value.screen == BookScreen.READER && controller.state.value.book?.id == other.id }
            if (controller.state.value.prompt != null) runBlocking { controller.dismissPrompt() }
            click(window, "book-explain-toggle")
            waitFor("other explain panel") { node(window, "explain-button") != null }
            click(window, "explain-button")
            waitFor("other consent") { node(window, "explain-consent") != null }
            shot(window, dark, "09-other-book-consent")
            click(window, "explain-consent-deny")
            waitFor("consent closed") { node(window, "explain-consent") == null }
            assertFalse(consent.allowed(other.id))
            Thread.sleep(500)
            assertEquals(5, mac.explains.get(), "不允许就不发原文")
            step("other-book-denied")
        } finally { edt { window.dispose() } }
    }

    /** 本机回环上的假 Mac。配对第 n 次发第 n 份凭据；[revokeFirst] 打开后第一份凭据讲解时回 401；[quota] 打开时回日额度用完。 */
    private class FakeMac(prefix: Char) : AutoCloseable {
        companion object {
            const val QUOTA = "今天的讲解额度用完了，明天再来。"
            const val ANSWER = "这一段说的是：细胞是生物体结构和功能的基本单位。"
        }
        val pairs = AtomicInteger()
        val explains = AtomicInteger()
        @Volatile var quota = false
        @Volatile var revokeFirst = false
        private val tokens = listOf(prefix + "a".repeat(42), prefix + "b".repeat(42))
        private var server: HttpServer? = open(0)
        private val port = server!!.address.port
        val url = "http://127.0.0.1:$port"

        init { check(port !in productionPorts) { "never a production port" } }

        private fun open(port: Int) = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
            createContext("/", ::handle); start()
        }

        /** 断连：整个服务停掉，端口上没人听。 */
        fun down() { server?.stop(0); server = null }
        fun up() {
            repeat(50) { runCatching { open(port) }.onSuccess { server = it; return }; Thread.sleep(100) }
            error("could not reopen the fake Mac on its port")
        }

        private fun handle(exchange: HttpExchange) {
            val sent = exchange.requestBody.use { it.readBytes() }.decodeToString()
            val bearer = exchange.requestHeaders.getFirst("Authorization").orEmpty().takeLast(2)
            println("FAKE_MAC ${exchange.requestMethod} ${exchange.requestURI.path} token=…$bearer at=${System.currentTimeMillis() % 100_000}")
            val (status, body) = when (exchange.requestURI.path) {
                "/v1/pair" -> 200 to """{"deviceId":"friend-pc","token":"${tokens[pairs.getAndIncrement().coerceAtMost(1)]}"}"""
                "/v1/hello" -> 200 to """{"epoch":"synthetic","lastSeq":0,"backedUpThroughSeq":0,"lastSeqBefore":0}"""
                "/v1/library/explain" -> {
                    explains.incrementAndGet()
                    when {
                        revokeFirst && exchange.requestHeaders.getFirst("Authorization") == "Bearer ${tokens[0]}" -> 401 to """{"error":"unauthorized"}"""
                        quota -> 429 to """{"error":"$QUOTA","code":"daily_quota"}"""
                        else -> {
                            val id = Json.parseToJsonElement(sent).jsonObject.getValue("blocks").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content
                            val page = Regex(":p(\\d+):").find(id)!!.groupValues[1]
                            200 to """{"text":"$ANSWER【S1】","sources":[{"docId":"$id","pdfPage":$page,"anchor":"S1"}]}"""
                        }
                    }
                }
                else -> 404 to "{}"
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        override fun close() = down()
    }

    private fun nodes(window: ComposeWindow): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        val owners = window.semanticsOwners + ownedDialogs(window).flatMap { it.semanticsOwners }
        return owners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun ownedDialogs(window: java.awt.Window): List<ComposeDialog> = window.ownedWindows
        .filter { it.isVisible }.flatMap { listOfNotNull(it as? ComposeDialog) + ownedDialogs(it) }
    private fun node(window: ComposeWindow, tag: String) = edt { nodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag } }
    private fun text(node: SemanticsNode?): String = edt {
        val target = checkNotNull(node)
        (listOf(target) + target.children).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString("") { it.text }
    }
    private fun enabled(node: SemanticsNode) = edt { node.config.getOrNull(SemanticsProperties.Disabled) == null }

    /** 这个文字节点实际用的颜色（「讲解这段」平时靠字色区分）。 */
    private fun labelColor(node: SemanticsNode): Color = edt {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        layouts.single().layoutInput.style.color
    }

    /** 一段文字画在屏幕上的第一条基线：节点顶 + 上下内边距 + 排版里的 firstBaseline。 */
    private fun firstBaseline(node: SemanticsNode): Float = edt {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        val inset = (node.boundsInRoot.height - layout.size.height) / 2f
        node.boundsInRoot.top + inset + layout.firstBaseline
    }
    private fun click(window: ComposeWindow, tag: String) {
        waitFor(tag) { node(window, tag) != null }
        edt { assertTrue(node(window, tag)!!.config[SemanticsActions.OnClick].action!!.invoke(), tag) }
        Thread.sleep(250)
    }
    private fun clickText(window: ComposeWindow, text: String) {
        waitFor(text) { edt { nodes(window).any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true } } }
        edt {
            val node = nodes(window).first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true }
            var button: SemanticsNode? = node
            while (button != null && button.config.getOrNull(SemanticsActions.OnClick) == null) button = button.parent
            assertTrue(checkNotNull(button).config[SemanticsActions.OnClick].action!!.invoke())
        }
        Thread.sleep(250)
    }
    private fun type(window: ComposeWindow, tag: String, value: String) {
        waitFor(tag) { node(window, tag) != null }
        edt {
            val field = nodes(window).first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            val editable = (listOf(field) + field.children).first { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(editable.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)), tag)
        }
        Thread.sleep(250)
    }
    /** 把要拍的那块滚进可见区：沿祖先找到能滚的那层，滚到它的顶上留一点边。 */
    private fun reveal(window: ComposeWindow, tag: String) {
        edt {
            val target = nodes(window).first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            val scroller = generateSequence(target.parent) { it.parent }.firstOrNull { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                ?: return@edt
            val dy = target.boundsInRoot.top - scroller.boundsInRoot.top - 48f
            scroller.config[SemanticsActions.ScrollBy].action!!.invoke(0f, dy)
        }
        Thread.sleep(400)
    }
    private fun shot(window: ComposeWindow, dark: Boolean, name: String) {
        Thread.sleep(400)
        edt {
            window.renderImmediately()
            val path = Path.of("build/screens/explain-live-native", if (dark) "dark" else "light")
            Files.createDirectories(path)
            VerifyHooks.capture(window, path.resolve("$name.png").toFile())
            ownedDialogs(window).forEachIndexed { index, dialog ->
                dialog.renderImmediately()
                VerifyHooks.capture(dialog, path.resolve("$name-dialog-$index.png").toFile())
            }
        }
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 60_000_000_000L
        while (!condition()) { check(System.nanoTime() < deadline) { "Timed out: $label" }; Thread.sleep(100) }
    }
    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
