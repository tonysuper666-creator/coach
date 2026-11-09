// Generated from approved V7 HTML; do not hand-edit.
package coach.design.tokens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class V7SurfaceShadow(val x: Dp, val y: Dp, val blur: Dp, val spread: Dp, val color: Color, val inset: Boolean)
object V7Surfaces {
    object Light {
        val sheet = listOf(V7SurfaceShadow(0.dp, 1.dp, 2.dp, 0.dp, Color(0x0F3C2D19), false), V7SurfaceShadow(0.dp, 10.dp, 30.dp, 0.dp, Color(0x173C2D19), false))
        val menu = listOf(V7SurfaceShadow(0.dp, 1.dp, 2.dp, 0.dp, Color(0x143C2D19), false), V7SurfaceShadow(0.dp, 6.dp, 18.dp, 0.dp, Color(0x1A3C2D19), false))
        val card = listOf(V7SurfaceShadow(0.dp, 1.dp, 2.dp, 0.dp, Color(0x123C2D19), false), V7SurfaceShadow(0.dp, 2.dp, 8.dp, 0.dp, Color(0x0D3C2D19), false))
        val option = listOf(V7SurfaceShadow(0.dp, 1.dp, 2.dp, 0.dp, Color(0x1A3C2D19), false), V7SurfaceShadow(0.dp, 2.dp, 6.dp, 0.dp, Color(0x0F3C2D19), false))
        val button = listOf(V7SurfaceShadow(0.dp, 1.dp, 2.dp, 0.dp, Color(0x1A3C2D19), false), V7SurfaceShadow(0.dp, 2.dp, 6.dp, 0.dp, Color(0x0F3C2D19), false))
    }
    object Dark {
        val sheet = listOf(V7SurfaceShadow(0.dp, 1.dp, 0.dp, 0.dp, Color(0x0AFFFFFF), true), V7SurfaceShadow(0.dp, 12.dp, 32.dp, 0.dp, Color(0x80000000), false))
        val menu = listOf(V7SurfaceShadow(0.dp, 1.dp, 0.dp, 0.dp, Color(0x0AFFFFFF), true), V7SurfaceShadow(0.dp, 8.dp, 22.dp, 0.dp, Color(0x73000000), false))
        val card = listOf(V7SurfaceShadow(0.dp, 1.dp, 0.dp, 0.dp, Color(0x09FFFFFF), true), V7SurfaceShadow(0.dp, 2.dp, 8.dp, 0.dp, Color(0x59000000), false))
        val option = listOf(V7SurfaceShadow(0.dp, 1.dp, 0.dp, 0.dp, Color(0x0AFFFFFF), true), V7SurfaceShadow(0.dp, 1.dp, 3.dp, 0.dp, Color(0x59000000), false))
        val button = listOf(V7SurfaceShadow(0.dp, 1.dp, 0.dp, 0.dp, Color(0x0AFFFFFF), true))
    }
    fun shadows(kind: String, dark: Boolean): List<V7SurfaceShadow> = when (kind) {
        "sheet" -> if (dark) Dark.sheet else Light.sheet
        "menu" -> if (dark) Dark.menu else Light.menu
        "card" -> if (dark) Dark.card else Light.card
        "option" -> if (dark) Dark.option else Light.option
        "button" -> if (dark) Dark.button else Light.button
        else -> if (dark) Dark.card else Light.card
    }
}
