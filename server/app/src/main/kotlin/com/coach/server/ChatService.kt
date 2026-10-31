package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeResult
import com.coach.ai.BridgeStreamHandle
import com.coach.ai.BridgeStreamSink
import com.coach.ai.StreamStatus
import com.coach.ai.VisibleStream
import com.coach.ai.teachRequest
import com.coach.core.*
import com.coach.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import com.coach.domain.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** WS 上所有连接共享的导师帧流（同一账号的多台设备都看同一条回答）。 */
class ChatHub(private val log: (String) -> Unit = { println(it) }) {
    val frames = MutableSharedFlow<JsonObject>(extraBufferCapacity = 1024)
    fun emit(frame: JsonObject) {
        if (!frames.tryEmit(frame)) {
            log("COACH chat-frame-drop msg=${frame["msg"]} type=${frame["t"]} index=${frame["i"]} reason=buffer_full")
        }
    }
}

/**
 * DESIGN §5.5 / §4.7：全部是进程内超时，一律单调钟（`System.nanoTime`），
 * 休眠期间不走的那部分由 §4.7 的唤醒检测兜住。
 */
data class ChatTimings(
    /** 8 秒还没出正文 → 发「还在想」进度帧（不算首字）。 */
    val thinkingAtMs: Long = 8_000,
    /** 首字截止 25 秒 → 主动断开并显式改走兜底。 */
    val firstTokenDeadlineMs: Long = 25_000,
    /** 30 秒没有新可见正文判卡住。 */
    val stallMs: Long = 30_000,
    /** 流总时长 180 秒。 */
    val totalMs: Long = 180_000,
    /** 进行中的消息每秒落库（§6 断线续传）。 */
    val persistEveryMs: Long = 1_000,
    val pollMs: Long = 200,
)

/**
 * 导师流式回合：`TEACH` 走桥副本的真流式，正文边到边发 `msg.delta(msg, i)`，
 * 每秒落库 `messages.status=streaming`，重连先发 `msg.snapshot` 再接 delta（§6）。
 */
class ChatService(
    private val actor: LedgerActor,
    private val scope: CoroutineScope,
    private val bridge: BridgeClient = BridgeClient(),
    private val timings: ChatTimings = ChatTimings(),
    private val fallbackModel: String = BridgeClient.CODEX,
    private val fallbackOnFirstTokenTimeout: Boolean = true,
    private val allowSourceLookup: Boolean = false,
    private val ledgerChanged: suspend (String) -> Unit = {},
    private val log: (String) -> Unit = { println(it) },
    private val sourceLookupProtected: () -> Boolean = { java.time.Instant.now() < java.time.Instant.parse("2026-11-02T15:00:00Z") },
    private val sourceLookupTimeoutMs: Long = 90_000,
    private val enrichSourcePages: Boolean = true,
    private val packsDirectory: java.nio.file.Path = java.nio.file.Path.of(System.getenv("COACH_PACKS_DIR")
        ?: java.io.File(System.getProperty("user.home"), "Coach/corpus/packs").path),
) {

    val hub = ChatHub(log)
    val tutor = TutorMachine(actor)
    val journals = JournalService(actor, bridge)
    private val preparation = LessonPreparation(actor,bridge,packsDirectory)
    private val supplyLock = Mutex()
    private val waitingPools = ConcurrentHashMap.newKeySet<String>()

    suspend fun prepareLessons(course: String, kps: List<String>, questionsOnly: Boolean = false): JsonObject = supplyLock.withLock {
        json("results" to JsonArray(kps.map { if(questionsOnly) preparation.prepareQuestions(course,it) else preparation.prepare(course,it) }))
    }

    fun startSupplyWorker(): Job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
        val worker = com.coach.ai.PoolWorker(actor,bridge)
        val grades = com.coach.ai.ConstructedGradeWorker(actor,bridge,ledgerChanged)
        while(isActive) {
            try { if (!grades.runOne()) supplyLock.withLock { worker.runOne() } }
            catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) { log("COACH supply failure=${e.javaClass.simpleName}") }
            delay(500)
        }
    }
    private val sessions = ConcurrentHashMap<String, Session>()

    class Session(
        val messageId: String,
        val threadId: String,
        val courseId: String,
        val model: String,
        val fallback: Boolean,
        val plan: TutorReplyPlan? = null,
    ) {
        @Volatile var phase: String = "teach"
        @Volatile var lookupAllowed: Boolean = false
        @Volatile var sourceProcess: Process? = null
        @Volatile var handle: BridgeStreamHandle? = null
        @Volatile var forcedStatus: String? = null
        @Volatile var forcedReason: String? = null
        @Volatile var lastDeltaAtMs: Long = -1
        @Volatile var thinkingSent: Boolean = false
        // One publication: a snapshot must never pair a new index with old text.
        data class Cursor(val text: String = "", val index: Int = 0)
        @Volatile var cursor = Cursor()
        val finished = AtomicBoolean(false)

        /** 取消只有一条路径：断开 HTTP，桥据此杀掉 CLI 整个进程组（§5.5）。 */
        fun abort(status: String, reason: String) {
            forcedStatus = status
            forcedReason = reason
            handle?.cancel()
            sourceProcess?.destroyForcibly()
        }

        fun attach(handle: BridgeStreamHandle) {
            this.handle = handle
            if (forcedStatus != null) handle.cancel()
        }
    }

    /** 追问/问答：立刻返回 messageId，流在后台推进（每帧经 [hub] 广播）。 */
    suspend fun ask(threadId: String, courseId: String, question: String, pack: String = ""): String {
        val quick = actor.execute { requireChatThread(courseId,threadId); isQuickThread(courseId,threadId) }
        if (quick) actor.execute { quickQuestion(courseId,threadId,question) }
        progressIntent(question)?.let { intent ->
            val (message, today) = actor.execute {
                tutorTransaction {
                    val today = todayProgress(courseId,threadId)
                    val message = UUID.randomUUID().toString()
                    beginStreamingMessage(threadId,message,"")
                    finishMessage(message,"complete",null,today.answer(intent),1)
                    message to today
                }
            }
            val session = Session(message,threadId,courseId,"",false)
            hub.emit(JsonObject(startFrame(session) + json("source" to "projection","fromSeq" to today.fromSeq)))
            hub.emit(json("t" to "msg.delta","msg" to message,"threadId" to threadId,"i" to 1,"text" to today.answer(intent)))
            hub.emit(endFrame(session,"complete",null,today.answer(intent).length))
            return message
        }
        if (quick) {
            val anchor = actor.execute { snapshot(courseId).projection.quickAsks.values.single { it.threadId == threadId } }
            return startReply(threadId,courseId,question,pack + "\nQuick ask: course=$courseId, anchored KP=${anchor.kpId.orEmpty()}. This context is read-only. Do not assess mastery or advance the lesson.",null)
        }
        when (tutorIntent(question)) {
            TutorIntent.NEXT_STEP -> return nextStep(threadId,courseId,pack,allowExample=false)
            TutorIntent.TEST_ME -> return nextStep(threadId,courseId,pack,true)
            null -> Unit
        }
        val plan = if (tutor.hasThread(courseId,threadId)) tutor.followup(courseId,threadId) else null
        return startReply(threadId,courseId,question,pack,plan)
    }

    suspend fun quickAsk(courseId: String, question: String, pack: String = ""): Pair<QuickAskThread, String> {
        val thread = actor.execute { openQuickAsk(courseId) }
        hub.emit(json("t" to "quick.thread","threadId" to thread.threadId,"courseId" to courseId,"day" to thread.day,"kpId" to thread.kpId))
        return thread to ask(thread.threadId,courseId,question,pack)
    }

    suspend fun openTutor(threadId: String, courseId: String, kpId: String, concepts: Set<String>, title: String) {
        publish(tutor.open(courseId,threadId,kpId,concepts,title),threadId,courseId)
    }

    /** Button actions retain §2.6's recap; text intents pass allowExample=false (§5.8). */
    suspend fun nextStep(threadId: String, courseId: String, pack: String = "", testUntaught: Boolean = false, allowExample: Boolean = true): String {
        val traceStarted=System.nanoTime()
        var outcome = tutor.next(courseId,threadId,testUntaught,allowExample=allowExample)
        if(System.getenv("COACH_POOL_TRACE")=="1") log("COACH_POOL thread=$threadId stage=posed elapsed_ms=${(System.nanoTime()-traceStarted)/1_000_000.0} cards=${outcome.cards.size}")
        if(outcome.waitingForPool) {
            val kp = actor.execute { events(courseId).lastOrNull { it.type=="kp_opened" && it.threadId==threadId }?.payload?.str("kpId") }
            if(kp!=null) {
                runCatching { preparation.ensure(courseId,kp) }
                // Do not call next twice: a fast refill could otherwise publish the same group twice.
                tutor.resumePool(courseId,threadId)?.let { outcome=it }
                if(outcome.waitingForPool && actor.execute {
                    supplyJobs(courseId,kp).any { it.kind in setOf("gen_mcq","blind_review") }
                }) outcome=outcome.copy(poolMessage="正在准备题目（约 30 秒，含独立复核；重试可能更久）")
                if(outcome.waitingForPool && waitingPools.add(threadId)) scope.launch {
                    try {
                        var previous = ""
                        var batches = 1
                        repeat(360) {
                            delay(500)
                            tutor.resumePool(courseId,threadId)?.let {
                                publish(it,threadId,courseId); return@launch
                            }
                            if(!tutor.poolWaitActive(courseId,threadId)) return@launch
                            val jobs=actor.execute { supplyJobs(courseId,kp).filter { it.kind in setOf("gen_mcq","blind_review") } }
                            if(jobs.isEmpty()) {
                                // A partial blind review can leave fewer than one full group.
                                if(batches < 4) {
                                    batches++
                                    preparation.ensure(courseId,kp)
                                    if(actor.execute { supplyJobs(courseId,kp).isNotEmpty() }) return@repeat
                                }
                                hub.emit(json("t" to "tutor.progress","threadId" to threadId,"courseId" to courseId,
                                    "phase" to "pool_failed","text" to "题目准备未成功，请重试。已通过复核的题目会保留。"));return@launch
                            }
                            val states=actor.execute { jobs.map { it to jobState(it.id)?.first } }
                            val phase=when {
                                states.any { it.first.kind=="blind_review" && it.second=="running" } -> "pool_reviewing"
                                states.any { it.first.subject.flag("retryMalformed") } -> "pool_retrying"
                                states.any { it.second=="running" } -> "pool_generating"
                                else -> "pool_queued"
                            }
                            val available=actor.execute { poolAvailable(courseId,kp) }
                            val text=when(phase) {
                                "pool_reviewing" -> "正在独立复核题目"
                                "pool_retrying" -> "上一轮未通过检查，正在换一种方式重新出题"
                                "pool_generating" -> "正在生成题目"
                                else -> "正在排队准备题目"
                            } + "（已有 $available 道通过复核）"
                            if(previous!=text) {
                                hub.emit(json("t" to "tutor.progress","threadId" to threadId,"courseId" to courseId,
                                    "phase" to phase,"text" to text));previous=text
                            }
                        }
                        hub.emit(json("t" to "tutor.progress","threadId" to threadId,"courseId" to courseId,
                            "phase" to "pool_failed","text" to "准备题目用时较长，请稍后重试。已通过复核的题目会保留。"))
                    } catch(e: kotlinx.coroutines.CancellationException) { throw e }
                    catch(e: Exception) {
                        log("COACH pool wait failure=${e.javaClass.simpleName}")
                        hub.emit(json("t" to "tutor.progress","threadId" to threadId,"courseId" to courseId,
                            "phase" to "pool_failed","text" to "准备题目中断，请重试。"))
                    } finally { waitingPools.remove(threadId) }
                }
            }
        }
        publish(outcome,threadId,courseId)
        if(System.getenv("COACH_POOL_TRACE")=="1") log("COACH_POOL thread=$threadId stage=emitted elapsed_ms=${(System.nanoTime()-traceStarted)/1_000_000.0}")
        return outcome.plan?.let { startReply(threadId,courseId,"请讲解当前知识点。",pack,it) }.orEmpty()
    }

    suspend fun configureTutor(threadId: String, courseId: String, groupSize: Int?, mode: TeachingMode?) {
        publish(tutor.settings(courseId,threadId,groupSize,mode),threadId,courseId)
    }

    suspend fun explain(threadId: String, courseId: String, mode: TeachingMode, pack: String) {
        val outcome = tutor.explain(courseId,threadId,mode)
        publish(outcome,threadId,courseId)
        startReply(threadId,courseId,"请按指定模式讲解。",pack,outcome.plan)
    }

    suspend fun submitGroup(threadId: String, courseId: String, group: String, answers: Map<String,String>, device: String, submission: String) {
        publish(tutor.submit(courseId,threadId,group,answers,device,submission),threadId,courseId)
    }

    private fun publish(outcome: TutorOutcome, thread: String, course: String) {
        hub.emit(json("t" to "tutor.state","threadId" to thread,"courseId" to course,"state" to outcome.state.name,"nextLabel" to outcome.nextLabel))
        if (outcome.cards.isNotEmpty()) hub.emit(json("t" to "tutor.cards","threadId" to thread,"courseId" to course,
            "groupId" to outcome.cards.first().payload.str("groupId"),"cards" to JsonArray(outcome.cards.map { it.payload })))
        if (outcome.grades.isNotEmpty()) hub.emit(json("t" to "tutor.feedback","threadId" to thread,"courseId" to course,
            "grades" to JsonArray(outcome.grades.map { it.payload })))
        if (outcome.waitingForPool) hub.emit(json("t" to "tutor.progress","threadId" to thread,"courseId" to course,
            "phase" to "pool_queued","text" to (outcome.poolMessage ?: "题池暂空，补题尚未运行")))
    }

    private suspend fun startReply(threadId: String, courseId: String, question: String, pack: String, plan: TutorReplyPlan?): String {
        val messageId = UUID.randomUUID().toString()
        val sourcePack = pack.ifBlank { storedTeachingPack(threadId, courseId) }
        val prepared = if(plan?.explanation==true && plan.firstExplanation && plan.mode==TeachingMode.CURRENT)
            preparation.cached(courseId,plan.context.kp,sourcePack) else null
        val session = Session(messageId, threadId, courseId, prepared?.model ?: BridgeClient.CLAUDE, false, plan)
        sessions[messageId] = session
        val recent = actor.execute { recentTurns(threadId) }
        actor.execute { beginStreamingMessage(threadId, messageId, session.model) }
        hub.emit(startFrame(session))
        if(prepared!=null) { scope.launch { deliverPrepared(session,prepared.text) };return messageId }
        val enriched = if (plan?.explanation != true && enrichSourcePages) {
            val hits = actor.execute { searchSourcePages(courseId,question) }
            SourcePack.prepare(sourcePack,hits).render()
        } else sourcePack.take(40_000)
        session.lookupAllowed = allowSourceLookup && plan?.explanation != true && !sourceLookupProtected()
        val context = listOf(TutorOutputGuard.modelInput(enriched), plan?.instruction().orEmpty()).joinToString("\n")
        scope.launch(Dispatchers.IO) { run(session, TutorOutputGuard.modelInput(question), context,
            recent.map { it.first to TutorOutputGuard.modelInput(it.second) }) }
        return messageId
    }

    private suspend fun deliverPrepared(session: Session, raw: String) {
        val guard=TutorOutputGuard(allowExamSection=true)
        val visible=VisibleStream()
        val text=StringBuilder()
        val safe=guard.push(raw)+guard.finish()
        for(chunk in safe.chunked(128)) {
            if(session.forcedStatus!=null) break
            for(segment in visible.push(chunk)) emitSegment(session,segment,text)
            delay(16)
        }
        for(segment in visible.finish()) emitSegment(session,segment,text)
        val status=session.forcedStatus ?: StreamStatus.fromBridge(StreamStatus.COMPLETE, false, text.isNotBlank())
        val reason=session.forcedReason ?: if (status == StreamStatus.FAILED) "empty_visible_body" else null
        if (reason == "empty_visible_body") log("COACH msg.empty msg=${session.messageId} reason=$reason")
        val persist: com.coach.store.LedgerStore.() -> Unit = {
            finishMessage(session.messageId,status,reason,text.toString(),session.cursor.index)
            recordAiCall(UUID.randomUUID().toString(),"TEACH_CACHE",session.model,session.model,0,0,0,text.length,status,0,0)
        }
        val plan=checkNotNull(session.plan)
        publish(tutor.finish(plan,session.messageId,status==StreamStatus.COMPLETE,persist),session.threadId,session.courseId)
        session.finished.set(true);sessions.remove(session.messageId)
        hub.emit(endFrame(session,status,reason,text.length))
    }

    private suspend fun storedTeachingPack(threadId: String, courseId: String): String {
        val kp = actor.execute { events(courseId).lastOrNull { it.type == "kp_opened" && it.threadId == threadId }?.payload?.str("kpId") } ?: return ""
        // IDs come from the ledger, but remain data, never unrestricted filesystem paths.
        val id = Regex("[A-Za-z0-9_-]+")
        if (!id.matches(courseId) || !id.matches(kp)) return ""
        val path = packsDirectory.resolve(courseId).resolve("$kp.md")
        return if (java.nio.file.Files.isRegularFile(path)) java.nio.file.Files.readString(path) else ""
    }

    fun cancel(messageId: String): Boolean {
        val session = sessions[messageId] ?: return false
        session.abort(StreamStatus.CANCELLED, "cancelled")
        return true
    }

    /** 唤醒后收尾（§4.7）：进行中的消息按 `truncated(reason=suspended)` 结束。 */
    fun abortAll(reason: String = "suspended") {
        sessions.values.forEach { it.abort(StreamStatus.TRUNCATED, reason) }
    }

    /** 重连带 `(msg, i)`：进行中的消息从内存取（DB 最多慢一秒），已结束的从库取。 */
    suspend fun snapshot(messageId: String): JsonObject? {
        val live = sessions[messageId]
        if (live != null) {
            val cursor = live.cursor
            return json(
                "t" to "msg.snapshot", "msg" to messageId, "threadId" to live.threadId,
                "i" to cursor.index, "text" to cursor.text, "status" to "streaming", "reason" to null, "phase" to live.phase,
            )
        }
        val row = actor.execute { messageSnapshot(messageId) } ?: return null
        return json(
            "t" to "msg.snapshot", "msg" to messageId, "threadId" to row.threadId,
            "i" to row.index, "text" to row.content, "status" to row.status, "reason" to row.reason,
        )
    }

    private suspend fun run(session: Session, question: String, pack: String, recent: List<Pair<String, String>>) {
        val visibleText = StringBuilder()
        val rawAnswer = StringBuilder()
        val started = System.nanoTime()
        var lastPersist = started
        var visible = VisibleStream { drop ->
            log("COACH visible-drop msg=${session.messageId} reason=${drop.reason} excerpt=${drop.excerpt}")
        }
        fun elapsedMs(): Long = (System.nanoTime() - started) / 1_000_000

        val guard = TutorOutputGuard(session.plan?.pending.orEmpty(),session.plan?.firstExplanation == true,streamSafePrefixes=true) { attempt ->
            session.plan?.let { plan -> runBlocking { tutor.leak(plan,attempt) } }
        }
        fun acceptFiltered(text: String) {
            val revision = guard.takeRevision()
            if (revision == null) {
                for (segment in visible.push(text)) emitSegment(session,segment,visibleText)
            } else {
                // A sentence can acquire a forbidden reference after its safe prefix was streamed.
                // Replace that prefix via the existing snapshot protocol; the letter itself never streams.
                visible = VisibleStream()
                visibleText.setLength(0)
                for (segment in visible.push(revision)) when (segment) {
                    is VisibleStream.Segment.Text -> visibleText.append(segment.text)
                    is VisibleStream.Segment.Math -> visibleText.append("\$\$").append(segment.tex).append("\$\$")
                }
                session.cursor = Session.Cursor(visibleText.toString(), session.cursor.index + 1)
                hub.emit(json("t" to "msg.snapshot","msg" to session.messageId,"threadId" to session.threadId,
                    "i" to session.cursor.index,"text" to session.cursor.text,"status" to "streaming"))
            }
        }
        val sink = object : BridgeStreamSink {
            override fun onDelta(text: String, atMs: Long) {
                if (rawAnswer.length < 100_000) rawAnswer.append(text.take(100_000 - rawAnswer.length))
                val previous = session.cursor
                acceptFiltered(guard.push(text))
                if (session.cursor != previous && session.cursor.text.removePrefix(previous.text).isNotBlank()) {
                    session.lastDeltaAtMs = elapsedMs()
                }
                val now = System.nanoTime()
                if (now - lastPersist >= timings.persistEveryMs * 1_000_000) {
                    lastPersist = now
                    // 每秒落库（§6）：写的是过滤后的可见正文与 (msg, i)
                    val cursor = session.cursor
                    scope.launch { actor.execute { updateStreamingMessage(session.messageId, cursor.text, cursor.index) } }
                }
            }
        }

        val watchdog = scope.launch {
            while (isActive) {
                delay(timings.pollMs)
                if (session.finished.get()) break
                val t = elapsedMs()
                when {
                    t >= timings.totalMs -> {
                        session.abort(StreamStatus.TRUNCATED, "total_timeout")
                        break
                    }
                    session.lastDeltaAtMs >= 0 && t - session.lastDeltaAtMs >= timings.stallMs -> {
                        session.abort(StreamStatus.TRUNCATED, "stall")
                        break
                    }
                    session.lastDeltaAtMs < 0 && t >= timings.firstTokenDeadlineMs -> {
                        session.abort(StreamStatus.FAILED, "first_token_timeout")
                        break
                    }
                    session.lastDeltaAtMs < 0 && t >= timings.thinkingAtMs && !session.thinkingSent -> {
                        // 8 秒「还在想」：进度帧，不算首字（§5.5）
                        session.thinkingSent = true
                        hub.emit(json("t" to "msg.progress", "msg" to session.messageId, "phase" to "thinking", "atMs" to t))
                    }
                }
            }
        }

        val result = try {
            withContext(Dispatchers.IO) {
                bridge.streamChat(teachRequest(question, pack, recent, session.model, session.lookupAllowed), sink) { handle ->
                    session.attach(handle)
                }
            }
        } catch (error: Exception) {
            BridgeResult.Failure(
                reason = "internal: ${error.javaClass.simpleName}: ${error.message}",
                terminal = null,
                call = com.coach.ai.AiCall(
                    callId = UUID.randomUUID().toString(), task = "TEACH", requestedModel = session.model,
                    servedBy = null, status = "failed", ttftMs = null, durMs = elapsedMs(), queueMs = null,
                    physicalCalls = 0, requestId = null, inChars = 0, outChars = visibleText.length,
                    startedAt = java.time.Instant.now().toString(), error = error.message,
                ),
            )
        } finally {
            watchdog.cancel()
        }

        acceptFiltered(guard.finish())
        for (segment in visible.finish()) emitSegment(session, segment, visibleText)

        if (result is BridgeResult.Success && session.forcedStatus == null && session.lookupAllowed && !sourceLookupProtected()) {
            sourceLookupQuery(rawAnswer.toString())?.let { query ->
                appendSourceLookup(session,question,query,visibleText)
            }
        }
        session.finished.set(true)
        val forcedStatus = session.forcedStatus
        val terminal = result.terminal
        var status = forcedStatus ?: when {
            terminal != null -> StreamStatus.fromBridge(terminal.status, false, visibleText.isNotBlank())
            visibleText.isBlank() -> StreamStatus.FAILED
            // ADR-0002：收不到终态帧按截断处理（已经出过字就一定不是「完整」）
            else -> StreamStatus.TRUNCATED
        }
        var reason = session.forcedReason ?: terminal?.reason ?: (result as? BridgeResult.Failure)
            ?.reason?.takeIf { it.isNotBlank() && it != "no_x_coach" }

        if (forcedStatus == null && terminal?.status == StreamStatus.COMPLETE && visibleText.isBlank()) {
            reason = "empty_visible_body"
        }

        val rateLimited = forcedStatus == null && (reason == "rate_limited" || (result as? BridgeResult.Failure)?.reason?.startsWith("http_429") == true)
        if (rateLimited) {
            reason = "rate_limited"
            status = StreamStatus.FAILED
            visibleText.setLength(0)
            session.cursor = Session.Cursor("", session.cursor.index + 1)
            hub.emit(json("t" to "msg.snapshot", "msg" to session.messageId, "threadId" to session.threadId,
                "i" to session.cursor.index, "text" to "", "status" to "failed", "reason" to "rate_limited"))
            hub.emit(json("t" to "tutor.progress", "threadId" to session.threadId, "courseId" to session.courseId,
                "phase" to "fallback", "text" to "Claude 暂时限流，已换 $fallbackModel 回答"))
            hub.emit(json("t" to "msg.progress", "msg" to session.messageId, "phase" to "rate_limited",
                "text" to "Claude 暂时限流，已切换到 $fallbackModel。"))
        }
        val content = visibleText.toString()
        val willFallback = rateLimited || (status == StreamStatus.FAILED && reason == "first_token_timeout" && fallbackOnFirstTokenTimeout)
        val persist: com.coach.store.LedgerStore.() -> Unit = {
            finishMessage(session.messageId, status, reason, content, session.cursor.index)
            if (isQuickThread(session.courseId,session.threadId)) append(session.courseId,"quick_ask_message",json("messageId" to session.messageId),thread=session.threadId)
            recordAiCall(
                callId = result.call.callId, task = result.call.task, requestedModel = result.call.requestedModel,
                servedBy = result.call.servedBy ?: session.model, ttftMs = result.call.ttftMs,
                durMs = result.call.durMs, inChars = result.call.inChars, outChars = content.length,
                status = if (guard.letterReferenceStripped) "letter_ref_stripped" else status, queueMs = result.call.queueMs, physicalCalls = result.call.physicalCalls,
            )
        }
        val plan = session.plan
        if (plan != null && !willFallback) {
            val outcome = tutor.finish(plan,session.messageId,result is BridgeResult.Success && status == StreamStatus.COMPLETE && content.isNotBlank(),persist)
            publish(outcome,session.threadId,session.courseId)
        } else actor.execute { tutorTransaction(persist) }
        sessions.remove(session.messageId)
        hub.emit(endFrame(session, status, reason, content.length, result.call.servedBy ?: terminal?.servedBy))
        log("COACH msg.end msg=${session.messageId} model=${session.model} status=$status reason=$reason chars=${content.length} ttft=${result.call.ttftMs} terminal=${terminal?.status} failure=${(result as? BridgeResult.Failure)?.reason}")
        if (willFallback) fallback(session,question,pack)
    }

    /** At most one invocation per original answer; the supplement cannot ask for another lookup. */
    private suspend fun appendSourceLookup(session: Session, question: String, query: String, content: StringBuilder) {
        val started = System.nanoTime()
        var lookupResult: BridgeResult? = null
        var checked = 0
        var accepted = 0
        var failure: String? = null
        val callId = UUID.randomUUID().toString()
        session.phase = "source_lookup"
        hub.emit(json("t" to "msg.progress","msg" to session.messageId,"phase" to "source_lookup","text" to "导师去翻原文了"))
        val deadline = scope.launch {
            delay(sourceLookupTimeoutMs.coerceAtMost(90_000))
            session.abort(StreamStatus.TRUNCATED,"source_lookup_timeout")
        }
        try {
            val health = bridge.health()
            val rate = health?.get("rate_limit") as? JsonObject
            if (health == null || rate == null || rate.str("all_paused") == "true" || rate?.str("background_paused") == "true" ||
                rate?.str("status")?.takeIf { it.isNotBlank() && it != "null" }?.let { it !in setOf("allowed","ok") } == true) {
                failure = "source_quota_protected"
                return
            }
            val hits = actor.execute { searchSourcePages(session.courseId,query) }
            val pack = SourceReadSandbox().read(SourcePack.prepare("",hits)) { process ->
                session.sourceProcess = process
                if (session.forcedStatus != null) process?.destroyForcibly()
            }
            if (pack.pages.isEmpty() || session.forcedStatus != null || sourceLookupProtected()) {
                failure = "source_unavailable"
                return
            }
            val gate = SourceCitationGate(pack.anchors)
            val guard = TutorOutputGuard(session.plan?.pending.orEmpty(),false) { attempt ->
                session.plan?.let { plan -> runBlocking { tutor.leak(plan,attempt) } }
            }
            val visible = VisibleStream()
            var heading = false
            fun publishParagraph(paragraph: String) {
                val publisher = VisibleStream()
                val segments = publisher.push(paragraph) + publisher.finish()
                if (segments.isEmpty()) return
                if (!heading) {
                    emitSegment(session,VisibleStream.Segment.Text("\n\n补充：\n"),content)
                    heading = true
                }
                segments.forEach { emitSegment(session,it,content) }
                val cursor = session.cursor
                runBlocking { actor.execute { updateStreamingMessage(session.messageId,cursor.text,cursor.index) } }
            }
            fun acceptSource(text: String) {
                val filtered = visible.push(text).joinToString("") { segment -> when(segment) {
                    is VisibleStream.Segment.Text -> segment.text
                    is VisibleStream.Segment.Math -> "\$\$${segment.tex}\$\$"
                } }
                gate.push(filtered).forEach(::publishParagraph)
            }
            val request = com.coach.ai.ChatRequest(
                task="SOURCE_LOOKUP",model=session.model,system=com.coach.ai.TeachPrompt.SYSTEM,
                user="""Read only the supplied textbook pages. Answer the student's question using this source lookup.
                    |Write ONLY supplement paragraphs, separated by a blank line. End EVERY paragraph with its source anchor, e.g. 【S1】.
                    |Every quotation must be copied verbatim from the anchored page. No unsupported claims. No questions, grading, tools, JSON or lookup markers.
                    |Student question: ${TutorOutputGuard.modelInput(question)}
                    |Lookup request: ${TutorOutputGuard.modelInput(query)}
                    |${TutorOutputGuard.modelInput(pack.render())}
                """.trimMargin(),stream=true,effort="medium")
            lookupResult = withContext(Dispatchers.IO) {
                bridge.streamChat(request,object : BridgeStreamSink {
                    override fun onDelta(text: String, atMs: Long) {
                        if (session.forcedStatus == null) acceptSource(guard.push(text))
                    }
                }) { session.attach(it) }
            }
            if (lookupResult is BridgeResult.Success && session.forcedStatus == null) {
                acceptSource(guard.finish())
                val tail = visible.finish().joinToString("") { segment -> when(segment) {
                    is VisibleStream.Segment.Text -> segment.text
                    is VisibleStream.Segment.Math -> "\$\$${segment.tex}\$\$"
                } }
                gate.push(tail).forEach(::publishParagraph)
            }
            gate.finish(lookupResult is BridgeResult.Success && session.forcedStatus == null).forEach(::publishParagraph)
            checked = gate.checked
            accepted = gate.accepted
            if (lookupResult !is BridgeResult.Success) failure = "source_lookup_failed"
        } catch (e: Exception) {
            failure = "source_lookup_unavailable"
            log("COACH source-lookup unavailable msg=${session.messageId} error=${e.javaClass.simpleName}")
        } finally {
            deadline.cancel()
            val call = lookupResult?.call
            actor.execute { recordAiCall(
                callId=call?.callId ?: callId,task="SOURCE_LOOKUP",requestedModel=session.model,
                servedBy=call?.servedBy ?: session.model,ttftMs=call?.ttftMs,
                durMs=(System.nanoTime()-started)/1_000_000,inChars=call?.inChars ?: 0,outChars=call?.outChars ?: 0,
                status=session.forcedReason ?: failure ?: if (accepted > 0) "complete" else "citation_rejected",
                queueMs=call?.queueMs,physicalCalls=call?.physicalCalls ?: 0,requestType="source_lookup") }
            log("COACH source-lookup msg=${session.messageId} durationMs=${(System.nanoTime()-started)/1_000_000} checked=$checked accepted=$accepted status=${session.forcedReason ?: failure ?: "complete"}")
            session.phase = "teach"
        }
    }

    /**
     * §5.5：首字 25 秒没到 → 主动断开（已在上面的 watchdog 里做）→ 显式调 Codex 非流式，
     * 新开一条消息，消息头写真实模型名，界面显示等待而不是「打字」。
     */
    private suspend fun fallback(previous: Session, question: String, pack: String) {
        val messageId = UUID.randomUUID().toString()
        val session = Session(messageId, previous.threadId, previous.courseId, fallbackModel, true, previous.plan)
        sessions[messageId] = session
        actor.execute { beginStreamingMessage(previous.threadId, messageId, fallbackModel) }
        hub.emit(startFrame(session))
        val request = teachRequest(question, pack, emptyList(), fallbackModel, false, effort = null)
            .copy(stream = false)
        val result = withContext(Dispatchers.IO) { bridge.chat(request) }
        val text = (result as? BridgeResult.Success)?.text.orEmpty()
        val visible = VisibleStream { drop ->
            log("COACH visible-drop msg=$messageId reason=${drop.reason} excerpt=${drop.excerpt}")
        }
        val guard = TutorOutputGuard(session.plan?.pending.orEmpty(),session.plan?.firstExplanation == true) { attempt ->
            session.plan?.let { plan -> runBlocking { tutor.leak(plan,attempt) } }
        }
        val segments = visible.push(guard.push(text) + guard.finish()) + visible.finish()
        val content = StringBuilder()
        for (segment in segments) emitSegment(session, segment, content)
        val status = if (result is BridgeResult.Success && content.isNotBlank()) StreamStatus.COMPLETE else StreamStatus.FAILED
        val reason = if (result is BridgeResult.Success && content.isBlank()) "empty_visible_body"
            else (result as? BridgeResult.Failure)?.reason
        val persist: com.coach.store.LedgerStore.() -> Unit = {
            finishMessage(messageId, status, reason, content.toString(), session.cursor.index)
            if (isQuickThread(session.courseId,session.threadId)) append(session.courseId,"quick_ask_message",json("messageId" to messageId),thread=session.threadId)
            recordAiCall(
                callId = result.call.callId, task = result.call.task, requestedModel = result.call.requestedModel,
                servedBy = result.call.servedBy ?: fallbackModel, ttftMs = result.call.ttftMs,
                durMs = result.call.durMs, inChars = result.call.inChars, outChars = content.length,
                status = if (guard.letterReferenceStripped) "letter_ref_stripped" else status, queueMs = result.call.queueMs, physicalCalls = result.call.physicalCalls,
            )
        }
        val plan = session.plan
        if (plan != null) publish(tutor.finish(plan,messageId,status == StreamStatus.COMPLETE,persist),session.threadId,session.courseId)
        else actor.execute { tutorTransaction(persist) }
        sessions.remove(session.messageId)
        hub.emit(endFrame(session, status, reason, content.length, result.call.servedBy))
        log("COACH fallback msg=$messageId model=$fallbackModel status=$status reason=$reason")
    }

    /**
     * 可见流只放行带类型的内容块（§5.8 第 4 条）：正文 `msg.delta`、公式 `msg.math`；
     * 两种都占一个 `(msg, i)` 序号，客户端按序号去重。
     */
    private fun emitSegment(session: Session, segment: VisibleStream.Segment, text: StringBuilder): Int = when (segment) {
        is VisibleStream.Segment.Text -> {
            if (segment.text.isEmpty()) session.cursor.index
            else {
                text.append(segment.text)
                val cursor = Session.Cursor(text.toString(), session.cursor.index + 1)
                session.cursor = cursor
                hub.emit(json("t" to "msg.delta", "msg" to session.messageId, "i" to cursor.index, "text" to segment.text))
                cursor.index
            }
        }
        is VisibleStream.Segment.Math -> {
            // 落库用 Markdown 形态，重连拿到 snapshot 也能重新渲染公式
            text.append("$$").append(segment.tex).append("$$")
            val cursor = Session.Cursor(text.toString(), session.cursor.index + 1)
            session.cursor = cursor
            hub.emit(
                json(
                    "t" to "msg.math", "msg" to session.messageId, "i" to cursor.index,
                    "tex" to segment.tex, "display" to segment.display,
                ),
            )
            cursor.index
        }
    }

    private fun startFrame(session: Session): JsonObject = json(
        "t" to "msg.start", "msg" to session.messageId, "threadId" to session.threadId,
        "courseId" to session.courseId, "model" to session.model, "fallback" to session.fallback,
    )

    /** `servedBy` 是桥在终态帧里报的实际出答模型；没有就退回请求模型。界面消息头以这里为准。 */
    private fun endFrame(session: Session, status: String, reason: String?, chars: Int, servedBy: String? = null): JsonObject = json(
        "t" to "msg.end", "msg" to session.messageId, "status" to status, "reason" to reason,
        "model" to (servedBy?.takeIf { it.isNotBlank() } ?: session.model), "fallback" to session.fallback, "chars" to chars,
    )
}
