package com.coach.domain

import com.coach.domain.grade.nfkc
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable data class Option(val optionId: String, val text: String)

/** Answer identity is independent of both model order and display letters. */
data class ChoiceKey(val options: List<Option>, val correctOptionId: String) {
    init {
        require(options.size in 2..26 && options.map { it.optionId }.distinct().size == options.size)
        require(options.all { it.optionId.isNotBlank() && it.text.isNotBlank() })
        require(options.map { normalizeChoiceText(it.text) }.distinct().size == options.size)
        require(options.any { it.optionId == correctOptionId })
    }
}

val CHOICE_LETTERS = listOf("A", "B", "C", "D")
fun optionLetters(count: Int): List<String> {
    require(count in 2..26)
    return ('A'..'Z').take(count).map { it.toString() }
}
fun normalizeChoiceText(text: String): String = nfkc(text).trim().replace(Regex("\\s+"), " ").lowercase()

/** Unambiguous, length-delimited content; neither option IDs nor display order affect the hash. */
fun questionFingerprint(stem: String, options: List<Option>): String = sha256Text(
    (listOf(normalizeChoiceText(stem)) + options.map { normalizeChoiceText(it.text) }.sorted())
        .joinToString("") { "${it.length}:$it" }
)
internal expect fun sha256Text(text: String): String

/** One map per question: optionId -> display letter. Legacy questions can have five options. */
fun assignLetters(group: List<ChoiceKey>, previousLastCorrectLetter: String?, rng: Random): List<Map<String, String>> {
    require(group.isNotEmpty() && group.size <= group.minOf { it.options.size }) {
        "A group needs enough letters to give every question a different correct letter"
    }
    require(previousLastCorrectLetter == null || previousLastCorrectLetter in optionLetters(26))
    val used = mutableSetOf<String>()
    return group.mapIndexed { index, question ->
        val letters = optionLetters(question.options.size)
        val correct = letters.filter { it !in used && (index != 0 || it != previousLastCorrectLetter) }.random(rng)
        used.add(correct)
        val remaining = letters.filter { it != correct }.shuffled(rng).iterator()
        question.options.associate { it.optionId to if (it.optionId == question.correctOptionId) correct else remaining.next() }
    }
}

fun validLetterMap(options: List<Option>, letterMap: Map<String, String>): Boolean =
    options.size in 2..26 && letterMap.keys == options.map { it.optionId }.toSet() &&
        letterMap.values.toSet() == optionLetters(options.size).toSet() && letterMap.size == options.size

/** The application builds feedback from the same frozen map used to draw the card. */
fun choiceFeedback(correctOptionId: String, chosenLetter: String, letterMap: Map<String, String>): String {
    val correct = letterMap.getValue(correctOptionId)
    return if (chosenLetter.trim().uppercase() == correct) "回答正确（$correct）。" else "回答错误，正确答案是 $correct。"
}
