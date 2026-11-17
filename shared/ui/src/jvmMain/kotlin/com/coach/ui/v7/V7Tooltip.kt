package com.coach.ui.v7

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme

private val AnchorGap = 6.dp

/**
 * Places the bubble right above its anchor: a 6 dp gap, centred horizontally, kept inside the
 * window's left and right edges, and moved below the anchor only when there is no room above.
 * It never covers the anchor — a bubble over the cursor reads as the pointer leaving, which
 * hides the tooltip and makes it flicker while the pointer stands still.
 */
@OptIn(ExperimentalFoundationApi::class)
private object AboveAnchorPlacement : TooltipPlacement {
    @Composable
    override fun positionProvider(cursorPosition: Offset): PopupPositionProvider {
        val gap = with(LocalDensity.current) { AnchorGap.roundToPx() }
        return remember(gap) { AboveAnchorPositionProvider(gap) }
    }
}

private class AboveAnchorPositionProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxLeft = maxOf(0, windowSize.width - popupContentSize.width)
        val centered = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val left = centered.coerceIn(0, maxLeft)
        val above = anchorBounds.top - gap - popupContentSize.height
        val below = anchorBounds.bottom + gap
        val top = when {
            above >= 0 -> above
            below + popupContentSize.height <= windowSize.height -> below
            // No room on either side: clip the top rather than cover the anchor.
            else -> above
        }
        return IntOffset(left, top)
    }
}

/** A short, supplemental label. Keep essential instructions in the page itself. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun V7Tooltip(text: String, modifier: Modifier = Modifier, shortcut: String? = null,
    content: @Composable () -> Unit) {
    val c = CoachTheme.colors
    TooltipArea(modifier = modifier, delayMillis = 500, tooltipPlacement = AboveAnchorPlacement, tooltip = {
        val shape = RoundedCornerShape(CoachV7.Radius.s)
        Row(Modifier.background(c.surface1, shape).border(1.dp, c.borderSubtle, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, color = c.fgPrimary, fontFamily = LocalV7Fonts.current.ui, fontSize = CoachV7.Size.ui)
            if (shortcut != null) Text(shortcut, color = c.fgSecondary,
                fontFamily = LocalV7Fonts.current.numbers, fontSize = CoachV7.Size.ui)
        }
    }, content = content)
}

@Composable
internal actual fun V7NavigationTip(text: String, modifier: Modifier, content: @Composable () -> Unit) =
    V7Tooltip(text, modifier, content = content)
