package com.coach.ui.v7
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import com.coach.ui.AppTab

/** The four compact navigation glyphs use the reference's 24-unit stroked geometry. */
@Composable internal fun DeskNavIcon(tab: AppTab, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val factor = size.width / 24f
        scale(factor, factor, Offset.Zero) {
            val stroke = Stroke(1.5f)
            fun line(x: Float,y: Float,ex: Float,ey: Float) = drawLine(color, Offset(x,y), Offset(ex,ey), 1.5f)
            when (tab) {
                AppTab.TODAY -> { drawRoundRect(color, Offset(4f,5f), androidx.compose.ui.geometry.Size(16f,15f), CornerRadius(2f), style=stroke); line(4f,10f,20f,10f);line(9f,3f,9f,7f);line(15f,3f,15f,7f) }
                AppTab.ROUTE -> { val p=Path().apply { moveTo(12f,6.5f);cubicTo(10f,5f,7f,4.6f,4f,5f);lineTo(4f,18f);cubicTo(7f,17.6f,10f,18f,12f,19.5f);cubicTo(14f,18f,17f,17.6f,20f,18f);lineTo(20f,5f);cubicTo(17f,4.6f,14f,5f,12f,6.5f);close() }; drawPath(p,color,style=stroke);line(12f,6.5f,12f,19f) }
                AppTab.REVIEW -> { drawRoundRect(color,Offset(5f,4f),androidx.compose.ui.geometry.Size(14f,16f),CornerRadius(2f),style=stroke);line(8.5f,12f,10.7f,14.2f);line(10.7f,14.2f,15.5f,9.2f) }
                else -> { drawRoundRect(color,Offset(4f,5f),androidx.compose.ui.geometry.Size(4f,15f),CornerRadius(1f),style=stroke);drawRoundRect(color,Offset(10f,4f),androidx.compose.ui.geometry.Size(4f,16f),CornerRadius(1f),style=stroke);line(17f,5f,21f,19f);line(20f,4f,24f,18f) }
            }
        }
    }
}
