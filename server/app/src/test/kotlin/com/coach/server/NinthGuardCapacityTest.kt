package com.coach.server

import com.coach.ai.*
import com.coach.core.LedgerActor
import com.coach.core.TutorOutputGuard
import com.coach.domain.*
import com.coach.domain.scope.VisibleScope
import com.coach.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Collections
import kotlin.test.*

/** Capacity regression: nine independent protected sentences with a full six-page public pack must not leak through initial, lookup (macOS sandbox only) or real-429 fallback paths. Synthetic only. */
class NinthGuardCapacityTest {
    private val secrets = listOf(
        "Cerulean badgers navigate crystalline tunnels beneath an ancient sugar refinery",
        "Platinum seahorses embroider botanical diagrams upon ceremonial woollen jackets",
        "Crimson alchemists balance porcelain teacups beside the silent volcanic observatory",
        "Silver penguins rehearse elaborate dances around a deserted saffron marketplace",
        "Emerald musicians bottle fragrant echoes from the distant tropical clock tower",
        "Indigo gardeners weave luminous hammocks for elderly travellers aboard airships",
        "Violet architects measure impossible doorways throughout the mossy cathedral",
        "Bronze detectives decipher encrypted postcards during the annual mushroom festival",
        "Golden otters deliver miniature telescopes across the windswept lavender plateau",
    )
    private val publicSentence = "Public lanterns illuminate the classroom safely"
    private val query = "capacityterm"

    private fun windows(text: String) = TutorOutputGuard.normalizeLeakText(text).windowed(24).toSet()
    private fun assertIndependentSentences() {
        secrets.forEachIndexed { i, sentence ->
            assertTrue(windows(sentence).isNotEmpty())
            secrets.take(i).forEach { other -> assertTrue(windows(sentence).intersect(windows(other)).isEmpty()) }
            assertTrue(windows(sentence).intersect(windows(publicSentence)).isEmpty())
        }
    }

    private inner class H : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(Files.createTempDirectory("qa-ninth-"))
        val actor = LedgerActor(scope, store)
        val stub = StubTutor()
        val frames = Collections.synchronizedList(mutableListOf<JsonObject>())
        init {
            // Six legitimately learned public pages fill the entire public pack budget.
            for (n in 1..6) store.registerSourcePage("c", "book", "Synthetic public book", n,
                "$publicSentence. Public page $n. " + "$query ".repeat(30))
            secrets.forEachIndexed { i, sentence ->
                store.registerSourcePage("c", "priv-${i + 1}", "Synthetic private ${i + 1}", 1,
                    "$sentence. " + "$query ".repeat(9 - i), learnerVisible = false)
            }
            DriverManager.getConnection("jdbc:sqlite:${store.directory.resolve("coach.sqlite")}").use { db ->
                db.prepareStatement("INSERT INTO outline_kp(kp_id,course_id,module_id,ord,title,kind,exam_formats,prereqs,anchors,retired,legacy_id) VALUES('known','c','m',1,'known','x','[]','[]',?,0,NULL)").use {
                    it.setString(1, """[{"docId":"book","pdfPage":6}]"""); it.executeUpdate()
                }
            }
            runBlocking { actor.execute { append("c", "kp_opened", json("kpId" to "known"), thread = "t") } }
        }
        val chat = ChatService(actor, scope, BridgeClient(BridgeConfig(stub.baseUrl)),
            fallbackOnFirstTokenTimeout = false, allowSourceLookup = true, log = {})
        private val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) { chat.hub.frames.collect { frames += it } }
        suspend fun end(id: String): JsonObject = withTimeout(8_000) {
            while (true) {
                val row = chat.snapshot(id)
                val ended = synchronized(frames) { frames.any { it.str("t") == "msg.end" && it.str("msg") == id } }
                if (row != null && row.str("status") != "streaming" && ended) return@withTimeout row
                delay(10)
            }
            error("unreachable")
        }
        suspend fun fallbackEnd(): JsonObject = withTimeout(8_000) {
            while (true) {
                val id = synchronized(frames) { frames.firstOrNull { it.str("t") == "msg.start" && it["fallback"]?.jsonPrimitive?.booleanOrNull == true }?.str("msg") }
                if (id != null) return@withTimeout end(id)
                delay(10)
            }
            error("unreachable")
        }
        override fun close() { runBlocking { actor.close() }; scope.cancel(); stub.close() }
    }

    private suspend fun observe(h: H, path: String, result: JsonObject, expectedRequests: Int) {
        val frameText = synchronized(h.frames) { h.frames.joinToString("\n") { it.toString() } }
        val ids = synchronized(h.frames) { h.frames.filter { it.str("t") == "msg.start" }.map { it.str("msg") }.distinct() }
        val snapshotText = ids.map { h.chat.snapshot(it).toString() }.joinToString("\n")
        val bodies = synchronized(h.stub.requestBodies) { h.stub.requestBodies.toList() }
        val outputs = listOf(result.str("text"), frameText, snapshotText)
        val outputLeaks = outputs.map { output -> secrets.mapIndexedNotNull { i, s -> if (windows(output).intersect(windows(s)).isNotEmpty()) i + 1 else null } }
        val inputLeaks = bodies.map { body -> secrets.mapIndexedNotNull { i, s -> if (windows(body).intersect(windows(s)).isNotEmpty()) i + 1 else null } }
        val publicPresent = outputs.map { publicSentence in it }
        val guard = h.actor.execute { restrictedForGuard("c", query) }
        val pack = h.actor.execute { searchSourcePages("c", query, learnedThrough("c", VisibleScope.learner(), "known")) }
        val guardPages = secrets.mapIndexedNotNull { i, s -> if (guard.any { s in it }) i + 1 else null }
        val observation = "NINTH_GUARD path=$path requests=${h.stub.requests} captured=${bodies.size} publicPack=${pack.size} " +
            "guard=$guardPages public(result,frames,snapshots)=$publicPresent " +
            "outputLeaks(result,frames,snapshots)=$outputLeaks perRequestLeaks=$inputLeaks status=${result.str("status")}"
        println(observation)
        assertEquals(expectedRequests, h.stub.requests, observation)
        assertEquals(expectedRequests, bodies.size, observation)
        assertEquals(6, pack.size, observation)
        assertTrue(pack.all { it.learnerVisible }, observation)
        assertTrue(publicPresent.all { it }, observation)
        assertTrue(bodies.last().contains(publicSentence), observation)
        assertTrue(inputLeaks.all { it.isEmpty() }, observation)
        // Output assertions precede guard-count assertions: the negative control proves an actual leak.
        assertTrue(outputLeaks.all { it.isEmpty() }, observation)
        assertEquals((1..9).toList(), guardPages, observation)
        assertEquals("complete", result.str("status"), observation)
    }

    @Test fun initialStreamBlocksNineIndependentSentencesWithSixPublicPages() = runBlocking {
        assertIndependentSentences()
        H().use { h ->
            h.stub.stream(1, "$publicSentence. " + secrets.joinToString(". ") + ".", StubTutor.terminal("complete"), StubTutor.DONE)
            observe(h, "initial", h.end(h.chat.ask("t", "c", query)), 1)
        }
    }

    @Test fun lookupRebuildsGuardAndBlocksNinthPageWithSixPublicPages() = runBlocking {
        assertIndependentSentences()
        H().use { h ->
            // Initial question matches no source. Only the lookup query can construct this guard.
            assertTrue(h.actor.execute { restrictedForGuard("c", "overview").isEmpty() })
            h.stub.stream(1, "Safe opening.【需查原文:$query】", StubTutor.terminal("complete"), StubTutor.DONE)
            val paragraphs = "“$publicSentence”【S1】\n\n" + secrets.joinToString("") { "$it.【S1】\n\n" }
            // Valid citation on unquoted protected statements keeps SourceCitationGate from masking a guard failure.
            h.stub.stream(1, paragraphs, StubTutor.terminal("complete"), StubTutor.DONE)
            val result = h.end(h.chat.ask("t", "c", "overview"))
            assertFalse(h.stub.requestBodies.first().contains(publicSentence))
            assertTrue(h.stub.requestBodies.last().contains("Lookup request: $query"))
            observe(h, "lookup", result, 2)
        }
    }

    @Test fun realHttp429FallbackBlocksNinthPageWithSixPublicPages() = runBlocking {
        assertIndependentSentences()
        H().use { h ->
            h.stub.rateLimit() // Actual HTTP 429 response; not a stream terminal marker.
            h.stub.completion("$publicSentence. " + secrets.joinToString(". ") + ".", BridgeClient.CODEX)
            val original = h.chat.ask("t", "c", query)
            val result = h.fallbackEnd()
            h.end(original)
            observe(h, "http429", result, 2)
        }
    }
}
