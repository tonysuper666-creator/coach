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
        private const val MAX_LESSON_PAGES = 6
        private val pageMarker = Regex("(?m)^[ \\t]*(?:\\*\\*)?〔PDF 第 ([0-9]+) 页[^〕]*〕(?:\\*\\*)?[ \\t]*\\r?\\n")
        private val stopWords = setOf("the", "and", "for", "with", "from", "that", "this", "what", "how", "why", "when", "where", "does", "are", "was", "were", "into", "their", "than", "then", "which", "while", "about", "using", "study", "chapter")

        private data class LessonPage(val pdfPage: Int, val order: Int, val text: String)

        /** Keep the unpaginated KP header, then select the most relevant complete PDF pages. */
        fun boundedInput(source: String, knowledgePoint: JsonObject): String {
            val markers = pageMarker.findAll(source).toList()
            if (markers.isEmpty()) {
                if (source.length <= MAX_LESSON_INPUT_CHARS) return source
                val paragraphEnd = source.lastIndexOf("\n\n", MAX_LESSON_INPUT_CHARS)
                val end = if (paragraphEnd >= MAX_LESSON_INPUT_CHARS * 3 / 4) paragraphEnd else MAX_LESSON_INPUT_CHARS
                return source.substring(0, end).trimEnd()
            }

            val header = source.substring(0, markers.first().range.first)
            if (header.length > MAX_LESSON_INPUT_CHARS) return source.take(MAX_LESSON_INPUT_CHARS)
            val pages = markers.mapIndexed { index, marker ->
                LessonPage(marker.groupValues[1].toInt(), index,
                    source.substring(marker.range.first, markers.getOrNull(index + 1)?.range?.first ?: source.length))
            }
            val terms = lessonTerms(knowledgePoint)
            val documentFrequency = terms.associateWith { term -> pages.count { countOccurrences(it.text, term) > 0 } }
            val anchors = (knowledgePoint["anchors"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val sourceAnchor = (knowledgePoint["sourceAnchor"] as? JsonObject)?.let(::listOf).orEmpty()
            val anchorPages = (anchors + sourceAnchor).mapNotNull { anchor ->
                anchor["pdfPage"]?.jsonPrimitive?.intOrNull
            }.toSet()
            val ranked = pages.sortedWith(compareByDescending<LessonPage> { page ->
                val body = page.text
                val lexical = terms.sumOf { term ->
                    val occurrences = countOccurrences(body, term).coerceAtMost(4)
                    if (occurrences == 0) 0.0 else {
                        val df = documentFrequency.getValue(term)
                        val inverseFrequency = kotlin.math.ln(1.0 + pages.size.toDouble() / (df + 1.0))
                        occurrences * inverseFrequency
                    }
                }
                lexical + if (page.pdfPage in anchorPages) 3.0 else 0.0
            }.thenBy { it.order })

            var used = header.length
            val selected = mutableListOf<LessonPage>()
            for (page in ranked) {
                if (selected.size == MAX_LESSON_PAGES) break
                if (used + page.text.length > MAX_LESSON_INPUT_CHARS) continue
                selected += page
                used += page.text.length
            }
            return header + selected.sortedBy { it.order }.joinToString("") { it.text }
        }

        private fun lessonTerms(knowledgePoint: JsonObject): Set<String> {
            val title = knowledgePoint.str("title").ifBlank { knowledgePoint.str("name") }
            val concepts = ((knowledgePoint["concepts"] as? JsonArray).orEmpty() +
                (knowledgePoint["conceptIds"] as? JsonArray).orEmpty())
                .mapNotNull { (it as? JsonPrimitive)?.content }
                .filterNot { it.matches(Regex("[A-Za-z0-9_-]{16,}")) }
            return Regex("[\\p{IsHan}]{2,}|[\\p{L}][\\p{L}\\p{N}]*").findAll((listOf(title) + concepts).joinToString(" "))
                .flatMap { match ->
                    val token = match.value.lowercase()
                    if (token.first().code in 0x4e00..0x9fff) {
                        if (token.length <= 3) sequenceOf(token) else token.windowed(3).asSequence()
                    } else if (token !in stopWords) sequenceOf(token) else emptySequence()
                }.toSet()
        }

        private fun countOccurrences(text: String, term: String): Int {
            var count = 0
            var offset = 0
            while (offset < text.length) {
                val found = text.indexOf(term, offset, ignoreCase = true)
                if (found < 0) break
                count++
                offset = found + term.length.coerceAtLeast(1)
            }
            return count
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

    private suspend fun metadataOrNull(course: String, kp: String): JsonObject? = actor.execute {
        events(course).asReversed().filter { it.type == "outline_revised" }.firstNotNullOfOrNull { event ->
            val seededPoint = (event.payload["knowledgePoints"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }.firstOrNull { it.str("id") == kp }
            seededPoint ?: (event.payload["ops"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }.firstOrNull { it.str("kpId") == kp }
        }
    }

    private suspend fun metadata(course: String, kp: String): JsonObject =
        metadataOrNull(course, kp) ?: error("Unknown knowledge point")

    suspend fun boundedInput(course: String, kp: String, source: String, fallback: JsonObject): String =
        boundedInput(source, metadataOrNull(course, kp) ?: fallback)

    suspend fun ensure(course: String, kp: String, target: Int = 6) {
        val meta=metadata(course,kp)
        val source=pack(course,kp)
        if(source.isNotBlank()) PoolWorker(actor,bridge).ensure(course,kp,meta.str("title").ifBlank { meta.str("name") },source.take(40_000),target,background=true)
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
            worker.ensure(course,kp,meta.str("title").ifBlank { meta.str("name") },source.take(40_000),6,background=false)
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
        val source=boundedInput(fullSource,meta)
        val worker=PoolWorker(actor,bridge)
        var steps=0
        while(actor.execute { poolAvailable(course,kp) } < 6 && steps < 8) {
            worker.ensure(course,kp,meta.str("title").ifBlank { meta.str("name") },fullSource.take(40_000),6,background=false)
            val step=worker.runOne(course,kp) ?: break
            steps++
            if(step.state=="stale") break
        }
        if(cached(course,kp,fullSource)==null) {
            val concepts=(meta["conceptIds"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: setOf(kp)
            val plan=TutorReplyPlan(TutorThread(course,"kp:$kp",kp,concepts,meta.str("title").ifBlank { meta.str("name") }),"preparation",true,concepts,emptySet(),true,TeachingMode.CURRENT)
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
