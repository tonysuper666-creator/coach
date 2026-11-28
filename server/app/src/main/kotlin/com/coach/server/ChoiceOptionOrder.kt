package com.coach.server

import kotlin.random.Random

/**
 * Puts the key of a multiple-choice item in an unpredictable position.
 *
 * The writer model tends to put the true option first: in the ECNM10119 runs of
 * 2026-11-27/28 the key sat in A or B for almost every item and in D for 3-5%.
 * A learner can spot that pattern without reading the options.
 *
 * The reorder runs after the blind check has agreed on the draft, so the checker
 * still answers in the writer's letter order and the stored item keeps exactly the
 * same option texts, reasons and citations — only their positions and letters change.
 *
 * Meta options ("all of the above", "none of the above", and their Chinese forms)
 * always stay at the end: moving them would make the item wrong rather than varied.
 *
 * The permutation is derived from the question's stable id, so regenerating the same
 * question produces the same order. Question ids repeat across exam points ("c1" in
 * every point), so callers seed with the exam point id as well.
 */
object ChoiceOptionOrder {
    private val letters = listOf("A", "B", "C", "D")

    private val tailOption = Regex(
        "^(?:" +
            "以上(?:(?:这)?(?:[一二三四五六七]|几)?个?(?:选项|各项|项|答案|说法))?(?:皆|全|都|均)(?:是|为|正确|对|错|错误|不对|非|不是)" +
            "|以上(?:(?:这)?(?:[一二三四五六七]|几)?个?(?:选项|各项|项|答案|说法))?(?:都|均)?(?:是|为)?(?:正确|对|错误|不对|错)" +
            "|(?:all|none|neither)oftheabove" +
            ")$",
    )

    /** True when the option is a meta choice that has to stay in last place. */
    fun isTailOption(text: String): Boolean = tailOption.matches(normalize(text))

    /**
     * Returns the same options re-lettered A, B, C, D in a seeded order.
     * Option payloads are untouched; only [DrillOptionDraft.letter] changes.
     */
    fun reorder(options: List<DrillOptionDraft>, seed: String): List<DrillOptionDraft> {
        if (options.size < 2) return options
        val (tail, body) = options.partition { isTailOption(it.text) }
        val ordered = body.toMutableList()
        val random = Random(fnv1a64(seed))
        for (index in ordered.lastIndex downTo 1) {
            val swap = random.nextInt(index + 1)
            val held = ordered[index]
            ordered[index] = ordered[swap]
            ordered[swap] = held
        }
        return (ordered + tail).mapIndexed { index, option ->
            option.copy(letter = letters.getOrElse(index) { option.letter })
        }
    }

    private fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    private fun fnv1a64(seed: String): Long {
        var hash = -0x340d631b7bdddcdbL
        for (byte in seed.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xffL)
            hash *= 0x100000001b3L
        }
        return hash
    }
}
