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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun IncidentExitPrompt(
    identity: DesktopIdentity?,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    var pending by remember { mutableStateOf(IncidentLog.currentOffer()) }
    var preview by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val offer = pending ?: return
    CoachTheme(darkTheme) {
    val colors = CoachTheme.colors
    Surface(modifier.testTag("incident-exit-prompt"), color = colors.surface1, shadowElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("上次异常退出，要把记录发给开发者吗？", color = colors.fgPrimary)
                TextButton({ preview = true }, Modifier.testTag("incident-view")) { Text("查看") }
                TextButton(
                    {
                        if (sending) return@TextButton
                        sending = true
                        notice = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                if (identity == null) IncidentUploadResult.NeedLogin
                                else IncidentUpload.send(identity, offer.text)
                            }
                            when (result) {
                                IncidentUploadResult.Sent -> {
                                    IncidentLog.markSent(offer)
                                    pending = null
                                }
                                IncidentUploadResult.NeedLogin -> notice = "登录后才能发送。记录还在这台电脑上。"
                                IncidentUploadResult.TooBig -> notice = "记录太大，没有发出去。记录还在这台电脑上。"
                                IncidentUploadResult.Limited -> notice = "发得太勤了，稍后再试。记录还在这台电脑上。"
                                IncidentUploadResult.NotSent -> notice = "没发出去，记录还在这台电脑上。"
                            }
                            sending = false
                        }
                    },
                    Modifier.testTag("incident-send"),
                    enabled = !sending,
                ) { Text(if (sending) "正在发送" else "发送") }
                TextButton(
                    {
                        IncidentLog.decline(offer)
                        pending = null
                    },
                    Modifier.testTag("incident-decline"),
                    enabled = !sending,
                ) { Text("不发") }
            }
            notice?.let { Text(it, color = colors.fgSecondary, modifier = Modifier.testTag("incident-notice")) }
        }
    }
    if (preview) {
        AlertDialog(
            onDismissRequest = { preview = false },
            title = { Text("要发送的记录") },
            text = {
                SelectionContainer {
                    Text(
                        offer.text,
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("incident-preview"),
                    )
                }
            },
            confirmButton = { TextButton({ preview = false }, Modifier.testTag("incident-preview-close")) { Text("关闭") } },
        )
    }
    }
}
