package com.coach.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DesktopPairingGate(settings: DesktopConnectionSettings, content: @Composable (DesktopIdentity) -> Unit) {
    val pairing = remember(settings) { DesktopPairing(settings) }
    var identity by remember { mutableStateOf<DesktopIdentity?>(null) }
    var initialized by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pairing) {
        try {
            val loaded = withContext(Dispatchers.IO) { settings.serverUrl() to pairing.load() }
            address = loaded.first.orEmpty()
            identity = loaded.second
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "无法读取连接信息，请检查 Coach 文件夹的访问权限，或重新输入 Mac 地址和配对码。" }
        finally { initialized = true }
    }
    LaunchedEffect(identity) {
        val current = identity ?: return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) { pairing.awaitRevocation(current) }
            identity = null
            error = "这台电脑已取消配对，请在 Mac 上生成新配对码。"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            identity = null
            error = "无法更新这台电脑的配对信息，请检查 Coach 文件夹的访问权限。"
        }
    }
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val paired = identity
            if (paired != null) content(paired)
            else if (!initialized) Box(Modifier.padding(24.dp)) { Text("正在读取连接信息…") }
            else Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("连接你的 Mac", style = MaterialTheme.typography.headlineMedium)
                Text("先打开 Tailscale，确认 Mac 上的 Coach 服务已运行，再输入 Mac 地址和 6 位配对码。")
                OutlinedTextField(value = address, onValueChange = { address = it; error = null },
                    label = { Text("Mac 地址") }, placeholder = { Text("http://你的Mac地址:18791") },
                    singleLine = true, enabled = !busy)
                OutlinedTextField(value = code, onValueChange = { code = it.filter { c -> c in '0'..'9' }.take(6); error = null },
                    label = { Text("配对码") }, singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                Button(enabled = code.length == 6 && address.isNotBlank() && !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            identity = withContext(Dispatchers.IO) {
                                pairing.pair(address, code, if (System.getProperty("os.name").startsWith("Windows")) "Windows 电脑" else "Mac 电脑")
                            }
                            code = ""
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "未能保存配对。请确认 Mac 地址、Tailscale 和文件夹访问权限，并在 Mac 上生成新配对码。" }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "正在配对…" else "配对") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
