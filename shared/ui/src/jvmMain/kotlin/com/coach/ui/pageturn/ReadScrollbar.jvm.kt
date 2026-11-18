package com.coach.ui.pageturn

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.coach.ui.v7.V7Scrollbar

@Composable
internal actual fun ReadScrollbar(state: LazyListState, modifier: Modifier) {
    V7Scrollbar(rememberScrollbarAdapter(state), modifier)
}
