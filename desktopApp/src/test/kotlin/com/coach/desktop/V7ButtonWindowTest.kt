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
import java.awt.EventQueue
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real OS window and input, with synthetic labels only. Also reusable against the pre-change revision. */
class V7ButtonWindowTest {
    @Test fun capturesFiveInputStatesInLightAndDark() {
        assumeTrue("Native Windows screenshot evidence needs the Windows console", System.getProperty("os.name").startsWith("Windows"))
        val robot = Robot().apply { autoDelay = 60 }
        val out = File("build/screens/ton1657/native").apply { mkdirs() }
        for (dark in listOf(false, true)) {
            val start = FocusRequester()
            val button = AtomicReference<Point>()
            val buttonFocused = AtomicBoolean(false)
            var clicks = 0
            lateinit var window: ComposeWindow
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
                                    button.set(Point((p.x + it.size.width / 2).toInt(), (p.y + it.size.height / 2).toInt()))
                                }) { Text("讲解这段") }
                                PrimaryButton({}) { Text("继续学") }
                            }
                            LaunchedEffect(Unit) { start.requestFocus() }
                        }
                    } }
                    isVisible = true
                    toFront()
                }
            }
            try {
                robot.waitForIdle()
                robot.delay(1000)
                val origin = AtomicReference<Point>()
                val frame = AtomicReference<Rectangle>()
                EventQueue.invokeAndWait { origin.set(window.contentPane.locationOnScreen); frame.set(window.bounds) }
                val p = button.get() ?: error("button never laid out")
                val transform = window.graphicsConfiguration.defaultTransform
                val x = origin.get().x + (p.x / transform.scaleX).toInt()
                val y = origin.get().y + (p.y / transform.scaleY).toInt()
                println("native coordinates root=$p origin=${origin.get()} scale=$transform mouse=$x,$y")
                val mode = if (dark) "dark" else "light"
                fun shot(state: String) {
                    robot.waitForIdle(); robot.delay(200)
                    ImageIO.write(robot.createScreenCapture(frame.get()), "png", File(out, "$mode-$state.png"))
                }
                robot.mouseMove(origin.get().x + 560, origin.get().y + 30)
                shot("normal")
                robot.mouseMove(x, y)
                shot("hover")
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                shot("pressed")
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                robot.mouseMove(origin.get().x + 560, origin.get().y + 30)
                EventQueue.invokeAndWait { start.requestFocus() }
                robot.waitForIdle(); robot.delay(200)
                // Windows can first return native focus to the Compose canvas after a window opens.
                // Walk the three focus targets with real Tab events and verify the actual target.
                for (attempt in 0 until 3) {
                    robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB)
                    robot.waitForIdle(); robot.delay(200)
                    if (buttonFocused.get()) break
                }
                assertTrue(buttonFocused.get(), "OS Tab must focus the captured button in $mode")
                shot("keyboard")
                robot.mouseMove(x, y)
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                robot.mouseMove(origin.get().x + 560, origin.get().y + 30)
                shot("mouse-after")
                EventQueue.invokeAndWait { assertEquals(2, clicks, "OS clicks must reach the actual button") }
                println("TON-1657 native $mode 5 states captured")
            } finally {
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                EventQueue.invokeAndWait { window.dispose() }
            }
        }
    }
}
