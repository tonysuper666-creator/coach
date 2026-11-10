package com.coach.ui

import androidx.compose.runtime.Composable
import com.coach.client.ReaderHttp

/** Test-host entry into the existing source dialog. No application navigation or saved credentials. */
@Composable
fun ReaderMeasurementSurface(reader: ReaderHttp) {
    PrivateSourceReference(reader, "Synthetic p.1", tag = "baseline-source")
}
