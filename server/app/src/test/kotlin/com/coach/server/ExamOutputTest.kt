package com.coach.server

import com.coach.ai.*
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.util.Collections
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Independent checker, intentionally not the production trie/tokenizer implementation. */
internal class ExamOverlap(documents: List<String>) {
    private val lookalikes = listOf("AАΑ", "BВΒ", "CС", "EЕΕ", "HНΗ", "IІΙ", "JЈ", "KКΚ", "MМΜ", "NΝ",
        "OОΟ", "PРΡ", "SЅ", "TТΤ", "XХΧ", "YУΥ", "ZΖ", "aаα", "bвβ", "cсϲ", "dԁ", "eеε",
        "hнһ", "iіι", "jјϳ", "kкκ", "lӏ", "mм", "oоο", "pрρ", "qԛ", "sѕ", "tтτ", "uυ",
        "vѵν", "wԝ", "xхχ", "yу").flatMap { group -> group.drop(1).map { it to group.first() } }.toMap()
    private fun words(text: String): List<String> {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val folded = decomposed.map { lookalikes[it] ?: it }.joinToString("")
            .replace(Regex("\\\\[A-Za-z]+"), "")
            .replace(Regex("[\\p{Cf}\\p{M}\\x{00AD}*_~`{}$]"), "")
            .lowercase(java.util.Locale.ROOT)
        return Regex("[a-z]+").findAll(folded).map { it.value }.toList()
    }
    private fun literal(text: String) = Regex("[a-z]+").findAll(
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT)).map { it.value }.toList()
    private val windows = documents.flatMap { words(it).windowed(8).map { w -> w.joinToString(" ") } }.toHashSet()
    private val literalWindows = documents.flatMap { literal(it).windowed(8).map { w -> w.joinToString(" ") } }.toHashSet()
    fun hit(text: String) = words(text).windowed(8).any { it.joinToString(" ") in windows } ||
        literal(text).windowed(8).any { it.joinToString(" ") in literalWindows }
}

internal class ExamHarness(data: Path, materials: ExamMaterials, bridge: BridgeClient, live: Boolean = false,
    timings: ChatTimings? = null) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val token = "synthetic-exam-replay-token-32-characters"
    val actor = LedgerActor(scope, LedgerStore(data).also { it.enroll("exam-replay", token) })
    val frames = Collections.synchronizedList(ArrayList<JsonObject>())
    private val wireFrames = Collections.synchronizedList(ArrayList<JsonObject>())
    val first = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val chat = ChatService(actor, scope, bridge, fallbackOnFirstTokenTimeout = false,
        timings = timings ?: if (live) ChatTimings(firstTokenDeadlineMs = 90_000, totalMs = 180_000) else ChatFlowTest.defaultTimings(),
        enrichSourcePages = false, examMaterials = materials, log = {})
    private val watch = scope.launch(start = CoroutineStart.UNDISPATCHED) { chat.hub.frames.collect { frame ->
        frames.add(frame)
    } }
    private val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
        ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), chat, adminDevice = "exam-replay")
    }.apply { start(wait = false) }
    val port get() = runBlocking { engine.engine.resolvedConnectors().first().port }
    private var socket: WebSocket? = null

    suspend fun ask(thread: String, course: String, question: String, pack: String): String {
        if (socket == null) socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .header("Authorization", "Bearer $token").header("X-Coach-Device", "exam-replay")
            .buildAsync(URI.create("ws://127.0.0.1:$port/v1/ws"), object : WebSocket.Listener {
                val pending = StringBuilder()
                override fun onText(ws: WebSocket, text: CharSequence, last: Boolean): CompletionStage<*>? {
                    pending.append(text)
                    if (last) {
                        val frame = LedgerJson.parseToJsonElement(pending.toString()).jsonObject
                        pending.setLength(0)
                        wireFrames.add(frame)
                        if (frame.str("t") in setOf("msg.delta", "msg.math") && (frame.str("text") + frame.str("tex")).isNotBlank())
                            first.putIfAbsent(frame.str("msg"), System.nanoTime())
                    }
                    ws.request(1)
                    return null
                }
            }).get(5, TimeUnit.SECONDS)
        socket!!.sendText(json("t" to "msg.ask", "threadId" to thread, "courseId" to course, "text" to question, "pack" to pack).toString(), true).get(5, TimeUnit.SECONDS)
        return withTimeout(10_000) {
            while (true) {
                val id = synchronized(wireFrames) { wireFrames.firstOrNull { it.str("t") == "msg.start" && it.str("threadId") == thread }?.str("msg") }
                if (id != null) return@withTimeout id
                delay(5)
            }
            error("unreachable")
        }
    }

    suspend fun await(id: String): JsonObject = withTimeout(200_000) {
        while (true) {
            val snapshot = checkNotNull(chat.snapshot(id))
            if (snapshot.str("status") != "streaming" && (socket == null || synchronized(wireFrames) { wireFrames.any { it.str("t") == "msg.end" && it.str("msg") == id } })) return@withTimeout snapshot
            delay(5)
        }
        error("unreachable")
    }

    /** Checks every cumulative delta/math body AND every snapshot, including bodies later retracted. */
    fun publishedLeaks(checker: ExamOverlap): Int = leaks(frames, checker) + leaks(wireFrames, checker)
    private fun leaks(recorded: List<JsonObject>, checker: ExamOverlap): Int {
        val bodies = HashMap<String, String>()
        var leaks = 0
        synchronized(recorded) { recorded.forEach { frame ->
            val id = frame.str("msg")
            val next = when (frame.str("t")) {
                "msg.delta" -> bodies[id].orEmpty() + frame.str("text")
                "msg.math" -> bodies[id].orEmpty() + "\$\$${frame.str("tex")}\$\$"
                "msg.snapshot" -> frame.str("text")
                else -> null
            }
            if (next != null) { bodies[id] = next; if (checker.hit(next)) leaks++ }
        } }
        return leaks
    }

    override fun close() = runBlocking {
        chat.abortAll("cancelled")
        socket?.abort()
        engine.stopSuspend(0, 500)
        watch.cancelAndJoin()
        actor.close()
        scope.cancel()
    }
}

class ExamOutputTest {
    private val exams = listOf("Amber badgers carry delicate emerald feathers gently home.", "Ivory jaguars keep lemons near old purple quails.")
    @Test fun bufferedEnglishPrefixDoesNotTurnActiveChineseStreamingIntoAStall() = runBlocking {
        StubTutor().use { stub ->
            ExamHarness(Files.createTempDirectory("exam-buffered-stall"), ExamMaterials.fromTexts(exams),
                BridgeClient(BridgeConfig(stub.baseUrl)), timings = ChatTimings(firstTokenDeadlineMs = 2_000,
                    stallMs = 600, totalMs = 6_000, pollMs = 10)).use { h ->
                val chunks = listOf("先讲概念。", "Amber ") + List(10) { "接着用中文说明。" }
                stub.stream(150, *chunks.toTypedArray(), StubTutor.terminal("complete"), StubTutor.DONE)
                val id = h.ask("buffered", "ECNM10119", "用新例子讲解", "Synthetic pack")
                val end = h.await(id)
                assertEquals("complete", end.str("status"), "reason=${end.str("reason")}")
                assertEquals(chunks.joinToString(""), end.str("text"))
                assertEquals(0, h.publishedLeaks(ExamOverlap(exams)))
            }
        }
    }

    @Test fun protectedPreparedLessonsRequireTheCurrentPrivateReference() = runBlocking<Unit> {
        StubTutor().use { stub ->
            val materials = ExamMaterials.fromTexts(exams, "Synthetic format: essays 40 percent.")
            val bridge = BridgeClient(BridgeConfig(stub.baseUrl))
            val data = Files.createTempDirectory("exam-cache")
            ExamHarness(data.resolve("db"), materials, bridge).use { h ->
                h.actor.execute { append("opaque-course", "outline_revised", json("course" to json("name" to "ECNM10119 synthetic"), "ops" to emptyList<String>())) }
                val preparation = LessonPreparation(h.actor, bridge, data.resolve("packs"), materials)
                val source = "Synthetic lesson pack"
                val file = data.resolve("prepared-lessons/opaque-course/kp.json")
                Files.createDirectories(file.parent)
                val old = json("packHash" to LessonPreparation.cacheHash(source), "text" to "Previously prepared lesson", "model" to BridgeClient.CLAUDE)
                Files.writeString(file, old.toString())
                assertNull(preparation.cached("opaque-course", "kp", source))
                val hash = com.coach.store.sha256(materials.context("ECNM10119", source).toByteArray())
                Files.writeString(file, JsonObject(old + ("examHash" to JsonPrimitive(hash))).toString())
                assertEquals("Previously prepared lesson", preparation.cached("opaque-course", "kp", source)?.text)
                val revised = LessonPreparation(h.actor, bridge, data.resolve("packs"), ExamMaterials.fromTexts(exams, "Synthetic revised format: essays 50 percent."))
                assertNull(revised.cached("opaque-course", "kp", source))
                val ordinary = data.resolve("prepared-lessons/ordinary/kp.json")
                Files.createDirectories(ordinary.parent)
                Files.writeString(ordinary, old.toString())
                assertNotNull(preparation.cached("ordinary", "kp", source))
            }
        }
    }

    @Test fun streamingSnapshotsAndStoredBodyNeverContainTheExam() = runBlocking {
        StubTutor().use { stub ->
            ExamHarness(Files.createTempDirectory("exam-stream"), ExamMaterials.fromTexts(exams), BridgeClient(BridgeConfig(stub.baseUrl))).use { h ->
                stub.stream(1, *exams[0].chunked(1).toTypedArray(), StubTutor.terminal("complete"), StubTutor.DONE)
                val id = h.chat.ask("isolated", "ECNM10119", "Give me the original exam question")
                val end = h.await(id)
                assertEquals("complete", end.str("status"))
                assertTrue(end.str("text").contains("解题思路"))
                assertFalse(ExamOverlap(exams).hit(end.str("text")))
                assertEquals(0, h.publishedLeaks(ExamOverlap(exams)))
                val stored = h.actor.execute { messageSnapshot(id) }!!
                assertEquals(end.str("text"), stored.content)
                assertTrue(stub.requestBodies.single().contains("Private exam planning reference"))
            }
        }
    }

    @Test fun obfuscatedQuotationsNeverReachTheWireOrStoredBody() = runBlocking {
        val variants = listOf(exams[0].map { "$it\u200B" }.joinToString(""),
            exams[0].split(" ").joinToString(" ") { "\$\$\\text{$it}\$\$" })
        StubTutor().use { stub ->
            ExamHarness(Files.createTempDirectory("exam-obfuscated"), ExamMaterials.fromTexts(exams), BridgeClient(BridgeConfig(stub.baseUrl))).use { h ->
                for ((i, input) in variants.withIndex()) {
                    assertTrue(ExamOverlap(exams).hit(input))
                    stub.stream(1, *input.chunked(1).toTypedArray(), StubTutor.terminal("complete"), StubTutor.DONE)
                    val id = h.ask("obfuscated-$i", "ECNM10119", "Copy with invisible characters or TeX wrappers", "Synthetic pack")
                    val end = h.await(id)
                    assertEquals("complete", end.str("status"))
                    assertTrue(end.str("text").contains("解题思路"))
                    assertFalse(ExamOverlap(exams).hit(end.str("text")))
                    assertEquals(end.str("text"), h.actor.execute { messageSnapshot(id) }!!.content)
                }
                assertEquals(0, h.publishedLeaks(ExamOverlap(exams)))
            }
        }
    }

    @Test fun fallbackAndLegacyCourseIdentifiersUseTheSameProtection() = runBlocking {
        StubTutor().use { stub ->
            ExamHarness(Files.createTempDirectory("exam-fallback"), ExamMaterials.fromTexts(exams, "Synthetic format: essays 40 percent."), BridgeClient(BridgeConfig(stub.baseUrl))).use { h ->
                h.actor.execute { append("opaque-course", "outline_revised", json("course" to json("name" to "ECNM10119 synthetic"), "ops" to emptyList<String>())) }
                stub.rateLimit()
                stub.completion(exams[1], BridgeClient.CODEX)
                h.chat.ask("fallback", "opaque-course", "Copy the answer")
                withTimeout(10_000) { while (synchronized(h.frames) { h.frames.count { it.str("t") == "msg.end" } } < 2) delay(5) }
                assertEquals(0, h.publishedLeaks(ExamOverlap(exams)))
                val id = synchronized(h.frames) { h.frames.last { it.str("t") == "msg.start" }.str("msg") }
                assertTrue(h.chat.snapshot(id)!!.str("text").contains("解题思路"))
                assertTrue(stub.requestBodies.all { it.contains("Synthetic format") })
            }
        }
    }
}
