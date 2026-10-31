package com.coach.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme

/** Window size in dp is measured from the content area, so split-screen and Mac windows follow the same rules as the Fold8 screens. */
@Composable
fun AdaptiveScaffold(
    modifier: Modifier = Modifier,
    hinge: Hinge? = null,
    asideOpen: Boolean = false,
    onAsideDismiss: () -> Unit = {},
    nav: @Composable (NavStyle) -> Unit,
    conversation: @Composable () -> Unit,
    aside: @Composable () -> Unit = {},
    outline: @Composable () -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxSize().background(CoachTheme.colors.bgCanvas)) {
        val layout = LayoutRules.compute(WindowSpec(maxWidth.value, maxHeight.value, hinge))
        AdaptiveScaffoldLayout(layout, asideOpen, onAsideDismiss, nav, conversation, aside, outline)
    }
}

/**
 * The three panes are `movableContentOf`: when a fold/unfold moves them to a different parent, their composition
 * (scroll state, selection, text-field internals, running streams) moves with them instead of being rebuilt.
 */
@Composable
fun AdaptiveScaffoldLayout(
    layout: PaneLayout,
    asideOpen: Boolean,
    onAsideDismiss: () -> Unit,
    nav: @Composable (NavStyle) -> Unit,
    conversation: @Composable () -> Unit,
    aside: @Composable () -> Unit,
    outline: @Composable () -> Unit,
) {
    val conversationNow by rememberUpdatedState(conversation)
    val asideNow by rememberUpdatedState(aside)
    val outlineNow by rememberUpdatedState(outline)
    val movableConversation = remember { movableContentOf { conversationNow() } }
    val movableAside = remember { movableContentOf { asideNow() } }
    val movableOutline = remember { movableContentOf { outlineNow() } }
    val colors = CoachTheme.colors
    when (layout.nav) {
        NavStyle.BottomBar -> Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { movableConversation() }
                nav(NavStyle.BottomBar)
            }
            if (asideOpen) {
                Scrim(onAsideDismiss)
                BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(maxHeight * 0.6f).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(colors.surface1)) { movableAside() }
                }
            }
        }
        else -> Row(Modifier.fillMaxSize()) {
            nav(layout.nav)
            if (layout.outlineDocked) Box(Modifier.width(layout.outlineWidthDp.dp).fillMaxHeight().background(colors.surface1)) { movableOutline() }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxHeight().widthIn(max = layout.conversationMaxDp.dp).align(Alignment.TopCenter)) { movableConversation() }
                if (layout.aside == AsidePlacement.Overlay && asideOpen) {
                    Scrim(onAsideDismiss)
                    Box(Modifier.align(Alignment.CenterEnd).width(layout.asideWidthDp.dp).fillMaxHeight().background(colors.surface1)) { movableAside() }
                }
                layout.dividerAtDp?.let { x ->
                    Box(Modifier.offset(x = (x - layout.navWidthDp).dp).width(1.dp).fillMaxHeight().background(colors.borderStrong))
                }
            }
            if (layout.aside == AsidePlacement.Docked) Box(Modifier.width(layout.asideWidthDp.dp).fillMaxHeight().background(colors.surface1)) { movableAside() }
        }
    }
}

@Composable
private fun Scrim(onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
}
