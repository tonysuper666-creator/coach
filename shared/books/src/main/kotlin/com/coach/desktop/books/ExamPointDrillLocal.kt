package com.coach.desktop.books

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

internal data class TeachingChoiceOption(
    val letter: String,
    val text: String,
    val correct: Boolean,
    val reason: String,
    val citation: CourseCitationRef,
)

internal data class TeachingChoice(
    val id: String,
    val stem: String,
    val options: List<TeachingChoiceOption>,
)

internal data class EssayOutlineParagraph(
    val claim: String,
    val citation: CourseCitationRef,
)

internal data class EssayOutline(
    val thesis: String,
    val paragraphs: List<EssayOutlineParagraph>,
)

internal data class ExamPointDrill(
    val choices: List<TeachingChoice>,
    val essay: EssayOutline?,
    val missingChoices: Int,
    val essayMissing: Boolean,
    val discards: Map<String, Int>,
    val checked: Int,
    val agreed: Int,
    val emptyReason: String?,
    val generatorModel: String = "",
    val checkerModel: String = "",
)

internal data class ExamPointDrillReport(
    val pointCount: Int,
    val questionsPerPoint: List<Int>,
    val checked: Int,
    val agreed: Int,
    val discards: Map<String, Int>,
    val zeroNotes: List<String>,
    val neverGenerated: Int = 0,
    val droppedByCheck: Int = 0,
) {
    fun summary(): String {
        val rate = "$agreed/$checked"
        val dropped = discards.values.sum()
        val histogram = if (discards.isEmpty()) "无" else discards.entries.joinToString(",") { "${it.key}:${it.value}" }
        val zeros = if (zeroNotes.isEmpty()) "0" else zeroNotes.joinToString("；")
        val counts = if (questionsPerPoint.isEmpty()) "无" else questionsPerPoint.joinToString("/")
        return "考点${pointCount}个，每考点题数$counts，一致$rate，未入库$dropped（$histogram），从未生成$neverGenerated，核对丢掉$droppedByCheck，零题：$zeros"
    }
}

/** Local copy of an admitted drill. Citations are checked again against the pages on this computer. */
internal object ExamPointDrillLocal {
    fun statusLine(drill: ExamPointDrill?): String? {
        drill ?: return null
        val essayPart = when {
            drill.essay != null -> "有论述提纲"
            drill.essayMissing -> "论述提纲未通过核对"
            else -> null
        }
        if (drill.choices.isEmpty()) {
            val why = drill.emptyReason ?: "这个考点还没有可用的题"
            val gap = if (drill.missingChoices > 0) "$why · 缺 ${drill.missingChoices} 题" else why
            return listOfNotNull(gap, essayPart).joinToString(" · ")
        }
        val choicePart = if (drill.missingChoices > 0) "缺 ${drill.missingChoices} 题" else "${drill.choices.size} 道单选"
        return listOfNotNull(choicePart, essayPart).joinToString(" · ")
    }

    fun preserve(next: CourseTopicPack, previous: CourseTopicPack?): CourseTopicPack =
        if (next.drill == null && previous?.drill != null) next.copy(drill = previous.drill) else next

    fun verify(drill: ExamPointDrill, pagesByBook: Map<String, List<BookPage>>): ExamPointDrill {
        var dropped = 0
        val kept = drill.choices.mapNotNull { choice ->
            val verified = choice.options.map { opt ->
                CourseClientGates.verifySingleCitation(opt.citation, pagesByBook[opt.citation.bookId].orEmpty())
            }
            if (verified.all { it != null }) {
                choice.copy(options = choice.options.zip(verified) { opt, citation -> opt.copy(citation = citation!!) })
            } else {
                dropped += 1
                null
            }
        }
        var essay = drill.essay
        var essayMissing = drill.essayMissing
        if (essay != null) {
            val verified = essay.paragraphs.map { paragraph ->
                CourseClientGates.verifySingleCitation(paragraph.citation, pagesByBook[paragraph.citation.bookId].orEmpty())
            }
            if (verified.all { it != null }) {
                essay = essay.copy(paragraphs = essay.paragraphs.zip(verified) { paragraph, citation ->
                    paragraph.copy(citation = citation!!)
                })
            } else {
                essay = null
                essayMissing = true
                dropped += 1
            }
        }
        val discards = if (dropped == 0) {
            drill.discards
        } else {
            drill.discards + ("source_mismatch" to ((drill.discards["source_mismatch"] ?: 0) + dropped))
        }
        val missing = (3 - kept.size).coerceAtLeast(0)
        val emptyReason = when {
            kept.isNotEmpty() -> null
            !drill.emptyReason.isNullOrBlank() -> drill.emptyReason
            else -> "引用对不上原文，这些题没有留下"
        }
        return drill.copy(
            choices = kept,
            essay = essay,
            missingChoices = missing,
            essayMissing = essayMissing,
            discards = discards,
            emptyReason = emptyReason,
        )
    }

    fun report(points: List<CourseTopicEntry>): ExamPointDrillReport {
        val examPoints = points.filter { it.weekLabel == CourseExamPoints.LABEL }
        val counts = examPoints.map { it.pack?.drill?.choices?.size ?: 0 }
        val drills = examPoints.mapNotNull { it.pack?.drill }
        val discards = linkedMapOf<String, Int>()
        drills.forEach { drill ->
            drill.discards.forEach { (reason, count) -> discards[reason] = (discards[reason] ?: 0) + count }
        }
        var neverGenerated = 0
        var droppedByCheck = 0
        val zeros = examPoints.mapIndexedNotNull { index, point ->
            val drill = point.pack?.drill
            if (drill != null && drill.choices.isNotEmpty()) return@mapIndexedNotNull null
            val attempted = drill != null && (
                drill.checked > 0 || drill.discards.isNotEmpty() || !drill.emptyReason.isNullOrBlank()
            )
            if (attempted) droppedByCheck += 1 else neverGenerated += 1
            val why = drill?.emptyReason?.takeIf { it.isNotBlank() } ?: "还没出题"
            "第${index + 1}个：$why"
        }
        return ExamPointDrillReport(
            pointCount = examPoints.size,
            questionsPerPoint = counts,
            checked = drills.sumOf { it.checked },
            agreed = drills.sumOf { it.agreed },
            discards = discards,
            zeroNotes = zeros,
            neverGenerated = neverGenerated,
            droppedByCheck = droppedByCheck,
        )
    }

    fun encode(drill: ExamPointDrill): JsonObject = buildJsonObject {
        put("choices", buildJsonArray {
            for (choice in drill.choices) {
                add(buildJsonObject {
                    put("id", choice.id)
                    put("stem", choice.stem)
                    put("options", buildJsonArray {
                        for (opt in choice.options) {
                            add(buildJsonObject {
                                put("letter", opt.letter)
                                put("text", opt.text)
                                put("correct", opt.correct)
                                put("reason", opt.reason)
                                put("citation", citationJson(opt.citation))
                            })
                        }
                    })
                })
            }
        })
        if (drill.essay != null) {
            put("essay", buildJsonObject {
                put("thesis", drill.essay.thesis)
                put("paragraphs", buildJsonArray {
                    for (paragraph in drill.essay.paragraphs) {
                        add(buildJsonObject {
                            put("claim", paragraph.claim)
                            put("citation", citationJson(paragraph.citation))
                        })
                    }
                })
            })
        }
        put("missingChoices", drill.missingChoices)
        put("essayMissing", drill.essayMissing)
        put("discards", buildJsonObject { drill.discards.forEach { (reason, count) -> put(reason, count) } })
        put("checked", drill.checked)
        put("agreed", drill.agreed)
        drill.emptyReason?.let { put("emptyReason", it) }
        put("generatorModel", drill.generatorModel)
        put("checkerModel", drill.checkerModel)
    }

    fun decode(body: JsonObject): ExamPointDrill? {
        if (body["choices"] !is JsonArray && body["missingChoices"] == null && body["emptyReason"] == null) return null
        val choices = (body["choices"] as? JsonArray).orEmpty().mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val options = (obj["options"] as? JsonArray).orEmpty().mapNotNull { optEl ->
                val opt = optEl as? JsonObject ?: return@mapNotNull null
                val citation = decodeCitation(opt["citation"] as? JsonObject) ?: return@mapNotNull null
                TeachingChoiceOption(
                    letter = opt.str("letter").trim().uppercase(),
                    text = opt.str("text").trim(),
                    correct = (opt["correct"] as? JsonPrimitive)?.contentOrNull == "true",
                    reason = opt.str("reason").trim(),
                    citation = citation,
                )
            }
            if (options.size != 4) return@mapNotNull null
            TeachingChoice(id = obj.str("id"), stem = obj.str("stem").trim(), options = options)
        }
        val essayObj = body["essay"] as? JsonObject
        val paragraphs = (essayObj?.get("paragraphs") as? JsonArray).orEmpty().mapNotNull { el ->
            val row = el as? JsonObject ?: return@mapNotNull null
            val citation = decodeCitation(row["citation"] as? JsonObject) ?: return@mapNotNull null
            EssayOutlineParagraph(claim = row.str("claim").trim(), citation = citation)
        }
        val thesis = essayObj?.str("thesis").orEmpty().trim()
        val essay = if (essayObj != null && thesis.isNotBlank() && paragraphs.size in 2..6 &&
            paragraphs.size == ((essayObj["paragraphs"] as? JsonArray)?.size ?: 0)
        ) {
            EssayOutline(thesis, paragraphs)
        } else {
            null
        }
        val serverMissing = body.int("missingChoices")
        val missing = maxOf(serverMissing, (3 - choices.size).coerceAtLeast(0))
        val essayMissing = body.bool("essayMissing") || (essayObj != null && essay == null)
        return ExamPointDrill(
            choices = choices,
            essay = essay,
            missingChoices = missing,
            essayMissing = essayMissing,
            discards = (body["discards"] as? JsonObject)?.entries?.mapNotNull { (key, value) ->
                val count = (value as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                key to count
            }?.toMap().orEmpty(),
            checked = body.int("checked"),
            agreed = body.int("agreed"),
            emptyReason = (body["emptyReason"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            generatorModel = body.str("generatorModel"),
            checkerModel = body.str("checkerModel"),
        )
    }

    private fun citationJson(citation: CourseCitationRef): JsonObject =
        CourseJson.encodeCitations(listOf(citation)).first().jsonObject

    private fun decodeCitation(raw: JsonObject?): CourseCitationRef? {
        if (raw == null) return null
        return CourseJson.decodeCitations(buildJsonArray { add(raw) }).firstOrNull()
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.contentOrNull == "true"
}
