package com.coach.desktop.books

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Transport is deliberately supplied by the caller; no private-book CLI route is installed. */
internal fun interface OwnedBookCliTransport {
    suspend fun complete(request: String): String
}

/** The policy is evaluated before constructing a request or invoking the transport. */
internal fun interface OwnedBookOutboundPolicy {
    fun allows(prompt: OwnedBookPrompt): Boolean
}

internal object DenyOwnedBookOutbound : OwnedBookOutboundPolicy {
    override fun allows(prompt: OwnedBookPrompt) = false
}

/**
 * Uses indices from the response only to select exact locally validated citations;
 * model-generated book hashes, pages or offsets are never trusted. Production policy
 * currently denies private AI, so callers must not substitute a blanket allow policy.
 */
internal class OwnedBookCliExecutor(
    private val transport: OwnedBookCliTransport,
    private val policy: OwnedBookOutboundPolicy = DenyOwnedBookOutbound,
) : OwnedBookExecutor {
    override suspend fun answer(prompt: OwnedBookPrompt): OwnedBookAnswer {
        require(policy.allows(prompt)) { "Private book outbound requests are not authorized" }
        require(prompt.excerpts.isNotEmpty())
        require((prompt.action == OwnedBookAction.EXPLAIN && prompt.question == null) ||
            (prompt.action == OwnedBookAction.ASK && !prompt.question.isNullOrBlank()))
        val request = buildJsonObject {
            put("action", prompt.action.name.lowercase())
            prompt.question?.let { put("question", it) }
            put("excerpts", buildJsonArray {
                prompt.excerpts.forEachIndexed { index, excerpt ->
                    add(buildJsonObject { put("id", index); put("text", excerpt.text) })
                }
            })
        }.toString()
        val response = Json.parseToJsonElement(transport.complete(request)) as? JsonObject
            ?: error("Expected a JSON object")
        require(response.keys == setOf("answer", "citations"))
        val answer = (response["answer"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: error("Expected answer string")
        val citations = response["citations"] as? JsonArray ?: error("Expected citation indices")
        require(answer.isNotBlank() && citations.isNotEmpty())
        return OwnedBookAnswer(answer, citations.map { element ->
            val primitive = element as? JsonPrimitive ?: error("Invalid citation")
            require(!primitive.isString)
            val index = primitive.intOrNull ?: error("Invalid citation")
            require(index in prompt.excerpts.indices)
            prompt.excerpts[index].citation
        })
    }
}
