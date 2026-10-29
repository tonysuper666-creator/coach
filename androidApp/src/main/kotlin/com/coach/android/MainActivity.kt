package com.coach.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.coach.ui.DiagnosticsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var folding by mutableStateOf("FLAT (no hinge)")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { info ->
                folding = info.displayFeatures.filterIsInstance<FoldingFeature>().joinToString { feature ->
                    "${feature.state} · ${feature.orientation} · separating=${feature.isSeparating} · ${feature.bounds}"
                }.ifEmpty { "FLAT (no hinge)" }
            }
        }
        setContent { DiagnosticsScreen(folding) }
    }
}
