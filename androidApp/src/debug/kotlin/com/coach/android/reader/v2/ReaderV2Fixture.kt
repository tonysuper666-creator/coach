package com.coach.android.reader.v2

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.coach.ui.reader.v2.*

/** Entirely invented pages and questions. Not course text, holdout data or a service. */
object ReaderV2Fixture {
    const val DocumentId = "synthetic-reader-v2"
    val Sha256 = "a".repeat(64)
    val columns = listOf(ReaderColumn(1, ReaderRegion(.08f, .12f, .46f, .88f)),
        ReaderColumn(2, ReaderRegion(.54f, .12f, .92f, .88f)))
    val pages = (0..1).map { ReaderPageLayout(it, 600f, 800f, ReaderRegion(.08f, .05f, .92f, .94f), columns, bodyFontSize = 14.4f) }
    val questions = listOf(
        ReaderQuestion("slope", "合成题：线条向哪边倾斜？", ReaderSourceResolution.Found(
            NativeSourceTarget(DocumentId, Sha256, "synthetic-sentence-1", 1, listOf(ReaderRegion(.54f, .35f, .92f, .375f))))),
        ReaderQuestion("unconfirmed", "合成题：没有核实过的出处", ReaderSourceResolution.Unconfirmed(ReaderSourceFailure.AMBIGUOUS)))
    fun session() = ReaderSession(DocumentId, Sha256)
}

@Composable
fun ReaderV2FixtureHost(window: ReaderWindow, session: ReaderSession, onSession: (ReaderSession) -> Unit,
    onBodySize: (Int, Float) -> Unit = { _, _ -> }) {
    val density = LocalDensity.current.density
    var bannerHeight by remember { mutableStateOf(0f) }
    Column(Modifier.fillMaxSize()) {
        Text("合成材料 · 非课程原文", style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.fillMaxWidth().onSizeChanged { bannerHeight = it.height / density }.padding(8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val fold = window.fold?.let { it.copy(bounds = it.bounds.copy(top = it.bounds.top - bannerHeight, bottom = it.bounds.bottom - bannerHeight)) }
            ReaderV2Screen(ReaderWindow(maxWidth.value, maxHeight.value, fold), ReaderV2Fixture.pages,
                ReaderV2Fixture.questions, session, onSession, pageContent = { ReaderV2SyntheticPage(it, onBodySize) })
        }
    }
}

@Composable
fun ReaderV2SyntheticPage(index: Int, onBodySize: (Int, Float) -> Unit = { _, _ -> }) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(0xFFFCFAF6))
        // Synthetic column rulers make the actual viewport width measurable.
        for (c in ReaderV2Fixture.columns) drawLine(Color(0xFF2F6A4B),
            Offset(c.region.left * size.width, .125f * size.height),
            Offset(c.region.right * size.width, .125f * size.height), strokeWidth = 3f)
        drawIntoCanvas { canvas ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(40, 37, 32); textSize = size.width * .024f }
            onBodySize(index, paint.textSize)
            canvas.nativeCanvas.drawText("合成材料 · 非课程原文 · 第${index + 1}页", size.width * .08f, size.height * .075f, paint)
            for (column in 0..1) {
                val x = size.width * if (column == 0) .08f else .54f
                for (line in 0..20) {
                    val y = size.height * (.145f + line * .032f)
                    val text = if (index == 1 && column == 1 && line == 7) "这条线从左上走向右下。" else "示意段落${line + 1}：读这一栏。"
                    canvas.nativeCanvas.drawText(text, x, y, paint)
                }
                paint.style = Paint.Style.STROKE
                canvas.nativeCanvas.drawRect(x, size.height * .81f, x + size.width * .36f, size.height * .87f, paint)
                paint.style = Paint.Style.FILL
            }
        }
    }
}
