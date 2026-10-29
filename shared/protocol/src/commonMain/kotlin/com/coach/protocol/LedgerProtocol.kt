package com.coach.protocol
import com.coach.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val PROTOCOL_VERSION = 1
@Serializable data class CommandBody(val type: String, val courseId: String, val clientTime: Long, val payload: JsonObject)
/** payloadBytes are an immutable UTF-8 JSON string; never re-serialize them during upload. */
@Serializable data class WireCommand(val deviceId: String, val commandId: String, val payloadBytes: String, val payloadSha256: String)
@Serializable data class Receipt(val commandId: String, val status: String, val eventSeqs: List<Long> = emptyList(),
    val result: JsonObject = json(), val error: String? = null, val epoch: String,
    val backedUpThroughSeq: Long)
@Serializable data class Hello(val epoch: String, val lastSeq: Long, val backedUpThroughSeq: Long,
    val lastSeqBefore: Long, val protocol: Int = PROTOCOL_VERSION, val serverTime: Long = 0)
@Serializable data class Snapshot(val hello: Hello, val courseId: String, val events: List<Event>, val projection: Projection)
@Serializable data class EventsPage(val epoch: String, val events: List<Event>, val throughSeq: Long, val hasMore: Boolean = false)
@Serializable data class LedgerFrame(val t: String = "ledger", val course: String, val epoch: String, val events: List<Event>)

/** Keys travel only with offline packs, separately from the question events. */
@Serializable data class OfflineKey(val attemptId: String, val correctOptionId: String)
@Serializable data class OfflineChoicePack(val groups: List<List<Event>>, val keys: List<OfflineKey>)
