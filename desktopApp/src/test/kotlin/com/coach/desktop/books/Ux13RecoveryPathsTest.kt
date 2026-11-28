package com.coach.desktop.books

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.WindowsTextbookHost
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * TON-1995: Synthetic verification for UX-13 plain copy and non-modal clickable recovery entries
 * across reader disconnected, literature guide unauthorized, and literature comparison unauthorized.
 */
@OptIn(ExperimentalTestApi::class)
class Ux13RecoveryPathsTest {
    private val banned = listOf(
        "Anthropic", "OpenAI", "Gemini", "Claude", "DeepSeek", "Grok", "ChatGPT",
        "Mac", "winbox", "朋友服务",
        "HTTP 4", "HTTP 5", "traceId",
        "智能", "小助手", "✨",
    )
    private val bannedAiWord = Regex("(?<![A-Za-z0-9_])AI(?![A-Za-z0-9_])")

    @Test
    fun readerDisconnectedShowsPlainCopyAndClickableRecoveryWithoutModal() {
        val root = Files.createTempDirectory("coach-ton1995-reader-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val md = root.resolve("synthetic-book.md")
            Files.writeString(
                md,
                """
                # Chapter 1 Principles

                Paragraph one explains marginal incentives and equilibrium adjustment clearly for readers.

                Paragraph two compares opportunity cost across two synthetic examples.
                """.trimIndent(),
            )
            val book = library.importBook(md)
            val controller = TextbookController(library)
            val explainer = object : BookExplainer(DesktopConnectionSettings(root.resolve("conn"), { null }), library) {
                init {
                    identity = null
                    loaded = true
                    revoked = true
                }

                override suspend fun load() {
                    loaded = true
                }
            }
            val reloginCalls = mutableListOf<String?>()
            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                setContent {
                    CompositionLocalProvider(LocalBookRelogin provides { reason -> reloginCalls += reason }) {
                        WindowsTextbookHost(controller, bookExplainer = explainer) { null }
                    }
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.catalog.size == 1 }
                runBlocking { controller.openBook(book.id, 1) }
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("reader-revoked-notice").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("revoked-notice-reconnect").assertHasClickAction()
                assertZeroModalsAndNoBannedCopy("阅读器顶部断线提示")

                onNodeWithTag("revoked-notice-dismiss").performClick()
                waitForIdle()

                selectPhrase(this, "marginal incentives")
                onNodeWithTag("selection-action-explain").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("inline-explain-error").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("inline-explain-error")
                    .assertTextContains("连接已断开", substring = true)
                    .assertTextContains("重新登录", substring = true)
                onNodeWithTag("inline-explain-relogin").assertIsDisplayed().assertHasClickAction()
                assertZeroModalsAndNoBannedCopy("阅读器选段讲解断线提示")
                capture("reader-disconnected")

                onNodeWithTag("inline-explain-relogin").performClick()
                waitForIdle()
                assertEquals(1, reloginCalls.size)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun literatureGuideUnauthorizedShowsPlainCopyAndInlineRecoveryContinues() {
        val root = Files.createTempDirectory("coach-ton1995-guide-")
        try {
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
            val books = (1..3).map { library.importBook(LiteratureGuideFixture.pdf(root, it)) }
            val course = library.withCourseDatabase { db ->
                CourseStore.createCourse(db, "合成文献导读课", CourseExamProfile(), books.map { it.id }).first
            }
            ReadingSettings(library.directory).chooseAiExplain(false)
            var serverCalls = 0
            val explainer = object : BookExplainer(library = library) {
                override suspend fun load() {
                    loaded = true
                }

                override suspend fun courseTopicPack(input: JsonObject): CourseServerCallResult {
                    serverCalls++
                    return CourseServerCallResult(LiteratureGuideFixture.reply(input), null)
                }

                override suspend fun literatureQuiz(input: JsonObject): CourseServerCallResult =
                    CourseServerCallResult(buildJsonObject { put("items", JsonArray(emptyList())) }, null)
            }
            val controller = TextbookController(library)
            runSkikoComposeUiTest(size = Size(1280f, 820f), density = Density(1f)) {
                setContent {
                    WindowsTextbookHost(controller, bookExplainer = explainer) { null }
                }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.catalog.size == books.size }
                runBlocking { controller.openCourse(course) }
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("course-literature-guide").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("course-literature-guide").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("guide-questions").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("guide-questions").performTextInput("样地植物生长有何记录？")
                waitUntil(timeoutMillis = 10_000) {
                    onNodeWithTag("guide-generate").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
                }
                onNodeWithTag("guide-generate").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("guide-allow-explain").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(0, serverCalls)
                onNodeWithTag("guide-notice")
                    .assertTextContains("伴读讲解与提问已关闭", substring = true)
                    .assertTextContains("开启后可继续生成导读", substring = true)
                onNodeWithTag("guide-allow-explain").assertIsDisplayed().assertHasClickAction()
                assertZeroModalsAndNoBannedCopy("文献导读未授权提示")
                capture("guide-unauthorized")

                onNodeWithTag("guide-allow-explain").performClick()
                waitUntil(timeoutMillis = 30_000) {
                    onAllNodesWithTag("guide-one-page").fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue(ReadingSettings(library.directory).storedAiExplainEnabled())
                assertTrue(serverCalls >= 1)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun literatureComparisonUnauthorizedShowsPlainCopyAndInlineRecoveryContinues() {
        val root = Files.createTempDirectory("coach-ton1995-comparison-")
        try {
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
            val books = (1..3).map { library.importBook(LiteratureGuideFixture.pdf(root, it)) }
            val course = library.withCourseDatabase { db ->
                CourseStore.createCourse(db, "合成文献对比课", CourseExamProfile(), books.map { it.id }).first
            }
            library.withCourseDatabase { db ->
                db.prepareStatement("UPDATE collection_materials SET week_num=? WHERE collection_id=?").use { sql ->
                    sql.setInt(1, 3)
                    sql.setString(2, course)
                    sql.executeUpdate()
                }
            }
            ReadingSettings(library.directory).chooseAiExplain(false)
            var serverCalls = 0
            val explainer = object : BookExplainer(library = library) {
                override suspend fun load() {
                    loaded = true
                }

                override suspend fun courseTopicPack(input: JsonObject): CourseServerCallResult {
                    serverCalls++
                    assertEquals("literatureComparison", input["kind"]?.jsonPrimitive?.content)
                    return CourseServerCallResult(comparisonReply(input, outlineCount = 3), null)
                }
            }
            val controller = TextbookController(library)
            runSkikoComposeUiTest(size = Size(1280f, 820f), density = Density(1f)) {
                setContent {
                    WindowsTextbookHost(controller, bookExplainer = explainer) { null }
                }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.catalog.size == books.size }
                runBlocking { controller.openCourse(course) }
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("course-literature-comparison").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("course-literature-comparison").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("comparison-week-3").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("comparison-week-3").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onNodeWithTag("comparison-generate").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
                }
                onNodeWithTag("comparison-generate").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("comparison-allow-explain").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(0, serverCalls)
                onNodeWithTag("comparison-notice")
                    .assertTextContains("伴读讲解与提问已关闭", substring = true)
                    .assertTextContains("开启后可继续生成对比表", substring = true)
                onNodeWithTag("comparison-allow-explain").assertIsDisplayed().assertHasClickAction()
                assertZeroModalsAndNoBannedCopy("文献对比未授权提示")
                capture("comparison-unauthorized")

                onNodeWithTag("comparison-allow-explain").performClick()
                waitUntil(timeoutMillis = 30_000) {
                    onAllNodesWithTag("comparison-table").fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue(ReadingSettings(library.directory).storedAiExplainEnabled())
                assertEquals(1, serverCalls)
                onNodeWithTag("comparison-outline").assertExists()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun selectPhrase(scope: SkikoComposeUiTest, phrase: String) {
        val textNode = scope.onNode(hasTestTag("reader-text") and hasText(phrase, substring = true))
        val layouts = mutableListOf<TextLayoutResult>()
        textNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val idx = layout.layoutInput.text.text.indexOf(phrase)
        assertTrue(idx >= 0, "Expected phrase '$phrase' in reader-text")
        val firstBox = layout.getBoundingBox(idx)
        val lastBox = layout.getBoundingBox(idx + phrase.lastIndex)
        val coords = textNode.fetchSemanticsNode().layoutInfo.coordinates
        val startRoot = coords.localToRoot(Offset(firstBox.left + 2f, firstBox.center.y))
        val endRoot = coords.localToRoot(Offset(lastBox.right - 2f, lastBox.center.y))
        scope.onRoot().performMouseInput {
            moveTo(startRoot)
            press()
            moveTo((startRoot + endRoot) / 2f)
            moveTo(endRoot)
            release()
        }
        scope.waitUntil(timeoutMillis = 10_000) {
            scope.onAllNodesWithTag("selection-explain-toolbar").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeUiTest.assertZeroModalsAndNoBannedCopy(scene: String) {
        val allNodes = onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(
            allNodes.none { SemanticsProperties.IsDialog in it.config || SemanticsProperties.IsPopup in it.config },
            "$scene 不应弹出模态框或浮层",
        )
        val texts = allNodes.flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }
        for (text in texts) {
            for (term in banned) {
                assertFalse(term in text, "$scene 出现禁用词「$term」：$text")
            }
            assertFalse(bannedAiWord.containsMatchIn(text), "$scene 出现禁用词「AI」：$text")
        }
    }

    private fun ComposeUiTest.capture(name: String) {
        val out = Path.of("build/screens/ton1995/$name.png")
        Files.createDirectories(out.parent)
        val image = (this as SkikoComposeUiTest).captureToImage().toAwtImage()
        assertTrue(ImageIO.write(image, "png", out.toFile()))
    }
}
