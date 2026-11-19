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
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.PaperButton

/**
 * 讲解没拿到时是哪一种情况。它只决定给人看哪句话、给不给「再试一次」；
 * HTTP 状态码、服务端的 `code` 和异常名一概不显示。
 */
internal enum class ExplainTrouble(val retry: Boolean) {
    /** 没连上：Mac 合盖、休眠、断网、超时，或中间的网关回了一页不是 JSON 的错误。 */
    Unreachable(true),
    /** 连上了，但讲解服务这会儿不行（5xx、408、`code=unavailable`）：原样显示服务端那句话。 */
    Busy(true),
    /** 今天或这个月的讲解额度用完了：原样显示服务端那句话，重试也没用，所以不给按钮。 */
    Quota(false),
    /** 这台电脑的连接被停用（401）：给一条回到配对的路。 */
    Revoked(false),
    /** 其余：出处对不上、原文变了、服务端拒绝了这次请求。显示那句话，可以再试。 */
    Other(true);

    companion object {
        const val UNREACHABLE = "讲解服务暂时连不上，读书和做题照常。"
        const val REVOKED = "这台电脑的连接已停用。请重新登录，或使用新的配对码。"

        /** [status] 为空表示请求没拿到任何回复（网络、超时、非 JSON 网关页）。 */
        fun of(status: Int?, code: String?): ExplainTrouble = when {
            status == null -> Unreachable
            status == 401 -> Revoked
            code?.endsWith("_quota") == true || status == 429 -> Quota
            code == "unavailable" || status == 408 || status >= 500 -> Busy
            else -> Other
        }

        /** 给人看的那一句：连不上和停用用固定的话，其余用服务端的 `error`，它空了才用兜底。 */
        fun message(trouble: ExplainTrouble, serverError: String?): String {
            val said = serverError?.trim().orEmpty()
            return when (trouble) {
                Unreachable -> UNREACHABLE
                Revoked -> REVOKED
                Quota -> said.ifEmpty { "讲解额度用完了。" }
                Busy -> said.ifEmpty { "讲解服务这会儿没顾上，稍后再试一次。" }
                Other -> said.ifEmpty { "这次没讲成，可以再试一次。" }
            }
        }
    }
}

/**
 * 讲解或回答没拿到时的那一行：左边是下一步（再试一次 / 重新配对），右边是那句话。
 * 额度用完不给按钮，话用次要色而不是朱色——这不是出错，只是今天到头了。
 * 行高固定，出现和消失都不把下面的内容推来推去。
 */
@Composable
internal fun ExplainTroubleLine(reply: BookLearningReply.Unavailable, onRetry: () -> Unit, onRepair: (() -> Unit)? = null,
    modifier: Modifier = Modifier) {
    val trouble = reply.trouble
    Row(modifier.heightIn(min = 40.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            trouble == ExplainTrouble.Revoked && onRepair != null ->
                PaperButton(onRepair, modifier = Modifier.testTag("explain-trouble-repair")) { Text("去重新配对") }
            trouble?.retry == true -> PaperButton(onRetry, modifier = Modifier.testTag("explain-trouble-retry")) { Text("再试一次") }
        }
        Text(reply.message, style = MaterialTheme.typography.bodySmall,
            color = if (trouble == ExplainTrouble.Quota) CoachTheme.colors.fgSecondary else v7Alert(),
            modifier = Modifier.weight(1f).testTag("explain-trouble"))
    }
}

/**
 * 逐书同意里写清原文去向。三处同意（原文页讲解、选段提问、答完题讲解）共用这几句，免得说法不一。
 * 李政的 Mac 只是中转；生成走 Anthropic 或 OpenAI 的 API，不发到别处；整本书不离开这台电脑。
 */
internal object ExplainPrivacy {
    const val TITLE = "这段原文会发到哪里"
    private const val ROUTE = "会经李政的 Mac 发给 Anthropic 或 OpenAI 的 API，不发到别处。API 默认不拿它训练模型。"

    fun explain(title: String) = "这段和前后各一段原文${ROUTE}整本书留在这台电脑上，《$title》只问这一次。"
    fun ask(title: String) = "你选的这段原文和你的问题${ROUTE}整本书留在这台电脑上，《$title》只问这一次。"
    fun answer(title: String) = "刚做的这段原文和前后各一段${ROUTE}整本书留在这台电脑上，《$title》只问这一次。"
}
