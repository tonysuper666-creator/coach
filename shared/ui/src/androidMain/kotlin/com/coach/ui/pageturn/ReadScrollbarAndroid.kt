package com.coach.ui.pageturn

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme

@Composable
fun BoxScope.ReadScrollbarAndroid(scroll: ScrollReadState) {
    val layout = scroll.list.layoutInfo
    if (layout.totalItemsCount <= 1 || layout.visibleItemsInfo.isEmpty()) return
    val visible = layout.visibleItemsInfo.size
    val total = layout.totalItemsCount
    val color = CoachTheme.colors.fgSecondary.copy(alpha = 0.55f)
    Canvas(Modifier.align(Alignment.CenterEnd).width(8.dp).fillMaxHeight()) {
        val height = (size.height * visible / total).coerceIn(12.dp.toPx().coerceAtMost(size.height), size.height)
        val fraction = scroll.list.firstVisibleItemIndex / (total - visible).coerceAtLeast(1).toFloat()
        drawRoundRect(color, Offset(size.width / 2f, (size.height - height) * fraction.coerceIn(0f, 1f)),
            Size(size.width / 2f, height), CornerRadius(2.dp.toPx()))
    }
}
