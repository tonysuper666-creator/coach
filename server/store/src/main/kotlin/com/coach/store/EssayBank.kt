package com.coach.store

import com.coach.domain.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** One `type: "essay"` item from a generated paper or pack (roadmap content format). */
data class EssayPrompt(
    val id: String, val course: String, val packTitle: String, val kind: String, val week: Int, val kp: String,
    val marks: Double, val wordTarget: Int, val stem: String, val rubric: JsonArray, val modelAnswer: String, val source: String,
)

/**
 * Generated papers and packs live in a private directory outside the repo (they are built from past papers).
 * Files are re-read on every request so a new pack needs no restart; a malformed file is skipped, never fatal.
 */
class EssayBank(private val root: Path?) {
    fun prompts(courseName: String): List<EssayPrompt> {
        if (root == null || !Files.isDirectory(root)) return emptyList()
        val files = Files.walk(root, 2).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }.sorted().toList() }
        return files.flatMap { runCatching { parse(it) }.getOrDefault(emptyList()) }
            .filter { it.course.isNotBlank() && courseName.trim().startsWith(it.course.trim(), ignoreCase = true) }
            // Practice packs first; essays inside a full mock paper come after, so the mock can stay unseen.
            .sortedBy { if (it.kind == "paper") 1 else 0 }
            .distinctBy { it.id }
    }

    private fun parse(file: Path): List<EssayPrompt> {
        val pack = LedgerJson.parseToJsonElement(Files.readString(file)).jsonObject
        val packId = pack.str("id").ifBlank { file.fileName.toString().removeSuffix(".json") }
        val sections = pack["sections"] as? JsonArray ?: return emptyList()
        return sections.flatMap { (it as? JsonObject)?.get("items") as? JsonArray ?: JsonArray(emptyList()) }.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val stem = item.str("stem").trim()
            val week = item.num("kp_week").toInt()
            if (item.str("type") != "essay" || stem.isBlank() || week < 1 || item.str("id").isBlank()) return@mapNotNull null
            EssayPrompt("$packId/${item.str("id")}", pack.str("course"), pack.str("title").ifBlank { packId }, pack.str("kind"), week,
                item.str("kp"), (item["marks"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 } ?: 20.0,
                item.num("word_target", Essay.WORD_TARGET.toLong()).toInt().takeIf { it > 0 } ?: Essay.WORD_TARGET,
                stem, item["rubric"] as? JsonArray ?: JsonArray(emptyList()), item.str("model_answer"), item.str("source"))
        }
    }
}

/** The four fixed essay criteria, scaled to the item's marks. Item-specific marking points are context only. */
fun essayRubric(marks: Double): JsonArray {
    val text = mapOf(
        "structure" to "Structure: a direct answer first, then definition, mechanism, evidence, evaluation and a one-sentence conclusion, in a logical order.",
        "argument" to "Argument: answers the question actually asked and states the economic mechanism correctly, with no contradictions.",
        "evidence" to "Evidence: cites specific studies (author, year) and at least one number, and says what each result shows for the claim.",
        "example" to "Example: uses a concrete case, policy or numerical illustration that fits the question and is explained, not just named.",
    )
    val points = Essay.CRITERIA.map { (id, weight) -> id to Math.round(marks * weight * 2) / 2.0 }.toMutableList()
    val drift = marks - points.sumOf { it.second }
    points[1] = points[1].first to points[1].second + drift
    return JsonArray(points.map { (id, value) ->
        // No textbook basis: the criteria are the exam's essay template, not a passage the learner could look up.
        json("id" to id, "text" to text.getValue(id), "points" to value, "basis" to "")
    })
}
