package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.coach.ui.SableLoginButton
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Provided by the top-level login gate (Windows / Android). Invoking it returns to the full-screen login page. */
internal val LocalBookRelogin = staticCompositionLocalOf<((reason: String?) -> Unit)?> { null }

/**
 * Full-screen login gate shown on first launch, when no valid identity is stored, or after a 401 revocation.
 * Primary path is Sable account login; pairing code stays tucked behind a subtle link as backup.
 */
@Composable
internal fun TextbookLoginScreen(
    sableLogin: Boolean,
    defaultAddress: String,
    hasBuiltInAddress: Boolean,
    revokedReason: String?,
    onSableLogin: suspend (suspend (String, String) -> Unit) -> Unit,
    onPair: suspend (address: String, code: String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    var showPairingForm by remember(sableLogin) { mutableStateOf(!sableLogin) }
    var address by remember(defaultAddress) { mutableStateOf(defaultAddress) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pairError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Surface(
        modifier = modifier.fillMaxSize().testTag("textbook-login-screen"),
        color = colors.bgCanvas,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            PaperCard(
                modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
                contentPadding = PaddingValues(28.dp),
                contentSpacing = 16.dp,
            ) {
                Text(
                    "登录 LearningCoach",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.fgPrimary,
                    modifier = Modifier.testTag("login-title"),
                )
                Text(
                    "登录后，看书和学习时可随时查看讲解、提问与同步进度。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.fgSecondary,
                    modifier = Modifier.testTag("login-subtitle"),
                )
                Text(
                    "使用讲解与提问时，仅发送所选片段或当前节内容至 Coach 服务，可在设置中随时关闭。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgTertiary,
                    modifier = Modifier.testTag("login-privacy-note"),
                )
                revokedReason?.takeIf(String::isNotBlank)?.let { reason ->
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = v7Alert(),
                        modifier = Modifier.testTag("login-revoked-reason"),
                    )
                }
                if (sableLogin) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SableLoginButton(busy = busy, onBusy = { busy = it }, login = onSableLogin)
                        V7TextButton(
                            onClick = {
                                showPairingForm = !showPairingForm
                                pairError = null
                            },
                            enabled = !busy,
                            modifier = Modifier.testTag("login-pairing-toggle"),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                        ) {
                            Text(
                                "没有 Sable 账号？用配对码",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.fgSecondary,
                            )
                        }
                    }
                }
                if (!sableLogin || showPairingForm) {
                    Column(
                        modifier = Modifier.fillMaxWidth().testTag("login-pairing-form"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        V7Field(
                            value = address,
                            onValueChange = { address = it; pairError = null },
                            label = if (hasBuiltInAddress) "服务地址（留空连默认服务）" else "服务地址",
                            enabled = !busy,
                            paper = colors.surface2,
                            modifier = Modifier.fillMaxWidth().testTag("login-address"),
                        )
                        V7Field(
                            value = code,
                            onValueChange = { code = it.filter(Char::isDigit).take(6); pairError = null },
                            label = "配对码",
                            enabled = !busy,
                            paper = colors.surface2,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.width(200.dp).testTag("login-code"),
                        )
                        PrimaryButton(
                            onClick = {
                                busy = true
                                pairError = null
                                scope.launch {
                                    try {
                                        if (!onPair(address, code)) {
                                            pairError = "没配上。核对服务地址，或联系给你配对码的人生成新码。"
                                        }
                                        code = ""
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        pairError = "没配上。核对服务地址，或联系给你配对码的人生成新码。"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = code.length == 6 && (address.isNotBlank() || hasBuiltInAddress) && !busy,
                            modifier = Modifier.testTag("login-pair"),
                        ) {
                            Text(if (busy) "正在配对…" else "配对")
                        }
                        pairError?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = v7Alert(),
                                modifier = Modifier.testTag("login-pair-error"),
                            )
                        }
                    }
                }
            }
        }
    }
}
