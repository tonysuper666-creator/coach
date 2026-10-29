package com.coach.ai

import com.coach.domain.grade.EvidenceCheck
import com.coach.domain.grade.VerbatimEvidence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.text.Normalizer

/** DESIGN §5.2 的任务与它们各自的 prompt / 输出解析 / 确定性闸门。 */

data class Mcq(
    val stem: String,
    val options: Map<String, String>,
    val key: String,
    val explanation: String,
    val sourceQuote: String,
)

data class ReviewVerdict(val id: Int, val answer: String, val defects: List<String>, val note: String)

data class ReviewGroupInput(val kpId: String, val excerpt: String, val firstId: Int, val questions: List<Mcq>)

data class RubricItem(val id: String, val text: String, val points: Double, val basis: String)

data class ConstructedQuestion(
    val kpId: String,
    val stem: String,
    val referenceAnswer: String,
    val rubric: List<RubricItem>,
)

data class GradeItem(val id: String, val points: Double, val reason: String, val evidence: String)

data class DraftGrade(val items: List<GradeItem>, val total: Double, val awarded: Double)

data class CrossReview(val verdict: String, val note: String, val revisedTotal: Double?)

object Prompts {

    val GEN_SYSTEM = "You write exam questions for a university health economics course. Output JSON only."

    fun genMcq(kp: String, excerpt: String, count: Int, avoid: List<String>): String {
        val avoidBlock = if (avoid.isEmpty()) "" else
            "\nDo NOT reuse these stems:\n" + avoid.joinToString("\n") { "- $it" }
        return """
Knowledge point (learning objective): $kp

Source excerpt (textbook; the ONLY allowed basis):
<<<
$excerpt
>>>

Write $count distinct single-answer multiple-choice questions (4 options A-D) that test this knowledge point at midterm level.
Rules: exactly one option is correct; distractors plausible; answerable from the excerpt alone; English.
For each, include "source_quote": a verbatim sentence fragment (>= 8 words) copied from the excerpt that supports the key.$avoidBlock
Return a JSON array: [{"stem":..., "options":{"A":...,"B":...,"C":...,"D":...}, "key":"A|B|C|D", "explanation":..., "source_quote":...}]
        """.trim()
    }

    val REVIEW_SYSTEM = "You are an independent blind reviewer of exam questions. Output JSON only."

    /** 盲复核：只给题干与选项，不给答案键（ADR-0003 决定 4 的「不看键、自己作答再比对」）。 */
    fun blindReview(excerpt: String, questions: List<Mcq>): String {
        val source = if (excerpt.isBlank()) {
            "No source excerpt is provided. Use standard health-economics knowledge and the question text only."
        } else {
            "Source excerpt:\n<<<\n$excerpt\n>>>"
        }
        val items = buildJsonArray {
            questions.forEachIndexed { index, question ->
                add(
                    buildJsonObject {
                        put("id", index)
                        put("stem", question.stem)
                        putJsonObject("options") {
                            question.options.forEach { (letter, text) -> put(letter, text) }
                        }
                    },
                )
            }
        }
        return """
Do not use any tools, shell, or files; answer directly.
You are an independent blind reviewer of exam questions. You are NOT given the answer key.
For each question: solve it yourself, then flag defects.

$source

Questions:
$items

Return JSON only: [{"id":0,"answer":"A|B|C|D|NONE","defects":["ambiguous"|"multiple_correct"|"no_correct"|"not_supported_by_source"|"other: ..."],"note":"..."}]
Use an empty defects list if the question is sound.
        """.trim()
    }

    /** 一次复核多个 KP 的题（Claude 每日 60 次硬上限下的批法）：每个 KP 带自己的原文，题号全局唯一。 */
    fun blindReviewGroup(groups: List<ReviewGroupInput>): String {
        val body = StringBuilder()
        groups.forEachIndexed { groupIndex, group ->
            val source = if (group.excerpt.isBlank()) {
                "No source excerpt is provided. Use standard health-economics knowledge and the question text only."
            } else {
                "Source excerpt:\n<<<\n${group.excerpt}\n>>>"
            }
            val items = buildJsonArray {
                group.questions.forEachIndexed { index, question ->
                    add(
                        buildJsonObject {
                            put("id", group.firstId + index)
                            put("stem", question.stem)
                            putJsonObject("options") {
                                question.options.forEach { (letter, text) -> put(letter, text) }
                            }
                        },
                    )
                }
            }
            body.append("=== Knowledge point ").append(groupIndex + 1).append(" (").append(group.kpId).append(") ===\n")
            body.append(source).append("\n\nQuestions:\n").append(items).append("\n\n")
        }
        return """
Do not use any tools, shell, or files; answer directly.
You are an independent blind reviewer of exam questions. You are NOT given any answer key.
For each knowledge point below you get its own source excerpt and the questions written from that excerpt.
Solve each question yourself using ONLY its own knowledge point's excerpt, then flag defects.
Use the "id" values exactly as given (they are unique across the whole batch).

$body

Return JSON only: [{"id":0,"answer":"A|B|C|D|NONE","defects":["ambiguous"|"multiple_correct"|"no_correct"|"not_supported_by_source"|"other: ..."],"note":"..."}]
Use an empty defects list if the question is sound.
        """.trim()
    }

    fun genConstructed(kp: String, excerpt: String, count: Int): String = """
Knowledge point (learning objective): $kp

Source excerpt (textbook; the ONLY allowed basis):
<<<
$excerpt
>>>

Write $count constructed-response (short essay) exam questions at midterm level, in English, answerable in 3-6 sentences from the excerpt.
For each question also write a reference answer and a frozen rubric: 3-5 rubric items, each with
"id" (R1..), "text" (what a full-credit answer must contain), "points" (sum = 10) and "basis" (verbatim clue from the excerpt).
Return a JSON array: [{"stem":..., "reference_answer":..., "rubric":[{"id":"R1","text":...,"points":3,"basis":...}]}]
    """.trim()

    fun gradeConstructed(question: ConstructedQuestion, answer: String): String {
        val rubric = buildJsonArray {
            question.rubric.forEach { item ->
                add(
                    buildJsonObject {
                        put("id", item.id)
                        put("text", item.text)
                        put("points", item.points)
                    },
                )
            }
        }
        return """
You are grading a student's constructed-response answer for a university health economics course.
Grade ONLY against the frozen rubric below. Do not invent rubric items, do not change their points.

Question: ${question.stem}

Frozen rubric (items and points are fixed):
$rubric

Student answer (verbatim, may contain typos and speech-input errors):
<<<
$answer
>>>

For EVERY rubric item, quote the student's own words that earn the points, as one evidence quote.
Rules for evidence: copy the WHOLE clause verbatim from the student answer, keep typos and speech-input
errors, do not rewrite, do not paraphrase, do not give character offsets. If the answer earns a numeric
final value, quote the number together with its clause. If an item is not earned, use an empty evidence
string and award 0.

Return JSON only:
{"items":[{"id":"R1","points":2,"reason":"...","evidence":"..."}],"total":10,"awarded":6.5,"confidence":0.0-1.0}
    """.trim()
    }

    val GRADE_SYSTEM = "You are a strict grader bound to a frozen rubric. Output JSON only."

    /** ADR-0003 决定 6：证据校验失败时，把原因发回原批改模型修一次（仍失败才算批改不可用）。 */
    fun repairGrade(
        question: ConstructedQuestion,
        answer: String,
        rejected: DraftGrade,
        defects: List<String>,
    ): String {
        val rubric = buildJsonArray {
            question.rubric.forEach { item ->
                add(
                    buildJsonObject {
                        put("id", item.id)
                        put("text", item.text)
                        put("points", item.points)
                    },
                )
            }
        }
        val previous = buildJsonArray {
            rejected.items.forEach { item ->
                add(
                    buildJsonObject {
                        put("id", item.id)
                        put("points", item.points)
                        put("reason", item.reason)
                        put("evidence", item.evidence)
                    },
                )
            }
        }
        return """
Your previous grading output failed the deterministic checks below. Rubric points are FROZEN: do not change
the rubric, do not change the point values, do not add or drop items.

Question: ${question.stem}

Frozen rubric:
$rubric

Student answer (verbatim, may contain typos and speech-input errors):
<<<
$answer
>>>

Your previous items:
$previous

Failures to fix:
${defects.joinToString("\n") { "- $it" }}

How to fix evidence: copy the WHOLE clause verbatim from the student answer, including the punctuation at the end,
keep typos and speech-input errors, do not rewrite, do not paraphrase, do not give character offsets. If the answer
earns a numeric final value, quote the number together with its clause. An item that is not earned gets 0 points and
an empty evidence string.

Return the corrected JSON only:
{"items":[{"id":"R1","points":2,"reason":"...","evidence":"..."}],"total":10,"awarded":6.5,"confidence":0.0-1.0}
        """.trim()
    }

    fun crossReviewGrade(
        question: ConstructedQuestion,
        answer: String,
        draft: DraftGrade,
        evidence: List<String>,
    ): String {
        val rubric = buildJsonArray {
            question.rubric.forEach { item ->
                add(
                    buildJsonObject {
                        put("id", item.id)
                        put("text", item.text)
                        put("points", item.points)
                    },
                )
            }
        }
        val assigned = buildJsonArray {
            draft.items.forEach { item ->
                add(
                    buildJsonObject {
                        put("id", item.id)
                        put("points", item.points)
                        put("evidence", item.evidence)
                    },
                )
            }
        }
        return """
You are a second, independent grader from a different model family. You see the FULL student answer, the frozen
rubric, the graded points and the evidence quotes another grader assigned.

Question: ${question.stem}

Frozen rubric:
$rubric

Full student answer (verbatim):
<<<
$answer
>>>

Proposed grading (item -> points, with quoted evidence):
$assigned

Evidence quotes accepted by the deterministic verbatim checker:
$evidence

Decide: does each awarded point follow from the student's own words under the rubric?
Return JSON only: {"verdict":"CONFIRM"|"REVISE","note":"...","revised_total":<number or null>}
Use CONFIRM only if every item's points are right; use REVISE if any item is over- or under-credited.
        """.trim()
    }

    val CROSS_REVIEW_SYSTEM = "You are an independent second grader. Output JSON only."

    fun reviewConstructedKey(question: ConstructedQuestion, excerpt: String): String = """
You are an independent reviewer from a different model family. Check the reference answer and the rubric
of a constructed-response question against the textbook excerpt.

Source excerpt:
<<<
$excerpt
>>>

Question: ${question.stem}

Reference answer:
${question.referenceAnswer}

Rubric:
${rubricJson(question.rubric)}

Return JSON only: {"verdict":"CONFIRM"|"REVISE","note":"...","rubric_fix":[{"id":"R1","text":...}],"reference_fix":"..."}
CONFIRM only if the reference answer is correct per the excerpt and every rubric item is checkable and worth its points.
    """.trim()

    fun rubricJson(rubric: List<RubricItem>): String = buildJsonArray {
        rubric.forEach { item ->
            add(
                buildJsonObject {
                    put("id", item.id)
                    put("text", item.text)
                    put("points", item.points)
                    put("basis", item.basis)
                },
            )
        }
    }.toString()
}

object JsonText {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun objectOf(text: String): JsonObject {
        val cleaned = stripFence(text)
        val start = cleaned.indexOf('{')
        require(start >= 0) { "no json object in output: ${cleaned.take(200)}" }
        return json.parseToJsonElement(cleaned.substring(start)).jsonObject
    }

    fun arrayOf(text: String): JsonArray {
        val cleaned = stripFence(text)
        val start = cleaned.indexOf('[')
        require(start >= 0) { "no json array in output: ${cleaned.take(200)}" }
        val end = cleaned.lastIndexOf(']')
        require(end > start) { "unterminated json array: ${cleaned.take(200)}" }
        return json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonArray
    }

    private fun stripFence(text: String): String {
        val fence = Regex("```(?:json)?\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL).find(text)
        return (fence?.groupValues?.get(1) ?: text).trim()
    }
}

object TaskParsing {

    fun mcqs(text: String): List<Mcq> = JsonText.arrayOf(text).map { element ->
        val obj = element.jsonObject
        val options = obj["options"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        Mcq(
            stem = obj["stem"]?.jsonPrimitive?.content?.trim() ?: "",
            options = options,
            key = obj["key"]?.jsonPrimitive?.content?.trim()?.uppercase() ?: "",
            explanation = obj["explanation"]?.jsonPrimitive?.content ?: "",
            sourceQuote = obj["source_quote"]?.jsonPrimitive?.content ?: "",
        )
    }

    fun verdicts(text: String): List<ReviewVerdict> = JsonText.arrayOf(text).map { element ->
        val obj = element.jsonObject
        ReviewVerdict(
            id = obj["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1,
            answer = obj["answer"]?.jsonPrimitive?.content?.trim()?.uppercase() ?: "NONE",
            defects = (obj["defects"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
            note = obj["note"]?.jsonPrimitive?.content ?: "",
        )
    }

    fun constructed(text: String): List<ConstructedQuestion> = JsonText.arrayOf(text).map { element ->
        val obj = element.jsonObject
        ConstructedQuestion(
            kpId = obj["kp_id"]?.jsonPrimitive?.content ?: "",
            stem = obj["stem"]?.jsonPrimitive?.content ?: "",
            referenceAnswer = obj["reference_answer"]?.jsonPrimitive?.content ?: "",
            rubric = (obj["rubric"] as? JsonArray)?.map { item ->
                val entry = item.jsonObject
                RubricItem(
                    id = entry["id"]?.jsonPrimitive?.content ?: "",
                    text = entry["text"]?.jsonPrimitive?.content ?: "",
                    points = entry["points"]?.jsonPrimitive?.double ?: 0.0,
                    basis = entry["basis"]?.jsonPrimitive?.content ?: "",
                )
            } ?: emptyList(),
        )
    }

    fun grade(text: String): DraftGrade {
        val obj = JsonText.objectOf(text)
        val items = (obj["items"] as? JsonArray)?.map { element ->
            val entry = element.jsonObject
            GradeItem(
                id = entry["id"]?.jsonPrimitive?.content ?: "",
                points = entry["points"]?.jsonPrimitive?.double ?: 0.0,
                reason = entry["reason"]?.jsonPrimitive?.content ?: "",
                evidence = entry["evidence"]?.jsonPrimitive?.content ?: "",
            )
        } ?: emptyList()
        return DraftGrade(
            items = items,
            total = obj["total"]?.jsonPrimitive?.double ?: items.sumOf { it.points },
            awarded = obj["awarded"]?.jsonPrimitive?.double ?: items.sumOf { it.points },
        )
    }

    fun crossReview(text: String): CrossReview {
        val obj = JsonText.objectOf(text)
        return CrossReview(
            verdict = obj["verdict"]?.jsonPrimitive?.content?.trim()?.uppercase() ?: "REVISE",
            note = obj["note"]?.jsonPrimitive?.content ?: "",
            revisedTotal = (obj["revised_total"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
        )
    }
}

/** 出题侧的确定性闸门：结构、答案键、原文引句（ADR-0003 决定 5）。 */
object QuestionGate {

    private val MARKUP = Regex("[*_`#>]")

    /** 去掉 markdown 强调/代码标记，NFKC，空白归一 —— 只用于引句比对，避免把推导题误弃。 */
    fun normalizeForQuoteCheck(text: String): String =
        Normalizer.normalize(MARKUP.replace(text, ""), Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()

    fun sourceQuoteVerbatim(quote: String, excerpt: String): Boolean {
        val q = normalizeForQuoteCheck(quote)
        if (q.length < 8) return false
        return normalizeForQuoteCheck(excerpt).contains(q)
    }

    fun structural(mcq: Mcq): List<String> {
        val defects = ArrayList<String>()
        if (mcq.stem.length < 15) defects.add("stem_too_short")
        if (mcq.options.size < 4) defects.add("needs_four_options")
        if (mcq.key !in mcq.options) defects.add("key_not_an_option")
        if (mcq.options.values.map { it.trim().lowercase() }.toSet().size != mcq.options.size) defects.add("duplicate_options")
        if (mcq.options.values.any { it.isBlank() }) defects.add("blank_option")
        if (mcq.sourceQuote.isBlank()) defects.add("missing_source_quote")
        return defects
    }

    fun quotedVerbatim(mcq: Mcq, excerpt: String): Boolean = sourceQuoteVerbatim(mcq.sourceQuote, excerpt)
}

/** 批改侧的确定性闸门：rubric 冻结、分数算术、逐字证据。 */
object GradeGate {

    fun rubricFingerprint(rubric: List<RubricItem>): String {
        val canonical = Prompts.rubricJson(rubric)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun arithmetic(draft: DraftGrade, rubric: List<RubricItem>): List<String> {
        val defects = ArrayList<String>()
        val byId = rubric.associateBy { it.id }
        if (draft.items.map { it.id }.toSet() != byId.keys) defects.add("rubric_items_changed")
        for (item in draft.items) {
            val reference = byId[item.id] ?: continue
            if (item.points < 0 || item.points > reference.points) defects.add("points_out_of_range:${item.id}")
        }
        val sum = draft.items.sumOf { it.points }
        if (kotlin.math.abs(sum - draft.awarded) > 1e-6) defects.add("awarded_not_sum_of_items")
        val max = rubric.sumOf { it.points }
        if (kotlin.math.abs(draft.total - max) > 1e-6) defects.add("total_not_rubric_sum")
        return defects
    }

    /** 每条的 evidence 必须在原答里逐字可定位；返回 (缺陷, 通过的区间文本)。 */
    fun evidence(answer: String, draft: DraftGrade): Pair<List<String>, List<String>> {
        val defects = ArrayList<String>()
        val spans = ArrayList<String>()
        for (item in draft.items) {
            if (item.points <= 0.0 && item.evidence.isBlank()) continue
            when (val check = VerbatimEvidence.verify(answer, item.evidence)) {
                is EvidenceCheck.Accepted -> spans.add(check.span.text)
                is EvidenceCheck.Rejected -> defects.add("evidence_${check.reason}:${item.id}")
            }
        }
        return defects to spans
    }
}
