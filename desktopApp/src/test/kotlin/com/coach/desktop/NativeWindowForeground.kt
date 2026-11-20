package com.coach.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

/**
 * 原生窗口验收用例的输入前台。winbox 的两个重活槽共用一个控制台会话，另一道作业的置顶窗口会在
 * 一次按键和下一次按键之间把前台拿走；`toFront()` / `requestFocus()` 在不持有前台时不会把窗口拉回来，
 * 被吃掉的那一次按键根本进不了这个进程。所以：
 *
 * - `action { }` 把「拿前台 + 送键盘/鼠标」放进 Win-Drive 那条跨作业互斥量 `Global\Coach.WinDrive.Foreground`，
 *   同一时刻只有一个作业能动真实输入；
 * - `activate` 借前台线程的输入队列把窗口真正拉到 OS 前台（`SetForegroundWindow` 单独调用会被系统拒绝）；
 * - `WindowKeyArrivals` 按窗口统计真的到过这个窗口的按键，用来区分「键没送到」和「产品没翻页」。
 *
 * 只在测试源集里用，产品代码不引用。
 */
internal object NativeWindowForeground {
    // 同一线程里嵌套调用直接复用外层那把锁：Win32 互斥量不可重入，重入会把自己锁死。
    private val depth = ThreadLocal.withInitial { 0 }

    fun <T> action(block: () -> T): T {
        if (depth.get() > 0) return block()
        depth.set(1)
        try {
            return WinDriveForegroundLock.hold(block = block)
        } finally {
            depth.set(0)
        }
    }

    fun handle(window: Window): Pointer = Native.getComponentPointer(window)

    fun isForeground(window: Window): Boolean = User32.GetForegroundWindow() == handle(window)

    fun foregroundDescription(): String {
        val handle = User32.GetForegroundWindow()
        val title = CharArray(256)
        User32.GetWindowTextW(handle, title, title.size)
        return "$handle:${Native.toString(title)}"
    }

    /** 一屏诊断：窗口有没有焦点、AWT 焦点在谁身上、OS 前台是谁。失败信息里带这个，才能分清是谁抢走的。 */
    fun describe(window: Window): String = runCatching {
        val owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        "windowFocused=${window.isFocused} active=${window.isActive} focusOwner=${owner?.javaClass?.simpleName} " +
            "foreground=${foregroundDescription()}"
    }.getOrElse { "diagnostics unavailable: $it" }

    /** 把窗口真正拉到 OS 前台再返回；十次都没拿回来就直接失败，不带着「大概有前台」继续送输入。 */
    fun activate(window: Window, diagnostics: () -> String = { "" }) {
        val target = handle(window)
        repeat(10) { attempt ->
            if (User32.IsIconic(target)) User32.ShowWindow(target, 9) // SW_RESTORE
            if (User32.GetForegroundWindow() != target) {
                val holder = User32.GetForegroundWindow()
                val thread = User32.GetWindowThreadProcessId(holder, null)
                val caller = Kernel32.GetCurrentThreadId()
                User32.GetQueueStatus(0x04ff)
                val attached = thread != 0 && caller != thread && User32.AttachThreadInput(caller, thread, true)
                try {
                    User32.BringWindowToTop(target)
                    User32.SetForegroundWindow(target)
                } finally {
                    if (attached) User32.AttachThreadInput(caller, thread, false)
                }
            }
            Thread.sleep(150)
            if (User32.GetForegroundWindow() == target) return
            println("FOREGROUND_RETRY attempt=${attempt + 1} target=$target foreground=${foregroundDescription()}; ${diagnostics()}")
        }
        error("Our window must own OS foreground; ${diagnostics()}")
    }

    /**
     * 等本进程里别的窗口真的关掉（`keep` 除外）。批量里前一个用例超时后，它的置顶窗口可能还在
     * dispose 的路上，两个置顶窗口叠着，真实点击会落到上面那个。等不到就打印出来继续：
     * 后面的前台互斥量还能兜住，不把「等窗口」本身做成新的失败点。
     */
    fun awaitOtherWindowsClosed(keep: Window?, timeoutNanos: Long = 15_000_000_000L) {
        val deadline = System.nanoTime() + timeoutNanos
        while (true) {
            val open = mutableListOf<String>()
            SwingUtilities.invokeAndWait {
                Window.getWindows().filter { it !== keep && it.isVisible }.forEach {
                    open += "${it.javaClass.simpleName}${(it as? java.awt.Frame)?.title?.let { title -> ":$title" }.orEmpty()}"
                }
            }
            if (open.isEmpty()) return
            if (System.nanoTime() >= deadline) {
                println("WINDOW_STILL_VISIBLE ${open.joinToString()}")
                return
            }
            Thread.sleep(100)
        }
    }
}

/** 按窗口统计到过这个窗口的按键（KEY_PRESSED），用来区分「键没送到」和「产品没翻页」。 */
internal class WindowKeyArrivals(private val window: Window) {
    private val pressed = AtomicInteger()
    private val listener = AWTEventListener { event ->
        if (event is KeyEvent && event.id == KeyEvent.KEY_PRESSED && windowOf(event.source) === window) pressed.incrementAndGet()
    }

    fun start(): WindowKeyArrivals {
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.KEY_EVENT_MASK)
        return this
    }

    fun stop() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
    }

    val count: Int get() = pressed.get()
}

/**
 * 按窗口统计真的落在某个屏幕区域里的鼠标按下/抬起。
 *
 * 用它判「点击有没有送到按钮上」，而不是看业务回调有没有加一：回调是产品行为，
 * 按钮收到点击却不回调正是要抓的缺陷；拿回调当送达证据会把这种缺陷漏掉。
 */
internal class WindowMouseArrivals(private val window: Window, private val area: () -> Rectangle?) {
    private val presses = AtomicInteger()
    private val releases = AtomicInteger()
    private val listener = AWTEventListener { event ->
        if (event !is MouseEvent) return@AWTEventListener
        if (event.id != MouseEvent.MOUSE_PRESSED && event.id != MouseEvent.MOUSE_RELEASED) return@AWTEventListener
        if (windowOf(event.source) !== window) return@AWTEventListener
        val target = area() ?: return@AWTEventListener
        val at = runCatching { event.locationOnScreen }.getOrNull() ?: return@AWTEventListener
        if (!target.contains(at)) return@AWTEventListener
        if (event.id == MouseEvent.MOUSE_PRESSED) presses.incrementAndGet() else releases.incrementAndGet()
    }

    fun start(): WindowMouseArrivals {
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
        return this
    }

    fun stop() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
    }

    val pressCount: Int get() = presses.get()
    val releaseCount: Int get() = releases.get()
}

private fun windowOf(source: Any?): Window? {
    val component = source as? Component ?: return null
    return if (component is Window) component else SwingUtilities.getWindowAncestor(component)
}

private interface FocusKernel : StdCallLibrary {
    fun GetCurrentThreadId(): Int
}

private interface FocusUser : StdCallLibrary {
    fun IsIconic(window: Pointer): Boolean
    fun ShowWindow(window: Pointer, command: Int): Boolean
    fun GetWindowTextW(window: Pointer, text: CharArray, count: Int): Int
    fun GetForegroundWindow(): Pointer
    fun GetWindowThreadProcessId(window: Pointer, process: Pointer?): Int
    fun GetQueueStatus(flags: Int): Int
    fun AttachThreadInput(from: Int, to: Int, attach: Boolean): Boolean
    fun BringWindowToTop(window: Pointer): Boolean
    fun SetForegroundWindow(window: Pointer): Boolean
}

private val Kernel32 by lazy { Native.load("kernel32", FocusKernel::class.java) }
private val User32 by lazy { Native.load("user32", FocusUser::class.java) }
