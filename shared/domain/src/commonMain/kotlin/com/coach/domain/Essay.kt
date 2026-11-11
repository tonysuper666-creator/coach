package com.coach.domain

import com.coach.domain.grade.nfkc

/** Timed essay drill. The client's live word count and the server's copy guard share one tokenizer. */
object Essay {
    const val WORD_TARGET = 300
    const val TIME_LIMIT_SEC = 25 * 60
    /** Late submissions within this window still count as on time (clock drift, slow network). */
    const val GRACE_SEC = 90
    /** Eight consecutive identical English words count as copying (same rule as the exam-overlap check). */
    const val COPY_RUN = 8

    /** Four fixed criteria, weighted for a 20-mark essay as 4/6/6/4. */
    val CRITERIA = listOf(
        "structure" to 0.2,
        "argument" to 0.3,
        "evidence" to 0.3,
        "example" to 0.2,
    )

    /** What the learner sees for each criterion id. */
    val LABELS = mapOf("structure" to "结构", "argument" to "论点", "evidence" to "证据", "example" to "例子")

    private val token = Regex("[a-z0-9]+(?:'[a-z0-9]+)*")

    /** Whitespace-separated tokens that contain a letter or digit, the way a word processor counts them. */
    fun wordCount(text: String): Int = text.split(' ', '\n', '\t', '\r').count { part -> part.any { it.isLetterOrDigit() } }

    fun words(text: String): List<String> =
        token.findAll(nfkc(text).lowercase().replace('’', '\'')).map { it.value }.toList()

    /** First run of [run] consecutive words that appears in both texts, or null. */
    fun sharedRun(text: String, other: String, run: Int = COPY_RUN): String? {
        val a = words(text)
        val b = words(other)
        if (a.size < run || b.size < run) return null
        val seen = (0..b.size - run).mapTo(HashSet()) { b.subList(it, it + run).joinToString(" ") }
        return (0..a.size - run).asSequence().map { a.subList(it, it + run).joinToString(" ") }.firstOrNull { it in seen }
    }
}
