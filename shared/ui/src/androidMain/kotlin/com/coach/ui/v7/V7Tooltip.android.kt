package com.coach.ui.v7

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 触屏没有悬停，和 [V7NavigationTip] 一样只放内容；必要说明仍写在页面里。 */
@Composable
fun V7Tooltip(text: String, modifier: Modifier = Modifier, shortcut: String? = null,
    content: @Composable () -> Unit) {
    Box(modifier) { content() }
}
