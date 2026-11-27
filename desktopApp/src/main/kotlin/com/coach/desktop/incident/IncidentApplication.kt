package com.coach.desktop.incident

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.application
import com.coach.desktop.DesktopIdentity
import com.coach.desktop.IncidentPromptHost
import com.coach.desktop.LocalIncidentPromptHost
import kotlin.system.exitProcess

/**
 * Runs the desktop app and writes the clean-exit mark only after the app closed itself
 * (window close, update restart, auto-exit). A hard kill, a power cut, or Windows ending the
 * process at logoff or shutdown never reaches this line, so the next start offers the prompt.
 */
internal fun runCoachApplication(content: @Composable ApplicationScope.() -> Unit): Nothing {
    application(exitProcessOnExit = false, content = content)
    IncidentLog.markStartedSessionClean()
    exitProcess(0)
}

/**
 * The exit prompt for windows that do not go through WindowsTextbookGate (installs with a packaged
 * course). The textbook home places it under its top bar; anything else shows it at the top.
 */
@Composable
internal fun IncidentPromptLayer(
    identity: DesktopIdentity?,
    darkTheme: Boolean = com.coach.desktop.windowsThemeOverride(System.getProperty("coach.windows.theme")) ?: androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember { IncidentPromptState(scope) }
    var hostManagesPrompt by remember { mutableStateOf(false) }
    val currentIdentity by rememberUpdatedState(identity)
    val currentDarkTheme by rememberUpdatedState(darkTheme)
    LaunchedEffect(identity) { state.onIdentityChanged(identity) }
    val host = remember {
        IncidentPromptHost(
            onHostMounted = { hostManagesPrompt = it },
            prompt = {
                IncidentExitPrompt(
                    identity = currentIdentity,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    darkTheme = currentDarkTheme,
                    state = state,
                )
            },
        )
    }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalIncidentPromptHost provides host) { content() }
        if (!hostManagesPrompt) {
            IncidentExitPrompt(identity, Modifier.align(Alignment.TopCenter).padding(top = 8.dp), darkTheme, state)
        }
    }
}
