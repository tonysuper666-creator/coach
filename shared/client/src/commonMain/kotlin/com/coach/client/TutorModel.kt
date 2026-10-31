package com.coach.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
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
    data class Learner(override val key: String, val text: String, val clientMsgId: String? = null, val createdAt: Long? = null, val deviceId: String? = null) : ChatItem
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
    val historyLoaded: Boolean = false,
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
    // 缺号回调在 [lock] 内触发：只记下要续传的消息，等锁放开后再交给发送端，避免在锁里做 I/O 或重入。
    private val assembler = ChatStreamAssembler(onResumeRequired = { id, _ -> resumeNeeded.add(id); resumeWanted.add(id) })
    private val lock = Any()
    private val resumeNeeded = LinkedHashSet<String>()
    private val resumeWanted = ArrayList<String>()
    private val resumeSentAt = HashMap<String, Long>()
    private val lastFrameAt = HashMap<String, Long>()
    private val stallProbed = HashSet<String>()

    /** 把 msg.resume 放进当前连接的发送队列；没连上或队列满返回 false（模型随后重试）。由 [TutorClient] 挂接。 */
    @kotlin.concurrent.Volatile internal var resumeSender: ((messageId: String) -> Boolean)? = null
    private val _state = MutableStateFlow(TutorUiState())
    val state: StateFlow<TutorUiState> = _state.asStateFlow()

    private val messageThread = HashMap<String, String>()
    private val pendingSend = HashMap<String, Pair<String, TimeSource.Monotonic.ValueTimeMark>>()
    private val sampled = HashSet<String>()
    private var localDeviceId: String? = null

    fun bindDevice(device: String) = synchronized(lock) { localDeviceId = device }

    fun unfinishedMessageIds(): List<String> = state.value.threads.values.flatMap { thread ->
        thread.items.mapNotNull { (it as? ChatItem.Tutor)?.message?.takeUnless { message -> message.done }?.id }
    }.distinct()

    fun onConnection(connected: Boolean) {
        synchronized(lock) {
            // 旧连接上发出的续传随连接作废；新连接首先靠 [TutorClient] 的重连续传，再靠缺号重新触发。
            resumeSentAt.clear()
            stallProbed.clear()
            _state.value = _state.value.copy(connected = connected, lastSeenAtMs = if (connected) wallClock() else _state.value.lastSeenAtMs)
        }
    }

    /** 重连时由客户端直接发出续传后登记，避免紧接着的缺号再发一遍。 */
    internal fun noteResumeSent(messageId: String) = synchronized(lock) { resumeSentAt[messageId] = wallClock() }

    /**
     * 周期调用（约 1 秒）：补发没发出去或没等到快照的续传；
     * 生成中的消息很久没有任何新帧（可能连终态帧都丢了）时，主动对一次快照。
     */
    fun resumeTick() {
        val ids = synchronized(lock) {
            val now = wallClock()
            for (id in unfinishedMessageIds()) {
                val quiet = lastFrameAt[id]?.let { now - it } ?: continue
                if (quiet >= STALL_MS && stallProbed.add(id)) resumeNeeded.add(id)
            }
            claimResumes(resumeNeeded.toList())
        }
        dispatchResumes(ids)
    }

    private fun claimResumes(ids: Collection<String>): List<String> {
        val now = wallClock()
        return ids.distinct().filter { id ->
            val sentAt = resumeSentAt[id]
            (sentAt == null || now - sentAt >= RESUME_RETRY_MS).also { if (it) resumeSentAt[id] = now }
        }
    }

    private fun dispatchResumes(ids: List<String>) {
        if (ids.isEmpty()) return
        val sender = resumeSender
        for (id in ids) {
            val sent = sender != null && runCatching { sender(id) }.getOrDefault(false)
            if (!sent) synchronized(lock) { resumeSentAt.remove(id) }
        }
    }

    private fun resumeSettled(id: String) {
        resumeNeeded.remove(id)
        resumeSentAt.remove(id)
        stallProbed.remove(id)
    }

    /** 学习者自己发出的一句话，立刻进线程；同时开始计首字。 */
    fun onSent(threadId: String, kind: String, learnerText: String?, clientMsgId: String? = null) = synchronized(lock) {
        pendingSend[threadId] = kind to TimeSource.Monotonic.markNow()
        mutateThread(threadId) {
            val key = clientMsgId?.let { "l:$it" } ?: "l:${items.size}:${wallClock()}"
            val items = if (learnerText == null || items.any { it.key == key || (it is ChatItem.Learner && it.clientMsgId == clientMsgId && it.deviceId == localDeviceId && clientMsgId != null) }) items
                else items + ChatItem.Learner(key, learnerText,clientMsgId,deviceId=localDeviceId)
            copy(items = items, error = null, poolText = null, notice = null)
        }
    }

    fun onFailure(threadId: String, error: String) = synchronized(lock) {
        pendingSend.remove(threadId)
        mutateThread(threadId) { copy(error = error) }
    }

    /** A full/closed transport queue never accepted this send; leave no duplicate optimistic row. */
    fun onSendRejected(threadId: String, clientMsgId: String) = synchronized(lock) {
        pendingSend.remove(threadId)
        mutateThread(threadId) { copy(items = items.filterNot { it.key == "l:$clientMsgId" }) }
    }

    fun onFrame(frame: String) {
        val ids = synchronized(lock) {
            handleFrame(frame)
            claimResumes(resumeWanted).also { resumeWanted.clear() }
        }
        dispatchResumes(ids)
    }

    private fun handleFrame(frame: String) {
        val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return
        _state.value = _state.value.copy(lastSeenAtMs = wallClock())
        when (obj.str("t")) {
            "msg.user" -> onUserFrame(obj)
            "msg.history" -> {
                val thread = obj.str("threadId")
                val ordered = (obj["messages"] as? JsonArray).orEmpty().map { it as JsonObject }
                for (row in ordered) if (row.str("role") == "user") onUserFrame(row) else onMessageFrame(row,restoring=true)
                val keys = ordered.map { (if (it.str("role") == "user") "l:" else "t:") + it.str("msg") }
                mutateThread(thread) {
                    val byKey = items.associateBy { it.key }
                    copy(items = keys.mapNotNull { byKey[it] } + items.filter { it.key !in keys }, historyLoaded = true,
                        notice = if (obj.str("legacyHistoryUncovered") == "true") "早期对话未完整保存，缺失的提问无法恢复。" else notice)
                }
            }
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

    private fun onUserFrame(obj: JsonObject) {
        val id = obj.str("msg")
        if (id.isBlank()) return
        val clientId = obj.str("clientMsgId").ifBlank { null }
        val user = ChatItem.Learner("l:$id",obj.str("text"),clientId,obj.str("createdAt").toLongOrNull(),obj.str("deviceId").ifBlank { null })
        mutateThread(obj.str("threadId")) {
            val index = items.indexOfFirst { it.key == user.key || (clientId != null && it.key == "l:$clientId" && it is ChatItem.Learner && it.deviceId == user.deviceId) }
            copy(items = if (index < 0) items + user else items.toMutableList().also { it[index] = user })
        }
    }

    private fun onMessageFrame(obj: JsonObject, restoring: Boolean = false) {
        // Snapshots register activity only after acceptance, and only while still streaming.
        if (obj.str("t") != "msg.snapshot") obj.str("msg").takeIf { it.isNotBlank() }?.let {
            lastFrameAt[it] = wallClock(); stallProbed.remove(it)
        }
        val updates = assembler.handle(obj)
        for (update in updates) when (update) {
            is ChatUpdate.Started -> {
                val thread = obj.str("threadId")
                messageThread[update.messageId] = thread
                if (update.fallback && pendingSend[thread] == null) pendingSend[thread] = "fallback" to TimeSource.Monotonic.markNow()
                mutateThread(thread) {
                    copy(items = if (items.any { it is ChatItem.Tutor && it.message.id == update.messageId }) items
                        else items + ChatItem.Tutor(TutorMessage(update.messageId, update.model, update.fallback)), poolText = null)
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
                ensureMessage(update.messageId, obj.str("threadId"),obj.str("model"))
                if (!restoring && update.text.isNotEmpty()) markFirstToken(update.messageId)
                resumeSettled(update.messageId)
                changeMessage(update.messageId) { copy(text = update.text, epoch = if (update.text == text) epoch else epoch + 1, status = update.status.ifBlank { status },
                    model = obj.str("model").ifBlank { model },reason = obj.str("reason").ifBlank { reason },thinking = false) }
                if (findMessage(update.messageId)?.done == true) lastFrameAt.remove(update.messageId)
                else lastFrameAt[update.messageId] = wallClock()
            }
            is ChatUpdate.Progress -> changeMessage(update.messageId) { copy(thinking = update.phase == "thinking" && text.isEmpty()) }
            is ChatUpdate.Ended -> {
                resumeSettled(update.messageId)
                lastFrameAt.remove(update.messageId)
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

    private fun ensureMessage(id: String, threadId: String, model: String = "") {
        val thread = messageThread[id] ?: threadId.takeIf { it.isNotBlank() } ?: return
        val view = _state.value.threads[thread]
        if (view?.items?.any { it is ChatItem.Tutor && it.message.id == id } == true) return
        mutateThread(thread) { copy(items = items + ChatItem.Tutor(TutorMessage(id, model, false))) }
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

    private companion object {
        /** 同一条消息的续传在这段时间内只发一次；到点还没等到快照就再发。 */
        const val RESUME_RETRY_MS = 3_000L
        /** 生成中的消息这么久没有任何帧，就主动对一次快照（可能丢了终态帧）。 */
        const val STALL_MS = 20_000L
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
