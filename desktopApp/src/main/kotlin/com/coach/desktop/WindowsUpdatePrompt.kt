package com.coach.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.coach.ui.CoachTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

@Composable
internal fun WindowsUpdatePrompt(save: suspend () -> Boolean, close: () -> Unit) {
    var channel by remember { mutableStateOf<WindowsUpdate?>(null) }
    var release by remember { mutableStateOf<WindowsRelease?>(null) }
    var transfer by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try {
            val result = withContext(Dispatchers.IO) {
                WindowsUpdate.installed()?.let { it to it.check() }
            } ?: return@LaunchedEffect
            channel = result.first
            release = result.second
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Offline and invalid manifests leave normal startup untouched.
        }
    }

    val update = channel ?: return
    val available = release ?: return
    fun dismiss() {
        transfer?.cancel()
        release = null
    }

    fun install() {
        if (transfer != null) return
        message = "正在下载并校验安装包…"
        transfer = scope.launch {
            try {
                val msi = withContext(Dispatchers.IO) {
                    update.download(available, Path.of(System.getProperty("java.io.tmpdir"), "LearningCoach-updates"))
                }
                message = "正在保存学习进度…"
                if (!save()) {
                    message = "学习进度还没有保存。可以继续学习，或重试更新。"
                    return@launch
                }
                currentCoroutineContext().ensureActive()
                val installer = Path.of(System.getenv("SystemRoot"), "System32", "msiexec.exe")
                ProcessBuilder(installer.toString(), "/i", msi.toString(), "/passive", "/norestart").start()
                release = null
                close()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                message = "更新未能开始。可以继续使用当前版本，或重试更新。"
            } finally {
                transfer = null
            }
        }
    }

    CoachTheme(windowsThemeOverride(System.getProperty("coach.windows.theme")) ?: isSystemInDarkTheme()) {
        AlertDialog(
            onDismissRequest = ::dismiss,
            title = { Text("有新版本") },
            text = { Text(message.ifEmpty { "LearningCoach ${available.version} 已可安装。更新前会保存学习进度。" }) },
            confirmButton = {
                if (transfer == null) TextButton(onClick = ::install) {
                    Text(if (message.isEmpty()) "立即更新" else "重试更新")
                }
            },
            dismissButton = { TextButton(onClick = ::dismiss) { Text(if (transfer == null) "继续学习" else "取消更新") } },
        )
    }
}
