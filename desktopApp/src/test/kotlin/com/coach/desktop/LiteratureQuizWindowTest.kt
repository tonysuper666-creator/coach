package com.coach.desktop

import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.coach.desktop.books.*
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Light and dark real windows for the literature multiple-choice set. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class LiteratureQuizWindowTest {
    @Test fun literatureQuizRepairSucceedsInLightWindow() = runWindow(PaperColor.LIGHT, failRepair = false)

    @Test fun literatureQuizRepairFailsInDarkWindow() = runWindow(PaperColor.DARK, failRepair = true)

    private fun runWindow(paper: PaperColor, failRepair: Boolean) {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("coach-quiz-window-")
        val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
        val books = (1..3).map { library.importBook(LiteratureGuideFixture.pdf(root, it)) }
        val course = library.withCourseDatabase { db ->
            CourseStore.createCourse(db, "文献选择题", CourseExamProfile(), books.map { it.id }).first
        }
        val controller = TextbookController(library)
        ReadingSettings(library.directory).choosePaper(paper)
        ReadingSettings(library.directory).chooseAiExplain(true)
        val guideBridge = LiteratureGuideFixture.Bridge()
        val prompts = listOf(
            "样地植物生长有何记录？",
            "遮阴是否让样本长得更快？",
            "不同样地的开花植物如何被记录？",
            "样本观察说明了什么生长差异？",
            "光照条件与生长速度有何关系？",
        )
        val explainer = object : BookExplainer(library = library) {
            override suspend fun load() { loaded = true }
            override suspend fun courseTopicPack(input: JsonObject) =
                CourseServerCallResult(guideBridge.service.generate(input, "local", "synthetic", "desktop"), null)
            override suspend fun literatureQuiz(input: JsonObject): CourseServerCallResult {
                val repair = (input["mode"] as? JsonPrimitive)?.contentOrNull == "repair"
                val body = when {
                    !repair -> quizJson(prompts.size, listOf(2, 2, 2, 2, 1))
                    failRepair -> buildJsonObject { put("items", JsonArray(emptyList())) }
                    else -> quizJson(prompts.size, listOf(0, 0, 0, 0, 1), nthOffset = 1)
                }
                return CourseServerCallResult(body, null)
            }
        }
        val window = edt {
            ComposeWindow().apply {
                title = "Coach · 文献选择题"
                setSize(1080, 780)
                setContent { WindowsTextbookHost(controller, bookExplainer = explainer) { null } }
                isVisible = true
            }
        }
        try {
            waitFor("library loaded", window) { controller.state.value.catalog.size == books.size }
            runBlocking { controller.openCourse(course) }
            click(window, "course-literature-guide")
            fun editAction(node: SemanticsNode): ((AnnotatedString) -> Boolean)? =
                node.config.getOrNull(SemanticsActions.SetText)?.action ?: node.children.firstNotNullOfOrNull(::editAction)
            waitFor("question field", window) { edt { node(window, "guide-questions")?.let(::editAction) != null } }
            edt { assertTrue(editAction(node(window, "guide-questions")!!)!!.invoke(AnnotatedString(prompts.joinToString("\n")))) }
            click(window, "guide-generate")
            waitFor("one-page guide", window, 90) { node(window, "guide-one-page") != null }
            assertNull(node(window, "guide-tab-1"))
            assertNull(node(window, "guide-tab-2"))
            if (failRepair) {
                waitFor("quiz gaps", window, 120) { node(window, "quiz-gaps") != null }
                val gaps = text(window, "quiz-gaps")
                assertTrue(gaps, "思考题 5" in gaps && "没有保存" in gaps)
                assertNull(node(window, "quiz-item-0"))
                val lit = books.map { library.literatureBook(it.id) }
                val key = LiteratureGuideStore.digest(LiteratureInput(lit, prompts).request())
                assertNull(library.withCourseDatabase { LiteratureQuizStore.cached(it, course, key) })
            } else {
                waitFor("quiz items", window, 120) { node(window, "quiz-item-0") != null }
                click(window, "quiz-choice-0-1")
                waitFor("explanation", window) { node(window, "quiz-explain-0") != null }
                val explanation = text(window, "quiz-explain-0")
                assertTrue(explanation, "对" in explanation && "错" in explanation && "出处" in explanation)
                val source = text(window, "quiz-source-0")
                val page = Regex("""PDF 第 (\d+) 页""").find(source)?.groupValues?.get(1)?.toInt()
                assertNotNull(source, page)
                click(window, "quiz-source-0")
                waitFor("jumped to the cited PDF page", window) {
                    val state = controller.state.value
                    state.screen == BookScreen.READER && state.page == page && state.pendingCourseJump == null
                }
                runBlocking { controller.back() }
                waitFor("guide restored", window) { node(window, "guide-one-page") != null && node(window, "quiz-item-0") != null }
                assertTrue(library.reviews().any { it.lesson.startsWith("litq-") })
            }
            val rendered = edt {
                nodes(window).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            }
            assertTrue(rendered.none { "印刷第" in it || "印刷页码" in it })
        } finally {
            guideBridge.close()
            edt { window.dispose() }
            root.toFile().deleteRecursively()
        }
    }

    private fun quizJson(questionCount: Int, counts: List<Int>, nthOffset: Int = 0) = buildJsonObject {
        put("items", buildJsonArray {
            for (question in 0 until questionCount) repeat(counts.getOrElse(question) { 0 }) { nth ->
                add(buildJsonObject {
                    put("questionIndex", question)
                    put("stem", "关于思考题${question + 1}的第${nth + nthOffset + 1}个判断，哪一项更贴近正文记录？")
                    put("options", buildJsonArray {
                        listOf(
                            Triple("生长记录支持这一判断。", true, "正确项对应正文里的观察记录。"),
                            Triple("样本观察与这一判断相反。", false, "此项把观察说成了相反结论。"),
                            Triple("保管流程解释了这一判断。", false, "此项改写成保管流程。"),
                            Triple("目录登记解释了这一判断。", false, "此项改写成目录登记。"),
                        ).forEach { (text, correct, why) ->
                            add(buildJsonObject {
                                put("text", text)
                                put("correct", correct)
                                put("why", why)
                                put("refs", buildJsonArray { add(JsonPrimitive(0)) })
                            })
                        }
                    })
                })
            }
        })
    }

    private fun nodes(window: ComposeWindow): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        val owners = window.semanticsOwners + ownedDialogs(window).flatMap { it.semanticsOwners }
        return owners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }

    private fun ownedDialogs(window: java.awt.Window): List<ComposeDialog> =
        window.ownedWindows.filter { it.isVisible }.flatMap { listOfNotNull(it as? ComposeDialog) + ownedDialogs(it) }

    private fun node(window: ComposeWindow, tag: String): SemanticsNode? =
        edt { nodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag } }

    private fun text(window: ComposeWindow, tag: String): String = edt {
        val target = requireNotNull(node(window, tag))
        val texts = target.config.getOrNull(SemanticsProperties.Text)
            ?: target.children.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
        texts.joinToString(" ") { it.text }
    }

    private fun click(window: ComposeWindow, tag: String) {
        waitFor(tag, window) { node(window, tag) != null }
        edt {
            val target = requireNotNull(node(window, tag))
            assertTrue(target.config[SemanticsActions.OnClick].action!!.invoke())
            runCatching { window.renderImmediately() }
        }
        Thread.sleep(40)
    }

    private fun waitFor(label: String, window: ComposeWindow, timeoutSeconds: Long = 30, condition: () -> Boolean) {
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
}
