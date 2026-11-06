package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private data class Reopen(val course: String, val thread: String, val kp: String, val old: Set<String>, val current: Set<String>, val review: Boolean)

/** Opt-in only. Copy a consistent SQLite backup to a disposable instance; never open the input for writing.
 * No source content, answers, IDs or credentials are printed, even on assertion failure.
 */
fun main() = runBlocking(Dispatchers.IO) {
    val backup = Path.of(requireNotNull(System.getenv("COACH_TUTOR_BACKUP")) { "Set COACH_TUTOR_BACKUP to a consistent SQLite backup" })
    val directory = Files.createTempDirectory("coach-tutor-migration-")
    Files.copy(backup, directory.resolve("coach.sqlite"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val actor = LedgerActor(scope, LedgerStore(directory))
    val token = java.util.UUID.randomUUID().toString()
    val chat = ChatService(actor, scope, BridgeClient(BridgeConfig("http://127.0.0.1:1")), log = {},
        packsDirectory = directory.resolve("packs"))
    val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
        ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), chat)
    }
    try {
        val courses = actor.execute { courses() }
        val before = actor.execute { courses.associateWith { snapshot(it) } }
        val callsBefore = actor.execute { supplyCallCount() }
        val candidates = before.values.flatMap { snapshot ->
            val metadata = mutableMapOf<String, Set<String>>()
            snapshot.events.filter { it.type == "outline_revised" }.forEach { event ->
                ((event.payload["knowledgePoints"] as? JsonArray).orEmpty() +
                    (event.payload["ops"] as? JsonArray).orEmpty()).forEach { raw ->
                    val point = raw.jsonObject
                    if ("conceptIds" in point) metadata[point.str("kpId", point.str("id"))] = point.strings("conceptIds").toSet()
                }
            }
            snapshot.events.filter { it.type == "kp_opened" && it.threadId != null && it.payload.strings("conceptIds").isNotEmpty() }
                .distinctBy { it.threadId }.mapNotNull { opened ->
                    val kp = opened.payload.str("kpId")
                    val old = opened.payload.strings("conceptIds").toSet()
                    val current = metadata[kp] ?: return@mapNotNull null
                    if (old == current) null else Reopen(snapshot.courseId, opened.threadId!!, kp, old, current, opened.payload.str("via") == "REVIEW")
                }
        }
        require(candidates.isNotEmpty()) { "No affected sessions in this backup" }
        val histories = actor.execute { candidates.map { chatHistory(it.course, it.thread) } }
        actor.execute {
            enroll("migration-desktop", token)
            enroll("migration-android", token)
        }
        engine.start(wait = false)
        val port = engine.engine.resolvedConnectors().first().port
        var requests = 0
        for ((device, stale) in listOf("migration-desktop" to false, "migration-android" to true)) {
            val frames = LinkedBlockingQueue<JsonObject>()
            val listener = object : WebSocket.Listener {
                private val buffer = StringBuilder()
                override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage<*>? {
                    buffer.append(data)
                    if (last) {
                        frames.add(LedgerJson.parseToJsonElement(buffer.toString()).jsonObject)
                        buffer.setLength(0)
                    }
                    ws.request(1)
                    return null
                }
            }
            val socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", "Bearer $token").header("X-Coach-Device", device)
                .buildAsync(URI.create("ws://127.0.0.1:$port/v1/ws"), listener).get(5, TimeUnit.SECONDS)
            try {
                for (c in candidates) {
                    socket.sendText(json("t" to "tutor.open", "courseId" to c.course, "threadId" to c.thread,
                        "kpId" to c.kp, "conceptIds" to (if (stale) c.old else c.current).sorted(),
                        "title" to "Private replay", "review" to c.review).toString(), true).get(5, TimeUnit.SECONDS)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    var opened = false
                    while (!opened && System.nanoTime() < deadline) {
                        val frame = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        check(frame.str("t") != "msg.error") { "Tutor open returned an error (private details suppressed)" }
                        opened = frame.str("t") == "tutor.state" && frame.str("threadId") == c.thread
                    }
                    check(opened) { "Tutor state was not received" }
                    requests++
                }
            } finally { socket.abort() }
        }
        val after = actor.execute { courses.associateWith { snapshot(it) } }
        for (course in courses) {
            val old = before.getValue(course)
            val now = after.getValue(course)
            check(old.events == now.events.take(old.events.size)) { "Historical events changed" }
            check(old.projection.mastery == now.projection.mastery) { "Mastery changed" }
            check(old.projection.taughtConceptIds == now.projection.taughtConceptIds) { "Taught evidence changed" }
            check(old.projection.explainedKpIds == now.projection.explainedKpIds) { "Explanation history changed" }
            check(old.projection.position == now.projection.position) { "Continue or pending position changed" }
            check(old.projection.review == now.projection.review) { "Review schedule changed" }
            check(old.projection.unsupportedTypes == now.projection.unsupportedTypes) { "New event unsupported" }
        }
        check(histories == actor.execute { candidates.map { chatHistory(it.course, it.thread) } }) { "Chat history changed" }
        val added = courses.flatMap { course -> after.getValue(course).events.drop(before.getValue(course).events.size) }
        check(added.size == candidates.size && added.all { it.type == "tutor_concepts_changed" }) { "Unexpected writes or duplicate revisions" }
        check(actor.execute { supplyCallCount() } == callsBefore) { "Unexpected AI calls" }
        println("TUTOR_MIGRATION_REPLAY sessions=${candidates.size} websocket_opens=$requests identity_errors=0 preserved_events=${before.values.sumOf { it.events.size }} history_unchanged=true mastery_unchanged=true pending_unchanged=true concept_revisions=${added.size} ai_calls=0")
    } finally {
        engine.stopSuspend(0, 500)
        actor.close()
        scope.cancel()
        directory.toFile().deleteRecursively()
    }
}
