package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.client.ChatStreamAssembler
import com.coach.core.LedgerActor
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun JsonObject.str(key: String): String =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""

/**
 * Ktor ↔ 桥（桩）↔ WS 三段的端到端：首字、续传、取消、中途断流、终态对应、可见流过滤。
 *
 * 全程桩桥，不调用任何真实订阅 CLI（DESIGN §13.2 期中保护）。
 */
class ChatFlowTest {
    @Test
    fun clientFirstTokenReportIsLoggedWithKnowledgePointAndRequestKind() {
        Harness().use { h ->
            runBlocking {
                h.actor.execute {
                    append("course-1", "outline_revised", com.coach.domain.json(
                        "knowledgePoints" to kotlinx.serialization.json.JsonArray(listOf(com.coach.domain.json(
                            "id" to "kp-1", "moduleId" to "module-1", "order" to 1, "title" to "Essay topic",
                            "kind" to null, "examFormats" to kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("ESSAY"))),
                            "prerequisites" to emptyList<String>(), "anchors" to kotlinx.serialization.json.JsonArray(emptyList()),
                            "retired" to false, "conceptIds" to kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("concept-1")))
                        ))),
                        "ops" to kotlinx.serialization.json.JsonArray(listOf(com.coach.domain.json(
                            "op" to "add", "kpId" to "kp-1", "prereqs" to emptyList<String>()
                        )))
                    ))
                    append("course-1", "kp_opened", com.coach.domain.json("kpId" to "kp-1"), thread = "thread-1")
                }
            }
            Ws(h.port,h.token).use { ws ->
                ws.send("""{"t":"client.ttft","courseId":"course-1","threadId":"thread-1","msg":"msg-1","kind":"next","ms":142,"model":"claude-sonnet-5-5","fallback":false}""")
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (h.logs.none { it.contains("client_ttft") } && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(h.logs.any { it.contains("kp=kp-1") && it.contains("kp_type=ESSAY") && it.contains("kind=next") && it.contains("ms=142") })
            }
        }
    }

    @Test
    fun lessonPreparationSelectsRelevantSeedPageAfterFifteenThousandCharacters() {
        val title = "RAND HIE Oregon CRHIE randomized experiment"
        val metadata = com.coach.domain.json(
            "name" to title,
            "sourceAnchor" to com.coach.domain.json("pdfPage" to 10)
        )
        val header = "Knowledge point: $title\nReference: textbook\n\n"
        val filler = "Unrelated chapter material without the target concepts. ".repeat(45)
        val source = header + buildString {
            for (page in 1..8) append("**〔PDF 第 $page 页 · 书第 $page 页〕**\n").appendLine(filler)
            append("**〔PDF 第 9 页 · 书第 9 页〕**\n")
            append("Late KP body: RAND HIE randomized experiment, Oregon Medicaid, and CRHIE compare insurance demand.\n")
            append("**〔PDF 第 10 页 · 书第 10 页〕**\nSource anchor reference page.\n")
            for (page in 11..14) append("**〔PDF 第 $page 页 · 书第 $page 页〕**\nUnrelated appendix.\n")
        }

        assertTrue(source.indexOf("Late KP body") > LessonPreparation.MAX_LESSON_INPUT_CHARS)
        val selected = LessonPreparation.boundedInput(source, metadata)

        assertTrue(selected.startsWith(header))
        assertTrue(selected.contains("Source anchor reference page."))
        val sourceMarkers = Regex("(?m)^.*〔PDF 第 ([0-9]+) 页.*$").findAll(source).toList()
        val sourcePageLengths = sourceMarkers.mapIndexed { index, marker ->
            marker.groupValues[1] to (source.substring(marker.range.first, sourceMarkers.getOrNull(index + 1)?.range?.first ?: source.length).length)
        }
        assertTrue(selected.contains("Late KP body"), "Selected=${Regex("〔PDF 第 ([0-9]+) 页").findAll(selected).map { it.groupValues[1] }.toList()}, sourcePages=$sourcePageLengths, selectedChars=${selected.length}, title=${metadata.str("title")}")
        assertTrue(selected.length <= LessonPreparation.MAX_LESSON_INPUT_CHARS)
        val selectedPages = Regex("〔PDF 第 ([0-9]+) 页").findAll(selected).map { it.groupValues[1].toInt() }.toList()
        assertTrue(selectedPages.size <= 6)
        assertEquals(selectedPages.sorted(), selectedPages)
    }

    @Test
    fun reteachBudgetKeepsTheRankedPageOpeningInsteadOfTheBareHeader() {
        val header = "Knowledge point: Moral hazard\nReference: textbook\n\n"
        val page = { n: Int, body: String -> "**〔PDF 第 $n 页 · 书第 $n 页〕**\n$body\n" }
        val source = header + page(1, "Unrelated filler. ".repeat(300)) + page(2, "Moral hazard changes behavior after insurance. ".repeat(300))
        val selected = LessonPreparation.boundedInput(source, com.coach.domain.json("name" to "Moral hazard"), LessonPreparation.MAX_RETEACH_INPUT_CHARS)

        assertTrue(selected.startsWith(header))
        assertTrue(selected.length <= LessonPreparation.MAX_RETEACH_INPUT_CHARS)
        assertTrue(selected.contains("Moral hazard changes behavior"), "the best-ranked page opening must survive a small budget")
        // The default budget is untouched.
        assertTrue(LessonPreparation.boundedInput(source, com.coach.domain.json("name" to "Moral hazard")).length > LessonPreparation.MAX_RETEACH_INPUT_CHARS)
    }

    @Test
    fun headerOnlyKeepsTitleAndReferenceButNoPages() {
        val header = "Knowledge point: Moral hazard\nReference: textbook p.5\n"
        val source = header + "**〔PDF 第 1 页 · 书第 1 页〕**\nPage body that must not be sent.\n"
        assertEquals(header.trimEnd(), LessonPreparation.headerOnly(source))
        assertEquals("Plain unpaginated text", LessonPreparation.headerOnly("Plain unpaginated text"))
    }

    /** An untaught knowledge point with one reviewed question: a wrong answer then asks for the first explanation, which is staged. */
    private suspend fun seedReviewed(h: Harness) {
        h.actor.execute {
            val id = enqueueSupply("course-1","kp-1","Objective","Synthetic source")
            val job = supplyJobs().single { it.id == id }
            val options = listOf("opportunity cost","sunk expenditure","fixed payment","total revenue").mapIndexed { j,text -> com.coach.domain.Option("o$j",text) }
            storeVerifiedBatch(claimJob(id)!!,job,listOf(
                com.coach.store.VerifiedPoolQuestion("Which cost describes the forgone alternative?",options,"o0",
                    "Opportunity cost is the best forgone alternative; sunk costs cannot be recovered.","Synthetic source","claude","gpt"),
                com.coach.store.VerifiedPoolQuestion("Which cost cannot be recovered once spent?",options,"o1",
                    "A sunk cost is already spent and cannot be recovered.","Synthetic source","claude","gpt")))
        }
    }

    /** Opens the thread, answers one reviewed question wrongly, and leaves the tutor ready for the first explanation. */
    private fun answerWrongBeforeTeaching(h: Harness, ws: Ws) {
        ws.send("""{"t":"tutor.open","threadId":"thread-1","courseId":"course-1","kpId":"kp-1","conceptIds":["cost"],"title":"Synthetic objective"}""")
        ws.await("tutor.state")
        ws.send("""{"t":"tutor.settings","threadId":"thread-1","courseId":"course-1","groupSize":1}""")
        ws.send("""{"t":"tutor.test","threadId":"thread-1","courseId":"course-1"}""")
        val group = ws.await("tutor.cards")
        val card = (group.getValue("cards") as kotlinx.serialization.json.JsonArray).first().jsonObject
        val wrong = runBlocking { h.actor.execute {
            val map = card.getValue("letterMap").jsonObject
            val correct = map.getValue(tutorChoiceKey(card.str("questionId")).correctOptionId)
            map.values.first { it != correct }
        } }
        ws.send("""{"t":"tutor.answer","threadId":"thread-1","courseId":"course-1","groupId":"${group.str("groupId")}","submissionId":"wrong-1","answers":{"${card.str("attemptId")}":$wrong}}""")
        ws.await("tutor.feedback")
        while (ws.await("tutor.state").str("state") != "FEEDBACK") Unit
    }

    private fun collectDeltas(ws: Ws): Pair<String, JsonObject> {
        val text = StringBuilder()
        while (true) {
            val frame = ws.await("msg.delta","msg.end", timeoutMs = 8_000)
            if (frame.str("t") == "msg.end") return text.toString() to frame
            text.append(frame.str("text"))
        }
    }

    @Test
    fun stagedFirstExplanationStreamsMistakesThenLessonInOrderEvenWhenTheLessonFinishesFirst() {
        Harness().use { h ->
            runBlocking { seedReviewed(h) }
            val partOne = listOf("你选的「sunk expenditure」不对，","因为已花掉的钱收不回；","正确项「opportunity cost」成立。")
            h.tutor.streamWhen("explains the graded mistakes only", 120, *partOne.toTypedArray(), StubTutor.terminal("complete"), StubTutor.DONE)
            h.tutor.streamWhen("Start directly with the new concepts", 5, "机会成本是放弃的最佳替代。", StubTutor.terminal("complete"), StubTutor.DONE)
            Ws(h.port,h.token).use { ws ->
                answerWrongBeforeTeaching(h, ws)
                ws.send("""{"t":"tutor.next","threadId":"thread-1","courseId":"course-1"}""")
                val (visible, end) = collectDeltas(ws)
                assertEquals("complete", end.str("status"))
                val first = visible.indexOf("你选的「sunk expenditure」不对")
                val second = visible.indexOf("机会成本是放弃的最佳替代。")
                assertTrue(first == 0 && second > visible.indexOf("正确项「opportunity cost」成立。"), "part one must come first and whole, then the lesson: $visible")
                assertEquals(1, Regex("你选的").findAll(visible).count(), "the lesson must not repeat the mistake block")
                assertEquals(2, h.tutor.requests, "one upstream request per part")
                assertEquals(setOf("low","medium"), h.tutor.requestEfforts.takeLast(2).toSet())
                val bodies = h.tutor.requestBodies.takeLast(2)
                val mistakesBody = bodies.single { it.contains("explains the graded mistakes only") }
                val lessonBody = bodies.single { it.contains("Start directly with the new concepts") }
                assertTrue(!mistakesBody.contains("Fully teach ONLY") && lessonBody.contains("Fully teach ONLY"))
                assertTrue(!lessonBody.contains("Cover EVERY graded question"), "the lesson part carries no mistake block")
                assertTrue(runBlocking { h.actor.execute { events("course-1").any { it.type == "explanation_delivered" } } })
            }
        }
    }

    @Test
    fun stagedLessonFailureKeepsPartOneAndEndsTruncatedWithARetryableReason() {
        Harness().use { h ->
            runBlocking { seedReviewed(h) }
            h.tutor.streamWhen("explains the graded mistakes only", 20, "你选的「sunk expenditure」不对。", StubTutor.terminal("complete"), StubTutor.DONE)
            h.tutor.httpErrorWhen("Start directly with the new concepts", 500)
            Ws(h.port,h.token).use { ws ->
                answerWrongBeforeTeaching(h, ws)
                ws.send("""{"t":"tutor.next","threadId":"thread-1","courseId":"course-1"}""")
                val messageId = ws.await("msg.start").str("msg")
                val (visible, end) = collectDeltas(ws)
                assertEquals("truncated", end.str("status"))
                assertEquals("lesson_part_failed", end.str("reason"))
                assertTrue(visible.contains("你选的「sunk expenditure」不对。"), "part one must survive: $visible")
                val stored = runBlocking { h.snapshot(messageId) }
                assertEquals("truncated", stored?.status)
                assertTrue(stored?.content.orEmpty().contains("你选的「sunk expenditure」不对。"))
                assertTrue(runBlocking { h.actor.execute { events("course-1").none { it.type == "explanation_delivered" } } },
                    "an unfinished lesson must not mark the concepts taught, so the retry teaches them")
            }
        }
    }

    @Test
    fun cancellingAStagedReplyStopsBothUpstreams() {
        Harness().use { h ->
            runBlocking { seedReviewed(h) }
            h.tutor.streamWhen("explains the graded mistakes only", 400, "一","二","三","四","五","六", StubTutor.terminal("complete"), StubTutor.DONE)
            h.tutor.streamWhen("Start directly with the new concepts", 400, "甲","乙","丙","丁","戊","己", StubTutor.terminal("complete"), StubTutor.DONE)
            Ws(h.port,h.token).use { ws ->
                answerWrongBeforeTeaching(h, ws)
                ws.send("""{"t":"tutor.next","threadId":"thread-1","courseId":"course-1"}""")
                val messageId = ws.await("msg.start").str("msg")
                ws.await("msg.delta")
                val deadline = System.nanoTime() + 3_000_000_000L
                while (h.tutor.requests < 2 && System.nanoTime() < deadline) Thread.sleep(20)
                assertEquals(2, h.tutor.requests, "the lesson request must be in flight before the cancel")
                ws.send("""{"t":"msg.cancel","msg":"$messageId"}""")
                val end = ws.await("msg.end", timeoutMs = 3_000)
                assertEquals("cancelled", end.str("status"))
                val stopAt = System.nanoTime() + 3_000_000_000L
                while (h.tutor.abortedRequests.get() < 2 && System.nanoTime() < stopAt) Thread.sleep(20)
                assertEquals(2, h.tutor.abortedRequests.get(), "both upstream streams must be cut")
            }
        }
    }

    @Test
    fun longUnmarkedLessonPackFallsBackToBoundedPrefix() {
        val source = "Introduction.\n\n" + "Unpaginated source text. ".repeat(900)
        val selected = LessonPreparation.boundedInput(source, com.coach.domain.json("name" to "Synthetic KP"))

        assertTrue(source.length > LessonPreparation.MAX_LESSON_INPUT_CHARS)
        assertTrue(selected.length <= LessonPreparation.MAX_LESSON_INPUT_CHARS)
        assertTrue(source.startsWith(selected))
    }


    @Test
    fun slowSubscribersCauseAnObservableDrop() = runBlocking {
        val logs = mutableListOf<String>()
        val hub = ChatHub { logs.add(it) }
        val received = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val subscriber = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            hub.frames.collect { received.complete(Unit); release.await() }
        }
        try {
            hub.emit(com.coach.domain.json("t" to "msg.delta", "msg" to "slow", "i" to 1))
            received.await()
            repeat(1100) { hub.emit(com.coach.domain.json("t" to "msg.delta", "msg" to "slow", "i" to it + 2)) }
            assertTrue(logs.any { it.contains("chat-frame-drop") && it.contains("slow") && it.contains("buffer_full") })
        } finally { subscriber.cancel(); release.complete(Unit) }
    }

    @Test
    fun cancelBeforeTheStreamHandleExistsIsAppliedWhenItArrives() {
        Harness().use { h ->
            h.tutor.stream(600, "迟到的正文", "不应读完整条流", StubTutor.terminal("complete"), StubTutor.DONE)
            val session = ChatService.Session("cancel-before-handle", "thread", "course", BridgeClient.CLAUDE, false)
            session.abort("cancelled", "cancelled")
            val started = System.nanoTime()
            val result = BridgeClient(BridgeConfig(h.tutor.baseUrl)).streamChat(
                com.coach.ai.teachRequest("合成问题", ""), object : com.coach.ai.BridgeStreamSink {},
            ) { session.attach(it) }
            assertEquals("cancelled", result.call.status)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        }
    }

    @Test
    fun everyBridgeTerminalStatusReachesTheClientUnchanged() {
        for (status in listOf("complete", "truncated", "failed", "cancelled")) Harness().use { h ->
            val frames = if (status == "failed") emptyArray() else arrayOf("导师回答。")
            h.tutor.stream(10, *frames, StubTutor.terminal(status), StubTutor.DONE)
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                val end = ws.await("msg.end")
                assertEquals(status, end.str("status"))
            }
        }
    }

    private class Harness(timings: ChatTimings = defaultTimings(), packDir: java.nio.file.Path = Files.createTempDirectory("coach-packs-"),
        dataDir: java.nio.file.Path = Files.createTempDirectory("coach-chat-")) : AutoCloseable {
        val tutor = StubTutor()
        val token = "stream-test-token-32-characters-minimum"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(dataDir).also { it.enroll("desktop", token) }
        val actor = LedgerActor(scope, store)
        val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
        val chat = ChatService(
            actor = actor,
            scope = scope,
            bridge = BridgeClient(BridgeConfig(tutor.baseUrl, Duration.ofSeconds(2), Duration.ofSeconds(30))),
            timings = timings,
            fallbackOnFirstTokenTimeout = false,
            log = { logs.add(it) },
            packsDirectory = packDir,
        )
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), chat)
        }.apply { start(wait = false) }

        val port: Int get() = runBlocking { engine.engine.resolvedConnectors().first().port }

        suspend fun snapshot(messageId: String) = actor.execute { messageSnapshot(messageId) }

        override fun close() {
            runBlocking { engine.stopSuspend(0, 500); actor.close() }
            scope.cancel()
            tutor.close()
        }
    }

    private class Ws(port: Int, token: String, device: String = "desktop") : AutoCloseable {
        private val queue = LinkedBlockingQueue<JsonObject>()
        private val pending = ArrayList<JsonObject>()
        private val socket: WebSocket

        init {
            val listener = object : WebSocket.Listener {
                private val text = StringBuilder()
                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    text.append(data)
                    if (last) {
                        runCatching { com.coach.domain.LedgerJson.parseToJsonElement(text.toString()).jsonObject }
                            .getOrNull()?.let { queue.add(it) }
                        text.setLength(0)
                    }
                    webSocket.request(1)
                    return null
                }
            }
            socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", "Bearer $token")
                .header("X-Coach-Device", device)
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:$port/v1/ws"), listener)
                .get(5, TimeUnit.SECONDS)
        }

        fun send(text: String) {
            socket.sendText(text, true).get(5, TimeUnit.SECONDS)
        }

        fun await(vararg types: String, timeoutMs: Long = 5_000): JsonObject {
            val wanted = types.toSet()
            pending.firstOrNull { it.str("t") in wanted }?.let { matched ->
                pending.remove(matched)
                return matched
            }
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                val frame = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (frame.str("t") in wanted) return frame
                pending.add(frame)
            }
            throw AssertionError("timeout waiting for $wanted; saw ${pending.map { it.str("t") }}")
        }

        override fun close() {
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(2, TimeUnit.SECONDS) }
            runCatching { socket.abort() }
        }
    }

    private fun ask(ws: Ws, threadId: String = "thread-1") = ws.send(
        """{"t":"msg.ask","threadId":"$threadId","courseId":"course-1","text":"什么是机会成本？"}""",
    )

    @Test
    fun originalUserTextRestoresOnAnotherDeviceAndReplayAfterRestartDoesNotCallTheModel() {
        val directory = Files.createTempDirectory("coach-persist-chat-")
        val question = "  合成追问：why β?\n保留空白与原文  "
        val request = com.coach.domain.json("t" to "msg.ask","threadId" to "thread-1","courseId" to "course-1",
            "clientMsgId" to "stable-send-1","text" to question).toString()
        var userId = ""
        var createdAt = ""
        try {
            Harness(dataDir=directory).use { h ->
                h.tutor.stream(5,"A synthetic response.",StubTutor.terminal("complete"),StubTutor.DONE)
                Ws(h.port,h.token).use { ws ->
                    ws.send(request)
                    val user = ws.await("msg.user")
                    userId = user.str("msg"); createdAt = user.str("createdAt")
                    assertEquals(question,user.str("text"))
                    assertTrue(createdAt.toLong() > 0)
                    assertEquals("complete",ws.await("msg.end").str("status"))
                    val body = com.coach.domain.LedgerJson.parseToJsonElement(h.tutor.requestBodies.single()).jsonObject
                        .getValue("messages").jsonArray.last().jsonObject.str("content")
                    assertEquals(1,Regex(Regex.escape(question.trim())).findAll(body).count(),"Current input must not appear twice in model context")
                }
            }
            Harness(dataDir=directory).use { h ->
                runBlocking { h.actor.execute { enroll("phone","synthetic-phone-token-long-enough") } }
                Ws(h.port,"synthetic-phone-token-long-enough","phone").use { phone ->
                    phone.send("""{"t":"msg.history","threadId":"thread-1","courseId":"course-1"}""")
                    val history = phone.await("msg.history")
                    val rows = history.getValue("messages").jsonArray.map { it.jsonObject }
                    assertEquals(listOf("user","assistant"),rows.map { it.str("role") })
                    assertEquals(question,rows.first().str("text"))
                    assertEquals(userId,rows.first().str("msg"))
                    assertEquals(createdAt,rows.first().str("createdAt"))
                    assertEquals("false",history.str("legacyHistoryUncovered"))
                    val restored = com.coach.client.TutorModel({ 1000L })
                    restored.onFrame(history.toString()); restored.onFrame(history.toString())
                    assertEquals(2,restored.state.value.threads.getValue("thread-1").items.size)
                    assertEquals(question,restored.state.value.threads.getValue("thread-1").items.filterIsInstance<com.coach.client.ChatItem.Learner>().single().text)
                }
                Ws(h.port,h.token).use { ws ->
                    ws.send(request)
                    assertEquals(userId,ws.await("msg.user").str("msg"))
                    assertEquals(2,ws.await("msg.history").getValue("messages").jsonArray.size)
                    assertEquals(0,h.tutor.requests)
                    ws.send(com.coach.domain.json("t" to "msg.ask","threadId" to "thread-1","courseId" to "course-1",
                        "clientMsgId" to "stable-send-1","text" to "Changed payload").toString())
                    assertTrue(ws.await("msg.error").str("error").contains("payload mismatch"))
                    ws.send("""{"t":"msg.history","threadId":"thread-1","courseId":"other-course"}""")
                    assertTrue(ws.await("msg.error").str("error").contains("another course"))
                }
                val backup = runBlocking { h.actor.execute { backup(directory.resolve("verification-copy")) } }
                java.sql.DriverManager.getConnection("jdbc:sqlite:${backup.resolve("coach.sqlite")}").use { db ->
                    db.createStatement().use { statement ->
                        statement.executeQuery("SELECT role,content_md,thread_id,created_at FROM messages ORDER BY mseq").use { rows ->
                            assertTrue(rows.next()); assertEquals("user",rows.getString(1)); assertEquals(question,rows.getString(2))
                            assertEquals("thread-1",rows.getString(3)); assertEquals(createdAt.toLong(),rows.getLong(4))
                            assertTrue(rows.next()); assertEquals("assistant",rows.getString(1)); assertTrue(!rows.next())
                        }
                    }
                }
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun progressAndPlainContinueAreSavedAndDuplicateContinueDoesNotAdvanceOrCallAi() {
        Harness().use { h ->
            runBlocking { seedTutor(h,6) }
            h.tutor.stream(5,"A synthetic lesson.",StubTutor.terminal("complete"),StubTutor.DONE)
            Ws(h.port,h.token).use { ws ->
                ws.send("""{"t":"tutor.open","threadId":"thread-1","courseId":"course-1","kpId":"kp-1","conceptIds":["cost"],"title":"Synthetic objective"}""")
                ws.await("msg.history")
                ws.send("""{"t":"tutor.next","threadId":"thread-1","courseId":"course-1"}""")
                ws.await("msg.end")
                ws.send("""{"t":"msg.ask","threadId":"thread-1","courseId":"course-1","clientMsgId":"progress-1","text":"  学了多少？  "}""")
                ws.await("msg.user"); ws.await("msg.end")
                val next = """{"t":"msg.ask","threadId":"thread-1","courseId":"course-1","clientMsgId":"next-1","text":" 继续 "}"""
                ws.send(next)
                ws.await("msg.user"); ws.await("tutor.cards")
                val before = runBlocking { h.actor.execute { events("course-1") } }
                ws.send(next)
                ws.await("msg.user")
                val history = ws.await("msg.history")
                assertEquals(before,runBlocking { h.actor.execute { events("course-1") } })
                val users = history.getValue("messages").jsonArray.map { it.jsonObject }.filter { it.str("role")=="user" }
                assertEquals(listOf("  学了多少？  "," 继续 "),users.map { it.str("text") })
                assertEquals(1,h.tutor.requests,"Only the initial explicit teaching action calls AI")
            }
        }
    }

    @Test
    fun quickAskSharesTheSamePersistenceAndRetryContract() {
        Harness().use { h ->
            h.tutor.stream(5,"A synthetic quick answer.",StubTutor.terminal("complete"),StubTutor.DONE)
            Ws(h.port,h.token).use { ws ->
                val request = """{"t":"quick.ask","courseId":"course-1","clientMsgId":"quick-send","text":"Why synthetic flowers?"}"""
                ws.send(request)
                val thread = ws.await("quick.thread").str("threadId")
                val user = ws.await("msg.user")
                ws.await("msg.end")
                ws.send(request)
                assertEquals(thread,ws.await("quick.thread").str("threadId"))
                assertEquals(user.str("msg"),ws.await("msg.user").str("msg"))
                assertEquals(listOf("user","assistant"),ws.await("msg.history").getValue("messages").jsonArray.map { it.jsonObject.str("role") })
                assertEquals(1,h.tutor.requests)
                assertEquals(1,runBlocking { h.actor.execute { events("course-1").count { it.type=="quick_ask_message" && it.actor=="LEARNER" } } })
            }
        }
    }

    @Test
    fun androidCanStartTeachingWithoutUploadingTheTextbookPack() {
        val packs=Files.createTempDirectory("coach-content-")
        Files.createDirectories(packs.resolve("course-1"))
        Files.writeString(packs.resolve("course-1/kp-1.md"), "Synthetic source: the cost is the next best alternative.")
        Harness(packDir=packs).use { h ->
            h.tutor.stream(10, "教材讲解。", StubTutor.terminal("complete"), StubTutor.DONE)
            Ws(h.port,h.token).use { ws ->
                ws.send("""{"t":"tutor.open","courseId":"course-1","threadId":"thread-1","kpId":"kp-1","conceptIds":["c1"],"title":"机会成本"}""")
                ws.await("tutor.state")
                ws.send("""{"t":"tutor.next","courseId":"course-1","threadId":"thread-1"}""")
                assertEquals("complete",ws.await("msg.end").str("status"))
                assertTrue(h.tutor.requestBodies.single().contains("the cost is the next best alternative"))
                h.tutor.stream(10, "追问解释。", StubTutor.terminal("complete"), StubTutor.DONE)
                ws.send("""{"t":"msg.ask","courseId":"course-1","threadId":"thread-1","text":"How does the alternative affect a decision?"}""")
                assertEquals("complete",ws.await("msg.end").str("status"))
                assertEquals(2,h.tutor.requestBodies.size)
                assertTrue(h.tutor.requestBodies.last().contains("the cost is the next best alternative"))
            }
        }
    }

    @Test
    fun preparedLessonUsesRealCachedTextAndOnlyMarksTeachingWhenDisplayed() {
        val root=Files.createTempDirectory("coach-prepared-")
        val packs=root.resolve("packs")
        Files.createDirectories(packs.resolve("course-1"))
        val source="Synthetic source material.\n" + "Long course page content. ".repeat(2_000)
        Files.writeString(packs.resolve("course-1/kp-1.md"),source)
        val cache=root.resolve("prepared-lessons/course-1")
        Files.createDirectories(cache)
        Files.writeString(cache.resolve("kp-1.json"),com.coach.domain.json("packHash" to LessonPreparation.cacheHash(source),
            "text" to "预备好的完整讲解。","model" to BridgeClient.CLAUDE).toString())
        try { Harness(packDir=packs).use { h ->
            Ws(h.port,h.token).use { ws ->
                ws.send("""{"t":"tutor.open","courseId":"course-1","threadId":"thread-1","kpId":"kp-1","conceptIds":["c1"],"title":"机会成本"}""")
                ws.await("tutor.state")
                assertTrue(runBlocking { h.actor.execute { events("course-1").none { it.type=="explanation_delivered" } } })
                ws.send("""{"t":"tutor.next","courseId":"course-1","threadId":"thread-1","pack":${kotlinx.serialization.json.JsonPrimitive(source.take(40_000))}}""")
                assertEquals("PREPARE", ws.await("tutor.state").str("state"))
                assertEquals("TEACH", ws.await("tutor.state").str("state"))
                val start=ws.await("msg.start")
                assertEquals("complete",ws.await("msg.end").str("status"))
                assertEquals("预备好的完整讲解。",runBlocking { h.snapshot(start.str("msg")) }!!.content)
                assertEquals(0,h.tutor.requests)
                assertEquals(1,runBlocking { h.actor.execute { events("course-1").count { it.type=="explanation_delivered" } } })
            }
        } } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun prepareQuestionsUsesSeedNameAsTheQueuedObjective() = runBlocking {
        val root = Files.createTempDirectory("coach-seed-objective-")
        val packs = root.resolve("packs")
        Files.createDirectories(packs.resolve("course-1"))
        Files.writeString(packs.resolve("course-1/kp-1.md"), "Source material for a synthetic question.")
        try {
            Harness(packDir = packs).use { h ->
                val name = "RAND HIE design and Oregon Medicaid"
                h.actor.execute {
                    append("course-1", "outline_revised", com.coach.domain.json(
                        "knowledgePoints" to kotlinx.serialization.json.JsonArray(listOf(com.coach.domain.json(
                            "id" to "kp-1", "name" to name,
                            "sourceAnchor" to com.coach.domain.json("pdfPage" to 1)
                        )))
                    ))
                }
                h.tutor.stream(5_000, StubTutor.terminal("complete"), StubTutor.DONE)
                val preparation = async { h.chat.prepareLessons("course-1", listOf("kp-1"), questionsOnly = true) }
                try {
                    val job = withTimeout(2_000) {
                        while (true) {
                            val pending = h.actor.execute { supplyJobs("course-1", "kp-1").firstOrNull() }
                            if (pending != null) return@withTimeout pending
                            delay(10)
                        }
                        error("unreachable")
                    }
                    assertEquals(name, job.subject.str("objective"))
                } finally {
                    preparation.cancelAndJoin()
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun official429FallsBackWithoutWaitingForFirstTokenDeadline() {
        Harness().use { h ->
            h.tutor.rateLimit()
            h.tutor.completion("兜底的完整回答。", BridgeClient.CODEX)
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                ws.await("msg.start")
                val notice = ws.await("tutor.progress")
                assertEquals("Claude 暂时限流，已换 ${BridgeClient.CODEX} 回答", notice.str("text"))
                val replacement = ws.await("msg.snapshot")
                assertEquals("", replacement.str("text"))
                ws.await("msg.end")
                val fallback = ws.await("msg.start")
                assertEquals(BridgeClient.CODEX, fallback.str("model"))
                val end = ws.await("msg.end")
                assertEquals("complete", end.str("status"))
                val row = runBlocking { h.snapshot(fallback.str("msg")) }!!
                assertEquals("兜底的完整回答。", row.content)
                assertEquals(2, h.tutor.requests)
            }
        }
    }

    @Test
    fun officialRateLimitAfterPartialTextReplacesTheIncompleteAnswer() {
        Harness().use { h ->
            h.tutor.stream(10, "未写完的解释。", StubTutor.terminal("truncated", "rate_limited"), StubTutor.DONE)
            h.tutor.completion("换模型后的完整解释。", BridgeClient.CODEX)
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                val original = ws.await("msg.start")
                ws.await("tutor.progress")
                val replacement = ws.await("msg.snapshot")
                assertEquals("", replacement.str("text"))
                ws.await("msg.end")
                val fallback = ws.await("msg.start")
                assertEquals("complete", ws.await("msg.end").str("status"))
                assertEquals("", runBlocking { h.snapshot(original.str("msg")) }!!.content)
                assertEquals("换模型后的完整解释。", runBlocking { h.snapshot(fallback.str("msg")) }!!.content)
            }
        }
    }

    @Test
    fun streamedAnswerReachesTheClientAndLandsComplete() {
        Harness().use { h ->
            h.tutor.stream(40, "你好，", "这是导师的", "回答。", StubTutor.terminal("complete"), StubTutor.DONE)
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                val start = ws.await("msg.start")
                val messageId = start.str("msg")
                val text = StringBuilder()
                var end: JsonObject? = null
                while (end == null) {
                    val frame = ws.await("msg.delta", "msg.end")
                    if (frame.str("t") == "msg.end") end = frame else text.append(frame.str("text"))
                }
                assertEquals("你好，这是导师的回答。", text.toString())
                assertEquals("complete", end!!.str("status"))
                val stored = runBlocking { h.snapshot(messageId) }
                assertNotNull(stored)
                assertEquals("complete", stored.status)
                assertEquals("你好，这是导师的回答。", stored.content)
            }
        }
    }

    @Test
    fun reconnectResumesFromSnapshotAndTheAssembledTextMatchesTheServerCopy() {
        Harness().use { h ->
            h.tutor.stream(
                150, "第一段。", "第二段。", "第三段。", "第四段。", "第五段。",
                StubTutor.terminal("complete"), StubTutor.DONE,
            )
            var messageId: String
            Ws(h.port, h.token).use { first ->
                ask(first)
                val start = first.await("msg.start")
                messageId = start.str("msg")
                first.await("msg.delta")
            }
            // 断网（socket 直接关闭），服务端流继续；再接回来必须从 snapshot 续上
            Thread.sleep(200)
            val assembler = ChatStreamAssembler()
            Ws(h.port, h.token).use { second ->
                second.send("""{"t":"msg.resume","msg":"$messageId"}""")
                val snapshot = second.await("msg.snapshot")
                assertEquals(messageId, snapshot.str("msg"))
                assembler.handle(snapshot)
                var end: JsonObject? = null
                while (end == null) {
                    val frame = second.await("msg.delta", "msg.end", "msg.math")
                    assembler.handle(frame)
                    if (frame.str("t") == "msg.end") end = frame
                }
                val server = runBlocking { h.snapshot(messageId) }
                assertNotNull(server)
                val clientText = assembler.text(messageId)
                assertEquals(server.content, clientText)
                assertEquals(sha256(server.content), sha256(clientText))
                assertEquals("complete", end!!.str("status"))
            }
        }
    }

    @Test
    fun cancelEndsTheMessageWithinTwoSecondsAndStopsTheBridgeStream() {
        Harness().use { h ->
            h.tutor.stream(
                400, "一", "二", "三", "四", "五", "六",
                StubTutor.terminal("complete"), StubTutor.DONE,
            )
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                val messageId = ws.await("msg.start").str("msg")
                ws.await("msg.delta")
                val startedAt = System.nanoTime()
                ws.send("""{"t":"msg.cancel","msg":"$messageId"}""")
                val end = ws.await("msg.end", timeoutMs = 3_000)
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                assertEquals("cancelled", end.str("status"))
                assertTrue(elapsedMs < 2_000, "cancel took ${elapsedMs}ms")
                val stored = runBlocking { h.snapshot(messageId) }
                assertEquals("cancelled", stored?.status)
                // 剩余帧没有再发完（桩桥最多写出取消前的那几帧）
                assertTrue(h.tutor.framesWritten < 8, "bridge kept streaming: ${h.tutor.framesWritten}")
            }
        }
    }

    @Test
    fun internalTextInjectedByTheStubNeverReachesTheVisibleStream() {
        Harness().use { h ->
            h.tutor.stream(
                30,
                "这是正文，应该出现。\n",
                "placeholder: {\"status\":\"complete\"}\n",
                "tool_call: {\"name\":\"read_file\"}\n",
                "{\"x_coach\":{\"status\":\"complete\"}}\n",
                "这是结尾，也应该出现。\n",
                StubTutor.terminal("complete"),
                StubTutor.DONE,
            )
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                val messageId = ws.await("msg.start").str("msg")
                val text = StringBuilder()
                var end: JsonObject? = null
                while (end == null) {
                    val frame = ws.await("msg.delta", "msg.end")
                    if (frame.str("t") == "msg.end") end = frame else text.append(frame.str("text"))
                }
                val visible = text.toString()
                assertTrue(visible.contains("这是正文"), visible)
                assertTrue(visible.contains("这是结尾"), visible)
                val leaked = listOf("placeholder", "tool_call", "x_coach").filter { visible.contains(it) }
                assertTrue(leaked.isEmpty(), "protocol text leaked: $leaked\n$visible")
                assertTrue(h.logs.any { it.contains("visible-drop") }, "drops were not logged: ${h.logs}")
                val stored = runBlocking { h.snapshot(messageId) }
                assertEquals(visible, stored?.content)
            }
        }
    }

    @Test
    fun filteredAndWhitespaceOnlyAnswersCannotComplete() {
        for (raw in listOf("{\"answer\":\"internal\"}\n", "placeholder\n", "【需查原文:query】", "  \n")) Harness().use { h ->
            h.tutor.stream(10, raw, StubTutor.terminal("complete"), StubTutor.DONE)
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                val id = ws.await("msg.start").str("msg")
                val end = ws.await("msg.end")
                assertEquals("failed", end.str("status"), raw)
                assertEquals("empty_visible_body", end.str("reason"), raw)
                val stored = runBlocking { h.snapshot(id) }!!
                assertTrue(stored.content.isBlank())
                assertEquals("failed", stored.status)
                assertEquals("empty_visible_body", stored.reason)
                assertTrue(h.logs.any { it.contains("empty_visible_body") })
            }
        }
    }

    @Test
    fun internalDeltasDoNotSuppressThinkingOrTheFirstVisibleTokenDeadline() {
        Harness(defaultTimings(firstTokenDeadlineMs = 600)).use { h ->
            h.tutor.stream(100, *Array(20) { "placeholder\n" }, StubTutor.terminal("complete"), StubTutor.DONE)
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                ws.await("msg.start")
                assertEquals("thinking", ws.await("msg.progress").str("phase"))
                val end = ws.await("msg.end")
                assertEquals("failed", end.str("status"))
                assertEquals("first_token_timeout", end.str("reason"))
            }
        }
    }

    @Test
    fun aDroppedDeltaIsRecoveredThroughTheResumeCallbackAndWsSnapshot() {
        Harness().use { h ->
            h.tutor.stream(40, *Array(12) { "一段正文。" }, StubTutor.terminal("complete"), StubTutor.DONE)
            Ws(h.port, h.token).use { ws ->
                var requests = 0
                val assembler = ChatStreamAssembler(onResumeRequired = { id, _ ->
                    requests++
                    ws.send("""{"t":"msg.resume","msg":"$id"}""")
                })
                ask(ws)
                val id = ws.await("msg.start").str("msg")
                while (assembler.status(id) != "complete") {
                    val frame = ws.await("msg.delta", "msg.snapshot", "msg.end")
                    if (frame.str("t") == "msg.delta" && frame.str("i") == "2") continue
                    assembler.handle(frame)
                }
                assertTrue(requests > 0)
                assertEquals("一段正文。".repeat(12), assembler.text(id))
                assertEquals(runBlocking { h.snapshot(id) }!!.content, assembler.text(id))
            }
        }
    }

    @Test
    fun resumeImmediatelyAfterConnectingIncludesTheFollowingDeltas() {
        Harness().use { h ->
            h.tutor.stream(80, *Array(12) { "正文。" }, StubTutor.terminal("complete"), StubTutor.DONE)
            val id = runBlocking { h.chat.ask("thread-1", "course-1", "合成问题") }
            Ws(h.port, h.token).use { ws ->
                ws.send("""{"t":"msg.resume","msg":"$id"}""")
                val assembler = ChatStreamAssembler()
                assembler.handle(ws.await("msg.snapshot"))
                while (true) {
                    val frame = ws.await("msg.delta", "msg.end")
                    assembler.handle(frame)
                    if (frame.str("t") == "msg.end") break
                }
                assertEquals("正文。".repeat(12), assembler.text(id))
                assertEquals(runBlocking { h.snapshot(id) }!!.content, assembler.text(id))
            }
        }
    }

    @Test
    fun bridgeTerminalStatusMapsOneToOneOntoMessageStatus() {
        Harness().use { h ->
            h.tutor.stream(30, "半句话", StubTutor.terminal("truncated", "timeout"), StubTutor.DONE)
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                val messageId = ws.await("msg.start").str("msg")
                var end: JsonObject? = null
                while (end == null) {
                    val frame = ws.await("msg.delta", "msg.end")
                    if (frame.str("t") == "msg.end") end = frame
                }
                assertEquals("truncated", end!!.str("status"))
                assertEquals("timeout", end.str("reason"))
                val stored = runBlocking { h.snapshot(messageId) }
                assertEquals("truncated", stored?.status)
                assertEquals("timeout", stored?.reason)
            }
        }
    }

    @Test
    fun firstTokenDeadlineAbortsTheStreamInsteadOfWaitingForever() {
        Harness(defaultTimings(firstTokenDeadlineMs = 400)).use { h ->
            // 每帧 700ms：首字截止 400ms 内拿不到正文，必须主动断开并写 failed(first_token_timeout)
            h.tutor.stream(700, "迟到的一句话", StubTutor.terminal("complete"), StubTutor.DONE)
            Ws(h.port, h.token).use { ws ->
                ask(ws)
                val messageId = ws.await("msg.start").str("msg")
                val end = ws.await("msg.end", timeoutMs = 5_000)
                assertEquals("failed", end.str("status"))
                assertEquals("first_token_timeout", end.str("reason"))
                val stored = runBlocking { h.snapshot(messageId) }
                assertEquals("failed", stored?.status)
            }
        }
    }

    private suspend fun seedTutor(h: Harness, count: Int = 3) {
        h.actor.execute {
            repeat(count) { i ->
                val options = listOf("opportunity cost $i","sunk expenditure $i","fixed payment $i","total revenue $i").mapIndexed { j,text -> com.coach.domain.Option("q$i-$j",text) }
                registerQuestion("q$i","course-1","","SINGLE_CHOICE","Synthetic question $i","VERIFIED",options.first().optionId,options,listOf("kp-1"))
            }
        }
    }

    @Test
    fun plainContinueAfterTwoErrorsPosesCardsWhileTheExampleButtonStillTeaches() {
        Harness().use { h ->
            runBlocking { seedTutor(h,9) }
            h.tutor.stream(10,"机会成本是放弃的最佳替代。",StubTutor.terminal("complete"),StubTutor.DONE)
            Ws(h.port,h.token).use { ws ->
                ws.send("""{"t":"tutor.open","threadId":"thread-1","courseId":"course-1","kpId":"kp-1","conceptIds":["cost"],"title":"Synthetic objective"}""")
                ws.await("tutor.state")
                ws.send("""{"t":"msg.ask","threadId":"thread-1","courseId":"course-1","text":"继续"}""")
                ws.await("msg.start")
                assertEquals("complete",ws.await("msg.end").str("status"))
                while (ws.await("tutor.state").str("state") != "AWAIT") Unit
                val seen = mutableSetOf<String>()
                repeat(3) { groupIndex ->
                    val before = runBlocking { h.actor.execute { events("course-1").last().seq } }
                    ws.send("""{"t":"msg.ask","threadId":"thread-1","courseId":"course-1","text":"继续"}""")
                    val group = ws.await("tutor.cards","msg.start")
                    assertEquals("tutor.cards",group.str("t"),"Plain continue must display questions before any teaching stream")
                    assertEquals(1,h.tutor.requests,"Continue must not call the bridge again")
                    val cards = (group.getValue("cards") as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
                    assertEquals(3,cards.size)
                    assertTrue(cards.all { seen.add(it.str("questionId")) },"The remaining pool must supply fresh cards")
                    assertTrue(runBlocking { h.actor.execute { events("course-1").none {
                        it.seq > before && (it.type == "explanation_delivered" ||
                            it.type == "tutor_state_changed" && it.payload.str("state") in setOf("PREPARE","TEACH"))
                    } } })
                    val answers = runBlocking { h.actor.execute {
                        JsonObject(cards.mapIndexed { i,card ->
                            val map = card.getValue("letterMap").jsonObject
                            val correct = map.getValue(tutorChoiceKey(card.str("questionId")).correctOptionId)
                            card.str("attemptId") to if (groupIndex == 1 && i < 2) map.values.first { it != correct } else correct
                        }.toMap())
                    } }
                    ws.send("""{"t":"tutor.answer","threadId":"thread-1","courseId":"course-1","groupId":"${group.str("groupId")}","submissionId":"errors-$groupIndex","answers":$answers}""")
                    ws.await("tutor.feedback")
                    while (ws.await("tutor.state").str("state") != "FEEDBACK") Unit
                }
                h.tutor.stream(10,"换一个例子来理解。",StubTutor.terminal("complete"),StubTutor.DONE)
                ws.send("""{"t":"tutor.next","threadId":"thread-1","courseId":"course-1"}""")
                assertEquals("complete",ws.await("msg.end").str("status"))
                assertEquals(2,h.tutor.requests)
                val recap = runBlocking { h.actor.execute { events("course-1").last { it.type == "explanation_delivered" } } }
                assertEquals("EXAMPLE",recap.payload.str("mode"))
            }
        }
    }

    @Test
    fun taughtContinueAndWholeGroupSubmissionGoThroughWsWithoutAnotherModelCall() {
        Harness().use { h ->
            runBlocking { seedTutor(h) }
            h.tutor.stream(15,"机会成本是放弃的最佳替代。",StubTutor.terminal("complete"),StubTutor.DONE)
            Ws(h.port,h.token).use { ws ->
                ws.send("""{"t":"tutor.open","threadId":"thread-1","courseId":"course-1","kpId":"kp-1","conceptIds":["cost"],"title":"Synthetic objective"}""")
                assertEquals("IDLE",ws.await("tutor.state").str("state"))
                ws.send("""{"t":"msg.ask","threadId":"thread-1","courseId":"course-1","text":"继续"}""")
                ws.await("msg.start")
                ws.await("msg.end")
                while (ws.await("tutor.state").str("state") != "AWAIT") Unit
                ws.send("""{"t":"msg.ask","threadId":"thread-1","courseId":"course-1","text":"继续"}""")
                val group = ws.await("tutor.cards")
                assertEquals(1,h.tutor.requests)
                val cards = group.getValue("cards") as kotlinx.serialization.json.JsonArray
                assertEquals(3,cards.size)
                val answers = runBlocking { h.actor.execute {
                    kotlinx.serialization.json.JsonObject(cards.associate { element ->
                        val card = element.jsonObject
                        val key = tutorChoiceKey(card.str("questionId"))
                        card.str("attemptId") to card.getValue("letterMap").jsonObject.getValue(key.correctOptionId)
                    })
                } }
                ws.send("""{"t":"tutor.answer","threadId":"thread-1","courseId":"course-1","groupId":"${group.str("groupId")}","submissionId":"ws-submit","answers":$answers}""")
                ws.await("tutor.feedback")
                val state = runBlocking { h.actor.execute { snapshot("course-1").projection } }
                assertTrue(state.mastery.getValue("kp-1").passed)
                assertEquals(3,state.mastery.getValue("kp-1").window.size)
                assertTrue(h.tutor.requestBodies.none { it.contains("Student question: 继续") })
            }
        }
    }

    @Test
    fun rawLeakIsDurableAfterSnapshotRemovesTheLetterSentence() {
        Harness().use { h ->
            runBlocking { seedTutor(h) }
            h.tutor.stream(40,"先想一想，", "选 ", "B，opportunity ", "cost 0。",StubTutor.terminal("complete"),StubTutor.DONE)
            Ws(h.port,h.token).use { ws ->
                ws.send("""{"t":"tutor.open","threadId":"thread-1","courseId":"course-1","kpId":"kp-1","conceptIds":["cost"],"title":"Synthetic objective"}""")
                ws.await("tutor.state")
                ws.send("""{"t":"tutor.test","threadId":"thread-1","courseId":"course-1"}""")
                ws.await("tutor.cards")
                ask(ws)
                val id = ws.await("msg.start").str("msg")
                val assembler = ChatStreamAssembler()
                var end = false
                var revised = false
                while (!end) {
                    val frame = ws.await("msg.delta","msg.snapshot","msg.end")
                    if (frame.str("t") == "msg.snapshot") revised = true
                    if (frame.str("t") == "msg.delta") assertTrue(!frame.str("text").contains("B"))
                    assembler.handle(frame)
                    end = frame.str("t") == "msg.end"
                }
                assertTrue(revised)
                assertEquals("",assembler.text(id))
                val facts = runBlocking { h.actor.execute { events("course-1") } }
                val leak = facts.single { it.type == "hint_revealed" }
                assertEquals("LEAK",leak.payload.str("cause"))
                assertTrue(h.tutor.requestBodies.single().contains("Unordered option texts"))
                assertTrue(!h.tutor.requestBodies.single().contains("letterMap"))
                val callStatus = java.sql.DriverManager.getConnection("jdbc:sqlite:${h.store.directory.resolve("coach.sqlite")}").use { connection ->
                    connection.createStatement().use { statement -> statement.executeQuery("SELECT status FROM ai_calls").use { rows -> rows.next(); rows.getString(1) } }
                }
                assertEquals("letter_ref_stripped",callStatus)
            }
        }
    }

    @Test
    fun progressAndQuickAskTravelThroughTheRealWebSocketRoutes() {
        Harness().use { h ->
            runBlocking {
                seedTutor(h)
                h.actor.execute { append("course-1","exam_weights_set",com.coach.domain.json("weights" to com.coach.domain.json("kp-1" to 30,"other" to 70))) }
            }
            Ws(h.port,h.token).use { ws ->
                ws.send("""{"t":"tutor.open","threadId":"thread-1","courseId":"course-1","kpId":"kp-1","conceptIds":["cost"],"title":"Synthetic objective"}""")
                ws.await("tutor.state")
                ws.send("""{"t":"tutor.test","threadId":"thread-1","courseId":"course-1"}""")
                ws.await("tutor.cards")
                val before = runBlocking { h.actor.execute { snapshot("course-1").projection } }
                ws.send("""{"t":"msg.ask","threadId":"thread-1","courseId":"course-1","text":"学了多少"}""")
                assertEquals("projection",ws.await("msg.start").str("source"))
                val delta = ws.await("msg.delta")
                assertEquals(runBlocking { h.actor.execute { todayProgress("course-1").coverageText } },delta.str("text"))
                ws.await("msg.end")
                assertEquals(0,h.tutor.requests)
                h.tutor.stream(5,"A quick answer.",StubTutor.terminal("complete"),StubTutor.DONE)
                ws.send("""{"t":"quick.ask","courseId":"course-1","text":"继续"}""")
                val thread = ws.await("quick.thread").str("threadId")
                ws.await("msg.start"); ws.await("msg.end")
                val after = runBlocking { h.actor.execute { snapshot("course-1").projection } }
                assertEquals(before.position,after.position)
                assertEquals(before.mastery,after.mastery)
                assertEquals(before.review,after.review)
                assertEquals(1,h.tutor.requests)
                assertTrue(thread.isNotBlank())
            }
        }
    }

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        fun defaultTimings(
            firstTokenDeadlineMs: Long = 3_000,
        ) = ChatTimings(
            thinkingAtMs = 200,
            firstTokenDeadlineMs = firstTokenDeadlineMs,
            stallMs = 5_000,
            totalMs = 20_000,
            persistEveryMs = 150,
            pollMs = 25,
        )
    }
}
