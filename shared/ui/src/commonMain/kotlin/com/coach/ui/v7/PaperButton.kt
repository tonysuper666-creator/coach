package com.coach.ui.v7

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme

/** Secondary button: the same paper, radius and shadow as the approved V7 buttons, without a border. */
@Composable
fun PaperButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp), content: @Composable RowScope.() -> Unit) {
    val colors = CoachTheme.colors
    Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = MaterialTheme.shapes.small,
        contentPadding = contentPadding, elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (colors.dark) CoachV7.Dark.btnBg else CoachV7.Light.btnBg,
            contentColor = colors.fgPrimary), content = content)
}

@Composable
fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp), content: @Composable RowScope.() -> Unit) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = MaterialTheme.shapes.small,
        contentPadding = contentPadding, content = content)
}
