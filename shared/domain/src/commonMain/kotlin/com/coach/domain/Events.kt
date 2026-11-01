package com.coach.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

const val PROJECTION_VERSION = 5
val LedgerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

enum class Format { SINGLE_CHOICE, SHORT_ANSWER, ESSAY, CALCULATION }
enum class Purpose { PRACTICE, CLOSED_BOOK, UNSEEN_VARIANT, RECALL, TIMED, FULL_MOCK }
enum class KeyState { VERIFIED, UNREVIEWED, REJECTED }

enum class EventType {
    outline_revised, exam_set, kp_opened, explanation_delivered, followup_asked,
    question_posed, hint_revealed, answer_submitted, attempt_graded, grade_reviewed,
    attempt_voided, kp_overridden, override_cleared, review_snoozed, review_completed,
    note_saved, note_deleted, quick_ask_filed, journal_edited, journal_summarized,
    mock_started, mock_submitted, legacy_imported, legacy_attempt, legacy_schedule,
    legacy_passed, exam_weights_set, explanation_pregenerated, server_restored,
    question_key_changed, answer_orphaned, tutor_state_changed, tutor_settings_changed,
    course_configured, quick_ask_opened, quick_ask_message, legacy_journal_imported, legacy_source_revised
}

@Serializable
data class Event(
    val seq: Long, val id: String, val courseId: String, val type: String,
    val schema: Int = 1, val recordedAt: Long, val occurredAt: Long,
    val deviceId: String, val actor: String, val commandId: String? = null,
    val effectIndex: Int = 0, val causationId: String? = null,
    val threadId: String? = null, val idemKey: String = id,
    val payload: JsonObject = JsonObject(emptyMap())
)

/** Explicit per-type migration chains. Unknown types/schemas retain their raw bytes in the replica. */
class Upcasters(private val chains: Map<String, List<(JsonObject) -> JsonObject>> = emptyMap()) {
    fun upcast(event: Event): Event? {
        if (EventType.entries.none { it.name == event.type }) return null
        val chain = chains[event.type].orEmpty()
        if (event.schema !in 1..(chain.size + 1)) return null
        var value = event.payload
        for (i in event.schema - 1 until chain.size) value = chain[i](value)
        return event.copy(schema = chain.size + 1, payload = value)
    }
}

fun JsonObject.str(key: String, default: String = ""): String = (get(key) as? JsonPrimitive)?.contentOrNull ?: default
fun JsonObject.num(key: String, default: Long = 0): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: default
fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
fun JsonObject.strings(key: String): List<String> = (get(key) as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
fun json(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((key, value) in pairs) put(key, when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Iterable<*> -> JsonArray(value.map { JsonPrimitive(it.toString()) })
        else -> JsonPrimitive(value.toString())
    })
}

/** Stable object-key ordering, used once at enqueue time; arrays retain order. */
fun canonicalJson(value: JsonElement): String = when (value) {
    is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { JsonPrimitive(it).toString() + ":" + canonicalJson(value.getValue(it)) }
    is JsonArray -> value.joinToString(",", "[", "]") { canonicalJson(it) }
    else -> value.toString()
}

/** Uses local calendar date before applying the 04:00 boundary, including DST transitions. */
expect fun logicalDay(epochMillis: Long, zone: String): Long

/** ULID encoding with caller-supplied clock/randomness keeps the domain free of IO. */
fun ulid(timestampMillis: Long, entropy: ByteArray): String {
    require(timestampMillis >= 0 && timestampMillis < (1L shl 48) && entropy.size == 10)
    val alphabet="0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    val chars=CharArray(26)
    var time=timestampMillis
    for(i in 9 downTo 0) { chars[i]=alphabet[(time and 31).toInt()];time=time ushr 5 }
    var accumulator=0;var bits=0;var pos=10
    for(byte in entropy) {
        accumulator=(accumulator shl 8) or (byte.toInt() and 255);bits+=8
        while(bits>=5) { bits-=5;chars[pos++]=alphabet[(accumulator ushr bits) and 31] }
    }
    return chars.concatToString()
}
