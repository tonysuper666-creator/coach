package com.coach.server

import com.coach.ai.*
import com.coach.core.*
import com.coach.domain.*
import com.coach.store.LedgerStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.nio.file.attribute.PosixFilePermissions
import java.net.ServerSocket
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

/** One foreground run owns its bridge; no test traffic is sent to production 8791. */
private class ReplayBridge(val client: BridgeClient, private val process: Process?, private val root: Path, val port: Int? = null) : AutoCloseable {
    override fun close() {
        process?.let {
            it.destroy()
            if(!it.waitFor(5,TimeUnit.SECONDS)) { it.destroyForcibly(); it.waitFor(5,TimeUnit.SECONDS) }
            Files.writeString(root.resolve("bridge-lifecycle.json"),json("owned_pid" to it.pid(),"port" to port,"stopped" to !it.isAlive).toString()+"\n")
        }
    }
    companion object {
        suspend fun open(root: Path, live: Boolean): ReplayBridge {
            if(!live) return ReplayBridge(BridgeClient(BridgeConfig("http://127.0.0.1:1")),null,root)
            System.getenv("COACH_BRIDGE_URL")?.let { url ->
                val uri=URI(url)
                require(uri.scheme=="http" && uri.host in setOf("127.0.0.1","localhost","::1") && uri.port>0 &&
                    uri.port !in setOf(8790,8791,3790)) { "Live replay requires an isolated loopback bridge; production ports are forbidden" }
                return ReplayBridge(BridgeClient(BridgeConfig(url)),null,root,uri.port)
            }
            val python=System.getenv("COACH_BRIDGE_PYTHON") ?: "tools/bridge/.venv/bin/python"
            require(Files.isExecutable(Path.of(python))) { "缺少独立桥环境；设置 COACH_BRIDGE_PYTHON 或安装 tools/bridge/.venv" }
            val port=ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val bridgeHome=root.resolve("bridge")
            val child=ProcessBuilder(python,"tools/bridge/subscription_bridge.py").apply {
                environment().remove("BRIDGE_CHILD_PATH")
                environment()["BRIDGE_PORT"]=port.toString()
                environment()["COACH_BRIDGE_HOME"]=bridgeHome.toString()
                environment()["BRIDGE_AGY_ENABLED"]="0"
                environment()["BRIDGE_CLAUDE_CONCURRENCY"]="1"
                redirectOutput(root.resolve("bridge.stdout.log").toFile())
                redirectError(root.resolve("bridge.stderr.log").toFile())
            }.start()
            val owned=ReplayBridge(BridgeClient(BridgeConfig("http://127.0.0.1:"+port)),child,root,port)
            try {
                withTimeout(20_000) {
                    while(true) {
                        check(child.isAlive) { "独立桥启动失败，见本机 bridge.stderr.log" }
                        val health=owned.client.health()
                        if(health!=null) {
                            check(health.str("bridge_home")==bridgeHome.toString()) { "Bridge ownership mismatch" }
                            break
                        }
                        delay(100)
                    }
                }
                // The stock profile permits only the standard bridge work directory.
                // Extend this private copy for this owned bridge's work directory alone.
                val profile=bridgeHome.resolve("work/agent_sandbox.sb")
                Files.writeString(profile,"\n(allow file-read* file-write* (subpath "+
                    JsonPrimitive(bridgeHome.resolve("work").toString())+"))\n",java.nio.file.StandardOpenOption.APPEND)
                Files.writeString(root.resolve("bridge-lifecycle.json"),json("owned_pid" to child.pid(),"port" to port,"stopped" to false).toString()+"\n")
                return owned
            } catch(e: Exception) { owned.close(); throw e }
        }
    }
}

private data class ReplayItem(val stem: String, val options: List<Option>, val key: String)
private val replayAssertions = listOf("letters_balanced_1000","three_consecutive_correct_letters_distinct",
    "taught_continue_yields_question_only","concept_full_lesson_at_most_once",
    "fallback_EMPTY","fallback_TEXT_ONLY","fallback_MALFORMED","fallback_TIMEOUT",
    "visible_stream_has_no_protocol_text","mcq_only_Essay","mcq_only_concept","mcq_only_design","mcq_only_calculation",
    "feedback_matches_letter_map","leak_recorded_before_stripping_false","leak_recorded_before_stripping_true",
    "lesson_before_question_or_untaught","group_size_one_or_three","progress_first_reply_is_projection")
private class ReplayChecks {
    private val counts = replayAssertions.associateWith { 0 to 0 }.toMutableMap()
    fun check(name: String, ok: Boolean) {
        val (samples, failures) = counts[name] ?: (0 to 0)
        counts[name] = samples + 1 to (failures + if (ok) 0 else 1)
    }
    fun result() = JsonObject(counts.mapValues { (_,c) -> json("samples" to c.first,"failures" to c.second) })
    fun failed() = counts.values.any { it.second > 0 }
}

/** Opt-in replay. Never opens production databases, prints utterances, or invents source speech. */
fun main() = runBlocking {
    val now = Instant.now()
    check(now < Instant.parse("2026-11-02T00:00:00Z") || now >= Instant.parse("2026-11-02T15:00:00Z"))
    val home = Path.of(System.getProperty("user.home"))
    val base = home.resolve("Coach/corpus/replay").toRealPath()
    val input = Path.of(requireNotNull(System.getenv("COACH_ACCEPTANCE_INPUT")) { "缺语料，未运行" }).toRealPath()
    require(input.startsWith(base)) { "Inputs must stay in private replay directory" }
    val root = base.resolve("runs/acceptance-" + now.toEpochMilli())
    Files.createDirectories(root)
    Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"))
    val rows = Files.readAllLines(input).filter(String::isNotBlank).map { LedgerJson.parseToJsonElement(it).jsonObject }
    require(rows.isNotEmpty()) { "缺语料，未运行：0 条用户发言" }
    val synthetic = System.getenv("COACH_REPLAY_SYNTHETIC") == "1"
    val live = System.getenv("COACH_REPLAY_LIVE") == "1"
    val structuralOnly = System.getenv("COACH_REPLAY_STRUCTURAL_ONLY") == "1"
    require(live || structuralOnly) { "Set COACH_REPLAY_LIVE=1 to use real CLI, or explicitly select structural-only" }
    val checks = ReplayChecks()
    val utteranceChecks = ReplayChecks()
    val sourceLetters = mutableListOf<String>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = LedgerStore(root.resolve("data"))
    val actor = LedgerActor(scope,store)
    val options = listOf("night flowering","day flowering","never flowering","underwater flowering").mapIndexed { i,s -> Option("o"+i,s) }
    var pack = "Moonflowers bloom at night. Sunflowers turn toward light. Starflowers have five petals."
    var title = "Synthetic flower facts"
    val questions = mutableListOf<ReplayItem>()
    if (synthetic) repeat(12) { questions += ReplayItem("Synthetic flower question "+it+": which property describes moonflowers?",options,"o0") }
    else {
        val corpus = home.resolve("Coach/corpus/gradegen")
        val kps = LedgerJson.parseToJsonElement(Files.readString(corpus.resolve("inputs/kps.json"))).jsonArray.map { it.jsonObject }
        Files.list(corpus.resolve("runs/kp")).use { files ->
            files.filter { it.toString().endsWith(".json") && !it.toString().endsWith(".gen.json") }.sorted().forEach { file ->
                val value = LedgerJson.parseToJsonElement(Files.readString(file)).jsonObject
                val accepted = value["accepted"] as? JsonArray ?: return@forEach
                if (accepted.size < 3) return@forEach
                if (questions.isNotEmpty()) return@forEach
                if (questions.isEmpty()) {
                    val id = value.getValue("kp").let { if(it is JsonObject) it.str("id") else it.jsonPrimitive.content }
                    val kp = kps.single { it.str("id") == id }
                    pack = kp.str("excerpt"); title = kp.str("objective")
                }
                accepted.forEach { q ->
                    val raw = q.jsonObject.getValue("options").jsonObject
                    val opts = raw.entries.mapIndexed { i,e -> Option("o"+i,e.value.jsonPrimitive.content) }
                    if(opts.size == 4) questions += ReplayItem(q.jsonObject.str("stem"),opts,opts[raw.keys.indexOf(q.jsonObject.str("key"))].optionId)
                }
            }
        }
        require(questions.size >= 3) { "缺少本机已复核题池，未运行" }
    }
    var replayed = 0
    var errors = 0
    suspend fun seed(kp: String, count: Int = questions.size) = actor.execute {
        repeat(count) { i ->
            val q = if(kp.startsWith("source-")) questions[i % questions.size]
                else ReplayItem("Synthetic probe "+kp+" distinct question "+i,options,"o0")
            registerQuestion(kp+"-q"+i,"replay","", "SINGLE_CHOICE",q.stem,"VERIFIED",q.key,q.options,listOf(kp))
        }
    }
    // Start only after corpus validation; failed startup cleans its own exact child PID.
    val replayBridge = try { ReplayBridge.open(root,!structuralOnly) }
        catch(e: Exception) { actor.close();scope.cancel();throw e }
    val bridge=replayBridge.client
    val chat = ChatService(actor,scope,bridge,log={})
    val frames = mutableListOf<JsonObject>()
    val collector = scope.launch(start=CoroutineStart.UNDISPATCHED) {
        chat.hub.frames.collect { frame -> synchronized(frames) { frames.add(frame) } }
    }
    fun snapshotFrames(): List<JsonObject> = synchronized(frames) { frames.toList() }
    try {
        val letters = actor.execute {
            repeat(1002) { i -> registerQuestion("distribution-"+i,"distribution","","SINGLE_CHOICE","Synthetic distinct question "+i,"VERIFIED","o0",options,listOf("kp")) }
            (0 until 334).flatMap { group -> takePoolGroup("distribution","kp","g"+group).events.map { it.payload.getValue("letterMap").jsonObject.str("o0") } }
        }
        letters.windowed(1000).forEach { window -> checks.check("letters_balanced_1000",CHOICE_LETTERS.all { letter -> window.count { it == letter } in 200..300 }) }
        letters.windowed(3).forEach { checks.check("three_consecutive_correct_letters_distinct",it.distinct().size == 3) }
        // Fault injection and mastery probes are synthetic state, never real-session samples.
        for (reason in listOf("EMPTY","TEXT_ONLY","MALFORMED","TIMEOUT")) {
            val kp = "fallback-"+reason
            seed(kp,3)
            actor.execute { val id=enqueueSupply("replay",kp,"Synthetic objective","Synthetic source"); failSupply(claimJob(id)!!,reason) }
            val result = actor.execute { takePoolGroup("replay",kp,kp) }
            checks.check("fallback_"+reason,result.events.size == 3 && actor.execute { events("replay").any { it.type=="fallback_used" && it.payload.str("reason")==reason } })
        }
        for (type in listOf("Essay","concept","design","calculation")) {
            val thread = "mastery-"+type
            seed(thread,7)
            chat.tutor.open("replay",thread,thread,setOf(thread),type)
            val plan = requireNotNull(chat.tutor.next("replay",thread).plan)
            chat.tutor.finish(plan,"synthetic-explanation-"+type,true)
            val cards = chat.tutor.next("replay",thread).cards
            checks.check("taught_continue_yields_question_only",cards.size==3 && cards.none { it.payload.flag("untaught") })
            for(size in listOf(0,2,4)) {
                val rejected = try { chat.tutor.settings("replay",thread,size); false } catch(_: IllegalArgumentException) { true }
                checks.check("group_size_one_or_three",rejected)
            }
            val answers = actor.execute { cards.associate { e -> e.payload.str("attemptId") to e.payload.getValue("letterMap").jsonObject.str(tutorChoiceKey(e.payload.str("questionId")).correctOptionId) } }
            val grades = chat.tutor.submit("replay",thread,cards.first().payload.str("groupId"),answers,"replay",thread).grades
            checks.check("mcq_only_"+type,actor.execute { snapshot("replay").projection.mastery[thread]?.passed == true })
            checks.check("feedback_matches_letter_map",grades.zip(cards).all { (g,c) -> g.payload.str("feedback").contains("（"+answers.getValue(c.payload.str("attemptId"))+"）") })
            for(size in listOf(1,3)) {
                chat.tutor.settings("replay",thread,size)
                val next=chat.tutor.next("replay",thread)
                checks.check("group_size_one_or_three",next.plan==null && next.cards.size==size)
                if(next.cards.isNotEmpty()) {
                    val choices=actor.execute { next.cards.associate { c -> c.payload.str("attemptId") to
                        c.payload.getValue("letterMap").jsonObject.str(tutorChoiceKey(c.payload.str("questionId")).correctOptionId) } }
                    chat.tutor.submit("replay",thread,next.cards.first().payload.str("groupId"),choices,"replay",thread+"-size-"+size)
                }
            }
        }
        for (withLetter in listOf(false,true)) {
            val thread = "leak-"+withLetter; seed(thread,3)
            chat.tutor.open("replay",thread,thread,setOf(thread),"Leak probe")
            val plan = requireNotNull(chat.tutor.next("replay",thread).plan)
            chat.tutor.finish(plan,"teach-"+thread,true)
            val cards = chat.tutor.next("replay",thread).cards
            val followup = chat.tutor.followup("replay",thread,cards.first().payload.str("attemptId"))
            val key = followup.pending.single().key
            val text = key.options.single { it.optionId == key.correctOptionId }.text
            val guard = TutorOutputGuard(followup.pending) { id -> runBlocking { chat.tutor.leak(followup,id) } }
            val raw = if(withLetter) "选 B，"+text+"。" else text+"。"
            raw.chunked(2).forEach { guard.push(it) }; guard.finish()
            checks.check("letter_reference_stripped_"+withLetter,guard.letterReferenceStripped==withLetter)
            chat.tutor.finish(followup,"followup-"+thread,true)
            val answers = actor.execute { cards.associate { it.payload.str("attemptId") to it.payload.getValue("letterMap").jsonObject.str(tutorChoiceKey(it.payload.str("questionId")).correctOptionId) } }
            chat.tutor.submit("replay",thread,cards.first().payload.str("groupId"),answers,"replay",thread)
            val target = cards.first().payload.str("attemptId")
            checks.check("leak_recorded_before_stripping_"+withLetter,actor.execute {
                events("replay").any { it.type=="hint_revealed" && it.payload.str("attemptId")==target && it.payload.str("cause")=="LEAK" } &&
                    target !in snapshot("replay").projection.mastery.getValue(thread).window
            })
        }
        if (!structuralOnly) {
            requireNotNull(bridge.health()) { "8791 不可用，live 未运行" }
            for ((index, session) in rows.groupBy { it.str("session_id") }.values.withIndex()) {
                val thread = "source-"+index; seed(thread)
                chat.openTutor(thread,"replay",thread,setOf(thread),title)
                var pending = emptyList<JsonObject>()
                val partial = linkedMapOf<String,String>()
                for ((turn, row) in session.withIndex()) {
                    val text = row.getValue("user").jsonObject.str("content")
                    val start = snapshotFrames().size
                    var activeMessage = ""
                    try {
                        if (row.str("intent")=="answer" && Regex("[A-Da-d]").matches(text.trim()) && pending.isNotEmpty()) {
                            partial[pending[partial.size].str("attemptId")] = text.trim().uppercase()
                            if (partial.size==pending.size) {
                                val answerCards=pending.toList()
                                chat.submitGroup(thread,"replay",pending.first().str("groupId"),partial.toMap(),"replay","source-"+replayed)
                                withTimeout(5000) { while(snapshotFrames().drop(start).none { it.str("t")=="tutor.feedback" }) delay(10) }
                                val feedback=snapshotFrames().drop(start).single { it.str("t")=="tutor.feedback" }.getValue("grades").jsonArray
                                feedback.forEach { g ->
                                    val card=answerCards.single { it.str("attemptId")==g.jsonObject.str("attemptId") }
                                    val correct=actor.execute { tutorChoiceKey(card.str("questionId")).correctOptionId }
                                    val letter=card.getValue("letterMap").jsonObject.str(correct)
                                    utteranceChecks.check("feedback_matches_letter_map",g.jsonObject.str("feedback").let { it.contains("（"+letter+"）") || it.contains("正确答案是 "+letter+"。") })
                                }
                                pending=emptyList(); partial.clear()
                            }
                        } else {
                            val wasTaught = actor.execute { thread in snapshot("replay").projection.explainedKpIds }
                            val today = actor.execute { todayProgress("replay",thread) }
                            val id = chat.ask(thread,"replay",text,pack)
                            activeMessage = id
                            withTimeout(240_000) {
                                while (if(id.isNotBlank()) snapshotFrames().drop(start).none { it.str("t")=="msg.end" && it.str("msg")==id }
                                    else snapshotFrames().drop(start).none { it.str("t")=="tutor.state" }) delay(25)
                            }
                            val emitted = snapshotFrames().drop(start)
                            val cards = emitted.lastOrNull { it.str("t")=="tutor.cards" }?.getValue("cards")?.jsonArray?.map { it.jsonObject }
                            if(cards!=null) {
                                // Re-displaying an unanswered group must retain partial answers.
                                if(pending.map { it.str("attemptId") }!=cards.map { it.str("attemptId") }) {
                                    pending=cards; partial.clear()
                                    cards.forEach { c ->
                                        val correct=actor.execute { tutorChoiceKey(c.str("questionId")).correctOptionId }
                                        sourceLetters += c.getValue("letterMap").jsonObject.str(correct)
                                    }
                                }
                                utteranceChecks.check("group_size_one_or_three",cards.size in setOf(1,3))
                            }
                            val p = progressIntent(text)
                            if(p!=null) utteranceChecks.check("progress_first_reply_is_projection",
                                emitted.firstOrNull { it.str("t")=="msg.start" }?.str("source")=="projection" && chat.snapshot(id)?.str("text")==today.answer(p))
                            if(wasTaught && tutorIntent(text)==TutorIntent.NEXT_STEP)
                                utteranceChecks.check("taught_continue_yields_question_only",cards?.isNotEmpty()==true && emitted.none { it.str("t") in setOf("msg.start","msg.delta") })
                            val visible = emitted.filter { it.str("t")=="msg.delta" }.joinToString("") { it.str("text") }
                            utteranceChecks.check("visible_stream_has_no_protocol_text",VisibleStream.TOKENS.none { visible.contains(it,true) } && !visible.contains(VisibleStream.MARK))
                            if(id.isNotBlank()) utteranceChecks.check("live_reply_complete",emitted.lastOrNull { it.str("t")=="msg.end" }?.str("status")=="complete")
                            cards?.forEach { c -> utteranceChecks.check("lesson_before_question_or_untaught",wasTaught || c.flag("untaught")) }
                        }
                        replayed++
                    } catch (e: Exception) {
                        if(e is CancellationException && e !is TimeoutCancellationException) throw e
                        if(activeMessage.isNotBlank()) chat.cancel(activeMessage)
                        errors++
                        Files.writeString(root.resolve("turn-errors.jsonl"),json("sessionIndex" to index,"turn" to turn,
                            "errorType" to e.javaClass.simpleName,"detail" to e.message).toString()+"\n",
                            java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND)
                    }
                    if((turn+1)%5==0) println("REPLAY_PROGRESS turns="+(turn+1)+" errors="+errors)
                }
            }
        }
        val full = actor.execute { events("replay").filter { it.type=="explanation_delivered" }.flatMap { e -> e.payload.strings("conceptIds").map { e.threadId to it } } }
        checks.check("concept_full_lesson_at_most_once",full.size==full.distinct().size)
        val sourceFull=full.filter { it.first?.startsWith("source-")==true }
        if(sourceFull.isNotEmpty()) utteranceChecks.check("concept_full_lesson_at_most_once",sourceFull.size==sourceFull.distinct().size)
        sourceLetters.windowed(3).forEach { utteranceChecks.check("three_consecutive_correct_letters_distinct",it.distinct().size==3) }
        sourceLetters.windowed(1000).forEach { window -> utteranceChecks.check("letters_balanced_1000",CHOICE_LETTERS.all { letter -> window.count { it==letter } in 200..300 }) }
        val report = json("synthetic_input" to synthetic,"live" to !structuralOnly,"input_utterances" to rows.size,
            "replayed" to replayed,"turn_errors" to errors,"structural" to checks.result(),"source_utterance_assertions" to utteranceChecks.result(),
            "input_sha256" to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)).joinToString("") { "%02x".format(it) },
            "isolated_bridge" to !structuralOnly,
            "bridge_port" to replayBridge.port,
            "driver_revision" to ProcessBuilder("git","rev-parse","HEAD").start().let { p ->
                val revision=p.inputStream.bufferedReader().readText().trim();check(p.waitFor()==0);revision
            },
            "ai_calls" to actor.execute { supplyCallCount() },
            "limitation" to "Historical Coach initial state unavailable. Isolated new threads and local reviewed pool; structural probes are synthetic. Zero samples are uncovered.")
        Files.writeString(root.resolve("report.json"),report.toString()+"\n")
        Files.writeString(root.resolve("frames.jsonl"),snapshotFrames().joinToString("\n",postfix="\n"))
        println("ACCEPTANCE_REPLAY "+report)
        check(!checks.failed() && !utteranceChecks.failed() && errors==0) { "Replay failed; inspect private aggregate report" }
    } finally {
        try {
            chat.abortAll("replay_finished")
            collector.cancelAndJoin(); actor.close(); scope.cancel()
        } finally { replayBridge.close() }
    }
}
