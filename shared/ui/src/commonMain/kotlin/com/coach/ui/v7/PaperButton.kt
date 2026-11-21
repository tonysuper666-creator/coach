package com.coach.ui.v7
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme

@Composable
fun PaperButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp), content: @Composable RowScope.() -> Unit) {
    val c = CoachTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val base = if (c.dark) CoachV7.Dark.btnBg else CoachV7.Light.btnBg
    val shape = RoundedCornerShape(CoachV7.Radius.btn)
    Row(modifier.v7FocusRing(shape).paperShadow("button", c.dark).clip(shape).v7ButtonSurface(base, interaction, enabled).border(BorderStroke(1.dp, c.borderSubtle), shape)
        .v7ButtonClick(interaction, enabled, onClick).defaultMinSize(minHeight = if (LocalDeskMetrics.current.inner) 36.dp else 44.dp).padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        CompositionLocalProvider(LocalContentColor provides c.fgPrimary.copy(alpha = if (enabled) 1f else .4f)) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}
@Composable
fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp), content: @Composable RowScope.() -> Unit) {
    val c = CoachTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(CoachV7.Radius.btn)
    Row(modifier.v7FocusRing(shape).clip(shape).v7ButtonSurface(c.actionPrimary.copy(alpha = if (enabled) 1f else .4f), interaction, enabled, primary = true)
        .v7ButtonClick(interaction, enabled, onClick).defaultMinSize(minHeight = if (LocalDeskMetrics.current.inner) 36.dp else 44.dp).padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        CompositionLocalProvider(LocalContentColor provides c.onAccent.copy(alpha = if (enabled) 1f else .7f)) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = CoachV7.Weight.uiStrong)) { content() }
        }
    }
}

/** Desktop owns mouse feedback; Android keeps its original fill and indication. */
@Composable
internal expect fun Modifier.v7ButtonSurface(base: Color, interaction: MutableInteractionSource,
    enabled: Boolean, primary: Boolean = false): Modifier

@Composable
internal expect fun Modifier.v7ButtonClick(interaction: MutableInteractionSource,
    enabled: Boolean, onClick: () -> Unit): Modifier
