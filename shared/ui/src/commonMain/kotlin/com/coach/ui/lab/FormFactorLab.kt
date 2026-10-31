package com.coach.ui.lab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.coach.ui.adaptive.*
import com.coach.ui.chat.AnchoredChatList
import com.coach.ui.chat.ChatListState
import com.coach.ui.input.ComposerField
import com.coach.ui.theme.CoachTheme
import com.coach.ui.theme.OptionMinHeight
import kotlinx.coroutines.delay

/**
 * Verification surface for fold/unfold, not a product screen: a long conversation, a live stream, an option card and a draft,
 * built from the same scaffold, anchored list and composer the study screens use. Everything the reader would lose on a window change lives here.
 */
class LabState(val streamTarget: String = LabText.streamed) {
    val chat = ChatListState()
    var draft by mutableStateOf(TextFieldValue())
    val selected: SnapshotStateMap<String, String> = mutableStateMapOf()
    var streamedChars by mutableStateOf(0)
    var asideOpen by mutableStateOf(false)
    val history = LabText.history
    val sent = mutableStateListOf<String>()
}

@Composable
fun FormFactorLab(state: LabState, tickMs: Long = 50L, chunk: Int = 6) {
    // The stream belongs to the screen state, not to a pane: it keeps running through every layout change.
    LaunchedEffect(state) {
        while (state.streamedChars < state.streamTarget.length) {
            delay(tickMs)
            state.streamedChars = minOf(state.streamedChars + chunk, state.streamTarget.length)
        }
    }
    CoachTheme {
        AdaptiveScaffold(
            asideOpen = state.asideOpen,
            onAsideDismiss = { state.asideOpen = false },
            nav = { style -> LabNav(style) },
            conversation = { LabConversation(state) },
            aside = { LabAside() },
            outline = { Text("大纲", Modifier.padding(16.dp)) },
        )
    }
}

private val navItems = listOf("今天", "学习", "复习", "路线", "记录")

@Composable
private fun LabNav(style: NavStyle) {
    val colors = CoachTheme.colors
    when (style) {
        NavStyle.BottomBar -> Row(Modifier.fillMaxWidth().background(colors.surface1).navigationBarsPadding().height(64.dp).testTag("nav-bottom"), verticalAlignment = Alignment.CenterVertically) {
            navItems.forEach { Text(it, Modifier.weight(1f), color = if (it == "学习") colors.actionPrimary else colors.fgSecondary, style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        else -> Column(Modifier.width(if (style == NavStyle.Rail) 72.dp else 56.dp).fillMaxHeight().background(colors.surface1).testTag("nav-rail"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
            navItems.forEach { Text(if (style == NavStyle.Rail) it else it.take(1), color = if (it == "学习") colors.actionPrimary else colors.fgSecondary, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun LabAside() {
    Column(Modifier.fillMaxSize().padding(20.dp).testTag("aside"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("本次参考", style = MaterialTheme.typography.titleMedium)
        Text("教材 p.31–34", color = CoachTheme.colors.fgSecondary)
    }
}

private sealed interface LabItem { val key: String }
private data class Turn(override val key: String, val who: String, val text: String) : LabItem
private data object StreamTurn : LabItem { override val key = "stream" }
private data object OptionsCard : LabItem { override val key = "options" }

@Composable
private fun LabConversation(state: LabState) {
    val colors = CoachTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth
        val items: List<LabItem> = remember(state.history) { state.history.mapIndexed { i, t -> Turn("t$i", if (i % 2 == 0) "导师" else "我", t) } } + OptionsCard + StreamTurn
        Column(Modifier.fillMaxSize()) {
            AnchoredChatList(
                items = items, key = { it.key }, state = state.chat, windowKey = widthPx,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat-list"), contentPadding = PaddingValues(16.dp),
            ) { item ->
                when (item) {
                    is Turn -> Bubble(item.who, item.text)
                    OptionsCard -> OptionList(state)
                    StreamTurn -> Bubble("导师", state.streamTarget.take(state.streamedChars).ifEmpty { "…" }, Modifier.testTag("stream-text"))
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).imePadding()) {
                ComposerField(state.draft, { state.draft = it }, onSend = { state.sent += it; true }, placeholder = "写下你的答案或追问")
            }
        }
    }
}

@Composable
private fun Bubble(who: String, text: String, modifier: Modifier = Modifier) {
    val colors = CoachTheme.colors
    Column(modifier.fillMaxWidth().background(if (who == "我") colors.accentSoft else colors.surface1, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(who, style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.fgPrimary)
    }
}

@Composable
private fun OptionList(state: LabState) {
    val colors = CoachTheme.colors
    Column(Modifier.fillMaxWidth().testTag("options"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("第 1 题 · 逆向选择最直接的后果是？", style = MaterialTheme.typography.titleMedium)
        LabText.options.forEach { (letter, label) ->
            val on = state.selected["q1"] == letter
            Surface(
                onClick = { state.selected["q1"] = letter }, shape = RoundedCornerShape(16.dp),
                color = if (on) colors.accentSoft else colors.surface1, border = BorderStroke(if (on) 2.dp else 1.dp, if (on) colors.actionPrimary else colors.borderStrong),
                modifier = Modifier.fillMaxWidth().heightIn(min = OptionMinHeight).testTag("option-$letter"),
            ) { Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text("$letter  $label", color = colors.fgPrimary) } }
        }
    }
}

object LabText {
    val options = listOf("A" to "保险公司只承保健康的人", "B" to "高风险者更倾向购买保险", "C" to "投保后行为变得更冒险", "D" to "保费随年龄线性上涨")
    val history: List<String> = (0 until 24).map { i ->
        "第 ${i + 1} 段：在健康保险市场里，投保人比保险公司更清楚自己的健康状况，这种信息不对称让保险公司难以按个人风险定价。" + "举例来说，" .repeat(1 + i % 4) + "同一份保单会被不同风险的人以不同的概率购买。".repeat(1 + i % 3)
    }
    val streamed: String = (0 until 40).joinToString("") { "流式讲解第 ${it + 1} 句：保费按平均风险定价时，低风险者退出，平均风险上升，保费随之再涨。" }
}
