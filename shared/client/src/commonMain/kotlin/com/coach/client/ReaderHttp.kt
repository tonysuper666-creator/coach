package com.coach.client

import io.ktor.http.encodeURLPathPart
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Preserve status, especially 409: a client must retain the draft and offer a merge, never blind retry. */
data class ReaderReply(val status: Int?, val body: JsonObject?) {
    val ok: Boolean get() = status != null && status in 200..299
}

/** Uses the existing paired CourseHttp transport; private pages never enter a tutor request. */
class ReaderHttp internal constructor(
    private val request: suspend (String, JsonObject?) -> ReaderReply,
    private val bytes: suspend (String) -> ByteArray?,
    private val text: suspend (String) -> String?,
) {
    suspend fun catalog(): ReaderReply = request("", null)
    suspend fun resolve(source: String): ReaderReply = request("resolve?source=${source.encodeURLParameter()}", null)
    suspend fun page(document: String, pdfPage: Int): ReaderReply = request("${document.encodeURLPathPart()}/pages/$pdfPage", null)
    suspend fun image(document: String, pdfPage: Int, version: String? = null): ByteArray? = bytes(
        "${document.encodeURLPathPart()}/pages/$pdfPage/image" + (version?.let { "?version=${it.encodeURLParameter()}" } ?: "")
    )
    /** Facts and questions whose registered source is this page; sources that cannot be placed are only counted. */
    suspend fun study(document: String, pdfPage: Int): ReaderReply = request("${document.encodeURLPathPart()}/pages/$pdfPage/study", null)
    /** Registered chapter → section → point → question tree; unplaceable items are only counted. */
    suspend fun map(document: String): ReaderReply = request("${document.encodeURLPathPart()}/map", null)
    suspend fun state(document: String): ReaderReply = request("${document.encodeURLPathPart()}/state", null)
    suspend fun exportMarkdown(document: String): String? = text("${document.encodeURLPathPart()}/notes.md")
    suspend fun saveProgress(document: String, version: String, pdfPage: Int, baseRevision: Long, mutationId: String): ReaderReply =
        request("${document.encodeURLPathPart()}/progress", buildJsonObject {
            put("version", version); put("pdfPage", pdfPage); put("baseRevision", baseRevision); put("mutationId", mutationId)
        })
    /** Send the same immutable body and mutationId after a transport failure. Offsets are UTF-16. */
    suspend fun saveNote(document: String, body: JsonObject): ReaderReply = request("${document.encodeURLPathPart()}/notes", body)
}
