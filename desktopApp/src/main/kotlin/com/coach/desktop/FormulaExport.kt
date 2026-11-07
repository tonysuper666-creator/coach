package com.coach.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import com.coach.ui.math.*
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.serialization.json.*
import kotlin.math.ceil

/** Rasterize the actual common Painter at the same em as the browser reference. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun exportFormulas() {
    val folder = File(System.getProperty("coach.exportFormulas"))
    val rows = Json.parseToJsonElement(File(folder, "formulas.json").readText()).jsonArray
    val out = File(folder, "painter").apply { mkdirs() }
    for (raw in rows) {
        val row = raw.jsonObject
        val id = row.getValue("id").jsonPrimitive.int
        File(out, "$id.png").writeBytes(rasterizeFormula(row.getValue("sha").jsonPrimitive.content, row.getValue("svg").jsonPrimitive.content))
    }
    println("PAINTER exported=${rows.size} em=32")
}

/** One common Painter for screen verification and printed notes. May be called from a worker. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun rasterizeFormula(sha: String, svg: String, em: Float = 32f): ByteArray {
    val doc = MathSvgDocument.parse(sha, svg)
    val w = ceil(doc.widthEm * em).toInt().coerceAtLeast(1)
    val h = ceil(doc.box[3] / 1000f * em).toInt().coerceAtLeast(1)
    require(w <= 16000 && h <= 16000 && w.toLong() * h <= 32_000_000) { "公式过大，请缩小导出范围" }
    var result: ByteArray? = null
    val render = {
        val scene = ImageComposeScene(w, h, Density(1f)) {
            val measurer = rememberTextMeasurer()
            Canvas(Modifier.fillMaxSize().background(Color.White)) {
                with(MathSvgPainter(doc, Color.Black, measurer, em)) { draw(size) }
            }
        }
        try {
            scene.render().use { image -> image.encodeToData()!!.use { result = it.bytes } }
        } finally { scene.close() }
    }
    if (SwingUtilities.isEventDispatchThread()) render() else SwingUtilities.invokeAndWait(render)
    return checkNotNull(result)
}
