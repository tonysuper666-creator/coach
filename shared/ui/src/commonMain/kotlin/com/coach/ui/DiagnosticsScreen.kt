package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun DiagnosticsScreen(foldingFeature: String) {
    val density = LocalDensity.current
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val width = maxWidth.value.roundToInt()
                val height = maxHeight.value.roundToInt()
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Coach", style = MaterialTheme.typography.headlineLarge)
                    Text("窗口 $width × $height dp")
                    Text("密度 ${density.density} · 字体缩放 ${density.fontScale}")
                    Text("FoldingFeature: $foldingFeature")
                }
            }
        }
    }
}
