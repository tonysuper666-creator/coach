package com.coach.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.coach.ui.DiagnosticsScreen
import kotlinx.coroutines.delay
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skia.Image
import java.awt.Component
import java.awt.Container
import java.io.File

private fun Component.skiaLayers(): Sequence<SkiaLayer> = sequence {
    if (this@skiaLayers is SkiaLayer) yield(this@skiaLayers)
    if (this@skiaLayers is Container) {
        for (child in components) yieldAll(child.skiaLayers())
    }
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Coach", state = rememberWindowState(width = 840.dp, height = 600.dp)) {
        DiagnosticsScreen("FLAT")
        LaunchedEffect(Unit) {
            delay(1500)
            val layer = window.skiaLayers().first()
            println("COACH window showing=${window.isShowing} bounds=${window.bounds} renderApi=${layer.renderApi}")
            // Capture the existing on-screen Skia surface. This needs no permission
            // to capture other applications and is only enabled for local verification.
            System.getProperty("coach.capturePath")?.let { path ->
                val bitmap = checkNotNull(layer.screenshot())
                bitmap.use {
                    Image.makeFromBitmap(it).use { image ->
                        image.encodeToData()!!.use { data -> File(path).writeBytes(data.bytes) }
                    }
                }
                println("COACH live window surface captured: $path")
            }
            System.getProperty("coach.autoExitSeconds")?.toLongOrNull()?.let {
                delay(it * 1000)
                exitApplication()
            }
        }
    }
}
