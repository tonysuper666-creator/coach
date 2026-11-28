package com.coach.desktop.incident

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
    onNeverShow: () -> Unit = {},
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
    var actionsOpen by remember { mutableStateOf(false) }
    // 发出去了就把菜单收起，让阅读区恢复可点；发失败再把菜单打开，说清为什么没发出去。
    LaunchedEffect(notice) { if (notice != null) actionsOpen = true }
    CoachTheme(darkTheme) {
    val colors = CoachTheme.colors
    // 顶栏角落的小徽标：不占一整行、不盖阅读区。点开才有 查看 / 发送 / 不发。
    Box {
        Surface(
            onClick = { actionsOpen = true },
            modifier = modifier.testTag("incident-exit-prompt"),
            shape = RoundedCornerShape(percent = 50),
            color = colors.surface1,
            contentColor = colors.fgSecondary,
            border = BorderStroke(1.dp, colors.borderSubtle),
        ) {
            Text(
                "上次异常退出",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).testTag("incident-badge-label"),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false,
            )
        }
        DropdownMenu(
            expanded = actionsOpen,
            onDismissRequest = { actionsOpen = false },
            modifier = Modifier.testTag("incident-actions"),
        ) {
            DropdownMenuItem(
                text = { Text("查看") },
                onClick = { actionsOpen = false; state.preview = true },
                modifier = Modifier.testTag("incident-view"),
            )
            DropdownMenuItem(
                text = { Text(if (sending) "正在发送" else "发送") },
                enabled = !sending,
                onClick = {
                    // 已登录时立刻收起菜单；没登录就留着菜单，让「登录后才能发送」看得见。
                    if (identity != null) actionsOpen = false
                    state.send(identity)
                },
                modifier = Modifier.testTag("incident-send"),
            )
            DropdownMenuItem(
                text = { Text("不发") },
                enabled = !sending,
                onClick = { actionsOpen = false; state.decline() },
                modifier = Modifier.testTag("incident-decline"),
            )
            DropdownMenuItem(
                text = { Text("不再提示") },
                onClick = { actionsOpen = false; onNeverShow() },
                modifier = Modifier.testTag("incident-never-ask"),
            )
            notice?.let { message ->
                DropdownMenuItem(
                    text = { Text(message, modifier = Modifier.testTag("incident-notice")) },
                    onClick = { actionsOpen = false },
                )
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
}
