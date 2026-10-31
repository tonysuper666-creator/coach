package com.coach.android

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.metrics.performance.JankStats
import com.coach.ui.input.LocalEnterSends
import com.coach.ui.lab.FormFactorLab
import com.coach.ui.lab.LabState

/** Fold/unfold verification surface (not a product screen). `adb shell wm size 1248x1972` / `1848x2448` plays the two Fold8 screens. */
class LabActivity : ComponentActivity() {
    class Holder : ViewModel() { val state = LabState() }
    private var jank: JankStats? = null
    private var frames = 0
    private var badFrames = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val holder = ViewModelProvider(this)[Holder::class.java]
        setContent {
            // A hardware keyboard sends on Enter; the soft keyboard inserts a newline.
            val hardware = resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS
            CompositionLocalProvider(LocalEnterSends provides hardware) { FormFactorLab(holder.state) }
        }
        jank = JankStats.createAndTrack(window) { frame -> frames++; if (frame.isJank) badFrames++ }
    }
    override fun onPause() {
        Log.i("CoachLab", "JANK frames=$frames jank=$badFrames")
        super.onPause()
    }
    override fun onDestroy() { jank?.isTrackingEnabled = false; super.onDestroy() }
}
