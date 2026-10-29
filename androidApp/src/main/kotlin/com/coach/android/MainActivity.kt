package com.coach.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.coach.ui.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import android.util.Log
import androidx.metrics.performance.JankStats
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var owner: ReplayOwner
    private var jank: JankStats? = null
    private var frames = 0
    private var badFrames = 0
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
        owner = ViewModelProvider(this)[ReplayOwner::class.java]
        owner.attach(this)
        val chinesePage = intent.getIntExtra("chinesePage", -1).takeIf { it >= 0 }
        setContent { RenderingReplayScreen(owner.state, owner.corpus, false, folding, owner::save, { Log.i("CoachRender", it) }, chinesePage, intent.getIntExtra("autoplay", 0), intent.getBooleanExtra("baseline", false), intent.getLongExtra("tickMs", 50L), intent.getIntExtra("streamOnly", -1), intent.getIntExtra("chunk", 30), intent.getLongExtra("runId", 0L)) }
        // JankStats needs the DecorView, which setContent installs.
        jank = JankStats.createAndTrack(window) { frame ->
            if (owner.state.running) { frames++; if (frame.isJank) badFrames++ }
        }
    }
    override fun onPause() {
        owner.save()
        Log.i("CoachRender", "JANK frames=$frames jank=$badFrames percent=${if (frames == 0) 0.0 else badFrames * 100.0 / frames}")
        super.onPause()
    }
    override fun onDestroy() { jank?.isTrackingEnabled = false; super.onDestroy() }
}

class ReplayOwner : ViewModel() {
    lateinit var state: ReplayState; private set
    var corpus: ReplayCorpus? = null; private set
    private var preferences: android.content.SharedPreferences? = null
    fun attach(activity: MainActivity) {
        if (::state.isInitialized) return
        preferences = activity.getSharedPreferences("render-replay", android.content.Context.MODE_PRIVATE)
        state = ReplayState(preferences?.getString("state", null))
        corpus = java.io.File(activity.filesDir, "replay.json").takeIf { it.exists() }?.let { ReplayCorpus.load(it.readText()) }
    }
    fun save() {
        // Synchronous private-file commit at lifecycle boundaries; replay is a small spike state.
        preferences?.edit()?.putString("state", state.snapshot())?.commit()
    }
}
