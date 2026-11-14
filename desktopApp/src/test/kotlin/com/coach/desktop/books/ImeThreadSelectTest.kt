package com.coach.desktop.books

import androidx.compose.ui.awt.ComposeWindow
import org.junit.Assume.assumeTrue
import java.awt.EventQueue
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.*

/**
 * 只做一件事（TON-1431 IME 门的最后一刀）：在 Coach 自己的 UI 线程（承载真实 TextbookWorkspace 的 ComposeWindow 的 AWT 事件线程）里
 * 调 TSF ActivateProfile 选择微软拼音（标志 TF_IPPMF_FORPROCESS：**进程级**，不是线程级、也不是全局；不写注册表/系统默认），
 * 并带回身份回执：pid/会话/线程 id/窗口 HWND 及其所属线程 id/线程前后键盘布局/其它线程与系统级未被改变的对照。
 * 不跑中文输入探针。JNA 不在测试依赖里，这里从 Gradle 缓存按路径动态加载并只用反射调用（不改构建文件）。
 */
@Suppress("OPT_IN_USAGE_ERROR", "OPT_IN_USAGE")
class ImeThreadSelectTest {
    private fun log(s: String) = println("IME_SELECT $s")

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

    @Test fun selectMicrosoftPinyinInCoachUiThreadAndReturnIdentityReceipt() {
        assumeTrue(System.getenv("COACH_IME_SELECT") == "1")
        // 渲染后端不在这里写死：需要隔离时由调用方传 -Dskiko.renderApi=...；默认用 Skiko 自己的选择。
        val n = Native(jnaLoader())
        val k = "kernel32"; val u = "user32"; val imm = "imm32"
        fun preload() = ProcessBuilder("reg", "query", "HKCU\\Keyboard Layout\\Preload").start().inputStream.bufferedReader().readText().lines().filter { it.isNotBlank() }.joinToString(" | ") { it.trim() }
        val preloadBefore = preload()

        val root = Files.createTempDirectory("ime-select-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(wordedChapterPdf(root))
        library.save(book.copy(learning = true, readingPage = 1, questionMode = QuestionMode.SHORT_ANSWER))
        val controller = TextbookController(library)
        lateinit var w: ComposeWindow
        EventQueue.invokeAndWait {
            w = ComposeWindow(); w.setSize(1044, 788); w.setLocation(48, 48); w.title = "coach-ime-select"
            w.setContent { TextbookWorkspace(controller) { null } }
            w.isVisible = true
        }
        try {
            val end = System.currentTimeMillis() + 30_000
            while (!(controller.state.value.book != null && !controller.state.value.busy)) { check(System.currentTimeMillis() < end); Thread.sleep(50) }
            val pid = n.call(k, "GetCurrentProcessId")
            val sessionBuf = n.memory(8); n.call(k, "ProcessIdToSessionId", pid.toInt(), sessionBuf)
            val session = (sessionBuf.javaClass.getMethod("getInt", java.lang.Long.TYPE).invoke(sessionBuf, 0L) as Int)
            val hwnd = w.windowHandle
            val tidBuf = n.memory(8)
            val hwndOwnerTid = n.call(u, "GetWindowThreadProcessId", hwnd, tidBuf)
            log("process pid=$pid session=$session java=${System.getProperty("java.version")} window.hwnd=0x${java.lang.Long.toHexString(hwnd)} window.ownerTid=$hwndOwnerTid")

            // 对照线程：一个不碰布局、全程存活的普通线程，记录它在操作前后的键盘布局
            val release = java.util.concurrent.CountDownLatch(1); val ready = java.util.concurrent.CountDownLatch(1)
            var otherTid = 0L; var otherBefore = 0L; var otherAfter = 0L
            val other = Thread {
                otherTid = n.call(k, "GetCurrentThreadId"); otherBefore = n.call(u, "GetKeyboardLayout", otherTid.toInt()); ready.countDown(); release.await()
                otherAfter = n.call(u, "GetKeyboardLayout", otherTid.toInt())
            }
            other.start(); ready.await()

            val CLSID_PROFILES = n.guid("{33C53A50-F456-4884-B049-85FD643ECFED}")
            val IID_MGR = n.guid("{71C6E74C-0F28-11D8-A82A-00065B84435C}")
            val PINYIN_CLSID = "{81D4E9C9-1D3B-41BC-9E6C-4B40BF79E35E}"; val PINYIN_PROFILE = "{FA550B04-5AD7-411F-A5AC-CA038EC515D7}"
            val CAT_KEYBOARD = n.guid("{34745C63-B2F0-4784-8B67-5E12C8701A31}")
            fun regName(clsid: String, profile: String) = ProcessBuilder("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\CTF\\TIP\\$clsid\\LanguageProfile\\0x00000804\\$profile", "/v", "Description").start()
                .inputStream.bufferedReader().readText().lines().firstOrNull { it.contains("Description") }?.trim() ?: "(no registry description)"
            log("pinyin tip registry: $PINYIN_CLSID $PINYIN_PROFILE -> ${regName(PINYIN_CLSID, PINYIN_PROFILE)}")

            var edtTid = 0L; var before = 0L; var after = 0L; var edtName = ""
            var coInit = 0; var create = 0; var getBefore = 0; var activate = 0; var getAfter = 0
            var activeBefore = ""; var activeAfter = ""
            EventQueue.invokeAndWait {
                edtName = Thread.currentThread().name
                edtTid = n.call(k, "GetCurrentThreadId")
                before = n.call(u, "GetKeyboardLayout", edtTid.toInt())
                coInit = n.call("ole32", "CoInitializeEx", null, 2).toInt()
                val out = n.memory(8)
                create = n.call("ole32", "CoCreateInstance", CLSID_PROFILES, null, 1, IID_MGR, out).toInt()
                val mgr = n.pointerAt(out, 0L)
                fun active(): Pair<Int, String> {
                    val st = n.memory(128)
                    val hr = n.vcall(mgr!!, 10, CAT_KEYBOARD, st)
                    val langid = st.javaClass.getMethod("getShort", java.lang.Long.TYPE).invoke(st, 4L) as Short
                    val hklv = n.memLong(st, 72L)
                    return hr to "type=${st.javaClass.getMethod("getInt", java.lang.Long.TYPE).invoke(st, 0L)} langid=0x${"%04x".format(langid)} clsid=${n.guidText(st, 8)} profile=${n.guidText(st, 24)} hkl=${hkl(hklv)}"
                }
                if (create == 0 && mgr != null) {
                    active().let { getBefore = it.first; activeBefore = it.second }
                    // TF_IPPMF_FORPROCESS(0x40000000)|TF_IPPMF_DONTCARECURRENTINPUTLANGUAGE(1)：只对本进程生效；不带 FORSESSION/ENABLEPROFILE
                    activate = n.vcall(mgr, 3, 1, 0x0804, n.guid(PINYIN_CLSID), n.guid(PINYIN_PROFILE), 0L, 0x40000001)
                    active().let { getAfter = it.first; activeAfter = it.second }
                }
                after = n.call(u, "GetKeyboardLayout", edtTid.toInt())
            }
            release.countDown(); other.join()
            fun hr(v: Int) = "0x" + Integer.toHexString(v)
            log("TSF (ITfInputProcessorProfileMgr via CoCreateInstance on EDT): CoInitializeEx=${hr(coInit)} CoCreateInstance=${hr(create)} GetActiveProfile(before)=${hr(getBefore)} ActivateProfile(MSPinyin,FORPROCESS)=${hr(activate)} GetActiveProfile(after)=${hr(getAfter)}")
            log("active keyboard TIP before: $activeBefore")
            log("active keyboard TIP after : $activeAfter")
            log("edt thread name=$edtName tid=$edtTid | window.ownerTid=$hwndOwnerTid | same=${edtTid == hwndOwnerTid}  (AWT 的原生窗口归 AWT-Windows 线程所有，不是 EDT)")
            log("edt keyboard layout (HKL) before=${hkl(before)} after=${hkl(after)}")
            log("other thread tid=$otherTid layout before=${hkl(otherBefore)} after=${hkl(otherAfter)} unchanged=${otherBefore == otherAfter}")
            val inputCtx = java.awt.im.InputContext.getInstance().locale
            val preloadAfter = preload()
            log("java InputContext.locale=$inputCtx")
            log("HKCU Preload unchanged=${preloadBefore == preloadAfter} before=[$preloadBefore] after=[$preloadAfter]")
            assertEquals(otherBefore, otherAfter, "其它线程的键盘布局不能被改")
            assertEquals(preloadBefore, preloadAfter, "不改系统/用户的默认输入法配置")
        } finally {
            EventQueue.invokeAndWait { w.isVisible = false; w.dispose() }
        }
    }
}
