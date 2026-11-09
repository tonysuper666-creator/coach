package com.coach.android.reader.v2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.coach.ui.reader.v2.*
import kotlinx.coroutines.launch

/** WindowManager reports bounds in window pixels, not Compose content-local dp.
 * Subtract content origin (status bars/insets) before laying out either pane. */
fun readerFold(feature: FoldingFeature, density: Float, contentOrigin: Offset): ReaderFold {
    require(density.isFinite() && density > 0)
    val b = feature.bounds
    return ReaderFold(ReaderPane((b.left - contentOrigin.x) / density, (b.top - contentOrigin.y) / density,
        (b.right - contentOrigin.x) / density, (b.bottom - contentOrigin.y) / density),
        feature.orientation == FoldingFeature.Orientation.VERTICAL,
        feature.isSeparating || feature.state == FoldingFeature.State.HALF_OPENED || feature.occlusionType == FoldingFeature.OcclusionType.FULL)
}

class ReaderV2FixtureActivity : ComponentActivity() {
    private var session by mutableStateOf(ReaderV2Fixture.session())
    private var features by mutableStateOf<List<FoldingFeature>>(emptyList())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("reader-v2-synthetic-only", MODE_PRIVATE)
        (savedInstanceState?.getString("session") ?: prefs.getString("session", null))?.let { snapshot ->
            ReaderSession.restore(snapshot)?.takeIf { it.documentId == ReaderV2Fixture.DocumentId && it.sha256 == ReaderV2Fixture.Sha256 }?.let { session = it }
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            WindowInfoTracker.getOrCreate(this@ReaderV2FixtureActivity).windowLayoutInfo(this@ReaderV2FixtureActivity).collect { info ->
                features = info.displayFeatures.filterIsInstance<FoldingFeature>()
            }
        } }
        setContent { MaterialTheme {
            val density = LocalDensity.current.density
            var origin by remember { mutableStateOf(Offset.Zero) }
            BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
                val readerWindow = ReaderWindow(maxWidth.value, maxHeight.value, features.firstOrNull()?.let { readerFold(it, density, origin) })
                BackHandler(session.handlesReaderBack(readerPanes(readerWindow, session.railVisible).arrangement)) { session = session.closeDrawer() }
                ReaderV2FixtureHost(readerWindow, session, { session = it })
            }
        } }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("session", session.snapshot()); super.onSaveInstanceState(outState) }
    override fun onStop() {
        getSharedPreferences("reader-v2-synthetic-only", MODE_PRIVATE).edit().putString("session", session.snapshot()).commit()
        super.onStop()
    }
}
