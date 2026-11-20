package com.coach.android

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private object UpdateCheck {
    private val started = AtomicBoolean(false)

    /** One channel read per process launch; a second activity instance must not ask again. */
    fun first(): Boolean = started.compareAndSet(false, true)
}

/**
 * Offers a published APK the way the Windows build offers an MSI: check at startup, download, verify size,
 * SHA-256 and signing key, then hand the package to the system installer. Offline or invalid manifests leave
 * normal startup untouched.
 */
@Composable
internal fun AndroidUpdatePrompt() {
    if (!remember { UpdateCheck.first() }) return
    val context = LocalContext.current
    var channel by remember { mutableStateOf<AndroidUpdate?>(null) }
    var release by remember { mutableStateOf<AndroidRelease?>(null) }
    var transfer by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val result = withContext(Dispatchers.IO) {
                AndroidUpdate.installed()?.let { it to it.check() }
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
            val coroutine = currentCoroutineContext()
            try {
                val apk = withContext(Dispatchers.IO) {
                    update.download(available, File(context.cacheDir, "updates")) { coroutine.ensureActive() }
                }
                message = "正在核对安装包…"
                val identity = withContext(Dispatchers.IO) { archiveIdentity(context, apk) }
                val installed = withContext(Dispatchers.IO) { installedIdentity(context) }
                if (!sameInstallIdentity(installed, identity)) {
                    apk.delete()
                    message = "新安装包与当前版本签名不一致，已停止安装。"
                    return@launch
                }
                if (!context.packageManager.canRequestPackageInstalls()) {
                    message = "请先在系统设置里允许 LearningCoach 安装应用，然后重试。"
                    runCatching { context.startActivity(installPermissionIntent(context.packageName)) }
                    return@launch
                }
                context.startActivity(updateInstallIntent(context, apk))
                release = null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                message = "更新未能开始。可以继续使用当前版本，或重试更新。"
            } finally {
                transfer = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("有新版本") },
        text = { Text(message.ifEmpty { "LearningCoach ${available.version} 已可安装。更新不会清除书、笔记和进度。" }) },
        confirmButton = {
            if (transfer == null) TextButton(onClick = ::install) {
                Text(if (message.isEmpty()) "立即更新" else "重试更新")
            }
        },
        dismissButton = { TextButton(onClick = ::dismiss) { Text(if (transfer == null) "以后再说" else "取消更新") } },
    )
}
