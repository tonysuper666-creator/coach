package com.coach.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * Windows 任务栏和标题栏左上角的图标。
 *
 * 顶栏品牌位按 V7 只留字，图形只在系统外壳里出现：exe、安装包和开始菜单快捷方式用
 * `desktopApp/icons/LearningCoach.ico`（由 `nativeDistributions.windows.iconFile` 打包），
 * 每个顶层窗口再用这里的 PNG，避免任务栏上出现 Java 默认的咖啡杯。
 */
internal object AppIcon {
    /** 尺寸最全的一张；Windows 从它缩到 16/20/24/32/40/48/64。 */
    private const val WINDOW = "icons/learningcoach-256.png"

    internal fun load(path: String): org.jetbrains.skia.Image =
        AppIcon::class.java.getResourceAsStream("/$path")?.use { Image.makeFromEncoded(it.readBytes()) }
            ?: error("Missing window icon resource /$path")

    @Composable
    fun window(): Painter = remember { BitmapPainter(load(WINDOW).toComposeImageBitmap()) }
}
