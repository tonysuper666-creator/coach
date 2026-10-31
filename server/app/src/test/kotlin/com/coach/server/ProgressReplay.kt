package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.LedgerStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Private, separately inspected labels are input, not inferred by the matcher under test. */
fun main() = runBlocking {
    val directory = Path.of(System.getenv("COACH_REPLAY_DIR") ?: "${System.getProperty("user.home")}/Coach/corpus/replay")
    val inputs = directory.resolve("replay-input.jsonl")
    val labelFile = directory.resolve("progress-labels.jsonl")
    require(Files.exists(inputs) && Files.exists(labelFile)) { "缺语料或独立标注，未运行进度回放" }
    val rows = Files.readAllLines(inputs).filter(String::isNotBlank).map { LedgerJson.parseToJsonElement(it).jsonObject }
    val labels = Files.readAllLines(labelFile).filter(String::isNotBlank).map { LedgerJson.parseToJsonElement(it).jsonObject }
        .associate { it.str("id") to it.str("progressIntent").takeIf(String::isNotBlank)?.let(ProgressIntent::valueOf) }
    require(rows.all { labels.containsKey(it.str("id")) }) { "Every utterance needs a separately inspected label" }
    val dir = Files.createTempDirectory("coach-progress-replay-")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = LedgerStore(dir)
    val actor = LedgerActor(scope,store)
    // Nothing in this replay is allowed to make a real AI call.
    val chat = ChatService(actor,scope,BridgeClient(BridgeConfig("http://127.0.0.1:1")),log={})
    val latencies = mutableListOf<Long>()
    var positive = 0; var recognized = 0; var falsePositive = 0; var missed = 0; var answers = 0
    try {
        actor.execute {
            append("course","exam_weights_set",json("weights" to json("kp" to 30,"other" to 70)))
            append("course","kp_opened",json("kpId" to "kp"))
            append("course","kp_overridden",json("kpId" to "kp","kind" to "MARK_PASSED"))
        }
        for (row in rows) {
            val expected = labels.getValue(row.str("id"))
            val text = row.getValue("user").jsonObject.str("content")
            val actual = progressIntent(text)
            if (expected != null) positive++
            if (actual != null) {
                if (actual != expected) falsePositive++ else recognized++
                val today = actor.execute { todayProgress("course") }
                val frame = scope.async(start=CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { chat.hub.frames.first { it.str("t") == "msg.start" } } }
                val started = System.nanoTime()
                val id = chat.ask("replay-thread","course",text)
                assertEquals("projection",frame.await().str("source"))
                latencies += (System.nanoTime()-started)/1_000_000
                assertEquals(today.answer(actual),assertNotNull(chat.snapshot(id)).str("text"))
                assertEquals(today.coverageText,today.answer(ProgressIntent.COVERAGE))
                answers++
            } else if (expected != null) missed++
        }
        val calls = java.sql.DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { connection ->
            connection.createStatement().use { statement -> statement.executeQuery("SELECT count(*) FROM ai_calls").use { rows -> rows.next(); rows.getInt(1) } }
        }
        assertEquals(0,calls)
        val report = json("utterances" to rows.size,"labelled_progress" to positive,"recognized_progress" to recognized,
            "false_positives" to falsePositive,"non_progress" to rows.size-positive,"missed_progress" to missed,
            "projection_answers" to answers,"ai_calls" to calls,"max_answer_ms" to latencies.maxOrNull(),
            "limitation" to "Real utterances, Builder-inspected local labels; Coach state is synthetic. Historical state is unavailable.")
        System.getenv("COACH_PROGRESS_REPORT")?.let { Files.writeString(Path.of(it),report.toString()+"\n") }
        println("PRIVATE_PROGRESS_REPLAY $report")
        assertEquals(0,falsePositive); assertEquals(0,missed)
    } finally {
        actor.close(); scope.cancel(); dir.toFile().deleteRecursively()
    }
}
