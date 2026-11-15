package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runSkikoComposeUiTest
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WindowsThemeTest {
    @Test fun processOverrideChangesTheActualHostCanvasAndLeavesSystemDefaultAvailable() {
        assertNull(windowsThemeOverride(null))
        assertNull(windowsThemeOverride("system"))
        assertEquals(true, windowsThemeOverride("DARK"))
        assertEquals(false, windowsThemeOverride("light"))
        val previous = System.getProperty("coach.windows.theme")
        try {
            fun canvas(mode: String): Float {
                System.setProperty("coach.windows.theme", mode)
                val controller = TextbookController(TextbookLibrary(Files.createTempDirectory("coach-theme-")))
                var brightness = 0f
                runSkikoComposeUiTest(size = Size(1200f, 900f)) {
                    setContent { WindowsTextbookHost(controller) { null } }
                    waitForIdle()
                    val color = onNodeWithTag("windows-host").captureToImage().toPixelMap()[5, 5]
                    brightness = color.red + color.green + color.blue
                }
                return brightness
            }
            assertTrue(canvas("dark") < canvas("light") - 1f, "host canvas must actually switch palettes")
        } finally {
            if (previous == null) System.clearProperty("coach.windows.theme")
            else System.setProperty("coach.windows.theme", previous)
        }
    }
}
