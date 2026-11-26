package com.coach.desktop

import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.desktop.books.BookExplainer
import com.coach.desktop.books.BookScreen
import com.coach.desktop.books.CourseExamProfile
import com.coach.desktop.books.CourseServerCallResult
import com.coach.desktop.books.CourseStore
import com.coach.desktop.books.LiteratureComparisonInput
import com.coach.desktop.books.LiteratureComparisonStore
import com.coach.desktop.books.LiteratureGuideFixture
import com.coach.desktop.books.PaperColor
import com.coach.desktop.books.ReadingSettings
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.comparisonReply
import com.coach.server.LiteratureComparisonService
import com.sun.net.httpserver.HttpServer
import java.awt.GraphicsEnvironment
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real Compose windows for the literature comparison table.
 * The model is a local Haiku batch stand-in. Citation checks use the library OCR path.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class LiteratureComparisonWindowTest {
    @Test fun comparisonFromThreePdfsLight() = verify(PaperColor.LIGHT, reopen = true)

    @Test fun comparisonFromThreePdfsDark() = verify(PaperColor.DARK, reopen = false)

    private fun verify(paper: PaperColor, reopen: Boolean) {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("coach-comparison-window-")
        val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
        val books = (1..3).map { library.importBook(LiteratureGuideFixture.pdf(root, it)) }
        val course = library.withCourseDatabase { db ->
            CourseStore.createCourse(db, "文献对比", CourseExamProfile(), books.map { it.id }).first
        }
        library.withCourseDatabase { db ->
            db.prepareStatement("UPDATE collection_materials SET week_num=? WHERE collection_id=?").use { sql ->
                sql.setInt(1, 3)
                sql.setString(2, course)
                sql.executeUpdate()
            }
        }
        ReadingSettings(library.directory).choosePaper(paper)
        ReadingSettings(library.directory).chooseAiExplain(true)
        val bridge = ComparisonBridge()
        val explainer = object : BookExplainer(library = library) {
            override suspend fun load() { loaded = true }
            override suspend fun courseTopicPack(input: kotlinx.serialization.json.JsonObject) =
                CourseServerCallResult(bridge.service.generate(input, "local", "synthetic", "desktop"), null)
        }
        val controller = TextbookController(library)
        val window = openWindow(controller, explainer, "Coach · 文献对比 ${paper.name}")
        val claim = "窗口里改过的主张。"
        try {
            openComparison(window, controller, course, books.size)
            click(window, "comparison-week-3")
            waitFor("generate enabled", window) {
                node(window, "comparison-generate")?.config?.contains(SemanticsProperties.Disabled) == false
            }
            click(window, "comparison-generate")
            waitFor("comparison table", window, timeoutSeconds = 120) { node(window, "comparison-table") != null }
            assertEquals(1, bridge.keys.size)
            val firstId = books[0].id
            click(window, "comparison-source-$firstId-claim")
            waitFor("reader opened on the cited page", window) {
                val state = controller.state.value
                state.screen == BookScreen.READER && state.book?.id == firstId && state.page in 1..2
            }
            runBlocking { controller.back() }
            waitFor("comparison restored", window) { node(window, "comparison-table") != null }
            setText(window, "comparison-claim-$firstId", claim)
            waitFor("edited claim saved", window) { storedClaim(library, course, books.map { it.id }) == claim }
            click(window, "comparison-export")
            val digest = LiteratureComparisonStore.digest(books.map { it.id })
            val exported = library.directory.resolve("literature-comparisons").resolve(digest.take(12) + ".md")
            waitFor("markdown exported", window) {
                node(window, "comparison-markdown") != null && Files.isRegularFile(exported)
            }
            val markdown = Files.readString(exported)
            assertTrue(claim in markdown)
            assertTrue("PDF 第" in markdown)
            assertTrue(node(window, "comparison-outline") != null)
            if (!reopen) return
            edt { window.dispose() }
            val again = TextbookController(library)
            val second = openWindow(again, explainer, "Coach · 文献对比 再次打开")
            try {
                openComparison(second, again, course, books.size)
                waitFor("cached comparison", second) {
                    node(second, "comparison-table") != null && fieldText(second, "comparison-claim-$firstId") == claim
                }
                assertEquals(1, bridge.keys.size)
                waitFor("cached table says it was not regenerated", second) {
                    "没有重新生成" in allText(second)
                }
                click(second, "comparison-regenerate")
                waitFor("regenerated without reusing the cache", second, timeoutSeconds = 120) {
                    bridge.keys.size == 2 &&
                        node(second, "comparison-table") != null &&
                        node(second, "comparison-regenerate")?.config?.contains(SemanticsProperties.Disabled) == false &&
                        "没有重新生成" !in allText(second)
                }
            } finally {
                edt { second.dispose() }
            }
        } finally {
            bridge.close()
            edt { runCatching { window.dispose() } }
            root.toFile().deleteRecursively()
        }
    }

    private fun storedClaim(library: TextbookLibrary, course: String, ids: List<String>): String? {
        val lit = ids.map(library::literatureBook)
        val loaded = library.withCourseDatabase { db ->
            LiteratureComparisonStore.load(db, course, LiteratureComparisonStore.digest(ids))
        } ?: return null
        return LiteratureComparisonInput.restore(loaded.stored ?: return null, lit)
            ?.rows?.firstOrNull { it.bookId == ids.first() }?.claim?.text
    }

    private fun openComparison(window: ComposeWindow, controller: TextbookController, course: String, expectedBooks: Int) {
        waitFor("library loaded", window) { controller.state.value.catalog.size == expectedBooks }
        runBlocking { controller.openCourse(course) }
        click(window, "course-literature-comparison")
    }
}

private fun openWindow(controller: TextbookController, explainer: BookExplainer, title: String): ComposeWindow =
    edt { ComposeWindow().apply {
        this.title = title
        setSize(1080, 780)
        setContent { WindowsTextbookHost(controller, bookExplainer = explainer) { null } }
        isVisible = true
    } }

/** Local batch stand-in. The first response is complete; the service test covers the pending poll. */
private class ComparisonBridge : AutoCloseable {
    val keys = CopyOnWriteArrayList<String>()
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.reader(Charsets.UTF_8).readText()).jsonObject
            val headers = exchange.requestHeaders
            check(headers.getFirst("X-Coach-Task") == "literature_comparison")
            check(headers.getFirst("X-Coach-Background") == "1")
            check(request["model"] == JsonPrimitive(BridgeClient.HAIKU))
            keys.add(requireNotNull(headers.getFirst("X-Coach-Batch-Key")))
            val user = request.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content
            val reply = comparisonReply(Json.parseToJsonElement(user).jsonObject, outlineCount = 4)
            val body = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject { put("message", buildJsonObject { put("content", reply.toString()) }) })
                })
                put("x_coach", buildJsonObject {
                    put("status", "complete")
                    put("served_by", BridgeClient.HAIKU)
                    put("finish_reason", "stop")
                    put("physical_calls", 1)
                    put("fallback", false)
                })
            }
            val bytes = body.toString().toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val service = LiteratureComparisonService(
        BridgeClient(BridgeConfig("http://127.0.0.1:${http.address.port}", testTraffic = true)),
    )
    override fun close() { http.stop(0) }
}

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
private fun nodes(window: ComposeWindow): List<SemanticsNode> {
    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    return window.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
}

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
private fun node(window: ComposeWindow, tag: String): SemanticsNode? =
    edt { nodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag } }

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
private fun click(window: ComposeWindow, tag: String) {
    waitFor(tag, window) { node(window, tag) != null }
    edt {
        val target = requireNotNull(node(window, tag)) { "Missing node $tag" }
        assertTrue("Click failed on $tag", target.config[SemanticsActions.OnClick].action!!.invoke())
        runCatching { window.renderImmediately() }
    }
    Thread.sleep(40)
}

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
private fun setText(window: ComposeWindow, tag: String, value: String) {
    waitFor(tag, window) { node(window, tag) != null }
    edt {
        fun action(n: SemanticsNode): ((AnnotatedString) -> Boolean)? =
            n.config.getOrNull(SemanticsActions.SetText)?.action ?: n.children.firstNotNullOfOrNull(::action)
        val target = requireNotNull(node(window, tag)) { "Missing text field $tag" }
        val set = requireNotNull(action(target)) { "comparison field $tag" }
        assertTrue("SetText failed on $tag", set.invoke(AnnotatedString(value)))
        runCatching { window.renderImmediately() }
    }
    Thread.sleep(40)
}

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
private fun fieldText(window: ComposeWindow, tag: String): String = edt {
    fun from(n: SemanticsNode): String? =
        n.config.getOrNull(SemanticsProperties.EditableText)?.text ?: n.children.firstNotNullOfOrNull(::from)
    node(window, tag)?.let(::from).orEmpty()
}

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
private fun allText(window: ComposeWindow): String = edt {
    nodes(window).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString("\n") { it.text }
}

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
private fun waitFor(
    label: String,
    window: ComposeWindow,
    timeoutSeconds: Long = 20,
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
    while (true) {
        edt { runCatching { window.renderImmediately() } }
        if (condition()) return
        check(System.nanoTime() < deadline) {
            val tags = runCatching {
                edt { nodes(window).mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.distinct().take(40) }
            }.getOrNull()
            "Timed out waiting for: $label; tags=$tags"
        }
        Thread.sleep(40)
    }
}

private fun <T> edt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}
