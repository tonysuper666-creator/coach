package com.coach.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
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

/** Synthetic-only native test host; no application navigation references this entry point. */
@Composable
fun ReaderLifetimeExerciseSurface(reader: ReaderHttp) {
    var opened by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf("v1") }
    var scaled by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    Column(Modifier.fillMaxSize()) {
        Row {
            TextButton(onClick = { other = false; opened = true }, modifier = Modifier.testTag("lifetime-open")) { Text("Open synthetic") }
            TextButton(onClick = { other = true; opened = true }, modifier = Modifier.testTag("lifetime-other")) { Text("Other synthetic") }
            TextButton(onClick = { other = false; version = "v2"; opened = true }, modifier = Modifier.testTag("lifetime-version")) { Text("Synthetic v2") }
            TextButton(onClick = { scaled = !scaled }, modifier = Modifier.testTag("lifetime-density")) { Text("Test density") }
        }
        Box(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalDensity provides Density(density.density * if (scaled) 1.1f else 1f, density.fontScale)) {
                if (opened) PrivatePdfDocument(reader, PrivateDocument(if (other) "synthetic-other" else "synthetic-baseline", "Synthetic", if (other) "v1" else version, 24)) { opened = false }
            }
        }
    }
}
