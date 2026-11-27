package com.coach.desktop.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.ui.v7.PaperButton

/**
 * 讲解没拿到时是哪一种情况。它只决定给人看哪句话、给不给「再试一次」；
 * HTTP 状态码、服务端的 `code` 和异常名一概不显示。
 */
internal enum class ExplainTrouble(val retry: Boolean) {
    /** 还没有配对：可以读书和做题，讲解需要先配对。 */
    Unpaired(false),
    /** 没连上：Mac 合盖、休眠、断网、超时，或中间的网关回了一页不是 JSON 的错误。 */
    Unreachable(true),
    /** 连上了，但讲解服务这会儿不可用：显示讲解请求返回的提示。 */
    Busy(true),
    /** 这台电脑的连接被停用（401）：给一条回到配对的路。 */
    Revoked(false),
    /** 其余：出处对不上、原文变了、服务端拒绝了这次请求。显示那句话，可以再试。 */
    Other(true);

    val needsPairing: Boolean get() = this == Unpaired || this == Revoked

    companion object {
        const val UNPAIRED = "讲解服务还没配对，读书和做题照常。"
        const val UNREACHABLE = OfflineCopy.EXPLAIN_OFFLINE
        /** 401 时的话，只指向这台电脑上真能用的入口：没放出 Sable 登录就只说配对码。 */
        const val REVOKED = "这台电脑的连接已停用，请用新的配对码重新配对。"
        const val REVOKED_LOGIN = "这台电脑的连接已停用。请重新登录，或使用新的配对码。"

        fun revoked(sableLogin: Boolean): String = if (sableLogin) REVOKED_LOGIN else REVOKED

        /** [status] 为空表示请求没拿到任何回复（网络、超时、非 JSON 网关页）。 */
        fun of(status: Int?, code: String?): ExplainTrouble = when {
            status == null -> Unreachable
            status == 401 -> Revoked
            // TON-740: the free grant ran out — show the server's sentence as is, never the generic "busy" copy.
            code == "free_grant_exhausted" -> Other
            code == "unavailable" || status == 408 || status == 429 || status >= 500 -> Busy
            else -> Other
        }

        /** 给人看的那一句：连不上和停用用固定的话，其余用服务端的 `error`，它空了才用兜底。 */
        fun message(trouble: ExplainTrouble, serverError: String?): String {
            val said = serverError?.trim()?.takeUnless { HTTP_ERROR_CODE.containsMatchIn(it) }.orEmpty()
            return when (trouble) {
                Unpaired -> UNPAIRED
                Unreachable -> UNREACHABLE
                Revoked -> REVOKED
                Busy -> said.ifEmpty { "讲解服务这会儿没顾上，稍后再试一次。" }
                Other -> said.ifEmpty { "这次没讲成，可以再试一次。" }
            }
        }
        private val HTTP_ERROR_CODE = Regex("""(?<![0-9])[45][0-9]{2}(?![0-9])""")
    }
}

/**
 * 讲解或回答没拿到时的那一行：左边是下一步（再试一次 / 重新配对），右边是那句话。
 * 行高固定，出现和消失都不把下面的内容推来推去。
 */
@Composable
internal fun ExplainTroubleLine(reply: BookLearningReply.Unavailable, onRetry: () -> Unit, onRepair: (() -> Unit)? = null,
    modifier: Modifier = Modifier) {
    val trouble = reply.trouble
    val onRelogin = LocalBookRelogin.current
    Row(modifier.heightIn(min = 40.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            trouble?.needsPairing == true && (onRelogin != null || onRepair != null) ->
                PaperButton(
                    onClick = { if (onRelogin != null) onRelogin("授权已撤销，请重新登录。") else onRepair?.invoke() },
                    modifier = Modifier.testTag("explain-trouble-repair"),
                ) {
                    Text(when {
                        onRelogin != null -> "重新登录"
                        trouble == ExplainTrouble.Revoked -> "去重新配对"
                        else -> "去配对"
                    })
                }
            trouble?.retry == true -> PaperButton(onRetry, modifier = Modifier.testTag("explain-trouble-retry")) { Text("再试一次") }
        }
        Text(reply.message, style = MaterialTheme.typography.bodySmall,
            color = v7Alert(),
            modifier = Modifier.weight(1f).testTag("explain-trouble"))
    }
}

/**
 * 设置里「AI 讲解」关着时，三处入口（原文页讲解、选段提问、答完题讲解）共用这几句写清原文去向。
 * 不再逐本书问：打开一次就对所有书生效，随时可以在设置里关掉。整本书不离开这台电脑。
 */
internal object ExplainPrivacy {
    const val TITLE = "这段原文会发到哪里"
    private const val ROUTE = "会发送给学习服务用于生成讲解，不作其他用途。"

    private const val GLOBAL = "整本书留在本地设备。开启后对所有书生效，可随时在设置里关闭。"
    fun explain(@Suppress("UNUSED_PARAMETER") title: String) = "这段和前后各一段原文${ROUTE}$GLOBAL"
    fun page(@Suppress("UNUSED_PARAMETER") title: String) = "这一页的正文${ROUTE}$GLOBAL"
    fun ask(@Suppress("UNUSED_PARAMETER") title: String) = "你选的这段原文和你的问题${ROUTE}$GLOBAL"
    fun answer(@Suppress("UNUSED_PARAMETER") title: String) = "刚做的这段原文和前后各一段${ROUTE}$GLOBAL"
}
