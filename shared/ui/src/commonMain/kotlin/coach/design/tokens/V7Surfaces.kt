// Generated from approved V7 HTML; do not hand-edit.
package coach.design.tokens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class V7SurfaceShadow(val x: Dp, val y: Dp, val blur: Dp, val spread: Dp, val color: Color, val inset: Boolean)
object V7Surfaces {
    object Light {
        val sheet = listOf(V7SurfaceShadow(0.dp, 8.dp, 24.dp, 0.dp, Color(0x1F141412), false))
        val menu = listOf(V7SurfaceShadow(0.dp, 8.dp, 24.dp, 0.dp, Color(0x1F141412), false))
        val card = listOf(V7SurfaceShadow(0.dp, 1.dp, 2.dp, 0.dp, Color(0x14141412), false))
        val option = listOf(V7SurfaceShadow(0.dp, 1.dp, 2.dp, 0.dp, Color(0x14141412), false))
        val button = listOf(V7SurfaceShadow(0.dp, 1.dp, 2.dp, 0.dp, Color(0x14141412), false))
    }
    object Dark {
        val sheet = listOf(V7SurfaceShadow(0.dp, 8.dp, 24.dp, 0.dp, Color(0x66000000), false))
        val menu = listOf(V7SurfaceShadow(0.dp, 8.dp, 24.dp, 0.dp, Color(0x66000000), false))
        val card = listOf(V7SurfaceShadow(0.dp, 1.dp, 2.dp, 0.dp, Color(0x4D000000), false))
        val option = listOf(V7SurfaceShadow(0.dp, 1.dp, 2.dp, 0.dp, Color(0x4D000000), false))
        val button = listOf(V7SurfaceShadow(0.dp, 1.dp, 2.dp, 0.dp, Color(0x4D000000), false))
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
