package com.coach.ui.pageturn

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.coach.ui.v7.V7Scrollbar

/** 桌面滚动阅读的右侧滚动条：共享的 V7Scrollbar，长度和位置按实际可滚范围走，深浅色和拖动沿用它。 */
@Composable
fun BoxScope.ReadScrollbarDesktop(state: ScrollReadState) {
    V7Scrollbar(rememberScrollbarAdapter(state.list), Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag("scroll-read-scrollbar"))
}
