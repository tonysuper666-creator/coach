package com.coach.desktop

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 图标接线断了，任务栏会退回 Java 咖啡杯，而窗口本身看不出异常——所以这里锁住资产和接线。 */
class AppIconTest {
    @Test
    fun windowIconPngsKeepTheirDeclaredPixels() {
        for (size in listOf(16, 24, 32, 48, 256)) {
            val image = AppIcon.load("icons/learningcoach-$size.png")
            assertEquals(size, image.width, "learningcoach-$size.png width")
            assertEquals(size, image.height, "learningcoach-$size.png height")
        }
    }

    @Test
    fun installerIconCarriesEveryWindowsSize() {
        val ico = java.io.File("icons/LearningCoach.ico").readBytes()
        assertTrue(ico.size > 6, "icons/LearningCoach.ico missing")
        val header = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, header.getShort().toInt() and 0xFFFF, "ICO reserved field")
        assertEquals(1, header.getShort().toInt(), "ICO type must be icon")
        val count = header.getShort().toInt() and 0xFFFF
        val sizes = (0 until count).map {
            val width = ico[6 + it * 16].toInt() and 0xFF
            if (width == 0) 256 else width // 256 is stored as 0 in the directory entry
        }
        assertEquals(listOf(16, 20, 24, 32, 40, 48, 64, 256), sizes, "Windows picks a pre-rendered size per DPI from this list")
    }

    @Test
    fun buildDeclaresTheInstallerIcon() {
        val build = java.io.File("build.gradle.kts").readText()
        assertTrue(
            build.contains("iconFile.set(project.file(\"icons/LearningCoach.ico\"))"),
            "nativeDistributions.windows.iconFile must point at the packaged ICO",
        )
    }
}
