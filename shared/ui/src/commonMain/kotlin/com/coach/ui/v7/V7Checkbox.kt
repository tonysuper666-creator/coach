package com.coach.ui.v7

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme

/** 方框边长固定，和「格式」这一行里的字、和 P4「计时作答」的方框都是同一个尺寸。 */
private val BoxSize = 16.dp

/**
 * V7 的小方框勾选：没勾是一道 `fgTertiary` 细线框，勾上是松绿实底、勾用纸色（`surface1`）。
 * 方框和勾都是画的，不用 Material 的 `Checkbox`/`CheckboxDefaults`，所以没有波纹、灰底和 48dp 触控盒，
 * 边框粗细、圆角和勾的线宽都按 V7 的细线来。切换时框本身不换尺寸、不跳动。
 *
 * 这里只画框：点击、空格切换、读屏的 Checkbox 角色由外层整行的 `toggleable(role = Role.Checkbox)` 负责，
 * 键盘焦点环也由那一行上的 `v7FocusRing()` 画，所以两处调用（导出弹窗、本地练习）语义完全一致。
 * [enabled] 为 false 时整框减淡，和同一行文字一起读成不可用。
 */
@Composable
fun V7Checkbox(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = CoachTheme.colors
    val fill = colors.accent
    val tickColor = colors.onAccent
    val border = colors.fgTertiary
    Canvas(modifier.size(BoxSize).alpha(if (enabled) 1f else .45f)) {
        val side = size.minDimension
        val stroke = 1.dp.toPx()
        val corner = CornerRadius(3.dp.toPx())
        if (checked) {
            drawRoundRect(fill, size = Size(side, side), cornerRadius = corner)
            // 勾的两笔按 16dp 的方框坐标算，框多大都留同样的边：左下起笔，到底，再挑到右上。
            val tick = Path().apply {
                moveTo(side * 3.7f / 16f, side * 8.4f / 16f)
                lineTo(side * 6.7f / 16f, side * 11.3f / 16f)
                lineTo(side * 12.3f / 16f, side * 5.1f / 16f)
            }
            drawPath(tick, tickColor, style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            // 线框画在方框内 (stroke / 2, stroke / 2)，外沿不越出 16dp，和选中态占同一块地方。
            drawRoundRect(border, topLeft = Offset(stroke / 2, stroke / 2), size = Size(side - stroke, side - stroke),
                cornerRadius = corner, style = Stroke(stroke))
        }
    }
}
