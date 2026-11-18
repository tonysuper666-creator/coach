package com.coach.ui.pageturn

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 滚动阅读的右侧滚动条。桌面走共享的 V7Scrollbar；移动端只画只读的比例条。 */
@Composable
internal expect fun ReadScrollbar(state: LazyListState, modifier: Modifier)
