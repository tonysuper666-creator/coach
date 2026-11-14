package com.coach.desktop.books

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assume.assumeTrue
import java.awt.EventQueue
import java.awt.Robot
import java.awt.event.KeyEvent
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.*

/**
 * 回归：学生还在选词时，未上屏的拼音不能被当成已作答写进草稿。
 * 用 java.awt.Robot（系统 SendInput 级真实按键）在活动控制台会话里驱动 Coach 自己窗口里的真实简答控件 `short-answer`，
 * 由**用户当前已有的微软拼音**真实产生组合；断言：组合中 答案/新实例读盘 均不含拼音，上屏后才有「你好」，取消组合后不含残留拼音。
 * 本测试从不调用 ActivateProfile、不切换输入法/输入模式、不抢前台窗口；前置条件不满足（非活动控制台、当前活动 profile 不是微软拼音、
 * 失去前台）一律让测试失败，而不是静默通过。仅在 COACH_IME_REAL=1 时运行。
 */
@Suppress("OPT_IN_USAGE_ERROR", "OPT_IN_USAGE")
class ImeRealCompositionTest {
    private fun log(s: String) = println("IME_REAL $s")
    private val msPinyinClsid = "81D4E9C9-1D3B-41BC-9E6C-4B40BF79E35E"

    private fun jnaLoader(): ClassLoader {
        val home = System.getenv("GRADLE_USER_HOME") ?: (System.getProperty("user.home") + "/.gradle")
        val jar = File(home, "caches/modules-2/files-2.1/net.java.dev.jna/jna").walkTopDown().filter { it.name == "jna-5.17.0.jar" }.first()
        log("jna=${jar.name}")
        return URLClassLoader(arrayOf(jar.toURI().toURL()), ClassLoader.getPlatformClassLoader())
    }

    private class Native(val loader: ClassLoader) {
        private val fnClass = Class.forName("com.sun.jna.Function", true, loader)
        private val getFn = fnClass.getMethod("getFunction", String::class.java, String::class.java)
        private val invoke = fnClass.getMethod("invoke", Class::class.java, Array<Any>::class.java)
        fun call(lib: String, name: String, vararg a: Any?): Long {
            val f = getFn.invoke(null, lib, name)
            val r = invoke.invoke(f, java.lang.Long.TYPE, arrayOf(*a)) as Long
            return r
        }
        fun callW(lib: String, name: String, vararg a: Any?): Long { // 宽字符串版本：Function.ALT_CONVENTION 不需要，W 后缀函数自己取 WString
            val f = getFn.invoke(null, lib, name)
            val wstr = Class.forName("com.sun.jna.WString", true, loader)
            val args = a.map { if (it is String) wstr.getConstructor(String::class.java).newInstance(it) else it }.toTypedArray()
            return invoke.invoke(f, java.lang.Long.TYPE, args) as Long
        }
        private val ptrClass = Class.forName("com.sun.jna.Pointer", true, loader)
        private val fnPtr = fnClass.getMethod("getFunction", ptrClass, Integer.TYPE)
        fun guid(text: String): Any {
            val hex = text.removePrefix("{").removeSuffix("}").replace("-", "")
            val b = ByteArray(16)
            fun h(i: Int) = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            // Data1(4) Data2(2) Data3(2) 小端；Data4(8) 原序
            for (i in 0 until 4) b[i] = h(3 - i); for (i in 0 until 2) { b[4 + i] = h(5 - i); b[6 + i] = h(7 - i) }; for (i in 8 until 16) b[i] = h(i)
            val m = memory(16); m.javaClass.getMethod("write", java.lang.Long.TYPE, ByteArray::class.java, Integer.TYPE, Integer.TYPE).invoke(m, 0L, b, 0, 16); return m
        }
        fun guidText(m: Any, off: Long): String {
            val bytes = ByteArray(16); m.javaClass.getMethod("read", java.lang.Long.TYPE, ByteArray::class.java, Integer.TYPE, Integer.TYPE).invoke(m, off, bytes, 0, 16)
            fun x(i: Int) = "%02X".format(bytes[i])
            return "{" + (3 downTo 0).joinToString("") { x(it) } + "-" + x(5) + x(4) + "-" + x(7) + x(6) + "-" + x(8) + x(9) + "-" + (10..15).joinToString("") { x(it) } + "}"
        }
        fun pointerAt(m: Any, off: Long): Any? = m.javaClass.getMethod("getPointer", java.lang.Long.TYPE).invoke(m, off)
        /** 调 COM 接口的第 index 个虚函数（含 this），返回 HRESULT。 */
        fun vcall(obj: Any, index: Int, vararg a: Any?): Int {
            val vtbl = pointerAt(obj, 0L)!!
            val f = pointerAt(vtbl, 8L * index)!!
            val fn = fnPtr.invoke(null, f, 0)
            return fnClass.getMethod("invokeInt", Array<Any>::class.java).invoke(fn, arrayOf(obj, *a)) as Int
        }
        fun memory(n: Long): Any = Class.forName("com.sun.jna.Memory", true, loader).getConstructor(java.lang.Long.TYPE).newInstance(n)
        fun memLong(m: Any, off: Long): Long = m.javaClass.getMethod("getLong", java.lang.Long.TYPE).invoke(m, off) as Long
    }



    private fun readProfile(): String {
        val script = listOf("desktopApp/src/test/kotlin/com/coach/desktop/books/ime_active_profile.py", "src/test/kotlin/com/coach/desktop/books/ime_active_profile.py").map { File(it) }.first { it.exists() }.absolutePath
        val p = ProcessBuilder("python", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim(); val code = p.waitFor()
        assertEquals(0, code, "profile reader failed: $out")
        return out
    }

    @Test fun uncommittedPinyinIsNeverWrittenIntoTheShortAnswerDraft() {
        assumeTrue(System.getenv("COACH_IME_REAL") == "1")
        val n = Native(jnaLoader())
        val k = "kernel32"; val u = "user32"
        val pid = n.call(k, "GetCurrentProcessId")
        val sb = n.memory(8); n.call(k, "ProcessIdToSessionId", pid.toInt(), sb)
        val session = sb.javaClass.getMethod("getInt", java.lang.Long.TYPE).invoke(sb, 0L) as Int
        val consoleSession = n.call(k, "WTSGetActiveConsoleSessionId").toInt()
        log("process pid=$pid session=$session activeConsole=$consoleSession java=${System.getProperty("java.version")} render=${System.getProperty("skiko.renderApi")}")
        assertTrue(session != 0 && session == consoleSession, "must run in the active console session (got session=$session console=$consoleSession); no key was sent")
        val profile = readProfile()
        log("active profile (read-only, separate process): $profile")
        assertTrue(profile.uppercase().contains(msPinyinClsid), "precondition: the current active profile is not Microsoft Pinyin; this test never activates or switches an input method")

        val root = Files.createTempDirectory("ime-real-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(wordedChapterPdf(root))
        library.save(book.copy(learning = true, readingPage = 1, questionMode = QuestionMode.SHORT_ANSWER))
        val controller = TextbookController(library)
        lateinit var w: ComposeWindow
        EventQueue.invokeAndWait {
            w = ComposeWindow(); w.setSize(1044, 788); w.setLocation(48, 48); w.title = "coach-ime-real"
            w.setContent { TextbookWorkspace(controller) { null } }
            w.isVisible = true
        }
        val robot = Robot(); robot.autoDelay = 0
        var keysSent = 0
        try {
            fun nodes(): List<SemanticsNode> {
                val out = mutableListOf<SemanticsNode>()
                fun walk(x: SemanticsNode) { out += x; x.children.forEach { walk(it) } }
                w.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
                return out
            }
            fun tag(t: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == t }
            fun until(what: String, ms: Long = 15_000, c: () -> Boolean) {
                val end = System.currentTimeMillis() + ms
                while (!c()) { assertTrue(System.currentTimeMillis() <= end, "timeout waiting for: $what (keysSent=$keysSent)"); Thread.sleep(50) }
            }
            val hwnd = w.windowHandle
            until("ready") { !controller.state.value.busy && controller.state.value.book != null && tag("home-learn") != null }
            fun click(t: String) {
                var cur: SemanticsNode? = checkNotNull(tag(t)) { "no node $t" }
                while (cur != null && cur.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) == null) cur = cur.parent
                val act = checkNotNull(cur?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.action)
                EventQueue.invokeAndWait { act() }; Thread.sleep(500)
            }
            click("home-learn")
            until("learning") { controller.state.value.screen == BookScreen.LEARNING && tag("short-answer") != null }

            // 前台必须已经是本进程这个窗口（只观察，不去抢）；每次按键前再核对，不满足立即失败（AssertionError，不吞）
            fun ownsForeground(): Boolean {
                val fg = n.call(u, "GetForegroundWindow")
                val b = n.memory(8); n.call(u, "GetWindowThreadProcessId", fg, b)
                return fg == hwnd && (b.javaClass.getMethod("getInt", java.lang.Long.TYPE).invoke(b, 0L) as Int).toLong() == pid
            }
            until("our window is the foreground window (not forced by this test)", 10_000) { ownsForeground() }
            fun tap(vk: Int, label: String) {
                assertTrue(ownsForeground(), "foreground is no longer this Coach window before '$label'; keys stopped (keysSent=$keysSent)")
                robot.keyPress(vk); Thread.sleep(40); robot.keyRelease(vk); keysSent++; Thread.sleep(160)
            }
            fun letters(s: String) { for (c in s) tap(KeyEvent.getExtendedKeyCodeForChar(c.code), "letter $c"); Thread.sleep(500) }

            val lessonId = controller.state.value.book!!.lessons[0].id
            fun field() = tag("short-answer")?.config?.getOrNull(SemanticsProperties.EditableText)?.text
            fun answer() = controller.state.value.answer.text
            fun disk() = TextbookLibrary(library.directory).answer(book.id, lessonId, QuestionMode.SHORT_ANSWER).text // 新实例，真读盘
            fun snap(label: String) = log("$label | field=${field()} | answer=${answer()} | disk(new instance)=${disk()} | revealed=${controller.state.value.answer.revealed} | screen=${controller.state.value.screen} | keysSent=$keysSent")
            val focus = checkNotNull(tag("short-answer")?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)?.action)
            EventQueue.invokeAndWait { focus() }; Thread.sleep(400)
            snap("0 start")
            assertEquals("", answer()); assertEquals("", disk())

            // a) 选词中：输入框是系统 IME 的组合串，答案与读盘都不得含拼音
            letters("nihao")
            snap("1 composing n-i-h-a-o")
            assertEquals("ni'hao", field(), "the system IME must be composing (field shows the composition string)")
            assertEquals("", answer(), "uncommitted pinyin leaked into the answer")
            assertEquals("", disk(), "uncommitted pinyin leaked into the persisted draft")
            // b) 选词上屏
            tap(KeyEvent.VK_SPACE, "space"); Thread.sleep(500)
            snap("2 Space selects the first candidate")
            assertEquals("你好", field()); assertEquals("你好", answer()); assertEquals("你好", disk())
            // c) 再次组合：输入框含拼音，答案/读盘仍只有已上屏的「你好」
            letters("nihao")
            snap("3 composing again")
            assertEquals("你好ni'hao", field())
            assertEquals("你好", answer(), "uncommitted pinyin leaked into the answer"); assertEquals("你好", disk(), "uncommitted pinyin leaked into the persisted draft")
            // d) 取消组合：不残留拼音
            tap(KeyEvent.VK_ESCAPE, "esc"); Thread.sleep(500)
            snap("4 Esc cancels the composition")
            assertEquals("你好", field()); assertEquals("你好", answer()); assertEquals("你好", disk())
            assertFalse(controller.state.value.answer.revealed); assertEquals(BookScreen.LEARNING, controller.state.value.screen)
        } finally {
            EventQueue.invokeAndWait { w.isVisible = false; w.dispose() }
        }
    }
}
