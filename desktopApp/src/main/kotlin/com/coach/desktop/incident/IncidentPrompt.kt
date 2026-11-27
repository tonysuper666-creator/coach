package com.coach.desktop.incident

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.desktop.DesktopIdentity
import com.coach.ui.theme.CoachTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NEED_LOGIN_NOTICE = "登录后才能发送。记录还在这台电脑上。"

@Stable
internal class IncidentPromptState(
    private val sendScope: CoroutineScope,
    initialOffer: PendingOffer? = IncidentLog.currentOffer(),
) {
    var pending by mutableStateOf(initialOffer)
        private set
    var preview by mutableStateOf(false)
    var sending by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    private var activeJob: Job? = null

    fun onIdentityChanged(identity: DesktopIdentity?) {
        if (identity != null && notice == NEED_LOGIN_NOTICE) {
            notice = null
        }
    }

    fun cancelSessionSend() {
        activeJob?.cancel()
        activeJob = null
        sending = false
    }

    fun send(identity: DesktopIdentity?) {
        val offer = pending ?: return
        if (sending) return
        if (identity == null) {
            notice = NEED_LOGIN_NOTICE
            return
        }
        sending = true
        notice = null
        val job = sendScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    IncidentUpload.send(identity, offer.text)
                }
                when (result) {
                    IncidentUploadResult.Sent -> {
                        IncidentLog.markSent(offer)
                        pending = null
                    }
                    IncidentUploadResult.NeedLogin -> notice = NEED_LOGIN_NOTICE
                    IncidentUploadResult.TooBig -> notice = "记录太大，没有发出去。记录还在这台电脑上。"
                    IncidentUploadResult.Limited -> notice = "发得太勤了，稍后再试。记录还在这台电脑上。"
                    IncidentUploadResult.NotSent -> notice = "没发出去，记录还在这台电脑上。"
                }
            } finally {
                sending = false
                activeJob = null
            }
        }
        activeJob = job
    }

    fun decline() {
        val offer = pending ?: return
        if (sending) return
        IncidentLog.decline(offer)
        pending = null
    }
}

@Composable
internal fun IncidentExitPrompt(
    identity: DesktopIdentity?,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
    state: IncidentPromptState = run {
        val scope = rememberCoroutineScope()
        remember { IncidentPromptState(scope) }
    },
) {
    DisposableEffect(state) {
        onDispose { state.preview = false }
    }
    val offer = state.pending ?: return
    val sending = state.sending
    val notice = state.notice
    CoachTheme(darkTheme) {
    val colors = CoachTheme.colors
    Surface(modifier.testTag("incident-exit-prompt"), color = colors.surface1, shadowElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "上次异常退出，要把记录发给开发者吗？",
                    color = colors.fgPrimary,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton({ state.preview = true }, Modifier.testTag("incident-view")) { Text("查看", maxLines = 1, softWrap = false) }
                TextButton(
                    { state.send(identity) },
                    Modifier.testTag("incident-send"),
                    enabled = !sending,
                ) { Text(if (sending) "正在发送" else "发送", maxLines = 1, softWrap = false) }
                TextButton(
                    { state.decline() },
                    Modifier.testTag("incident-decline"),
                    enabled = !sending,
                ) { Text("不发", maxLines = 1, softWrap = false) }
            }
            notice?.let { Text(it, color = colors.fgSecondary, modifier = Modifier.testTag("incident-notice")) }
        }
    }
    if (state.preview) {
        AlertDialog(
            onDismissRequest = { state.preview = false },
            title = { Text("要发送的记录") },
            text = {
                SelectionContainer {
                    Text(
                        offer.text,
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("incident-preview"),
                    )
                }
            },
            confirmButton = { TextButton({ state.preview = false }, Modifier.testTag("incident-preview-close")) { Text("关闭") } },
        )
    }
    }
}
