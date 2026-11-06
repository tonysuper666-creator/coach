package com.coach.android

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.coach.client.PairedCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class AppRelease(val code: Long, val name: String, val sha256: String, val size: Long)
internal data class UpdateState(val release: AppRelease? = null, val progress: Int? = null,
    val ready: Boolean = false, val error: String? = null, val hidden: Boolean = false)
internal class UpdateRejected(message: String) : Exception(message)

/** Only the configured, authenticated server is contacted. Redirects never receive credentials. */
internal class AppUpdater(private val context: Context, private val base: String, private val credentials: PairedCredentials) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var checking = false
    private val apk = File(context.cacheDir, "updates/verified.apk")
    private var connection: HttpURLConnection? = null
    private fun request(path: String): HttpURLConnection = (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 10_000; readTimeout = 20_000
        setRequestProperty("X-Coach-Device", credentials.deviceId)
        setRequestProperty("Authorization", "Bearer ${credentials.token}")
        setRequestProperty("Accept-Encoding", "identity")
    }
    fun check() {
        if (checking || mutable.value.progress != null || mutable.value.ready) return
        checking = true
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) {
                    val conn = request("/v1/app-update?packageName=${Uri.encode(context.packageName)}")
                    connection = conn
                    try {
                        if (conn.responseCode == 204 || conn.responseCode == 404) return@withContext null
                        check(conn.responseCode == 200)
                        val bytes = conn.inputStream.use { it.readNBytes(16_385) }
                        require(bytes.size <= 16_384)
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        require(json.getString("packageName") == context.packageName)
                        AppRelease(json.getLong("versionCode"), json.getString("versionName"), json.getString("sha256"), json.getLong("size")).also {
                            require(it.code in 2..2100000000 && it.name.length in 1..100)
                            require(it.sha256.matches(Regex("[a-f0-9]{64}")) && it.size in 1..268435456)
                        }
                    } finally { conn.disconnect(); connection = null }
                }
                val current = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                if (release != null && release.code > current && release != mutable.value.release) mutable.value = UpdateState(release)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Offline/startup checks must not interrupt a lesson. Retry on foreground. */ }
            finally { checking = false }
        }
    }
    fun hide() { if (mutable.value.progress == null) mutable.value = mutable.value.copy(hidden = true) }
    fun download() {
        val release = mutable.value.release ?: return
        if (mutable.value.progress != null) return
        mutable.value = mutable.value.copy(progress = 0, error = null, ready = false)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    apk.parentFile!!.mkdirs(); apk.delete()
                    val partial = File(apk.parentFile, "download.part")
                    val conn = request("/app.apk?sha256=${release.sha256}")
                    connection = conn
                    try {
                        check(conn.responseCode == 200)
                        if (conn.contentLengthLong != release.size) throw UpdateRejected("安装包大小不符，已拒绝安装。请稍后重试。")
                        conn.inputStream.use { input -> partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024); var size = 0L
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer); if (n < 0) break
                                size += n
                                if (size > release.size) throw UpdateRejected("安装包大小不符，已拒绝安装。")
                                output.write(buffer, 0, n)
                                mutable.value = mutable.value.copy(progress = (size * 100 / release.size).toInt())
                            }
                        } }
                        verifyUpdate(context, partial, release)
                        check(partial.renameTo(apk))
                    } finally { conn.disconnect(); connection = null; partial.delete() }
                }
                mutable.value = mutable.value.copy(progress = null, ready = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                apk.delete()
                mutable.value = mutable.value.copy(progress = null, ready = false,
                    error = (e as? UpdateRejected)?.message ?: "下载未完成，请确认 Mac 和 Tailscale 已连接，再重试。")
            }
        }
    }
    suspend fun installer(): Intent? = withContext(Dispatchers.IO) {
        val release = mutable.value.release ?: return@withContext null
        try {
            // Re-check after the unknown-sources settings screen, before granting a read capability.
            verifyUpdate(context, apk, release)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(
                FileProvider.getUriForFile(context, "${context.packageName}.updates", apk), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val pm = context.packageManager
            val candidates = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(
                (PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY).toLong()))
            return@withContext trustedInstaller(intent, candidates) { packageName ->
                pm.checkPermission(android.Manifest.permission.INSTALL_PACKAGES, packageName) == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Exception) {
            apk.delete()
            mutable.value = mutable.value.copy(ready = false, error = (e as? UpdateRejected)?.message ?: "安装包无法验证，请重新下载。")
            return@withContext null
        }
    }
    fun installError() { mutable.value = mutable.value.copy(error = "未能打开系统安装器，请重试。") }
    fun close() { connection?.disconnect(); scope.cancel() }
}

/** URI access goes only to an explicit, privileged system installer, never a chooser/default handler. */
internal fun trustedInstaller(intent: Intent, candidates: List<ResolveInfo>, canInstallPackages: (String) -> Boolean): Intent {
    val activity = candidates.firstOrNull { candidate ->
        val activity = candidate.activityInfo ?: return@firstOrNull false
        val app = activity.applicationInfo ?: return@firstOrNull false
        activity.exported && activity.enabled && app.enabled &&
            (app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0 &&
            canInstallPackages(activity.packageName)
    }?.activityInfo ?: throw UpdateRejected("未找到可信的系统安装器，已停止安装。请联系 Mac 上的更新服务。")
    return Intent(intent).setComponent(ComponentName(activity.packageName, activity.name))
}

/** Check bytes and archive metadata, then require the exact currently installed signing identity. */
internal fun verifyUpdate(context: Context, file: File, release: AppRelease) {
    val digest = MessageDigest.getInstance("SHA-256")
    if (!file.isFile || file.length() != release.size) throw UpdateRejected("安装包大小不符，已拒绝安装。")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    if (digest.digest().joinToString("") { "%02x".format(it) } != release.sha256)
        throw UpdateRejected("安装包校验失败，已拒绝安装。请重新下载。")
    val pm = context.packageManager
    val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
    val installed = pm.getPackageInfo(context.packageName, flags)
    val archive = pm.getPackageArchiveInfo(file.path, flags) ?: throw UpdateRejected("安装包无法识别，已拒绝安装。")
    if (archive.packageName != context.packageName || archive.longVersionCode != release.code || archive.versionName != release.name || archive.longVersionCode <= installed.longVersionCode)
        throw UpdateRejected("安装包的应用或版本不符，已拒绝安装。")
    fun signers(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
    val current = signers(installed)
    if (current.isEmpty() || current != signers(archive)) throw UpdateRejected("安装包签名与当前应用不一致，已拒绝安装。请联系 Mac 上的更新服务。")
}

@Composable
internal fun AppUpdatePrompt(context: Context, serverUrl: String, credentials: PairedCredentials) {
    val updater = remember(serverUrl, credentials) { AppUpdater(context.applicationContext, serverUrl, credentials) }
    val state by updater.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { /* A cancelled install can be retried. */ }
    val uiScope = rememberCoroutineScope()
    fun install() { uiScope.launch { updater.installer()?.let { intent -> runCatching { installer.launch(intent) }.onFailure { updater.installError() } } } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
    }
    DisposableEffect(updater, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) updater.check() }
        lifecycle.addObserver(observer)
        updater.check()
        onDispose { lifecycle.removeObserver(observer); updater.close() }
    }
    val release = state.release ?: return
    if (state.hidden) return
    AlertDialog(onDismissRequest = { updater.hide() }, title = { Text("Coach 有新版本") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("版本 ${release.name} · 升级会保留学习记录和配对。")
            // The indicator reads its lambda later (draw/semantics); capture the value instead of re-reading nullable state.
            state.progress?.let { progress -> LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth()); Text("正在下载 $progress%") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.ready && !context.packageManager.canRequestPackageInstalls()) Text("首次更新需允许 Coach 安装应用；允许后会继续安装。")
        } },
        confirmButton = { TextButton(enabled = state.progress == null, onClick = {
            if (!state.ready) updater.download()
            else if (context.packageManager.canRequestPackageInstalls()) install()
            else runCatching { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }.onFailure { updater.installError() }
        }) { Text(if (state.ready) "安装更新" else if (state.error != null) "重新下载" else "下载并更新") } },
        dismissButton = { TextButton(enabled = state.progress == null, onClick = { updater.hide() }) { Text("稍后") } })
    // Continue directly to the system installer once the download has been verified.
    LaunchedEffect(state.ready) {
        if (state.ready && context.packageManager.canRequestPackageInstalls()) install()
        else if (state.ready) runCatching { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }.onFailure { updater.installError() }
    }
}
