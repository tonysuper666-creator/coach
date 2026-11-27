package com.coach.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Desktop installs these. Android and tests leave the local unset, so the buttons stay hidden. */
internal class IncidentFolderActions(
    val openFolder: () -> Unit,
    val clearRecords: () -> Unit,
)

internal val LocalIncidentFolderActions = staticCompositionLocalOf<IncidentFolderActions?> { null }

/** Desktop gate installs this so the exit prompt renders on Home and stays off the reader and subpages. */
internal class IncidentPromptHost(
    val onHostMounted: (Boolean) -> Unit,
    val prompt: @Composable () -> Unit,
)

internal val LocalIncidentPromptHost = staticCompositionLocalOf<IncidentPromptHost?> { null }
