package com.coach.desktop.books

import kotlinx.serialization.json.*

/** Whole lessons have their own protocol; the selection protocol must never truncate one. */
internal object BookLessonWire {
    const val TOO_LONG = "这一节太长，先选一段问。"
    private const val MAX_REQUEST_BYTES = 131_072
    fun fits(blocks: List<SourceBlock>): Boolean = blocks.size in 1..128 &&
        blocks.all { !it.heading && it.text.isNotBlank() && it.text.length <= 8_000 } &&
        blocks.sumOf { it.text.length.toLong() } <= 24_000

    fun request(
        requestId: String,
        blocks: List<SourceBlock>,
        bookPages: Int,
        stanceRequest: ExplainStanceRequest? = null,
    ): JsonObject {
        require(fits(blocks)) { TOO_LONG }
        require(requestId.matches(Regex("[a-f0-9]{1,32}")) && bookPages in 1..100_000)
        require(blocks.map { it.id }.distinct().size == blocks.size &&
            blocks.map { it.id.substringBefore(":p") }.distinct().single().matches(Regex("[a-f0-9]{64}")))
        require(blocks.all { it.page in 1..bookPages && it.start >= 0 && it.end - it.start == it.text.length &&
            it.id == "${it.id.substringBefore(":p")}:p${it.page}:${it.start}" })
        return buildJsonObject {
            put("requestId", requestId); put("kind", "lesson"); put("bookPages", bookPages)
            put("blocks", buildJsonArray {
                blocks.forEach { block -> add(buildJsonObject { put("id", block.id); put("text", block.text) }) }
            })
            if (stanceRequest != null) {
                put("stance", stanceRequest.stance.wire)
                if (stanceRequest.guards.isNotEmpty()) {
                    put("guards", buildJsonArray { stanceRequest.guards.forEach { add(JsonPrimitive(it.wire)) } })
                }
                stanceRequest.readThroughPage?.let { put("readThroughPage", it) }
            }
        }
    }

    /** Count JSON escaping, UTF-8 and receipt metadata against the existing HTTP body limit. */
    fun withSource(input: JsonObject, source: JsonObject): JsonObject =
        JsonObject(input + ("localSource" to source)).also {
            require(it.toString().toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) { TOO_LONG }
        }
}
