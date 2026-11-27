package com.coach.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.coach.client.PairingClient
import com.coach.desktop.books.LearningJournal
import com.coach.desktop.incident.coachVersion
import com.coach.domain.str
import com.sun.jna.Native
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class SessionDisplay(val method: String, val nickname: String = "", val maskedEmail: String = "")

internal val LocalLogout = staticCompositionLocalOf<suspend (Boolean) -> Unit> { { error("No active session") } }

@Composable
internal fun AccountSection(identity: DesktopIdentity, clientDirectory: Path, logout: suspend (Boolean) -> Unit) {
    var display by remember(identity) { mutableStateOf<SessionDisplay?>(null) }
    var pending by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(identity) {
        val store = DesktopCredentials(clientDirectory)
        display = withContext(Dispatchers.IO) { runCatching { store.display(identity) }.getOrNull() }
        val client = PairingClient(identity.serverUrl)
        try {
            val result = client.account(identity.credentials)
            val refreshed = SessionDisplay(result.str("method"), result.str("nickname"), result.str("maskedEmail"))
            display = refreshed
            withContext(Dispatchers.IO) { store.rememberDisplay(identity, refreshed) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { notice = "暂时无法刷新账号资料。" }
        finally { client.close() }
    }
    val account = display
    Text(account?.nickname?.ifBlank { null } ?: when (account?.method) {
        "配对码" -> "已配对的设备"
        "Sable" -> "Sable 用户"
        else -> "已登录"
    }, style = MaterialTheme.typography.titleMedium)
    if (account?.method == "Sable") Text(account.maskedEmail.ifBlank { "账号未提供邮箱" })
    Text("登录方式　${account?.method ?: "待确认"}")
    Text("本设备　${System.getenv("COMPUTERNAME") ?: "本机"}")
    fun leave(switching: Boolean) {
        if (pending) return
        pending = true
        scope.launch {
            try { logout(switching) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                notice = if (failure.message == "save") "草稿未保存，请稍后重试。"
                else "未能完成退出，请联网后重试。"
            } finally { pending = false }
        }
    }
    Row {
        TextButton({ leave(true) }, enabled = !pending) { Text("切换账号") }
        TextButton({ leave(false) }, enabled = !pending) { Text("退出登录") }
    }
    if (notice.isNotEmpty()) Text(notice)
}

internal class SyncDisplay(directory: Path) {
    private val marker = directory.resolve("last-learning-sync")
    var last by mutableStateOf(runCatching { Files.getLastModifiedTime(marker).toInstant() }.getOrNull())
        private set
    var failure by mutableStateOf("")
    fun confirm(): Instant {
        Files.writeString(marker, "")
        return Files.getLastModifiedTime(marker).toInstant()
    }
    fun record(confirmed: Instant?) {
        if (confirmed != null) last = confirmed
        failure = if (confirmed == null) "服务器尚未确认全部学习数据，稍后会自动重试。" else ""
    }
}

@Composable
internal fun DataSection(profile: LearningProfile, sync: SyncDisplay, updateResult: String, onCheck: () -> Unit,
    offerReports: Boolean, onReports: (Boolean) -> Unit) {
    val directory = profile.directory
    val size by produceState<String>("正在统计…", directory) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = Files.walk(directory).use { paths ->
                    paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum()
                }
                "%.1f MB".format(bytes / 1048576.0)
            }.getOrDefault("暂时无法统计")
        }
    }
    val arch = remember {
        runCatching { windowsUpdateArch(Native.load("kernel32", UpdateKernel32::class.java)) }.getOrNull()
            ?.let { if (it == "arm64") "ARM64" else it } ?: System.getProperty("os.arch")
    }
    val date = remember {
        SyncDisplay::class.java.getResourceAsStream("/coach-build-date.txt")?.bufferedReader()?.use { it.readText().trim() } ?: "未知"
    }
    Text("LearningCoach ${coachVersion()} · $arch")
    Text("构建日期　$date")
    TextButton(onCheck, enabled = updateResult != "正在检查更新…") { Text("检查更新") }
    if (updateResult.isNotBlank()) Text(updateResult)
    Text(when {
        !LearningJournal.enabled -> "学习同步未开启"
        profile.scope == null -> "学习同步尚未连接到账号"
        else -> sync.last?.let {
            "上次同步　" + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(it)
        } ?: "尚无成功同步记录"
    })
    if (sync.failure.isNotBlank()) Text(sync.failure)
    Text("本机数据占用　$size")
    var notice by remember { mutableStateOf("") }
    TextButton({
        try { Desktop.getDesktop().open(directory.toFile()) }
        catch (_: Exception) { notice = "无法打开文件夹，可复制下方路径。" }
    }) { Text("打开文件夹") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("异常退出后询问是否发送问题记录", Modifier.weight(1f))
        Switch(offerReports, { enabled ->
            try { onReports(enabled) }
            catch (_: Exception) { notice = "偏好未保存，请检查本机目录权限。" }
        }, modifier = Modifier.semantics { contentDescription = "问题上报提醒" })
    }
    if (notice.isNotEmpty()) Text(notice)
}
