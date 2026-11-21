package com.coach.android

import android.os.Build
import android.util.Log
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
import com.coach.client.SABLE_ORIGIN
import com.coach.client.SableLoginError
import com.coach.desktop.books.AndroidBookConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor

/** The application and integration screens can reuse the same persisted credential. */
@Composable
fun PairingGate(
    store: CoachCredentialStore,
    serverUrl: String,
    friendUrl: String = AndroidBookConnection.configuredFriendUrl(),
    sableIssuer: String = SABLE_ORIGIN,
    friendStore: CoachCredentialStore? = null,
    content: @Composable (PairedCredentials) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val normalizedFriendUrl = remember(friendUrl) { AndroidBookConnection.normalizeOptionalOrigin(friendUrl) }
    val resolvedFriendStore = remember(normalizedFriendUrl, friendStore) {
        friendStore ?: normalizedFriendUrl.takeIf(String::isNotBlank)?.let { KeystoreCredentials(context, it, "paired-friend") }
    }
    val initial = remember(store, resolvedFriendStore, normalizedFriendUrl, serverUrl) {
        resolvedFriendStore?.load()?.let { normalizedFriendUrl to it } ?: (serverUrl to store.load())
    }
    var credentials by remember(initial) { mutableStateOf(initial.second) }
    var friendEndpoint by remember(initial) { mutableStateOf(initial.first) }
    val scope = rememberCoroutineScope()
    suspend fun revokeCurrent() {
        val current = credentials
        withContext(Dispatchers.IO) {
            if (normalizedFriendUrl.isNotEmpty() && friendEndpoint == normalizedFriendUrl) {
                if (current != null) resolvedFriendStore?.revokeIfCurrent(current) else resolvedFriendStore?.clear()
            } else {
                if (current != null) store.revokeIfCurrent(current) else store.clear()
            }
        }
        if (current?.role == "friend") AndroidBookConnection.markFriendRevoked(friendEndpoint, current)
        credentials = null
    }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(credentials) {
        val paired = credentials ?: return@LaunchedEffect
        if (paired.role == "friend" && friendEndpoint.isNotBlank()) {
            val client = PairingClient(friendEndpoint)
            var startup = true
            try {
                while (true) {
                    val authorized = try {
                        withContext(Dispatchers.IO) { client.friendAuthorized(paired, startup) }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { true }
                    if (!authorized) { revokeCurrent(); error = "授权已撤销，请重新登录。"; break }
                    startup = false
                    kotlinx.coroutines.delay(5_000)
                }
            } finally { client.close() }
            return@LaunchedEffect
        }
        val transport = HttpLedgerTransport(serverUrl, paired.deviceId, paired.token)
        try { withContext(Dispatchers.IO) { transport.hello(null) } }
        catch (_: DeviceAuthorizationException) {
            withContext(Dispatchers.IO) { store.revokeIfCurrent(paired) }
            credentials = null
            error = "这台设备已取消配对，请找给你配对码的人生成新码。"
        }
        catch (_: Exception) { /* Offline use keeps its pairing; retry on the next connection. */ }
        finally { transport.close() }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val paired = credentials
        if (paired != null) content(paired)
        else Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("连接服务", style = MaterialTheme.typography.headlineMedium)
            com.coach.ui.SableLoginButton(busy, { busy = it }) { open ->
                if (normalizedFriendUrl.isBlank()) throw SableLoginError("此安装包尚未配置 Coach 服务地址。")
                val callbackDispatcher = currentCoroutineContext()[ContinuationInterceptor] ?: Dispatchers.Main.immediate
                val value = try {
                    withContext(Dispatchers.IO) {
                        val client = PairingClient(normalizedFriendUrl)
                        try {
                            val creds = client.sable({ uri, c ->
                                withContext(callbackDispatcher) { open(uri, c) }
                            }, sableIssuer)
                            checkNotNull(resolvedFriendStore).save(creds)
                            AndroidBookConnection.syncFriendSaved(normalizedFriendUrl, creds, context.applicationContext)
                            creds
                        } finally { client.close() }
                    }
                } catch (c: CancellationException) { throw c }
                catch (e: SableLoginError) { throw e }
                catch (e: Exception) {
                    Log.w("CoachSable", "pairing-gate sable failed: ${e.javaClass.simpleName}")
                    throw e
                }
                friendEndpoint = normalizedFriendUrl
                credentials = value
            }
            Text("没有 Sable 账号？用配对码")
            OutlinedTextField(value = code, onValueChange = { code = it.filter { c -> c in '0'..'9' }.take(6); error = null },
                label = { Text("配对码") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            Button(enabled = code.length == 6 && !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val target = normalizedFriendUrl.ifBlank { serverUrl }
                        val value = withContext(Dispatchers.IO) {
                            val client = PairingClient(target)
                            try {
                                val value = client.pair(code, Build.MODEL.take(80))
                                if (value.role == "friend") check(client.friendAuthorized(value))
                                else {
                                    check(target == serverUrl)
                                    val transport = HttpLedgerTransport(target, value.deviceId, value.token)
                                    try { transport.hello(null) } finally { transport.close() }
                                }
                                if (target == normalizedFriendUrl && resolvedFriendStore != null) {
                                    resolvedFriendStore.save(value)
                                    AndroidBookConnection.syncFriendSaved(normalizedFriendUrl, value, context.applicationContext)
                                } else {
                                    store.save(value)
                                }
                                value
                            } finally { client.close() }
                        }
                        friendEndpoint = target
                        credentials = value
                        code = ""
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        error = "未能配对。请确认 Tailscale 已连接，并找给你配对码的人生成新码。"
                    } finally { busy = false }
                }
            }) { Text(if (busy) "正在配对…" else "配对") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
