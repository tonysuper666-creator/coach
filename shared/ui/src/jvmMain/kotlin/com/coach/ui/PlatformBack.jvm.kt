package com.coach.ui

import androidx.compose.runtime.Composable

// Desktop Escape is handled by the shared focus tree; there is no OS activity stack.
@Composable
internal actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
