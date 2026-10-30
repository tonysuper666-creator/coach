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
    SwingUtilities.invokeAndWait {
        for (raw in rows) {
            val row = raw.jsonObject
            val id = row.getValue("id").jsonPrimitive.int
            val doc = MathSvgDocument.parse(row.getValue("sha").jsonPrimitive.content, row.getValue("svg").jsonPrimitive.content)
            val w = ceil(doc.widthEm * 32).toInt().coerceAtLeast(1)
            val h = ceil(doc.box[3] / 1000f * 32).toInt().coerceAtLeast(1)
            val scene = ImageComposeScene(w, h, Density(1f)) {
                val measurer = rememberTextMeasurer()
                Canvas(Modifier.fillMaxSize().background(Color.White)) {
                    with(MathSvgPainter(doc, Color.Black, measurer, 32f)) { draw(size) }
                }
            }
            try {
                scene.render().use { image -> image.encodeToData()!!.use { File(out, "$id.png").writeBytes(it.bytes) } }
            } finally { scene.close() }
        }
    }
    println("PAINTER exported=${rows.size} em=32")
}
