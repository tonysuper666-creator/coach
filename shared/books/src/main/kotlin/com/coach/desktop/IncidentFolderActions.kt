package com.coach.desktop

import androidx.compose.runtime.staticCompositionLocalOf

/** Desktop installs these. Android and tests leave the local unset, so the buttons stay hidden. */
internal class IncidentFolderActions(
    val openFolder: () -> Unit,
    val clearRecords: () -> Unit,
)

internal val LocalIncidentFolderActions = staticCompositionLocalOf<IncidentFolderActions?> { null }
