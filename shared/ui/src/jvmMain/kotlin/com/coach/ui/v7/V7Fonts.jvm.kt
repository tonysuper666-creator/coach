package com.coach.ui.v7

import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

@Composable
internal actual fun rememberV7Fonts(): V7Fonts = remember {
    fun family(file: String) = FontFamily(listOf(300, 370, 400, 500, 600, 700).map { Font("font/$file.ttf", FontWeight(it)) })
    V7Fonts(family("literata"), family("ibm_plex_sans"), family("lxgw_wenkai"), family("ibm_plex_mono"))
}
@Composable internal actual fun rememberReducedMotion(): Boolean = false
