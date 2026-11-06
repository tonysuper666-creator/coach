package com.coach.android

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun ReminderSettings(reminder: ReviewReminder) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    @Suppress("UNUSED_VARIABLE") val refresh = revision
    LaunchedEffect(reminder) { reminder.dismissNotification() }
    TextButton(onClick = { show = true; revision++ }) {
        Text(when {
            !reminder.enabled -> "每日提醒 · 已关闭"
            !reminder.notificationsAllowed() -> "每日提醒 · 通知未允许"
            else -> "每日提醒 %02d:%02d".format(reminder.hour, reminder.minute)
        })
    }
    if (show) {
        // Refresh after permission results, time changes and returning from Android settings.
        AlertDialog(onDismissRequest = { show = false }, title = { Text("复习提醒") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = reminder.enabled, role = Role.Switch, onValueChange = { enabled ->
                        reminder.update(enabled = enabled)
                        revision++
                        if (enabled && !reminder.notificationsAllowed() && !reminder.permissionAsked) {
                            reminder.markPermissionAsked()
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }).clearAndSetSemantics {
                        // Expose one labelled, operable switch rather than separate text/decoration.
                        contentDescription = "每日到期提醒"
                        role = Role.Switch
                        toggleableState = ToggleableState(reminder.enabled)
                    }, verticalAlignment = Alignment.CenterVertically) {
                    Text("每日到期提醒", Modifier.weight(1f))
                    Switch(checked = reminder.enabled, onCheckedChange = null)
                }
                TextButton(onClick = {
                    TimePickerDialog(context, { _, hour, minute -> reminder.update(hour = hour, minute = minute); revision++ },
                        reminder.hour, reminder.minute, true).show()
                }) { Text("提醒时间 %02d:%02d".format(reminder.hour, reminder.minute)) }
                Text("按手机当地时间和本机已同步的复习队列提醒；没有到期知识点时不发送。省电模式可能延后送达。")
                if (reminder.enabled && !reminder.notificationsAllowed()) {
                    Text("通知权限未开启或提醒通知已关闭，暂时无法提醒。请到系统设置中允许通知。", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }) { Text("打开系统通知设置") }
                }
            }
        }, confirmButton = { TextButton(onClick = { show = false }) { Text("完成") } })
    }
}
