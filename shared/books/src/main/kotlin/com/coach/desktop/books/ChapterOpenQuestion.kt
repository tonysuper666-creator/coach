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

    fun buildGenerateRequest(bookId: String, chapterId: String, blocks: List<SourceBlock>): JsonObject {
        var remaining = 8_000
        val chosen = ArrayList<SourceBlock>()
        for (block in blocks) {
            if (block.heading || block.text.isBlank() || remaining <= 0) continue
            if (chosen.size >= 12) break
            chosen += block
            remaining -= block.text.length.coerceAtMost(900)
        }
        return buildJsonObject {
            put("bookId", bookId)
            put("chapterId", chapterId)
            put("blocks", buildJsonArray {
                chosen.forEach { block ->
                    add(buildJsonObject {
                        put("id", block.id)
                        put("page", block.page)
                        put("text", block.text.take(900))
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
        blocks: List<SourceBlock>,
    ): JsonObject {
        val wanted = retryIds.toSet()
        val points = if (wanted.isEmpty()) question.points else question.points.filter { it.id in wanted }
        val pages = points.map { it.page }.toSet()
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
                    add(buildJsonObject {
                        put("cid", point.id)
                        put("slotLabel", point.claim)
                        put("maxMarks", 1)
                        put("pdfPage", point.page)
                        put("quoteAnchor", point.quoteAnchor)
                    })
                }
            })
            put("sourcePages", buildJsonArray {
                blocks.filter { it.page in pages && !it.heading && it.text.isNotBlank() }
                    .distinctBy { it.id }
                    .take(12)
                    .forEach { block ->
                        add(buildJsonObject {
                            put("pdfPage", block.page)
                            put("text", block.text.take(1_200))
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
