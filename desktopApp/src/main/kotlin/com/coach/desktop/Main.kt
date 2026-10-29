package com.coach.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.coach.ui.*
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

fun main() {
    if (System.getProperty("coach.exportFormulas") != null) { exportFormulas(); return }
    val stateFile = File(System.getenv("COACH_RENDER_STATE") ?: File(System.getProperty("user.home"), "Coach/state/render-spike.json").path)
    val state = ReplayState(stateFile.takeIf { it.exists() }?.readText())
    val corpusFile = File(System.getenv("COACH_REPLAY_FILE") ?: File(System.getProperty("user.home"), "Coach/corpus/render/replay.json").path)
    val corpus = corpusFile.takeIf { it.exists() }?.let { ReplayCorpus.load(it.readText()) }
    fun save() {
        stateFile.parentFile.mkdirs()
        val tmp = File(stateFile.parentFile, "render-spike.next")
        tmp.writeText(state.snapshot())
        Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    application {
    Window(onCloseRequest = ::exitApplication, title = "Coach", state = rememberWindowState(width = 840.dp, height = 600.dp)) {
        RenderingReplayScreen(state, corpus, true, "FLAT", ::save, ::println)
        DisposableEffect(window) {
            val listener = object : java.awt.event.WindowAdapter() {
                override fun windowLostFocus(e: java.awt.event.WindowEvent) { save() }
            }
            window.addWindowFocusListener(listener)
            onDispose { save(); window.removeWindowFocusListener(listener) }
        }
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

}
