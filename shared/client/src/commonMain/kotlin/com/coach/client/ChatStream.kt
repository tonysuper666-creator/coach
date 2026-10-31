package com.coach.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 客户端最小流式消费端（Compose 之外，UI 卡直接可用）。
 *
 * 只做三件事：解析 WS 帧、按 `(msg, i)` 去重、把可见内容块拼成一条消息。
 * `msg.snapshot` 是重连后的权威状态：先用它整体替换，再接后续 delta（DESIGN §6）。
 */
sealed interface ChatUpdate {
    data class Started(val messageId: String, val model: String, val fallback: Boolean) : ChatUpdate
    data class Text(val messageId: String, val index: Int, val text: String) : ChatUpdate
    data class Math(val messageId: String, val index: Int, val tex: String, val display: Boolean) : ChatUpdate
    data class Snapshot(val messageId: String, val index: Int, val text: String, val status: String) : ChatUpdate
    data class Progress(val messageId: String, val phase: String, val text: String = "") : ChatUpdate
    data class Ended(val messageId: String, val status: String, val reason: String?, val chars: Int) : ChatUpdate
}

/** `msg.delta / msg.math` 共用一条 `(msg, i)` 序号；重复到达的序号直接丢弃。 */
class ChatStreamAssembler(private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }) {

    private val lastIndex = HashMap<String, Int>()
    private val bodies = HashMap<String, StringBuilder>()
    private val states = HashMap<String, String>()

    fun feed(frame: String): List<ChatUpdate> {
        val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return emptyList()
        return handle(obj)
    }

    fun handle(obj: JsonObject): List<ChatUpdate> {
        val messageId = obj.str("msg")
        if (messageId.isBlank()) return emptyList()
        val type = obj.str("t")
        val updates = when (type) {
            "msg.start" -> {
                states[messageId] = "streaming"
                listOf(ChatUpdate.Started(messageId, obj.str("model"), obj["fallback"]?.jsonPrimitive?.content == "true"))
            }
            "msg.delta" -> {
                val index = obj.str("i").toIntOrNull() ?: return emptyList()
                if (!accept(messageId, index)) return emptyList()
                val text = obj.str("text")
                bodies.getOrPut(messageId) { StringBuilder() }.append(text)
                listOf(ChatUpdate.Text(messageId, index, text))
            }
            "msg.math" -> {
                val index = obj.str("i").toIntOrNull() ?: return emptyList()
                if (!accept(messageId, index)) return emptyList()
                val tex = obj.str("tex")
                bodies.getOrPut(messageId) { StringBuilder() }.append("$$").append(tex).append("$$")
                listOf(ChatUpdate.Math(messageId, index, tex, obj.str("display") != "false"))
            }
            "msg.snapshot" -> {
                val index = obj.str("i").toIntOrNull() ?: 0
                // An earlier resume response can arrive after a newer cursor.
                if (index < (lastIndex[messageId] ?: 0)) return emptyList()
                val text = obj.str("text")
                lastIndex[messageId] = index
                bodies[messageId] = StringBuilder(text)
                states[messageId] = obj.str("status")
                listOf(ChatUpdate.Snapshot(messageId, index, text, obj.str("status"))) +
                    if (obj.str("phase") == "source_lookup") listOf(ChatUpdate.Progress(messageId,"source_lookup","导师去翻原文了")) else emptyList()
            }
            "msg.progress" -> listOf(ChatUpdate.Progress(messageId, obj.str("phase"), obj.str("text")))
            "msg.end" -> {
                states[messageId] = obj.str("status")
                listOf(
                    ChatUpdate.Ended(
                        messageId, obj.str("status"),
                        obj.str("reason").ifBlank { null },
                        obj.str("chars").toIntOrNull() ?: bodies[messageId]?.length ?: 0,
                    ),
                )
            }
            else -> emptyList()
        }
        // Keep the cursor for as long as the body: late replayed deltas after end must still deduplicate.
        return updates
    }

    /** 已拼好的可见正文（重连后由 snapshot 重置，所以与服务端落库的文本一致）。 */
    fun text(messageId: String): String = bodies[messageId]?.toString().orEmpty()

    fun status(messageId: String): String? = states[messageId]

    private fun accept(messageId: String, index: Int): Boolean {
        val previous = lastIndex[messageId] ?: 0
        if (index <= previous) return false
        lastIndex[messageId] = index
        return true
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
}
