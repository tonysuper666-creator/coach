package com.coach.server

import com.coach.ai.*
import com.coach.core.*
import com.coach.domain.*
import com.coach.store.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.time.Instant

/** Local course assets only. A prepared lesson never marks a concept taught until displayed. */
class LessonPreparation(private val actor: LedgerActor, private val bridge: BridgeClient, private val packs: Path) {
    companion object {
        const val MAX_LESSON_INPUT_CHARS = 15_000
        fun boundedInput(source: String): String {
            if (source.length <= MAX_LESSON_INPUT_CHARS) return source
            val limit = MAX_LESSON_INPUT_CHARS
            val paragraphEnd = source.lastIndexOf("\n\n", limit)
            val end = if (paragraphEnd >= limit * 3 / 4) paragraphEnd else limit
            return source.substring(0, end).trimEnd()
        }
        // Preserve the existing full-pack digest so prewarmed caches remain importable.
        internal fun cacheHash(source: String): String = sha256(source.toByteArray())
    }
    data class Prepared(val text: String, val model: String)
    private fun path(course: String, kp: String, cache: Boolean = false): Path {
        val id=Regex("[A-Za-z0-9_-]+")
        require(id.matches(course) && id.matches(kp)) { "Invalid course asset ID" }
        return (if(cache) packs.resolveSibling("prepared-lessons") else packs).resolve(course).resolve(kp + if(cache) ".json" else ".md")
    }
    fun pack(course: String, kp: String): String = path(course,kp).let { if(Files.isRegularFile(it)) Files.readString(it) else "" }
    fun cached(course: String, kp: String, source: String): Prepared? = runCatching {
        val row=LedgerJson.parseToJsonElement(Files.readString(path(course,kp,true))).jsonObject
        if(row.str("packHash") != cacheHash(source) || row.str("text").isBlank() || row.str("model").isBlank()) null
        else Prepared(row.str("text"),row.str("model"))
    }.getOrNull()

    private suspend fun metadata(course: String, kp: String): JsonObject = actor.execute {
        events(course).asReversed().filter { it.type=="outline_revised" }.flatMap {
            (it.payload["ops"] as? JsonArray).orEmpty()
        }.map { it.jsonObject }.firstOrNull { it.str("kpId")==kp } ?: error("Unknown knowledge point")
    }

    suspend fun ensure(course: String, kp: String, target: Int = 6) {
        val meta=metadata(course,kp)
        val source=pack(course,kp)
        if(source.isNotBlank()) PoolWorker(actor,bridge).ensure(course,kp,meta.str("title"),source.take(40_000),target,background=true)
    }

    /** Bounded pool-only prewarm through the running server's actor; no second DB writer. */
    suspend fun prepareQuestions(course: String, kp: String): JsonObject {
        val meta=metadata(course,kp)
        val source=pack(course,kp)
        require(source.isNotBlank()) { "教材备课包缺失" }
        val worker=PoolWorker(actor,bridge)
        val before=actor.execute { supplyCallCount() }
        val started=System.nanoTime()
        var batches=0
        while(actor.execute { poolAvailable(course,kp) } < 6 && batches < 6) {
            worker.ensure(course,kp,meta.str("title"),source.take(40_000),6,background=false)
            while(true) {
                val step=worker.runOne(course,kp) ?: break
                if(step.state in setOf("failed","stale")) break
            }
            batches++
        }
        val available=actor.execute { poolAvailable(course,kp) }
        return json("kpId" to kp,"available" to available,"target" to 6,"reached" to (available>=6),
            "physicalCalls" to (actor.execute { supplyCallCount() }-before),
            "wallMs" to (System.nanoTime()-started)/1_000_000)
    }

    suspend fun prepare(course: String, kp: String): JsonObject {
        val meta=metadata(course,kp)
        val fullSource=pack(course,kp)
        require(fullSource.isNotBlank()) { "教材备课包缺失" }
        val source=boundedInput(fullSource)
        val worker=PoolWorker(actor,bridge)
        var steps=0
        while(actor.execute { poolAvailable(course,kp) } < 6 && steps < 8) {
            worker.ensure(course,kp,meta.str("title"),fullSource.take(40_000),6,background=false)
            val step=worker.runOne(course,kp) ?: break
            steps++
            if(step.state=="stale") break
        }
        if(cached(course,kp,fullSource)==null) {
            val concepts=(meta["conceptIds"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: setOf(kp)
            val plan=TutorReplyPlan(TutorThread(course,"kp:$kp",kp,concepts,meta.str("title")),"preparation",true,concepts,emptySet(),true,TeachingMode.CURRENT)
            val context=TutorOutputGuard.modelInput(source) + "\n" + plan.instruction()
            val result=withContext(Dispatchers.IO) { bridge.chat(teachRequest("请讲解当前知识点。",context).copy(task="PREPARE_TEACH",stream=false)) }
            actor.execute { recordAiCall(result.call.toJson(),"foreground") }
            require(result is BridgeResult.Success && result.text.isNotBlank()) { "预生成讲解失败；保留实时讲解" }
            val file=path(course,kp,true)
            Files.createDirectories(file.parent)
            val temp=Files.createTempFile(file.parent,".lesson-",".tmp")
            try {
                Files.writeString(temp,json("packHash" to cacheHash(fullSource),"text" to result.text,
                    "model" to result.call.servedBy,"createdAt" to Instant.now().toString()).toString())
                Files.move(temp,file,ATOMIC_MOVE,REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
        }
        return json("kpId" to kp,"available" to actor.execute { poolAvailable(course,kp) },"prepared" to (cached(course,kp,fullSource)!=null))
    }
}
