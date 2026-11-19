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
 * 截图落在 `build/screens/ton1731/native/`，只取窗口内容区（或独立弹层）自身边界，留焦点环和展开菜单的样子。
 *
 * 菜单分两次展开：第一次只按 Esc，断言收起且仍是第 1 周；第二次按 ↓ ↓ 回车，断言收起并选中第 2 周。
 * 「↓↓ 回车得第 2 周」是 Material3 1.12-alpha03 菜单的现状（KeyDown 阶段吃掉上下键，焦点不跟着走），
 * main 基线 `0f617f25` 上浅深实测同值；本卡要求下拉键盘行为不变，所以把现状钉住，改了会在这里变红。
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
                // Windows 的 window.bounds 含两侧和底部看不见的缩放边框，会带进桌面；只取 Coach 自己画的内容区。
                EventQueue.invokeAndWait {
                    val pane = window.contentPane
                    frame.set(Rectangle(pane.locationOnScreen, pane.size))
                }
                val mode = if (dark) "dark" else "light"
                val shots = mutableMapOf<String, BufferedImage>()
                // 只拍 Coach 窗口内容区，窗口以外的桌面不进图（焦点环和菜单都画在窗口里）。
                fun shot(name: String) {
                    robot.waitForIdle(); robot.delay(250)
                    val image = robot.createScreenCapture(Rectangle(frame.get()))
                    shots[name] = image
                    ImageIO.write(image, "png", File(out, "$mode-$name.png"))
                }
                // 菜单是独立的一层（弹出窗口）：有这一层就单拍它本身，没有就说明画在窗口里，窗口那张已经包含。
                fun menuShot(name: String) {
                    robot.waitForIdle(); robot.delay(250)
                    val popup = java.awt.Window.getWindows().firstOrNull {
                        it !== window && it.isVisible && it.width > 0 && it.height > 0
                    }
                    if (popup == null) {
                        println("TON-1731 native $mode menu renders inside the window; $name skipped")
                        return
                    }
                    val image = robot.createScreenCapture(Rectangle(popup.bounds))
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
                    // 往回一格就是下拉：回车展开菜单（菜单自己把焦点放到第 1 周）。
                    key(KeyEvent.VK_TAB, shift = true)
                    shot("dropdown-focused")
                    assertEquals(1, state.week)
                    key(KeyEvent.VK_ENTER)
                    assertTrue(state.expanded, "Enter on the focused 周次 button must open the menu")
                    shot("menu-open"); menuShot("menu-only")

                    // 第一次展开：只按 Esc。菜单必须是开着时按下的，收起后仍是第 1 周。
                    key(KeyEvent.VK_ESCAPE)
                    assertFalse(state.expanded, "Esc must close the open menu")
                    assertEquals(1, state.week, "Esc must keep week 1")
                }
                foreground {
                    // 第二次展开：Esc 后焦点回到下拉按钮，回车再开，↓ ↓ 回车确认。
                    key(KeyEvent.VK_ENTER)
                    assertTrue(state.expanded, "Enter must reopen the menu after Esc")
                    key(KeyEvent.VK_DOWN)
                    key(KeyEvent.VK_DOWN)
                    key(KeyEvent.VK_ENTER)
                    println("TON-1731 native $mode menu keys: ↓↓Enter -> week=${state.week} expanded=${state.expanded}")
                    assertFalse(state.expanded, "Enter on a menu row must close the menu")
                    assertEquals(2, state.week, "↓ ↓ Enter from week 1 selects week 2 (main baseline behaviour)")
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
