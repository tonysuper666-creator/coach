package com.coach.android

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.coach.client.DeviceAuthorizationException
import com.coach.client.HttpLedgerTransport
import com.coach.client.PairedCredentials
import com.coach.client.PairingClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The application and integration screens can reuse the same persisted credential. */
@Composable
fun PairingGate(store: KeystoreCredentials, serverUrl: String, content: @Composable (PairedCredentials) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val friendUrl = BuildConfig.COACH_FRIEND_URL
    val friendStore = remember(friendUrl) { KeystoreCredentials(context, friendUrl, "paired-friend") }
    val initial = remember(store, friendStore) { friendStore.load()?.let { friendUrl to it } ?: (serverUrl to store.load()) }
    var credentials by remember(initial) { mutableStateOf(initial.second) }
    var friendEndpoint by remember(initial) { mutableStateOf(initial.first) }
    fun revoked() {
        if (friendEndpoint == friendUrl) friendStore.clear() else store.clear()
        credentials = null
    }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(credentials) {
        val paired = credentials ?: return@LaunchedEffect
        if (paired.role == "friend") {
            val client = PairingClient(friendEndpoint)
            var startup = true
            try {
                while (true) {
                    val authorized = try { client.friendAuthorized(paired, startup) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { true }
                    if (!authorized) { revoked(); error = "授权已撤销，请重新登录。"; break }
                    startup = false
                    kotlinx.coroutines.delay(5_000)
                }
            } finally { client.close() }
            return@LaunchedEffect
        }
        val transport = HttpLedgerTransport(serverUrl,paired.deviceId,paired.token)
        try { transport.hello(null) }
        catch (_: DeviceAuthorizationException) { store.clear(); credentials=null; error="这台设备已取消配对，请在 Mac 上生成新配对码。" }
        catch (_: Exception) { /* Offline use keeps its pairing; retry on the next connection. */ }
        finally { transport.close() }
    }
    Surface(Modifier.fillMaxSize(), color=MaterialTheme.colorScheme.background) {
        val paired = credentials
        if (paired?.role == "friend") FriendQuestion(friendEndpoint, paired) { revoked(); error = "授权已撤销，请重新登录。" }
        else if (paired != null) content(paired)
        else Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("连接你的 Mac", style = MaterialTheme.typography.headlineMedium)
            com.coach.ui.SableLoginButton(busy, { busy = it }) { open ->
                if (friendUrl.isBlank()) throw com.coach.client.SableLoginError("此安装包尚未配置朋友服务地址。")
                val client = PairingClient(friendUrl)
                try {
                    val value = client.sable(open)
                    friendStore.save(value)
                    friendEndpoint = friendUrl
                    credentials = value
                } finally { client.close() }
            }
            Text("没有 Sable 账号？用配对码")
            OutlinedTextField(value=code, onValueChange={ code=it.filter { c -> c in '0'..'9' }.take(6); error=null },
                label={ Text("配对码") }, singleLine=true, enabled=!busy,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
            Button(enabled=code.length==6 && !busy, onClick={
                busy=true
                scope.launch {
                    try {
                        val target = friendUrl.ifBlank { serverUrl }
                        val value = withContext(Dispatchers.IO) {
                            val client = PairingClient(target)
                            try {
                                val value = client.pair(code, Build.MODEL.take(80))
                                if (value.role == "friend") check(client.friendAuthorized(value))
                                else {
                                    check(target == serverUrl)
                                    val transport = HttpLedgerTransport(target,value.deviceId,value.token)
                                    try { transport.hello(null) } finally { transport.close() }
                                }
                                (if (target == friendUrl) friendStore else store).save(value)
                                value
                            } finally { client.close() }
                        }
                        friendEndpoint = target
                        credentials = value
                        code=""
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) {
                        error="未能配对。请确认 Tailscale 已连接，并在 Mac 上生成新配对码。"
                    } finally { busy=false }
                }
            }) { Text(if (busy) "正在配对…" else "配对") }
            error?.let { Text(it, color=MaterialTheme.colorScheme.error) }
        }
    }
}
