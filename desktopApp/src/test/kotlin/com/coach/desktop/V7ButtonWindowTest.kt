package com.coach.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.ComposeWindow
import com.coach.ui.CoachTheme
import com.coach.ui.theme.CoachTheme as Theme
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PrimaryButton
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Real OS window and input, with synthetic labels only. Also reusable against the pre-change revision.
 *
 * winbox runs two heavy slots in the same console session, so another job's window can take the keyboard
 * foreground while this window still receives mouse input. Every OS Tab is therefore checked on arrival:
 * a Tab this window never received is outside interference (re-activate and retry); a Tab it did receive that
 * does not focus the button is a product defect and fails at once.
 */
class V7ButtonWindowTest {
    @Test fun capturesFiveInputStatesInLightAndDark() {
        assumeTrue("Native Windows screenshot evidence needs the Windows console", System.getProperty("os.name").startsWith("Windows"))
        val robot = Robot().apply { autoDelay = 60 }
        val out = File("build/screens/ton1657/native").apply { mkdirs() }
        for (dark in listOf(false, true)) {
            // 批量里前一个用例（原生段落）超时后，它的置顶窗口可能还在关闭的路上。两个置顶窗口叠着，
            // 真实点击和像素截图都会落到上面那个，所以等它真的关掉再摆这一轮的窗口。
            NativeWindowForeground.awaitOtherWindowsClosed(keep = null)
            val start = FocusRequester()
            val button = AtomicReference<Rectangle>()
            val buttonFocused = AtomicBoolean(false)
            var clicks = 0
            lateinit var window: ComposeWindow
            val tabsHere = AtomicInteger()
            val tabListener = AWTEventListener { e ->
                if (e is KeyEvent && e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_TAB &&
                    (e.source as? java.awt.Component)?.let { SwingUtilities.getWindowAncestor(it) ?: it } === window) tabsHere.incrementAndGet()
            }
            EventQueue.invokeAndWait {
                window = ComposeWindow().apply {
                    title = "Coach · 按钮手感验证"
                    setSize(640, 280)
                    setLocation(160, 160)
                    setContent { CoachTheme(dark) {
                        Column(Modifier.fillMaxSize().background(Theme.colors.bgCanvas).padding(40.dp),
                            verticalArrangement = Arrangement.spacedBy(28.dp)) {
                            Text("按钮 · 交互样张", color = Theme.colors.fgPrimary)
                            Box(Modifier.size(1.dp).focusRequester(start).focusable())
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                PaperButton({ clicks++ }, Modifier.onFocusChanged { buttonFocused.set(it.isFocused) }.onGloballyPositioned {
                                    val p = it.positionInRoot()
                                    button.set(Rectangle(p.x.toInt(), p.y.toInt(), it.size.width, it.size.height))
                                }) { Text("讲解这段") }
                                PrimaryButton({}) { Text("继续学") }
                            }
                            LaunchedEffect(Unit) { start.requestFocus() }
                        }
                    } }
                    // Above other jobs' windows, so OS clicks and screen captures hit this fixture, not an overlapping one.
                    isAlwaysOnTop = true
                    isVisible = true
                    toFront()
                }
            }
            Toolkit.getDefaultToolkit().addAWTEventListener(tabListener, AWTEvent.KEY_EVENT_MASK)
            try {
                robot.waitForIdle()
                robot.delay(1000)
                val origin = AtomicReference<Point>()
                val frame = AtomicReference<Rectangle>()
                EventQueue.invokeAndWait { origin.set(window.contentPane.locationOnScreen); frame.set(window.bounds) }
                val b = button.get() ?: error("button never laid out")
                val transform = window.graphicsConfiguration.defaultTransform
                fun sx(px: Int) = origin.get().x + (px / transform.scaleX).toInt()
                fun sy(px: Int) = origin.get().y + (px / transform.scaleY).toInt()
                val x = sx(b.x + b.width / 2)
                val y = sy(b.y + b.height / 2)
                println("native coordinates root=$b origin=${origin.get()} scale=$transform mouse=$x,$y")
                val mode = if (dark) "dark" else "light"
                val neutral = Point(origin.get().x + 560, origin.get().y + 30)
                val shots = mutableMapOf<String, BufferedImage>()
                fun shot(state: String) {
                    robot.waitForIdle(); robot.delay(200)
                    val image = robot.createScreenCapture(frame.get())
                    shots[state] = image
                    ImageIO.write(image, "png", File(out, "$mode-$state.png"))
                }
                // 每一次真实输入都放进 Win-Drive 的前台互斥量：先拿回前台再送鼠标/键盘。
                // 批量里另一道作业的置顶窗口会在两条命令之间抢走前台，鼠标事件就落到别人窗口上了。
                fun withForeground(block: () -> Unit) = NativeWindowForeground.action {
                    NativeWindowForeground.activate(window) { NativeWindowForeground.describe(window) }
                    block()
                }
                withForeground {
                    robot.mouseMove(neutral.x, neutral.y)
                    shot("normal")
                    robot.mouseMove(x, y)
                    shot("hover")
                    robot.mouseMove(neutral.x, neutral.y)
                }
                // From the 1dp start target, one real Tab must land on the button.
                var foreignForeground = 0
                while (true) {
                    val before = tabsHere.get()
                    withForeground {
                        EventQueue.invokeAndWait { start.requestFocus() }
                        robot.waitForIdle(); robot.delay(200)
                        robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB)
                        robot.waitForIdle(); robot.delay(200)
                    }
                    if (tabsHere.get() > before) {
                        assertTrue(buttonFocused.get(), "OS Tab reached the $mode window but did not focus the button")
                        break
                    }
                    // 前台在别处：这次 Tab 根本没进这个进程，是外部干扰，重新拿前台再发。
                    if (++foreignForeground > 3) fail("OS Tab never reached the $mode window: keyboard foreground held by another process; ${NativeWindowForeground.describe(window)}")
                    println("TON-1657 native $mode Tab went to another window; re-activating ($foreignForeground)")
                }
                withForeground { shot("keyboard") }
                // 两次点击各自核对「按下/抬起有没有真的落在按钮上」。送达证据只认 AWT 鼠标事件，
                // 不认业务回调：回调是产品行为，按钮收到点击却不回调正是要抓的缺陷。
                // 没送到就重发（最多 3 次）；送到了而回调数不对，立即失败。
                fun callbacks(): Int {
                    val value = AtomicInteger()
                    EventQueue.invokeAndWait { value.set(clicks) }
                    return value.get()
                }
                val buttonScreen = Rectangle(sx(b.x), sy(b.y),
                    (b.width / transform.scaleX).toInt().coerceAtLeast(1),
                    (b.height / transform.scaleY).toInt().coerceAtLeast(1))
                val clicksReaching = WindowMouseArrivals(window) { buttonScreen }.start()
                try {
                    var redelivered = 0
                    for (index in 0 until 2) {
                        while (true) {
                            val pressBefore = clicksReaching.pressCount
                            val releaseBefore = clicksReaching.releaseCount
                            val callbacksBefore = callbacks()
                            withForeground {
                                robot.mouseMove(x, y)
                                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                                if (index == 0) shot("pressed")
                                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                                robot.waitForIdle(); robot.delay(300)
                                robot.mouseMove(neutral.x, neutral.y)
                            }
                            val presses = clicksReaching.pressCount - pressBefore
                            val releases = clicksReaching.releaseCount - releaseBefore
                            if (presses == 0 || releases == 0) {
                                if (++redelivered > 3) fail("OS click ${index + 1} never reached the $mode button: presses=$presses releases=$releases; ${NativeWindowForeground.describe(window)}")
                                println("TON-1657 native $mode click ${index + 1} did not reach the button (presses=$presses releases=$releases); re-sending ($redelivered)")
                                continue
                            }
                            val callbacksAfter = callbacks()
                            assertEquals(callbacksBefore + 1, callbacksAfter,
                                "the $mode button received OS click ${index + 1} (presses=$presses releases=$releases) but its callbacks went $callbacksBefore -> $callbacksAfter")
                            break
                        }
                    }
                } finally { clicksReaching.stop() }
                withForeground { shot("mouse-after") }
                EventQueue.invokeAndWait {
                    assertEquals(2, clicks, "OS clicks must reach the actual button")
                    assertTrue(buttonFocused.get(), "the clicked button keeps focus, so a missing ring is the mouse rule, not lost focus")
                }
                // The ring sits 2dp outside the button: sample the band left of the button at its vertical centre.
                val f = frame.get()
                val bandY = sy(b.y + b.height / 2) - f.y
                val left = sx(b.x) - f.x
                fun ringPixels(state: String): Int {
                    val img = shots.getValue(state); val ref = shots.getValue("normal")
                    return (left - 6 until left).count { px ->
                        val a = img.getRGB(px, bandY); val r = ref.getRGB(px, bandY)
                        (0..16 step 8).any { abs((a shr it and 0xFF) - (r shr it and 0xFF)) > 40 }
                    }
                }
                assertTrue(ringPixels("keyboard") > 0, "$mode keyboard focus must draw the ring in the native window")
                assertEquals(0, ringPixels("mouse-after"), "$mode mouse click on the focused button must not draw the ring")
                println("TON-1657 native $mode 5 states captured; ring keyboard=${ringPixels("keyboard")} mouse-after=0; " +
                    "OS clicks reaching the button: presses=${clicksReaching.pressCount} releases=${clicksReaching.releaseCount} callbacks=$clicks")
            } finally {
                Toolkit.getDefaultToolkit().removeAWTEventListener(tabListener)
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                EventQueue.invokeAndWait { window.dispose() }
            }
        }
    }
}
