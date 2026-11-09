package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.LedgerStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*

private val readerId = Regex("[a-zA-Z0-9_-]{1,80}")
private fun badReader(message: String): Nothing = throw ReaderFailure(HttpStatusCode.BadRequest, message)
private fun conflictReader(message: String): Nothing = throw ReaderFailure(HttpStatusCode.Conflict, message)
private fun JsonObject.readerLong(name: String): Long = (get(name) as? JsonPrimitive)?.longOrNull ?: badReader("缺少 $name")

/** Reader data is personal context, never a mastery/learning-evidence event. */
internal class ReaderLedger {
    private fun LedgerStore.latest(course: String, id: String, type: String): List<Event> = events(course)
        .filter { it.type == type && it.payload.str("documentId") == id }
    internal fun state(store: LedgerStore, course: String, d: ReaderDocument): JsonObject = with(store) {
        val notes = latest(course, d.id, "reader_note_saved").groupBy { it.payload.str("id") }.values.map { it.last() }
            .filterNot { it.payload.flag("deleted") }.map { e -> JsonObject(e.payload + mapOf("revision" to JsonPrimitive(e.seq),
                "stale" to JsonPrimitive(e.payload.str("version") != d.version), "savedAt" to JsonPrimitive(e.recordedAt))) }
        val progress = latest(course, d.id, "reader_progress_saved").lastOrNull()?.let { e ->
            JsonObject(e.payload + mapOf("revision" to JsonPrimitive(e.seq), "stale" to JsonPrimitive(e.payload.str("version") != d.version))) }
        json("document" to d.view(), "progress" to progress, "notes" to JsonArray(notes))
    }

    internal fun save(store: LedgerStore, course: String, d: ReaderDocument, input: JsonObject,
                      device: String, note: Boolean, pageText: JsonObject?): JsonObject = with(store) {
        val mutation = input.str("mutationId").takeIf { readerId.matches(it) } ?: badReader("无效的 mutationId")
        val fingerprint = ReaderLibrary.digest("${d.id}:$note:${JsonObject(input.toSortedMap())}".toByteArray())
        val key = "reader:" + ReaderLibrary.digest(json("course" to course, "device" to device, "mutation" to mutation).toString().toByteArray())
        val previousMutation = events(course).firstOrNull { it.idemKey == key }
        if (previousMutation != null) {
            if (previousMutation.payload.str("requestHash") != fingerprint) conflictReader("这次保存编号已用于不同内容")
            return@with json("revision" to previousMutation.seq, "savedAt" to previousMutation.recordedAt, "replayed" to true)
        }
        if (input.str("version") != d.version) conflictReader("资料版本已改变，请重新定位；旧批注仍保留")
        val page = input.readerLong("pdfPage").takeIf { it in 1L..d.pages.toLong() } ?: badReader("PDF 页码超出文档")
        val base = input.readerLong("baseRevision").takeIf { it >= 0 } ?: badReader("无效的版本号")
        val type = if (note) "reader_note_saved" else "reader_progress_saved"
        val id = if (note) input.str("id").takeIf { readerId.matches(it) } ?: badReader("无效的批注编号") else "progress"
        val last = latest(course, d.id, type).lastOrNull { !note || it.payload.str("id") == id }
        if ((last?.seq ?: 0L) != base) conflictReader("另一台设备已保存更新，请重新载入后合并")
        val payload = buildJsonObject {
            put("documentId", d.id); put("version", d.version); put("pdfPage", page)
            put("mutationId", mutation); put("requestHash", fingerprint)
            if (note) {
                val text = pageText?.str("text") ?: badReader("无法核对选中文本")
                val start = input.readerLong("start"); val end = input.readerLong("end")
                val quote = input.str("quote")
                if (pageText.str("textSha256") != input.str("textSha256")) conflictReader("页面文字版本已改变，请重新选中")
                if (start < 0 || end <= start || end > text.length || end - start > 4000 || quote != text.substring(start.toInt(), end.toInt()))
                    badReader("选中文本与这一页不符")
                val body = input.str("note")
                if (body.length > 8000) badReader("笔记太长")
                if (last != null && listOf("pdfPage", "start", "end", "quote", "textSha256", "version").any { last.payload[it] != input[it] })
                    conflictReader("已有批注不能静默改绑到别处，请新建批注")
                if (input["deleted"] !is JsonPrimitive || input["deleted"]?.jsonPrimitive?.booleanOrNull == null) badReader("缺少 deleted")
                put("id", id); put("start", start); put("end", end); put("quote", quote); put("note", body)
                put("textSha256", input.str("textSha256")); put("deleted", input.flag("deleted"))
            }
        }
        val event = append(course, type, payload, idem = key, device = device, actor = "LEARNER")
        json("revision" to event.seq, "savedAt" to event.recordedAt, "replayed" to false)
    }

    internal fun markdown(store: LedgerStore, course: String, d: ReaderDocument): String {
        fun quote(text: String) = text.lines().joinToString("\n") { "> $it" }
        val notes = state(store, course, d).getValue("notes").jsonArray.map { it.jsonObject }
            .sortedWith(compareBy({ it.num("pdfPage") }, { it.num("start") }, { it.str("id") }))
        return buildString {
            append("# ").append(d.title.replace('\n', ' ')).append("\n\n个人阅读批注；不作为掌握证据。\n\n")
            for (n in notes) {
                val page = n.num("pdfPage").toInt()
                append("## PDF p.").append(page)
                if (!n.flag("stale")) d.printedPage(page)?.let { append(" · 印刷 p.").append(it) }
                if (n.flag("stale")) append(" · 旧资料版本，定位待核对")
                append("\n\n").append(quote(n.str("quote"))).append("\n\n").append(n.str("note")).append("\n\n")
                append("<!-- annotation:").append(n.str("id")).append(" version:").append(n.str("version"))
                    .append(" revision:").append(n.num("revision")).append(" -->\n\n")
            }
        }
    }
}

/** Authenticated, course-scoped routes. Also enforce auth when mounted without ledgerRoutes in a test. */
fun Application.readerRoutes(actor: LedgerActor, pushes: MutableSharedFlow<String>, library: ReaderLibrary) {
    val ledger = ReaderLedger()
    suspend fun ApplicationCall.answer(block: suspend (String, String) -> Unit) {
        response.header(HttpHeaders.CacheControl, "no-store")
        response.header("X-Content-Type-Options", "nosniff")
        val device = request.header("X-Coach-Device")
        val auth = request.headers.getAll(HttpHeaders.Authorization)
        val token = auth?.singleOrNull()?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
        if (device == null || token == null || request.queryParameters.names().any { it.lowercase() in setOf("token", "access_token", "authorization", "code") } ||
            !actor.execute { authorize(device, token) }) return respond(HttpStatusCode.Unauthorized)
        try { block(parameters["course"]!!, device) }
        catch (e: ReaderFailure) { respondText(json("error" to e.message).toString(), ContentType.Application.Json, e.status) }
    }
    suspend fun ApplicationCall.json(value: JsonObject) = respondText(value.toString(), ContentType.Application.Json)
    suspend fun ApplicationCall.input(): JsonObject {
        val channel = receiveChannel(); val bytes = ByteArray(32_769); var size = 0
        while (size < bytes.size) { val n = channel.readAvailable(bytes, size, bytes.size - size); if (n < 0) break; size += n }
        if (size > 32_768) throw ReaderFailure(HttpStatusCode.PayloadTooLarge, "保存内容太长")
        return runCatching { LedgerJson.parseToJsonElement(bytes.decodeToString(0, size, throwOnInvalidSequence = true)).jsonObject }
            .getOrElse { badReader("保存内容不是有效 JSON") }
    }
    fun ApplicationCall.page(): Int = parameters["page"]?.toIntOrNull() ?: badReader("无效的 PDF 页码")
    routing {
        route("/v1/courses/{course}/reader") {
            get { call.answer { course, _ -> call.json(json("documents" to JsonArray(library.list(course).map { it.view() }),
                "privateAi" to "disabled_no_local_inference")) } }
            get("/resolve") { call.answer { course, _ -> call.json(library.resolve(course, call.request.queryParameters["source"].orEmpty())) } }
            get("/{document}/pages/{page}") { call.answer { course, _ -> call.json(library.page(library.document(course, call.parameters["document"]!!), call.page())) } }
            get("/{document}/pages/{page}/image") { call.answer { course, _ ->
                val d = library.document(course, call.parameters["document"]!!)
                val version = call.request.queryParameters["version"]
                if (version != null && version != d.version) conflictReader("资料版本已改变，请重新定位")
                call.respondBytes(library.image(d, call.page()), ContentType.Image.PNG)
            } }
            get("/{document}/state") { call.answer { course, _ ->
                val d = library.document(course, call.parameters["document"]!!)
                call.json(actor.execute { ledger.state(this, course, d) })
            } }
            get("/{document}/notes.md") { call.answer { course, _ ->
                val d = library.document(course, call.parameters["document"]!!)
                val markdown = actor.execute { ledger.markdown(this, course, d) }
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${d.id}-notes.md\"")
                call.respondText(markdown, ContentType.parse("text/markdown; charset=utf-8"))
            } }
            for (note in listOf(false, true)) post("/{document}/" + if (note) "notes" else "progress") {
                call.answer { course, device ->
                    val d = library.document(course, call.parameters["document"]!!)
                    val input = call.input()
                    if (input.str("version") != d.version) conflictReader("资料版本已改变，请重新定位；旧批注仍保留")
                    val page = input.readerLong("pdfPage").takeIf { it in 1L..d.pages.toLong() }?.toInt() ?: badReader("PDF 页码超出文档")
                    val text = if (note) library.page(d, page) else null
                    call.json(actor.execute { ledger.save(this, course, d, input, device, note, text) })
                    pushes.emit(course)
                }
            }
            // No route to ChatService/SourcePack/BridgeClient: private extracts cannot reach a cloud CLI.
        }
    }
}
