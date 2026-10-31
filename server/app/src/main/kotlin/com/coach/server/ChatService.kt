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
class ChatHub {
    val frames = MutableSharedFlow<JsonObject>(extraBufferCapacity = 1024)
    fun emit(frame: JsonObject) {
        frames.tryEmit(frame)
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
    /** 30 秒没有新 delta 判卡住。 */
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
    private val log: (String) -> Unit = { println(it) },
    private val packsDirectory: java.nio.file.Path = java.nio.file.Path.of(System.getenv("COACH_PACKS_DIR")
        ?: java.io.File(System.getProperty("user.home"), "Coach/corpus/packs").path),
) {

    val hub = ChatHub()
    val tutor = TutorMachine(actor)
    private val preparation = LessonPreparation(actor,bridge,packsDirectory)
    private val supplyLock = Mutex()
    private val waitingPools = ConcurrentHashMap.newKeySet<String>()

    suspend fun prepareLessons(course: String, kps: List<String>): JsonObject = supplyLock.withLock {
        json("results" to JsonArray(kps.map { preparation.prepare(course,it) }))
    }

    fun startSupplyWorker(): Job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
        val worker = com.coach.ai.PoolWorker(actor,bridge)
        while(isActive) {
            try { supplyLock.withLock { worker.runOne() } }
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
        }

        fun attach(handle: BridgeStreamHandle) {
            this.handle = handle
            if (forcedStatus != null) handle.cancel()
        }
    }

    /** 追问/问答：立刻返回 messageId，流在后台推进（每帧经 [hub] 广播）。 */
    suspend fun ask(threadId: String, courseId: String, question: String, pack: String = ""): String {
        when (tutorIntent(question)) {
            TutorIntent.NEXT_STEP -> return nextStep(threadId,courseId,pack)
            TutorIntent.TEST_ME -> return nextStep(threadId,courseId,pack,true)
            null -> Unit
        }
        val plan = if (tutor.hasThread(courseId,threadId)) tutor.followup(courseId,threadId) else null
        return startReply(threadId,courseId,question,pack,plan)
    }

    suspend fun openTutor(threadId: String, courseId: String, kpId: String, concepts: Set<String>, title: String) {
        publish(tutor.open(courseId,threadId,kpId,concepts,title),threadId,courseId)
    }

    suspend fun nextStep(threadId: String, courseId: String, pack: String = "", testUntaught: Boolean = false): String {
        var outcome = tutor.next(courseId,threadId,testUntaught)
        if(outcome.waitingForPool) {
            val kp = actor.execute { events(courseId).lastOrNull { it.type=="kp_opened" && it.threadId==threadId }?.payload?.str("kpId") }
            if(kp!=null) {
                runCatching { preparation.ensure(courseId,kp) }
                outcome=tutor.next(courseId,threadId,testUntaught)
                if(waitingPools.add(threadId)) scope.launch {
                    try {
                        repeat(360) {
                            delay(500)
                            val available=actor.execute { poolAvailable(courseId,kp) }
                            if(available>=3) { publish(tutor.next(courseId,threadId,testUntaught),threadId,courseId);return@launch }
                            if(actor.execute { supplyJobs(courseId,kp).isEmpty() }) {
                                hub.emit(json("t" to "tutor.progress","threadId" to threadId,"courseId" to courseId,
                                    "phase" to "pool_failed","text" to "题池暂空，补题未成功，可以重试。"));return@launch
                            }
                        }
                    } finally { waitingPools.remove(threadId) }
                }
            }
        }
        publish(outcome,threadId,courseId)
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
        val context = listOf(TutorOutputGuard.modelInput(sourcePack.take(40_000)), plan?.instruction().orEmpty()).joinToString("\n")
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
        val status=session.forcedStatus ?: StreamStatus.COMPLETE
        val persist: com.coach.store.LedgerStore.() -> Unit = {
            finishMessage(session.messageId,status,session.forcedReason,text.toString(),session.cursor.index)
            recordAiCall(UUID.randomUUID().toString(),"TEACH_CACHE",session.model,session.model,0,0,0,text.length,status,0,0)
        }
        val plan=checkNotNull(session.plan)
        publish(tutor.finish(plan,session.messageId,status==StreamStatus.COMPLETE,persist),session.threadId,session.courseId)
        session.finished.set(true);sessions.remove(session.messageId)
        hub.emit(endFrame(session,status,session.forcedReason,text.length))
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
                "i" to cursor.index, "text" to cursor.text, "status" to "streaming", "reason" to null,
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
                session.lastDeltaAtMs = maxOf(session.lastDeltaAtMs, atMs)
                acceptFiltered(guard.push(text))
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
                bridge.streamChat(teachRequest(question, pack, recent, session.model, allowSourceLookup), sink) { handle ->
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
            session.finished.set(true)
        }

        acceptFiltered(guard.finish())
        for (segment in visible.finish()) emitSegment(session, segment, visibleText)

        val forcedStatus = session.forcedStatus
        val terminal = result.terminal
        var status = forcedStatus ?: when {
            terminal != null -> StreamStatus.fromBridge(terminal.status, false, visibleText.isNotEmpty())
            visibleText.isEmpty() -> StreamStatus.FAILED
            // ADR-0002：收不到终态帧按截断处理（已经出过字就一定不是「完整」）
            else -> StreamStatus.TRUNCATED
        }
        var reason = session.forcedReason ?: terminal?.reason ?: (result as? BridgeResult.Failure)
            ?.reason?.takeIf { it.isNotBlank() && it != "no_x_coach" }

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
        }
        val content = visibleText.toString()
        val willFallback = rateLimited || (status == StreamStatus.FAILED && reason == "first_token_timeout" && fallbackOnFirstTokenTimeout)
        val persist: com.coach.store.LedgerStore.() -> Unit = {
            finishMessage(session.messageId, status, reason, content, session.cursor.index)
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
        hub.emit(endFrame(session, status, reason, content.length))
        log("COACH msg.end msg=${session.messageId} model=${session.model} status=$status reason=$reason chars=${content.length} ttft=${result.call.ttftMs} terminal=${terminal?.status} failure=${(result as? BridgeResult.Failure)?.reason}")
        if (willFallback) fallback(session,question,pack)
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
        val request = teachRequest(question, pack, emptyList(), fallbackModel, allowSourceLookup, effort = null)
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
        val status = if (result is BridgeResult.Success && content.isNotEmpty()) StreamStatus.COMPLETE else StreamStatus.FAILED
        val reason = (result as? BridgeResult.Failure)?.reason
        val persist: com.coach.store.LedgerStore.() -> Unit = {
            finishMessage(messageId, status, reason, content.toString(), session.cursor.index)
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
        hub.emit(endFrame(session, status, reason, content.length))
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

    private fun endFrame(session: Session, status: String, reason: String?, chars: Int): JsonObject = json(
        "t" to "msg.end", "msg" to session.messageId, "status" to status, "reason" to reason,
        "model" to (session.model), "fallback" to session.fallback, "chars" to chars,
    )
}
