package com.coach.server

import com.coach.ai.*
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Private textbook pages, production orchestration, stub model. Never claims to measure model quality. */
fun main(): Unit = runBlocking {
    val corpus = Path.of(System.getenv("COACH_SOURCE_CORPUS") ?: Path.of(System.getProperty("user.home"),"Coach/corpus/textbooks").toString())
    require(Files.isDirectory(corpus)) { "缺语料，未运行" }
    val root = Path.of(System.getenv("COACH_SOURCE_OUT") ?: Path.of(System.getProperty("user.home"),"Coach/corpus/source-lookup/${System.currentTimeMillis()}").toString())
    Files.createDirectories(root)
    val pages = Files.walk(corpus).use { files -> files.filter { it.toString().endsWith(".md") && tutorSourceAllowed(it.toString()) }.sorted().flatMap { file ->
        val text = Files.readString(file)
        val marks = Regex("<!-- PDF 第 ([0-9]+) 页[^>]*-->").findAll(text).toList()
        marks.mapIndexed { i,m -> SourcePage(file.fileName.toString(),m.groupValues[1].toInt(),text.substring(m.range.last+1,marks.getOrNull(i+1)?.range?.first ?: text.length)) }.stream()
    }.filter { it.text.isNotBlank() }.toList() }
    require(pages.size >= 20) { "缺教材页，未运行" }
    val questions = pages.filter { it.text.length < 8_000 }.distinctBy { it.docId }.take(20).mapIndexed { i,p ->
        val term = Regex("[A-Za-z]{8,}").find(p.text)?.value ?: error("No searchable word")
        Triple("case-$i","Explain $term",p)
    }
    require(questions.size == 20)
    val rows = mutableListOf<String>()
    fun record(vararg fields: Pair<String,Any?>) { val line=json(*fields).toString(); rows += line; println("SOURCE_REPLAY $line") }
    record("mode" to "private textbook + stub model; generated questions", "pages" to pages.size)
    val baseline = mutableListOf<Long>()
    val enriched = mutableListOf<Long>()
    var displayedValid = 0
    val lookupDurations = mutableListOf<Long>()
    for (enabled in listOf(false,true)) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(root.resolve(if(enabled) "feature" else "baseline"))
        pages.forEach { store.registerSourcePage("c",it.docId,it.docId,it.pdfPage,it.text) }
        val actor = LedgerActor(scope,store)
        StubTutor().use { stub ->
            val chat = ChatService(actor,scope,BridgeClient(BridgeConfig(stub.baseUrl)),allowSourceLookup=true,
                sourceLookupProtected={false},enrichSourcePages=enabled,log={ line ->
                    Regex("COACH source-lookup .*durationMs=([0-9]+)").find(line)?.let { lookupDurations += it.groupValues[1].toLong() }
                })
            val first = ConcurrentHashMap<String,Long>()
            val watch = scope.launch(start=CoroutineStart.UNDISPATCHED) { chat.hub.frames.collect { f ->
                if (f.str("t") == "msg.delta") first.putIfAbsent(f.str("msg"),System.nanoTime())
            } }
            suspend fun await(id: String): String = withTimeout(10_000) {
                while (true) {
                    val snapshot=chat.snapshot(id)!!
                    if (snapshot.str("status") != "streaming") return@withTimeout snapshot.str("text")
                    delay(2)
                }
                error("unreachable")
            }
            // Same 20 questions, no marker: measure the extra local retrieval before first visible text.
            for ((label,q,p) in questions) {
                stub.stream(1,"Answer from the pack.",StubTutor.terminal("complete"),StubTutor.DONE)
                val start=System.nanoTime()
                val id=chat.ask(label,"c",q,p.text)
                await(id)
                withTimeout(1000) { while (first[id] == null) delay(2) }
                val ms=(first.getValue(id)-start)/1_000_000
                (if(enabled) enriched else baseline) += ms
                record("case" to label,"enrichment" to enabled,"ttftMs" to ms)
            }
            if (enabled) for ((i,case) in questions.withIndex()) {
                val (label,q,p)=case
                val outside=i>=10
                val hits=actor.execute { searchSourcePages("c",q.removePrefix("Explain ")) }
                val (anchor,quote) = SourcePack.prepare("",hits).pages.mapIndexedNotNull { index,page ->
                    Regex("[A-Za-z][A-Za-z ,;:()0-9-]{7,120}").findAll(page.text)
                        .firstOrNull { !com.coach.core.TutorOutputGuard.referencesLetter(it.value) }
                        ?.let { "S${index+1}" to it.value.trim() }
                }.first()
                stub.stream(1,"Initial." + if(outside) "【需查原文:${q.removePrefix("Explain ")}】" else "",StubTutor.terminal("complete"),StubTutor.DONE)
                if(outside) stub.stream(1,"“$quote”【$anchor】\n\n","“invented quotation”【S1】\n\n",StubTutor.terminal("complete"),StubTutor.DONE)
                val text=await(chat.ask("mixed-$label","c",q,if(outside) "Unrelated preparation page." else p.text))
                check(!text.contains("invented") && !text.contains("需查原文"))
                check(text.contains("补充") == outside)
                if(outside) displayedValid++
                record("case" to label,"outsidePack" to outside,"lookupTriggered" to outside,"displayedValid" to if(outside) 1 else 0)
            }
            watch.cancelAndJoin()
        }
        actor.close(); scope.cancel()
    }
    fun median(values: List<Long>)=values.sorted()[values.size/2]
    record("noTriggerBaselineP50Ms" to median(baseline),"noTriggerFeatureP50Ms" to median(enriched),
        "deltaMs" to median(enriched)-median(baseline),"triggerRate" to 0.5,
        "meanLookupMs" to lookupDurations.average(),
        "displayedValidParagraphs" to displayedValid,"displayedCitationPassRate" to 1.0,"realModelCalls" to 0)
    check(median(enriched)-median(baseline) <= 200)
    Files.writeString(root.resolve("metrics.jsonl"),rows.joinToString("\n",postfix="\n"))
}
