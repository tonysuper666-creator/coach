package com.coach.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import com.coach.client.ReaderHttp

/** Test-host entry into the existing source dialog. No application navigation or saved credentials. */
@Composable
fun ReaderMeasurementSurface(reader: ReaderHttp) {
    PrivateSourceReference(reader, "Synthetic p.1", tag = "baseline-source")
}

/** Separate behavior host: an already open book plus a question's source link, sharing the real reader scope. */
@Composable
fun ReaderCacheExerciseSurface(reader: ReaderHttp) {
    Column(Modifier.fillMaxSize()) {
        PrivateSourceReference(reader, "Synthetic p.1", tag = "cache-citation")
        Box(Modifier.weight(1f)) { PrivateReaderScreen(reader) {} }
    }
}
