package com.coach.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.TimeSource

/** 一条导师消息。消息头显示 [model]（兜底时 [fallback]=true，写的是实际出答的模型名）。 */
data class TutorMessage(
    val id: String,
    val model: String,
    val fallback: Boolean,
    val text: String = "",
    /** streaming / complete / truncated / failed / cancelled */
    val status: String = "streaming",
    val reason: String? = null,
    /** 8 秒还没出正文时的「还在想」。 */
    val thinking: Boolean = false,
    /** snapshot 整体替换正文时加一，渲染层据此重建而不是追加。 */
    val epoch: Int = 0,
) {
    val done: Boolean get() = status != "streaming"
}

sealed interface ChatItem {
    val key: String
    data class Tutor(val message: TutorMessage) : ChatItem { override val key get() = "t:${message.id}" }
    data class Learner(override val key: String, val text: String) : ChatItem
}

/** 客户端首字时间：从点按发出到共享消费端交付第一个非空正文/公式，毫秒。进度帧不算。 */
data class TtftSample(val threadId: String, val messageId: String, val kind: String, val ms: Long, val model: String, val fallback: Boolean)

data class ThreadView(
    val items: List<ChatItem> = emptyList(),
    /** 服务端状态机的状态名（IDLE / TEACH / AWAIT_ANSWER ...），尚未收到则为 null。 */
    val tutorState: String? = null,
    /** 状态机给出的「下一步」按钮文字（含「换个例子再讲」）。 */
    val nextLabel: String? = null,
    /** 题池暂空时服务端给的真实进度文字。 */
    val poolText: String? = null,
    val notice: String? = null,
    val error: String? = null,
) {
    val streaming: Boolean get() = items.any { it is ChatItem.Tutor && !it.message.done }
    val busy: Boolean get() = streaming || tutorState in BUSY_STATES
    companion object { val BUSY_STATES = setOf("PREPARE", "TEACH", "POSE", "GRADE", "REVIEW") }
}

data class TutorUiState(
    val threads: Map<String, ThreadView> = emptyMap(),
    /** 到 Mac 服务端的 WS 是否在线。 */
    val connected: Boolean = false,
    /** 最近一次连上（或最近一次收到服务端帧）的时刻，epoch 毫秒。 */
    val lastSeenAtMs: Long? = null,
    val ttft: List<TtftSample> = emptyList(),
)

/**
 * 把 WS 帧归约成界面状态（无 Compose、无网络，可直接单测）。
 * 流式正文用 [ChatStreamAssembler] 去重并拼接；`tutor.*` 帧只提供状态、按钮文字和进度，
 * 题卡与判分一律读账本（重启、断线后都还在）。
 */
class TutorModel(
    private val wallClock: () -> Long,
    private val onTtft: (TtftSample) -> Unit = {},
) {
    private val assembler = ChatStreamAssembler()
    private val lock = Any()
    private val _state = MutableStateFlow(TutorUiState())
    val state: StateFlow<TutorUiState> = _state.asStateFlow()

    private val messageThread = HashMap<String, String>()
    private val pendingSend = HashMap<String, Pair<String, TimeSource.Monotonic.ValueTimeMark>>()
    private val sampled = HashSet<String>()

    fun unfinishedMessageIds(): List<String> = state.value.threads.values.flatMap { thread ->
        thread.items.mapNotNull { (it as? ChatItem.Tutor)?.message?.takeUnless { message -> message.done }?.id }
    }.distinct()

    fun onConnection(connected: Boolean) = update {
        copy(connected = connected, lastSeenAtMs = if (connected) wallClock() else lastSeenAtMs)
    }

    /** 学习者自己发出的一句话，立刻进线程；同时开始计首字。 */
    fun onSent(threadId: String, kind: String, learnerText: String?) = synchronized(lock) {
        pendingSend[threadId] = kind to TimeSource.Monotonic.markNow()
        mutateThread(threadId) {
            val items = if (learnerText == null) items else items + ChatItem.Learner("l:${items.size}:${wallClock()}", learnerText)
            copy(items = items, error = null, poolText = null, notice = null)
        }
    }

    fun onFailure(threadId: String, error: String) = synchronized(lock) {
        pendingSend.remove(threadId)
        mutateThread(threadId) { copy(error = error) }
    }

    fun onFrame(frame: String) = synchronized(lock) {
        val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return@synchronized
        _state.value = _state.value.copy(lastSeenAtMs = wallClock())
        when (obj.str("t")) {
            "tutor.state" -> mutateThread(obj.str("threadId")) {
                copy(tutorState = obj.str("state"), nextLabel = obj.str("nextLabel").ifBlank { null }, poolText = null, error = null)
            }
            "tutor.progress" -> mutateThread(obj.str("threadId")) {
                if (obj.str("phase") == "fallback") copy(notice = obj.str("text").ifBlank { null }, poolText = null)
                else copy(poolText = obj.str("text").ifBlank { null })
            }
            "msg.error" -> {
                val thread = obj.str("threadId")
                if (thread.isNotBlank()) mutateThread(thread) { copy(error = obj.str("error")) }
            }
            else -> if (obj.str("t").startsWith("msg.")) onMessageFrame(obj)
        }
    }

    private fun onMessageFrame(obj: JsonObject) {
        val updates = assembler.handle(obj)
        for (update in updates) when (update) {
            is ChatUpdate.Started -> {
                val thread = obj.str("threadId")
                messageThread[update.messageId] = thread
                if (update.fallback && pendingSend[thread] == null) pendingSend[thread] = "fallback" to TimeSource.Monotonic.markNow()
                mutateThread(thread) {
                    copy(items = items + ChatItem.Tutor(TutorMessage(update.messageId, update.model, update.fallback)), poolText = null)
                }
            }
            is ChatUpdate.Text, is ChatUpdate.Math -> {
                val id = messageIdOf(update)
                val text = assembler.text(id)
                if (text.isNotEmpty()) markFirstToken(id)
                changeMessage(id) { copy(text = text, thinking = false) }
            }
            is ChatUpdate.Snapshot -> {
                // 重连后的权威状态：整体替换，渲染层按 epoch 重建。
                if (messageThread[update.messageId] == null) obj.str("threadId").takeIf { it.isNotBlank() }?.let { messageThread[update.messageId] = it }
                ensureMessage(update.messageId, obj.str("threadId"))
                if (update.text.isNotEmpty()) markFirstToken(update.messageId)
                changeMessage(update.messageId) { copy(text = update.text, epoch = epoch + 1, status = update.status.ifBlank { status }, thinking = false) }
            }
            is ChatUpdate.Progress -> changeMessage(update.messageId) { copy(thinking = update.phase == "thinking" && text.isEmpty()) }
            is ChatUpdate.Ended -> {
                val final = assembler.text(update.messageId)
                // 桥实际出答的模型名随终态帧到达：消息头在这条消息结束前改成它（含桥内部换模型）。
                changeMessage(update.messageId) { copy(status = update.status, reason = update.reason, thinking = false, model = update.model ?: model, text = if (final.isNotEmpty()) final else text) }
                if (update.reason != "rate_limited") messageThread[update.messageId]?.let { pendingSend.remove(it) }
            }
        }
    }

    private fun messageIdOf(update: ChatUpdate) = when (update) {
        is ChatUpdate.Text -> update.messageId
        is ChatUpdate.Math -> update.messageId
        else -> error("unreachable")
    }

    private fun ensureMessage(id: String, threadId: String) {
        val thread = messageThread[id] ?: threadId.takeIf { it.isNotBlank() } ?: return
        val view = _state.value.threads[thread]
        if (view?.items?.any { it is ChatItem.Tutor && it.message.id == id } == true) return
        mutateThread(thread) { copy(items = items + ChatItem.Tutor(TutorMessage(id, "", false))) }
    }

    private fun markFirstToken(id: String) {
        if (!sampled.add(id)) return
        val thread = messageThread[id] ?: return
        val (kind, mark) = pendingSend.remove(thread) ?: return
        val message = findMessage(id)
        val sample = TtftSample(thread, id, kind, mark.elapsedNow().inWholeMilliseconds, message?.model.orEmpty(), message?.fallback == true)
        _state.value = _state.value.copy(ttft = _state.value.ttft + sample)
        onTtft(sample)
    }

    private fun findMessage(id: String): TutorMessage? =
        messageThread[id]?.let { _state.value.threads[it] }?.items?.firstNotNullOfOrNull { (it as? ChatItem.Tutor)?.message?.takeIf { m -> m.id == id } }

    private fun changeMessage(id: String, change: TutorMessage.() -> TutorMessage) {
        val thread = messageThread[id] ?: return
        mutateThread(thread) {
            copy(items = items.map { if (it is ChatItem.Tutor && it.message.id == id) ChatItem.Tutor(it.message.change()) else it })
        }
    }

    private fun mutateThread(threadId: String, change: ThreadView.() -> ThreadView) {
        if (threadId.isBlank()) return
        val current = _state.value
        val next = (current.threads[threadId] ?: ThreadView()).change()
        _state.value = current.copy(threads = current.threads + (threadId to next))
    }

    private fun update(change: TutorUiState.() -> TutorUiState) = synchronized(lock) { _state.value = _state.value.change() }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty().let { if (it == "null") "" else it }

    private companion object { val json = Json { ignoreUnknownKeys = true; isLenient = true } }
}
