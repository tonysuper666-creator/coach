package com.coach.desktop

import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.coach.desktop.books.*
import com.coach.client.PairedCredentials
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.awt.GraphicsEnvironment
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** Explicit workflow_dispatch driver, never included in the installed app.
 * MSI launch and production-host semantics are separate checks: this process drives
 * the same WindowsTextbookHost and pairing/import/HTTP code, not the MSI process.
 * Only self-authored text and allowlisted screenshots leave this disposable runner.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
object ExternalFriendSmoke {
    private lateinit var root: Path
    private lateinit var evidence: Path
    private lateinit var url: String
    private var window: ComposeWindow? = null
    private var stage = "configuration"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    @JvmStatic fun main(args: Array<String>) {
        var result = 1
        try {
            check(System.getProperty("os.name").startsWith("Windows"))
            check(System.getenv("GITHUB_ACTIONS") == "true")
            root = Path.of(required("COACH_EXTERNAL_PRIVATE"))
            evidence = Files.createDirectories(Path.of(required("COACH_EXTERNAL_EVIDENCE")))
            val endpoint = URI(required("COACH_EXTERNAL_URL"))
            check(endpoint.scheme == "https" && endpoint.host.endsWith(".ts.net") && endpoint.port in listOf(-1, 443))
            check(endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null && endpoint.path in listOf("", "/"))
            url = desktopServerUrl(endpoint.toString())
            for (name in listOf("COACH_URL", "COACH_CLIENT_DIR", "COACH_TOKEN_FILE", "COACH_AGENT_RUN_ID", "COACH_ACCEPTANCE_REPLAY"))
                check(System.getenv(name).isNullOrBlank())
            System.setProperty("user.home", Files.createDirectories(root.resolve("home-a")).toString())
            System.setProperty("jpackage.app-path", required("COACH_EXTERNAL_EXE"))
            when (args.single()) {
                "probe" -> probe()
                "revoke" -> revoke()
                else -> error("mode")
            }
            result = 0
        } catch (_: Throwable) {
            // HTTP exceptions/semantics trees may contain endpoint, entered code or token.
            System.err.println("EXTERNAL_FRIEND FAIL stage=$stage (private details suppressed)")
        } finally {
            runCatching { window?.let { edt { it.dispose() } } }
        }
        exitProcess(result)
    }

    private fun probe() {
        stage = "prepaired-b"
        // B was paired on the Mac before A's code was signed. It is a quota and
        // revocation control, not a second Windows native pairing acceptance.
        val settings = bSettings()
        val b = prepairedB(settings)
        check(status(b).statusCode() == 200)
        stage = "import"
        val source = root.resolve("Self-authored sample.txt")
        Files.writeString(source, "Self-authored acceptance sample\n\n主动回忆是一种学习方法：先合上书，尝试用自己的话说出刚读过的概念，再打开原文核对遗漏。这个样例只用于连接验收。")
        val library = TextbookLibrary(root.resolve("library"))
        val controller = TextbookController(library)
        // Uses the native import core; does not claim to test file-picker/drag gestures.
        runBlocking { controller.importBook(source) }
        val w = edt { ComposeWindow().apply {
            title = "LearningCoach · 外网连接验收 · 自写样例"
            val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            setBounds(screen.x + 20, screen.y + 20, minOf(1400, screen.width - 40), minOf(900, screen.height - 60))
            setContent { WindowsTextbookHost(controller) { null } }
            isVisible = true
        } }
        window = w
        waitFor { node("read-only") != null }
        click("read-only")
        waitFor { node("read-only") != null }
        click("read-only")
        waitFor { node("book-explain-toggle") != null }
        click("book-explain-toggle")
        stage = "pair-a"
        waitFor { node("explain-pairing") != null }
        type("explain-address", url)
        type("explain-code", code("COACH_EXTERNAL_CODE_A"))
        click("explain-pair")
        waitFor { node("explain-pairing") == null }
        val a = checkNotNull(DesktopPairing(DesktopConnectionSettings()).load())
        check(a.credentials.role == "friend")
        check(status(a).statusCode() == 200)
        // Never capture a pairing form, even on failure.
        click("book-explain-toggle")
        click("ask-book-passage")
        click("ask-paragraph-0")
        type("book-passage-question", "请用自己的话解释主动回忆，并给一个例子。")
        click("book-passage-send")
        waitFor { node("ask-consent-allow") != null }
        click("ask-consent-allow")
        stage = "explanation-a"
        waitFor { node("book-passage-reply") != null && node("book-passage-citation") != null }
        shot("explanation.png")
        click("book-passage-citation")
        waitFor { node("book-passage-source") != null }
        shot("source.png")
        pass("nativeImport=1 friendPair=1 consent=1 selectionReply=1 citation=1")

        stage = "quota-b"
        check(status(b).statusCode() == 200)
        // Mac must configure B.daily_calls=1 before pre-pairing it.
        val book = checkNotNull(controller.state.value.book)
        val blocks = StudyCoachCourse.blocks(book.id, book.pages).filterNot { it.heading }
        val explainer = BookExplainer(settings, library)
        runBlocking {
            explainer.load()
            val first = explainer.ask(blocks.first(), "请解释这段话。")
            check(first is ExplainResult.Done && first.explanation.citations.isNotEmpty())
            val second = explainer.ask(blocks.first(), "请再解释这段话。")
            check(second is ExplainResult.Refused && second.trouble == ExplainTrouble.Quota && second.message == "今天的讲解额度用完了，明天再来。")
        }
        pass("bPrepairedOnMac=1 bFirst=200 bSecond=429 dailyQuotaChinese=1")
        stage = "routes"
        val denied = listOf("/v1/ws", "/v1/commands", "/v1/devices", "/v1/pairing-code", "/v1/courses/x/snapshot", "/v1/courses/x/today", "/v1/hello", "/v1/usage", "/health", "/v1/reader", "/v1/library", "/v1/%64evices", "/v1/library/explain/")
        for (path in denied) {
            val reply = request(a, path)
            check(reply.statusCode() == 404 && reply.body().isEmpty())
        }
        stage = "ports"
        for (port in listOf(18791, 8791, 8443, 10000)) {
            var connected = false
            try { Socket().use { it.connect(InetSocketAddress(URI(url).host, port), 3000); connected = true } }
            catch (_: java.io.IOException) { }
            check(!connected)
        }
        Files.writeString(evidence.resolve("ready-for-revoke.json"), """{"ready":true,"forbiddenPaths":13,"otherPorts":4,"bCalls":1}""")
        pass("forbiddenEmpty404=13 otherPortsClosed=4 readyForMacRevoke=1")
    }

    private fun revoke() {
        stage = "revocation"
        val a = checkNotNull(DesktopPairing(DesktopConnectionSettings()).load())
        val b = checkNotNull(DesktopPairing(bSettings()).load())
        val deadline = System.nanoTime() + 180_000_000_000L
        while (status(a).statusCode() == 200) {
            check(System.nanoTime() < deadline)
            Thread.sleep(2000)
        }
        check(status(a).statusCode() == 401)
        check(status(b).statusCode() == 200)
        Files.writeString(evidence.resolve("revocation.json"), """{"a":401,"b":200}""")
        pass("revokedA=401 unaffectedB=200")
    }

    private fun bSettings() = DesktopConnectionSettings(home = root.resolve("home-b"), environment = { null }, mac = false)
    private fun prepairedB(settings: DesktopConnectionSettings): DesktopIdentity {
        val raw = required("COACH_EXTERNAL_B")
        mask(raw)
        val value = Json.parseToJsonElement(raw).jsonObject
        check(value.keys == setOf("deviceId", "token", "role"))
        fun field(name: String) = value.getValue(name).jsonPrimitive.also { check(it.isString) }.content
        val device = field("deviceId").also { mask(it); check(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
        val token = field("token").also { mask(it); check(it.matches(Regex("[A-Za-z0-9_-]{43}"))) }
        check(field("role") == "friend")
        val identity = DesktopIdentity(url, PairedCredentials(device, token, "friend"))
        check(status(identity).statusCode() == 200)
        settings.saveAddress(url)
        DesktopCredentials(settings.clientDirectory).save(url, identity.credentials)
        return identity
    }
    private fun mask(value: String) {
        println("::add-mask::" + value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A"))
    }
    private fun status(identity: DesktopIdentity) = request(identity, "/v1/public/status")
    private fun request(identity: DesktopIdentity, path: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder(URI(url + path)).timeout(Duration.ofSeconds(15))
            .header("X-Coach-Device", identity.credentials.deviceId).header("Authorization", "Bearer " + identity.credentials.token).GET().build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }
    private fun required(name: String) = checkNotNull(System.getenv(name)).also { check(it.isNotBlank()) }
    private fun code(name: String) = required(name).also { mask(it); check(it.matches(Regex("[0-9]{6}"))) }
    private fun pass(summary: String) = println("EXTERNAL_FRIEND PASS $summary")
    private fun nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        fun dialogs(w: java.awt.Window): List<ComposeDialog> = w.ownedWindows.filter { it.isVisible }
            .flatMap { listOfNotNull(it as? ComposeDialog) + dialogs(it) }
        val w = checkNotNull(window)
        return (w.semanticsOwners + dialogs(w).flatMap { it.semanticsOwners }).flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun node(tag: String) = edt { nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag } }
    private fun click(tag: String) {
        waitFor { node(tag) != null }
        edt { check(node(tag)!!.config[SemanticsActions.OnClick].action!!.invoke()) }
        Thread.sleep(250)
    }
    private fun type(tag: String, value: String) {
        waitFor { node(tag) != null }
        edt {
            val n = node(tag)!!
            val field = (listOf(n) + n.children).first { it.config.getOrNull(SemanticsActions.SetText) != null }
            check(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
        }
        Thread.sleep(200)
    }
    private fun shot(name: String) {
        check(node("explain-pairing") == null)
        Thread.sleep(350)
        edt {
            val w = checkNotNull(window)
            // Capture only the foremost Coach dialog, which contains self-authored text.
            fun foremost(parent: java.awt.Window): ComposeDialog? = parent.ownedWindows
                .filter { it.isVisible }.lastOrNull()?.let { foremost(it) ?: it as? ComposeDialog }
            val dialog = foremost(w)
            if (dialog != null) { dialog.renderImmediately(); VerifyHooks.capture(dialog, evidence.resolve(name).toFile()) }
            else { w.renderImmediately(); VerifyHooks.capture(w, evidence.resolve(name).toFile()) }
        }
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 90_000_000_000L
        while (!condition()) { check(System.nanoTime() < deadline); Thread.sleep(100) }
    }
    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
