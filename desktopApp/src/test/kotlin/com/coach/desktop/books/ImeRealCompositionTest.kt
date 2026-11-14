package com.coach.desktop.books

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assume.assumeTrue
import java.awt.EventQueue
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.KeyEvent
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * TON-1431 IME 门：同一进程内先 TSF ActivateProfile(微软拼音, FORPROCESS)，再用 java.awt.Robot（系统 SendInput 级真实按键）
 * 往 Coach 自己窗口里的真实简答控件 `short-answer` 打字，让系统 IME 真实产生组合；记录组合中 Enter/Esc、确定后的草稿/落盘、
 * 看原文再返回后的草稿，以及探针前后（独立进程读数）用户的活动 profile。
 * 安全：必须在活动控制台会话（非 Session 0）；每次按键前核对前台窗口就是本进程这个 HWND，否则立刻停止所有按键并退出；
 * 只截本窗口；不做全局输入法切换（FORPROCESS）。不使用合成 InputMethodEvent，也不直接写状态。
 */
@Suppress("OPT_IN_USAGE_ERROR", "OPT_IN_USAGE")
class ImeRealCompositionTest {
    private fun log(s: String) { println("IME_REAL $s"); report.appendText(s + "\n") }
    private val outDir = File("build/ime-real").also { it.mkdirs() }
    private val report = File(outDir, "report.txt").also { it.writeText("") }

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


    private fun hkl(v: Long) = "0x" + java.lang.Long.toHexString(v and 0xFFFFFFFFL).padStart(8, '0')

    private class Abort(msg: String) : RuntimeException(msg)

    private fun readProfile(): String {
        val script = listOf("desktopApp/src/test/kotlin/com/coach/desktop/books/ime_active_profile.py", "src/test/kotlin/com/coach/desktop/books/ime_active_profile.py").map { File(it) }.first { it.exists() }.absolutePath
        val p = ProcessBuilder("python", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim(); val code = p.waitFor()
        return "$out (exit=$code)"
    }

    @Test fun realMicrosoftPinyinCompositionInTheShortAnswerControl() {
        assumeTrue(System.getenv("COACH_IME_REAL") == "1")
        val n = Native(jnaLoader())
        val k = "kernel32"; val u = "user32"
        val pid = n.call(k, "GetCurrentProcessId")
        val sb = n.memory(8); n.call(k, "ProcessIdToSessionId", pid.toInt(), sb)
        val session = sb.javaClass.getMethod("getInt", java.lang.Long.TYPE).invoke(sb, 0L) as Int
        val consoleSession = n.call(k, "WTSGetActiveConsoleSessionId").toInt()
        log("process pid=$pid session=$session activeConsole=$consoleSession java=${System.getProperty("java.version")} headless=${java.awt.GraphicsEnvironment.isHeadless()} render=${System.getProperty("skiko.renderApi")}")
        if (session == 0 || session != consoleSession) throw Abort("not running in the active console session; no key was sent")

        val profileBefore = readProfile()
        log("user active profile BEFORE (separate process): $profileBefore")

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
            fun until(what: String, ms: Long = 30_000, c: () -> Boolean) {
                val end = System.currentTimeMillis() + ms
                while (!c()) { if (System.currentTimeMillis() > end) throw Abort("timeout: $what"); Thread.sleep(50) }
            }
            val hwnd = w.windowHandle
            val ownerBuf = n.memory(8)
            n.call(u, "GetWindowThreadProcessId", hwnd, ownerBuf)
            until("ready") { !controller.state.value.busy && controller.state.value.book != null && tag("home-learn") != null }
            fun semanticClick(t: String) {
                val node = checkNotNull(tag(t)) { "no node $t" }
                var cur: SemanticsNode? = node
                while (cur != null && cur.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) == null) cur = cur.parent
                val act = checkNotNull(cur?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.action)
                EventQueue.invokeAndWait { act() }; Thread.sleep(500)
            }
            semanticClick("home-learn")
            until("learning") { controller.state.value.screen == BookScreen.LEARNING && tag("short-answer") != null }

            // ---- 前台守卫：每次按键前都核对前台窗口就是本进程这个 HWND，否则停键 ----
            fun foreground(): Pair<Long, Long> {
                val fg = n.call(u, "GetForegroundWindow")
                val b = n.memory(8); n.call(u, "GetWindowThreadProcessId", fg, b)
                return fg to (b.javaClass.getMethod("getInt", java.lang.Long.TYPE).invoke(b, 0L) as Int).toLong()
            }
            fun guard(label: String) {
                repeat(3) {
                    val (fg, fpid) = foreground()
                    if (fg == hwnd && fpid == pid) return
                    EventQueue.invokeAndWait { w.toFront(); w.requestFocus() }
                    n.call(u, "SetForegroundWindow", hwnd); Thread.sleep(300)
                }
                val (fg, fpid) = foreground()
                if (fg == hwnd && fpid == pid) return
                throw Abort("foreground is hwnd=0x${java.lang.Long.toHexString(fg)} pid=$fpid, not ours (0x${java.lang.Long.toHexString(hwnd)}/$pid) before '$label'; keys stopped (sent=$keysSent)")
            }
            fun tap(vk: Int, label: String) { guard(label); robot.keyPress(vk); Thread.sleep(40); robot.keyRelease(vk); keysSent++; Thread.sleep(160) }
            fun letters(s: String) { for (c in s) tap(KeyEvent.getExtendedKeyCodeForChar(c.code), "letter $c") ; Thread.sleep(500) }

            // ---- 同一进程内先选微软拼音（进程级），读活动 profile ----
            val CLSID_PROFILES = n.guid("{33C53A50-F456-4884-B049-85FD643ECFED}"); val IID_MGR = n.guid("{71C6E74C-0F28-11D8-A82A-00065B84435C}")
            val CAT = n.guid("{34745C63-B2F0-4784-8B67-5E12C8701A31}")
            var mgr: Any? = null; var activate = -1
            fun activeInProcess(): String {
                var r = ""
                EventQueue.invokeAndWait {
                    val st = n.memory(128); val hr = n.vcall(mgr!!, 10, CAT, st)
                    val lang = st.javaClass.getMethod("getShort", java.lang.Long.TYPE).invoke(st, 4L) as Short
                    r = "hr=0x${Integer.toHexString(hr)} langid=0x${"%04x".format(lang)} clsid=${n.guidText(st, 8)} profile=${n.guidText(st, 24)} hkl=${hkl(n.memLong(st, 72L))}"
                }
                return r
            }
            EventQueue.invokeAndWait {
                n.call("ole32", "CoInitializeEx", null, 2)
                val o = n.memory(8); val cr = n.call("ole32", "CoCreateInstance", CLSID_PROFILES, null, 1, IID_MGR, o).toInt()
                check(cr == 0) { "CoCreateInstance=0x${Integer.toHexString(cr)}" }
                mgr = n.pointerAt(o, 0L)
                // TF_IPPMF_FORPROCESS(0x40000000)|DONTCARECURRENTINPUTLANGUAGE(1)；微软拼音 CLSID/profile
                activate = n.vcall(mgr!!, 3, 1, 0x0804, n.guid("{81D4E9C9-1D3B-41BC-9E6C-4B40BF79E35E}"), n.guid("{FA550B04-5AD7-411F-A5AC-CA038EC515D7}"), 0L, 0x40000001)
            }
            log("window hwnd=0x${java.lang.Long.toHexString(hwnd)} ownerPid=$pid; ActivateProfile(MSPinyin,FORPROCESS)=0x${Integer.toHexString(activate)}")
            if (activate != 0) throw Abort("ActivateProfile failed; no key was sent")
            log("in-process active profile after activate: ${activeInProcess()}")

            val lessonId = controller.state.value.book!!.lessons[0].id
            fun field() = tag("short-answer")?.config?.getOrNull(SemanticsProperties.EditableText)?.text
            fun disk() = library.answer(book.id, lessonId, QuestionMode.SHORT_ANSWER).text
            fun esc(s: String?) = (s ?: "null").replace("\n", "\\n")
            fun state(label: String) {
                val (fg, fpid) = foreground()
                log("$label | field=${esc(field())} | answer=${esc(controller.state.value.answer.text)} | disk=${esc(disk())} | revealed=${controller.state.value.answer.revealed} | screen=${controller.state.value.screen} | fg=0x${java.lang.Long.toHexString(fg)}/pid$fpid | tip=${activeInProcess()}")
            }
            fun shot(name: String) {
                guard("screenshot $name")
                ImageIO.write(robot.createScreenCapture(Rectangle(w.x, w.y, w.width, w.height)), "png", File(outDir, "$name.png"))
                log("screenshot $name.png (own window rect only)")
            }
            fun focusField() { val f = checkNotNull(tag("short-answer")?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)?.action); EventQueue.invokeAndWait { f() }; Thread.sleep(400) }
            fun clear() { guard("clear"); robot.keyPress(KeyEvent.VK_CONTROL); robot.keyPress(KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_CONTROL); keysSent += 2; Thread.sleep(150); tap(KeyEvent.VK_BACK_SPACE, "clear"); Thread.sleep(300) }
            fun hasCjk(s: String?) = s?.any { it.code in 0x4E00..0x9FFF } == true

            guard("start"); focusField(); state("0 start (field focused via semantics; keys below are real Robot/SendInput)")
            // S1：组合 nihao，空格选第一候选
            letters("nihao"); state("1 typed n-i-h-a-o (system IME composing?)"); shot("1-composing-nihao")
            tap(KeyEvent.VK_SPACE, "space"); Thread.sleep(500); state("2 Space (select first candidate)")
            if (!hasCjk(field())) {
                log("no CJK produced -> IME probably in English mode; tapping Shift once (IME mode toggle) and retrying once")
                clear(); tap(KeyEvent.VK_SHIFT, "shift toggle"); Thread.sleep(400)
                letters("nihao"); state("2a retry typed n-i-h-a-o"); tap(KeyEvent.VK_SPACE, "space"); Thread.sleep(500); state("2b retry Space")
            }
            val afterS1 = field()
            // S2：组合中 Enter
            letters("nihao"); state("3 composing again"); tap(KeyEvent.VK_ENTER, "enter"); Thread.sleep(500); state("4 Enter while composing")
            // S3：组合中 Esc
            letters("ma"); state("5 composing 'ma'"); shot("5-composing-ma"); tap(KeyEvent.VK_ESCAPE, "esc"); Thread.sleep(500); state("6 Esc while composing"); shot("6-after-esc")
            tap(KeyEvent.VK_ESCAPE, "esc2"); Thread.sleep(400); state("7 second Esc (no composition left)")
            // S4：看原文再返回
            semanticClick("open-lesson-source")
            until("source") { tag("remote-highlight") != null || controller.state.value.source != null }
            state("8 after opening the source page")
            val back = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "返回学习" } == true }
            if (back != null) {
                var cur: SemanticsNode? = back
                while (cur != null && cur.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) == null) cur = cur.parent
                val act = checkNotNull(cur?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.action)
                EventQueue.invokeAndWait { act() }
                until("back") { controller.state.value.screen == BookScreen.LEARNING && tag("short-answer") != null }
            }
            state("9 after returning to the question"); shot("9-after-return")
            log("keysSent=$keysSent  S1 result=${esc(afterS1)}")
        } catch (a: Abort) {
            log("ABORT: ${a.message}")
        } finally {
            EventQueue.invokeAndWait { w.isVisible = false; w.dispose() }
            log("user active profile AFTER (separate process): ${readProfile()}")
            log("user active profile BEFORE was                : $profileBefore")
        }
    }
}
