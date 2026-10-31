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
    var credentials by remember { mutableStateOf(store.load()) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(credentials) {
        val paired = credentials ?: return@LaunchedEffect
        val transport = HttpLedgerTransport(serverUrl,paired.deviceId,paired.token)
        try { transport.hello(null) }
        catch (_: DeviceAuthorizationException) { store.clear(); credentials=null; error="这台设备已取消配对，请在 Mac 上生成新配对码。" }
        catch (_: Exception) { /* Offline use keeps its pairing; retry on the next connection. */ }
        finally { transport.close() }
    }
    MaterialTheme {
        val paired = credentials
        if (paired != null) content(paired)
        else Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("连接你的 Mac", style = MaterialTheme.typography.headlineMedium)
            Text("先打开 Tailscale，再输入 Mac 上显示的 6 位配对码。")
            OutlinedTextField(value=code, onValueChange={ code=it.filter { c -> c in '0'..'9' }.take(6); error=null },
                label={ Text("配对码") }, singleLine=true, enabled=!busy,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
            Button(enabled=code.length==6 && !busy, onClick={
                busy=true
                scope.launch {
                    try {
                        credentials = withContext(Dispatchers.IO) {
                            val client = PairingClient(serverUrl)
                            try {
                                val value = client.pair(code, Build.MODEL.take(80))
                                val transport = HttpLedgerTransport(serverUrl,value.deviceId,value.token)
                                try { transport.hello(null) } finally { transport.close() }
                                store.save(value)
                                value
                            } finally { client.close() }
                        }
                        code=""
                    } catch (_: Exception) {
                        error="未能配对。请确认 Tailscale 已连接，并在 Mac 上生成新配对码。"
                    } finally { busy=false }
                }
            }) { Text(if (busy) "正在配对…" else "配对") }
            error?.let { Text(it, color=MaterialTheme.colorScheme.error) }
        }
    }
}
