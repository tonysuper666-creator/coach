package com.coach.desktop.books

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.event.InputMethodEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.font.TextHitInfo
import java.nio.file.Files
import java.text.AttributedString
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * 只读探针（不改产品）：在自有隔离的 ComposeWindow 里宿主真实 TextbookWorkspace（简答输入控件 `short-answer`），
 * 往它的 SkiaLayer 派发 AWT 的 InputMethodEvent（Compose 桌面把系统 IME 的组合文本接进输入框的那条路径），
 * 记录组合中 Enter/Esc、提交后草稿（含落盘）、看原文再返回后的草稿。
 * 边界：这是合成的 AWT InputMethodEvent，不是 Microsoft 拼音/TSF 的真实 IME；真实 IME 会在 AWT 之前吞掉组合中的按键。
 */
@Suppress("OPT_IN_USAGE_ERROR", "OPT_IN_USAGE")
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ImeProbeTest {
    private fun log(s: String) = println("IME_PROBE $s")

    private fun tree(c: Component, depth: Int = 0, out: MutableList<String> = mutableListOf()): List<String> {
        out += "  ".repeat(depth) + c.javaClass.name + " " + c.width + "x" + c.height + " listeners(im=" + c.inputMethodListeners.size + ",key=" + c.keyListeners.size + ",mouse=" + c.mouseListeners.size + ")"
        if (c is Container) c.components.forEach { tree(it, depth + 1, out) }
        return out
    }

    /** 真正持有 InputMethodListener 的 AWT 组件（Compose 把 IME 监听挂在 SkiaLayer 上）。 */
    private fun layerOf(c: Container): Component? {
        for (ch in c.components) {
            if (ch.inputMethodListeners.isNotEmpty() && ch.mouseListeners.isNotEmpty() && ch.keyListeners.isNotEmpty()) return ch
            if (ch is Container) layerOf(ch)?.let { return it }
        }
        return null
    }

    @Test fun compositionEnterEscAndDraftRoundTrip() {
        assumeTrue(System.getenv("COACH_IME_PROBE") == "1")
        System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
        val root = Files.createTempDirectory("ime-probe-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(wordedChapterPdf(root))
        library.save(book.copy(learning = true, readingPage = 1, questionMode = QuestionMode.SHORT_ANSWER))
        val controller = TextbookController(library)
        log("env java=${System.getProperty("java.version")} os=${System.getProperty("os.name")} headless=${java.awt.GraphicsEnvironment.isHeadless()} render=${System.getProperty("skiko.renderApi")}")
        val ic = java.awt.im.InputContext.getInstance()
        log("inputContext locale=${ic.locale} selectChina=${runCatching { ic.selectInputMethod(java.util.Locale.CHINA) }.getOrNull()} im=${ic.javaClass.name}")

        lateinit var w: ComposeWindow
        EventQueue.invokeAndWait {
            w = ComposeWindow(); w.setSize(1044, 788); w.setLocation(48, 48); w.title = "ime-probe"
            w.setContent { TextbookWorkspace(controller) { null } }
            w.isVisible = true
        }
        try {
            fun nodes(): List<SemanticsNode> {
                val out = mutableListOf<SemanticsNode>()
                fun walk(n: SemanticsNode) { out += n; n.children.forEach { walk(it) } }
                w.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
                return out
            }
            fun tag(t: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == t }
            fun until(what: String, ms: Long = 30_000, c: () -> Boolean) {
                val end = System.currentTimeMillis() + ms
                while (!c()) { check(System.currentTimeMillis() < end) { "timeout: $what" }; Thread.sleep(50) }
            }
            until("ready") { !controller.state.value.busy && controller.state.value.book != null && tag("home-learn") != null }
            tree(w).forEach { log("tree $it") }
            val layer = checkNotNull(layerOf(w)) { "no component with an InputMethodListener" }
            log("window=${w.x},${w.y} ${w.width}x${w.height} focused=${w.isFocused} showing=${w.isShowing} layer=${layer.javaClass.name}")
            val scale = runCatching { layer.graphicsConfiguration.defaultTransform.scaleX }.getOrDefault(1.0)
            /** 点击/聚焦走 Compose 语义动作（OnClick / RequestFocus），不依赖 AWT 鼠标；IME 与按键才走 AWT 事件。 */
            fun clickNode(n: SemanticsNode) {
                val act = checkNotNull(n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.action) { "node has no OnClick" }
                EventQueue.invokeAndWait { act() }
                Thread.sleep(400)
            }
            fun click(t: String) {
                val n = checkNotNull(tag(t)) { "no node $t" }
                val focus = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)?.action
                if (t == "short-answer" && focus != null) { EventQueue.invokeAndWait { focus() }; Thread.sleep(400) } else clickNode(n)
            }
            fun ime(text: String, committed: Int) {
                val s = AttributedString(text)
                EventQueue.invokeAndWait {
                    layer.dispatchEvent(InputMethodEvent(layer, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, System.currentTimeMillis(),
                        s.iterator, committed, TextHitInfo.afterOffset(text.length), null))
                }
                Thread.sleep(300)
            }
            fun key(code: Int, ch: Char) {
                EventQueue.invokeAndWait {
                    val now = System.currentTimeMillis()
                    layer.dispatchEvent(KeyEvent(layer, KeyEvent.KEY_PRESSED, now, 0, code, ch))
                    layer.dispatchEvent(KeyEvent(layer, KeyEvent.KEY_RELEASED, now, 0, code, ch))
                }
                Thread.sleep(300)
            }
            fun field() = tag("short-answer")?.config?.getOrNull(SemanticsProperties.EditableText)?.text
            fun state(label: String) = log("$label | field=${field()?.replace("\n", "\\n")} | answer.text=${controller.state.value.answer.text.replace("\n", "\\n")} | revealed=${controller.state.value.answer.revealed} | screen=${controller.state.value.screen} | disk=${library.answer(book.id, controller.state.value.book!!.lessons[0].id, QuestionMode.SHORT_ANSWER).text.replace("\n", "\\n")}")

            click("home-learn")
            Thread.sleep(3000)
            log("after click: screen=${controller.state.value.screen} prompt=${controller.state.value.prompt} busy=${controller.state.value.busy} mode=${controller.state.value.book?.questionMode} tags=${nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }}")
            until("learning") { controller.state.value.screen == BookScreen.LEARNING && tag("short-answer") != null }
            click("short-answer")
            log("focused field = ${tag("short-answer")?.config?.getOrNull(SemanticsProperties.Focused)}")
            state("0 start")
            ime("ni", 0); state("1 composing 'ni' (uncommitted)")
            ime("nihao", 0); state("2 composing 'nihao'")
            key(KeyEvent.VK_ENTER, '\n'); state("3 AWT Enter KeyEvent while composing (real IME would eat this key)")
            ime("你好", 2); state("4 IME commit '你好'")
            ime("ma", 0); state("5 composing 'ma' after commit")
            key(KeyEvent.VK_ESCAPE, 27.toChar()); state("6 AWT Esc KeyEvent while composing (real IME would eat this key)")
            ime("吗", 1); state("7 IME commit '吗'")
            ImageIO.write(java.awt.Robot().createScreenCapture(java.awt.Rectangle(w.x, w.y, w.width, w.height)), "png", root.resolve("ime-after.png").toFile())
            click("open-lesson-source")
            until("source") { tag("remote-highlight") != null || controller.state.value.source != null }
            state("8 after opening source")
            val back = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "返回学习" } == true }
            if (back != null) {
                var target: SemanticsNode? = back
                while (target != null && target.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) == null) target = target.parent
                clickNode(checkNotNull(target) { "no clickable ancestor of 返回学习" })
                until("back") { controller.state.value.screen == BookScreen.LEARNING && tag("short-answer") != null }
            }
            state("9 after returning to the question")
            log("shot=${root.resolve("ime-after.png")}")
        } finally {
            EventQueue.invokeAndWait { w.isVisible = false; w.dispose() }
        }
    }
}
