package com.coach.desktop.books

import com.coach.domain.grade.ChapterOpenGate
import com.coach.domain.grade.ChapterOpenHistory
import com.coach.domain.grade.ChapterOpenJudgement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class ChapterOpenPoint(
    val id: String,
    val claim: String,
    val blockId: String,
    val page: Int,
    val quoteAnchor: String,
    val startUtf16: Int,
    val endUtf16: Int,
)

internal data class ChapterOpenQuestion(
    val stem: String,
    val points: List<ChapterOpenPoint>,
)

internal data class ChapterOpenAttempt(
    val n: Int,
    val answer: String,
    val items: List<ChapterOpenJudgement>,
)

internal object ChapterOpenJson {
    private val parser = Json { ignoreUnknownKeys = true }

    private fun JsonObject.s(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    fun decodeQuestion(raw: String): ChapterOpenQuestion? {
        if (raw.isBlank()) return null
        val obj = runCatching { parser.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val stem = obj.s("stem")
        val points = (obj["points"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.s("id")
            val claim = o.s("claim")
            val anchor = o.s("quoteAnchor")
            val start = (o["startUtf16"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val end = (o["endUtf16"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            if (id.isBlank() || claim.isBlank() || anchor.isBlank() || end <= start) return@mapNotNull null
            ChapterOpenPoint(
                id = id,
                claim = claim,
                blockId = o.s("blockId"),
                page = (o["page"] as? JsonPrimitive)?.intOrNull ?: 0,
                quoteAnchor = anchor,
                startUtf16 = start,
                endUtf16 = end,
            )
        }
        if (stem.isBlank() || points.size !in ChapterOpenGate.MIN_POINTS..ChapterOpenGate.MAX_POINTS) return null
        return ChapterOpenQuestion(stem, points)
    }

    // Earlier saved questions have block-relative offsets. Verify against the actual page
    // before using a range, including pages whose extracted line endings are CRLF.
    fun ChapterOpenQuestion.withPageOffsets(blocks: List<SourceBlock>, pageText: (Int) -> String?): ChapterOpenQuestion =
        copy(points = points.map { point ->
            val page = pageText(point.page) ?: return@map point
            val block = blocks.firstOrNull { it.id == point.blockId && it.page == point.page } ?: return@map point
            if (point.startUtf16 >= block.start && point.endUtf16 - point.startUtf16 == point.quoteAnchor.length &&
                page.startsWith(point.quoteAnchor, point.startUtf16)
            ) return@map point
            val local = block.text.indexOf(point.quoteAnchor)
            if (local < 0 || block.start !in page.indices) return@map point
            val offset = block.start + local
            val range = if (page.startsWith(point.quoteAnchor, offset)) {
                offset until offset + point.quoteAnchor.length
            } else {
                val pattern = point.quoteAnchor.split(Regex("""\s+""")).joinToString("""\s+""") { Regex.escape(it) }
                val found = Regex(pattern).find(page, block.start) ?: return@map point
                if (found.range.first > block.end + block.text.count { it == '\n' }) return@map point
                found.range
            }
            point.copy(startUtf16 = range.first, endUtf16 = range.last + 1, quoteAnchor = page.substring(range))
        })

    fun encodeQuestion(question: ChapterOpenQuestion): String = buildJsonObject {
        put("stem", question.stem)
        put("points", buildJsonArray {
            question.points.forEach { point ->
                add(buildJsonObject {
                    put("id", point.id)
                    put("claim", point.claim)
                    put("blockId", point.blockId)
                    put("page", point.page)
                    put("quoteAnchor", point.quoteAnchor)
                    put("startUtf16", point.startUtf16)
                    put("endUtf16", point.endUtf16)
                })
            }
        })
    }.toString()

    fun decodeAttempts(raw: String): List<ChapterOpenAttempt> {
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { parser.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val items = (o["items"] as? JsonArray).orEmpty().mapNotNull { itemEl ->
                val item = itemEl as? JsonObject ?: return@mapNotNull null
                val id = item.s("id")
                if (id.isBlank()) return@mapNotNull null
                ChapterOpenJudgement(
                    id = id,
                    verdict = item.s("verdict").ifBlank { "ZERO" },
                    reason = item.s("reason"),
                    missCause = item.s("missCause"),
                )
            }
            ChapterOpenAttempt(
                n = (o["n"] as? JsonPrimitive)?.intOrNull ?: 0,
                answer = o.s("answer"),
                items = items,
            )
        }
    }

    fun encodeAttempts(attempts: List<ChapterOpenAttempt>): String = buildJsonArray {
        attempts.forEach { attempt ->
            add(buildJsonObject {
                put("n", attempt.n)
                put("answer", attempt.answer)
                put("items", buildJsonArray {
                    attempt.items.forEach { item ->
                        add(buildJsonObject {
                            put("id", item.id)
                            put("verdict", item.verdict)
                            put("reason", item.reason)
                            put("missCause", item.missCause)
                        })
                    }
                })
            })
        }
    }.toString()

    fun historyOf(attempts: List<ChapterOpenAttempt>): ChapterOpenHistory =
        attempts.fold(ChapterOpenHistory()) { history, attempt -> history.add(attempt.items) }

    fun missedIds(attempt: ChapterOpenAttempt?): List<String> =
        attempt?.items?.filter { it.verdict != "FULL" && it.verdict != "PARTIAL" }?.map { it.id }.orEmpty()

    fun buildGenerateRequest(
        bookId: String,
        chapterId: String,
        blocks: List<SourceBlock>,
        pageText: (Int) -> String?,
    ): JsonObject {
        val prose = blocks.filter { !it.heading && it.text.isNotBlank() }
        val count = minOf(12, prose.size)
        val chosen = if (count == prose.size) prose else List(count) { index ->
            prose[(index.toLong() * prose.lastIndex / (count - 1)).toInt()]
        }
        val limit = minOf(900, 8_000 / count.coerceAtLeast(1))
        return buildJsonObject {
            put("bookId", bookId)
            put("chapterId", chapterId)
            put("blocks", buildJsonArray {
                chosen.forEach { block ->
                    val page = pageText(block.page) ?: return@forEach
                    val end = minOf(block.end, page.length)
                    if (block.start !in 0 until end) return@forEach
                    val excerpt = page.substring(block.start, block.start + minOf(limit, end - block.start))
                        .dropLastWhile { it.isHighSurrogate() }
                    add(buildJsonObject {
                        put("id", block.id)
                        put("page", block.page)
                        put("startUtf16", block.start)
                        put("text", excerpt)
                    })
                }
            })
        }
    }

    fun buildGradeRequest(
        bookId: String,
        chapterId: String,
        question: ChapterOpenQuestion,
        answer: String,
        retryIds: List<String>,
        pageText: (Int) -> String?,
    ): JsonObject {
        val wanted = retryIds.toSet()
        val points = if (wanted.isEmpty()) question.points else question.points.filter { it.id in wanted }
        val sentences = points.associateWith { point ->
            val page = pageText(point.page).orEmpty()
            ChapterOpenGate.sentenceAround(page, point.quoteAnchor, point.startUtf16)
        }
        return buildJsonObject {
            put("mode", "CHAPTER_OPEN")
            put("bookId", bookId)
            put("chapterId", chapterId)
            put("questionId", chapterId)
            put("questionStem", question.stem)
            put("studentAnswer", answer)
            put("generateVariant", false)
            if (wanted.isNotEmpty()) {
                put("retryPointIds", buildJsonArray { wanted.forEach { add(JsonPrimitive(it)) } })
            }
            put("rubricCriteria", buildJsonArray {
                points.forEach { point ->
                    val sentence = sentences.getValue(point)
                    add(buildJsonObject {
                        put("cid", point.id)
                        put("slotLabel", point.claim)
                        put("maxMarks", 1)
                        put("pdfPage", point.page)
                        put("quoteAnchor", point.quoteAnchor)
                        if (sentence.isNotBlank()) put("sourceExcerpt", sentence)
                    })
                }
            })
            put("sourcePages", buildJsonArray {
                sentences.forEach { (point, sentence) ->
                    if (sentence.isNotBlank()) add(buildJsonObject {
                        put("pdfPage", point.page)
                        put("text", sentence)
                        put("authorityRank", 1)
                    })
                }
            })
        }
    }

    fun judgementsFrom(response: PracticeGradeDiagnoseResponse): List<ChapterOpenJudgement> =
        response.rubricItems.map { item ->
            ChapterOpenJudgement(
                id = item.cid,
                verdict = item.verdict.ifBlank { "ZERO" },
                reason = item.feedbackNoteZh,
                missCause = item.missCause,
            )
        }
}
