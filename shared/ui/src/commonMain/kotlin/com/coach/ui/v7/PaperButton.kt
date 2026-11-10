package com.coach.ui.v7
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    Row(modifier.paperShadow("button", c.dark).clip(RoundedCornerShape(8.dp)).background(if (c.dark) CoachV7.Dark.btnBg else CoachV7.Light.btnBg)
        .clickable(enabled = enabled, onClick = onClick).defaultMinSize(minHeight = if (LocalDeskMetrics.current.inner) 36.dp else 44.dp).padding(contentPadding),
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
    Row(modifier.clip(RoundedCornerShape(8.dp)).background(c.fgPrimary.copy(alpha = if (enabled) 1f else .4f))
        .clickable(enabled = enabled, onClick = onClick).defaultMinSize(minHeight = if (LocalDeskMetrics.current.inner) 36.dp else 44.dp).padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        CompositionLocalProvider(LocalContentColor provides c.surface1) { ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() } }
    }
}
