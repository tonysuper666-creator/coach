package com.coach.client

import com.coach.domain.*
import kotlinx.serialization.json.*

const val MISTAKE_CLEAR_STREAK = 3

data class Mistake(val attemptId: String, val kpId: String, val card: QuizCard,
    val correctAnswer: String, val reason: String) {
    val reasonSentence: String get() {
        val line = reason.lineSequence().firstOrNull().orEmpty().trim()
        val end = Regex("[。！？!?]|\\.\\s+(?=[A-Z])").find(line)?.range?.first
        return if (end == null) line else line.take(end + 1)
    }
}

data class MistakeReview(val items: List<Mistake>, val hadMistakes: Boolean) {
    val emptyText: String get() = if (hadMistakes) "错题都已掌握，继续保持。" else "还没有答错的题"
}

/** Rebuilt from the replica. Final reviews apply in answer order, so a late correction
 * cannot masquerade as another successful answer. Voided/uncertain evidence never clears mistakes. */
fun mistakeReview(input: List<Event>): MistakeReview {
    val events = input.distinctBy { it.id }.sortedBy { it.seq }
    val posed = events.filter { it.type == "question_posed" }.associateBy { it.payload.str("attemptId") }
    val answers = events.filter { it.type == "answer_submitted" }.distinctBy { it.payload.str("attemptId") }
    val grades = mutableMapOf<String, JsonObject>()
    val confirmed = mutableMapOf<String, Boolean>()
    val voided = events.filter { it.type in setOf("attempt_voided", "answer_orphaned") }.map { it.payload.str("attemptId") }.toSet()
    val hinted = events.filter { it.type == "hint_revealed" }.map { it.payload.str("attemptId") }.toSet()
    for (e in events) {
        val p = e.payload; val id = p.str("attemptId")
        when (e.type) {
            "attempt_graded" -> { grades[id] = p; confirmed[id] = p.str("status", p.str("grader")) in setOf("EXACT", "VERIFIED") }
            "grade_reviewed" -> {
                confirmed[id] = p.str("decision") in setOf("CONFIRM", "REVISE")
                (p["revised"] as? JsonObject)?.let { grades[id] = JsonObject(grades[id].orEmpty() + it) }
            }
        }
    }
    val outstanding = linkedMapOf<Pair<String, String>, Mistake>()
    val streak = mutableMapOf<String, Int>()
    val seenCorrect = mutableMapOf<String, MutableSet<String>>()
    var hadMistakes = false
    for (answer in answers) {
        val id = answer.payload.str("attemptId")
        val q = posed[id]?.payload ?: continue
        val g = grades[id]
        val kps = q.strings("kpIds")
        if (id in voided) continue
        if (g == null || confirmed[id] != true || g.num("max") <= 0) {
            kps.forEach { streak[it] = 0; seenCorrect.remove(it) }
            continue
        }
        val correct = g.num("score") == g.num("max")
        val letters = (q["letterMap"] as? JsonObject).orEmpty()
        val options = (q["options"] as? JsonArray).orEmpty().mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            val letter = (letters[o.str("optionId")] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            letter to o.str("text")
        }.sortedBy { it.first }
        val feedback = g.str("feedback")
        // Old ledgers contain the frozen correct letter in feedback; new grades carry structured data.
        val right = g.str("correctAnswer").ifBlank {
            val letter = Regex("正确答案(?:是|为)\\s*([A-Za-z])").find(feedback)?.groupValues?.get(1)?.uppercase()
            options.firstOrNull { it.first == letter }?.let { "${it.first} · ${it.second}" } ?: "原判分未记录正确答案"
        }
        val shownAnswer = answer.payload.str("answer").let { if (q.str("format") == "SINGLE_CHOICE") it.trim().uppercase() else it }
        val card = QuizCard(id, q.str("stem"), options, q.flag("untaught"), shownAnswer,
            CardGrade(correct, g.num("score").toInt(), g.num("max").toInt(), feedback))
        for (kp in kps) {
            if (!correct) {
                hadMistakes = true
                streak[kp] = 0; seenCorrect.remove(kp)
                outstanding[kp to id] = Mistake(id, kp, card, right,
                    g.str("rationale").ifBlank { feedback.ifBlank { "按本题已复核答案判分。" } })
            } else {
                val fresh = seenCorrect.getOrPut(kp) { mutableSetOf() }.add(q.str("questionId").ifBlank { id })
                if (id in hinted || !fresh) { streak[kp] = 0; continue }
                streak[kp] = (streak[kp] ?: 0) + 1
                if (streak.getValue(kp) >= MISTAKE_CLEAR_STREAK) outstanding.keys.removeAll { it.first == kp }
            }
        }
    }
    return MistakeReview(outstanding.values.distinctBy { it.attemptId }, hadMistakes)
}
