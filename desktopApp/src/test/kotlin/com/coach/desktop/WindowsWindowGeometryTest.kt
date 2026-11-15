package com.coach.desktop

import java.awt.Insets
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsWindowGeometryTest {
    @Test fun ordinaryWindowFitsSmallScreensAtCommonWindowsScales() {
        for (scale in listOf(1.0, 1.25, 1.5, 2.0)) {
            // Model AWT's logical work area for a 1920x1080 monitor, not raw device pixels.
            val screen = Rectangle(0, 0, (1920 / scale).toInt(), (1080 / scale).toInt())
            val insets = Insets(0, 0, (48 / scale).toInt(), 0)
            val bounds = windowsInitialWindowBounds(screen, insets)
            val work = Rectangle(0, 0, screen.width, screen.height - insets.bottom)
            assertTrue(work.contains(bounds), "normal frame must fit at $scale scale: $bounds in $work")
            assertTrue(bounds.width > 0 && bounds.height > 0)
            assertTrue(bounds.height < work.height, "leave space to move the normal window")
        }
    }

    @Test fun twoHundredPercentVmKeepsSubmitViewportAboveTaskbar() {
        assertEquals(Rectangle(24, 24, 1248, 744),
            windowsInitialWindowBounds(Rectangle(0, 0, 1296, 840), Insets(0, 0, 48, 0)))
    }

    @Test fun largeDesktopKeepsPreferredSize() {
        assertEquals(Rectangle(560, 246, 1440, 900),
            windowsInitialWindowBounds(Rectangle(0, 0, 2560, 1440), Insets(0, 0, 48, 0)))
    }

    @Test fun negativeMonitorOriginAndTopOrSideTaskbarsAreRespected() {
        for (insets in listOf(Insets(40, 0, 0, 0), Insets(0, 64, 0, 0), Insets(0, 0, 0, 64))) {
            val screen = Rectangle(-1280, -200, 1280, 800)
            val frame = windowsInitialWindowBounds(screen, insets)
            val work = Rectangle(screen.x + insets.left, screen.y + insets.top,
                screen.width - insets.left - insets.right, screen.height - insets.top - insets.bottom)
            assertTrue(work.contains(frame), "$frame must fit $work")
            assertEquals(work.centerX, frame.centerX, 0.5)
            assertEquals(work.centerY, frame.centerY, 0.5)
        }
    }
}
