package com.coach.ui.pageturn

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme

/** 手机上只画一条只读的比例条：长度按可见项占总项数估算，位置按已滚过的页数。 */
@Composable
internal actual fun ReadScrollbar(state: LazyListState, modifier: Modifier) {
    val color: Color = CoachTheme.colors.fgTertiary
    androidx.compose.foundation.layout.Box(modifier.width(8.dp).drawBehind {
        val info = state.layoutInfo
        val total = info.totalItemsCount
        if (total == 0 || info.visibleItemsInfo.isEmpty()) return@drawBehind
        val visible = info.visibleItemsInfo.size.toFloat()
        val h = (size.height * (visible / total)).coerceIn(24.dp.toPx(), size.height)
        val first = info.visibleItemsInfo.first()
        val progress = ((first.index + (-first.offset).toFloat() / first.size.coerceAtLeast(1)) / total).coerceIn(0f, 1f)
        val y = (size.height - h) * progress
        drawRoundRect(color, Offset(size.width - 4.dp.toPx(), y), Size(3.dp.toPx(), h), CornerRadius(2.dp.toPx()))
    })
}
