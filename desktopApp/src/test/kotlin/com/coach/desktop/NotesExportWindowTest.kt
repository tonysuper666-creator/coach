package com.coach.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.coach.ui.CoachTheme
import com.coach.ui.theme.CoachTheme as Theme
import java.awt.EventQueue
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * 导出弹窗的真窗口键盘走一遍：真实 OS Tab / 空格 / 回车 / Esc，输入走 Win-Drive 前台互斥量。
 *
 * 只断言「按下去以后产品里的值有没有变」：空格翻的是 `includePdf`，回车开的是 `expanded`，Esc 收起但不动选择。
 * 焦点落在哪一格不用另找探针——别的控件都不会改这几个值，值变了就说明键盘真的到了那一格。
 * 截图落在 `build/screens/ton1731/native/`，留焦点环和展开菜单的样子。
 *
 * 菜单行不参与走查：Material3 1.12-alpha03 的菜单在 KeyDown 阶段自己吃掉上下键和回车，菜单行只收到 KeyUp，
 * 焦点不移动、回车也不选中（改动前就是这样，本卡要求「下拉键盘行为不变」，没有动它，另记在交付评论里）。
 */
class NotesExportWindowTest {
    @Test fun keyboardWalkthroughTogglesCheckboxAndDropdown() {
        assumeTrue("Native Windows keyboard evidence needs the Windows console", System.getProperty("os.name").startsWith("Windows"))
        val robot = Robot().apply { autoDelay = 60 }
        val out = File("build/screens/ton1731/native").apply { mkdirs() }
        for (dark in listOf(false, true)) {
            NativeWindowForeground.awaitOtherWindowsClosed(keep = null)
            val state = NotesExportState(listOf(1, 2, 3), emptyList(), 1, null)
            val start = FocusRequester()
            lateinit var window: ComposeWindow
            EventQueue.invokeAndWait {
                window = ComposeWindow().apply {
                    title = "Coach · 导出弹窗键盘验证"
                    setSize(620, 700)
                    setLocation(200, 120)
                    setContent {
                        CoachTheme(dark) {
                            Column(Modifier.fillMaxSize().background(Theme.colors.bgCanvas)) {
                                Box(Modifier.size(1.dp).focusRequester(start).focusable())
                                NotesExportPanel(state, onPickDirectory = {}, onExport = {}, onClose = {}, onOpen = {})
                                LaunchedEffect(Unit) { start.requestFocus() }
                            }
                        }
                    }
                    isAlwaysOnTop = true
                    isVisible = true
                    toFront()
                }
            }
            val arrivals = WindowKeyArrivals(window).start()
            try {
                robot.waitForIdle(); robot.delay(1000)
                val frame = AtomicReference<Rectangle>()
                EventQueue.invokeAndWait { frame.set(window.bounds) }
                val mode = if (dark) "dark" else "light"
                val shots = mutableMapOf<String, BufferedImage>()
                fun shot(name: String, extraBottom: Int = 0) {
                    robot.waitForIdle(); robot.delay(250)
                    val f = frame.get()
                    val area = Rectangle(f.x - 8, f.y - 8, f.width + 16, f.height + 16 + extraBottom)
                    val image = robot.createScreenCapture(area)
                    shots[name] = image
                    ImageIO.write(image, "png", File(out, "$mode-$name.png"))
                }
                fun foreground(block: () -> Unit) = inputOnDesktop(window) { block() }
                // 一次按键没进这个进程（前台被另一道作业拿走）就重发；进来过就不重发，免得同一次 Tab 走两格。
                fun key(code: Int, shift: Boolean = false) {
                    repeat(3) { attempt ->
                        val before = arrivals.count
                        if (shift) robot.keyPress(KeyEvent.VK_SHIFT)
                        robot.keyPress(code); robot.keyRelease(code)
                        if (shift) robot.keyRelease(KeyEvent.VK_SHIFT)
                        robot.waitForIdle(); robot.delay(180)
                        if (arrivals.count > before) return
                        println("TON-1731 native $mode key $code attempt ${attempt + 1} did not reach the window; re-activating")
                        inputOnDesktop(window) {}
                    }
                    error("OS key $code never reached the $mode notes export window")
                }

                // 起点是左上角那个 1dp 的空节点。这张卡的知识点列表是空的，「按知识点」按设计不可点、不进 Tab 链，
                // 所以顺序是「按周次 → 下拉 → 格式勾选框」，三格到勾选框。
                foreground {
                    key(KeyEvent.VK_TAB)
                    key(KeyEvent.VK_TAB)
                    key(KeyEvent.VK_TAB)
                    shot("checkbox-focused")
                    assertTrue(state.includePdf)
                    key(KeyEvent.VK_SPACE)
                    assertFalse(state.includePdf, "Space on the focused PDF row must clear the box")
                    shot("checkbox-unchecked")
                    key(KeyEvent.VK_SPACE)
                    assertTrue(state.includePdf, "Space again must tick it back")
                }
                foreground {
                    // 往回一格就是下拉：回车展开菜单（菜单自己把焦点放到第 1 周），Esc 收起且不改选。
                    key(KeyEvent.VK_TAB, shift = true)
                    shot("dropdown-focused")
                    key(KeyEvent.VK_ENTER)
                    assertTrue(state.expanded, "Enter on the focused 周次 button must open the menu")
                    shot("menu-open", extraBottom = 160)
                    key(KeyEvent.VK_ESCAPE)
                    assertFalse(state.expanded, "Esc must close the menu")
                    assertEquals(1, state.week, "Esc must not change the selection")
                }
                println("TON-1731 native $mode keyboard walkthrough passed: week=${state.week} includePdf=${state.includePdf} shots=${shots.keys}")
            } finally {
                arrivals.stop()
                EventQueue.invokeAndWait { window.dispose() }
            }
        }
    }
}

/**
 * 送输入前把窗口放到前台。有交互桌面时走 Win-Drive 互斥量加 Win32 前台（另一道作业的窗口会抢键盘）；
 * 服务会话（`GetForegroundWindow` 为空，例如从守护进程里跑测试）没有可抢的前台，`activate` 会一直等一个
 * 永远不会出现的 OS 前台，这里改用 AWT 自己的 `toFront`/`requestFocus`。
 */
private fun inputOnDesktop(window: ComposeWindow, block: () -> Unit) {
    if (NativeWindowForeground.foregroundDescription().startsWith("null")) {
        EventQueue.invokeAndWait { window.toFront(); window.requestFocus() }
        block()
        return
    }
    NativeWindowForeground.action {
        NativeWindowForeground.activate(window) { NativeWindowForeground.describe(window) }
        block()
    }
}
