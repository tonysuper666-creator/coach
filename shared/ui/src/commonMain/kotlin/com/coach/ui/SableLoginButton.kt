package com.coach.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import com.coach.client.SableLoginError
import com.coach.ui.v7.PrimaryButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SableLoginButton(busy: Boolean, onBusy: (Boolean) -> Unit, login: suspend (suspend (String, String) -> Unit) -> Unit) {
    val browser = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var error by remember { mutableStateOf("") }
    PrimaryButton(enabled = !busy, onClick = {
        onBusy(true)
        error = ""
        pending = scope.launch {
            try {
                login { uri, value ->
                    code = value
                    browser.openUri(uri)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: SableLoginError) { error = e.message.orEmpty() }
            catch (_: Exception) { error = "未能登录，请检查网络连接后重试。" }
            finally { code = ""; onBusy(false) }
        }
    }) { Text("用 Sable 账号登录") }
    if (code.isNotEmpty()) {
        Text("请在浏览器中核对授权码 $code，然后点「允许」。")
        androidx.compose.material3.TextButton(onClick = { pending?.cancel() }) { Text("取消登录") }
    }
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
}
