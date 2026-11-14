package com.coach.desktop.books

/**
 * A source snapshot and its original answer; retry answers never overwrite the lesson draft.
 *
 * [originalGuessed] keeps the fact that a right first answer was a guess: the text then equals the
 * solution, so the text alone cannot tell a lucky guess from real mastery.
 */
internal data class LocalBookReview(
    val book: String, val lesson: String, val mode: QuestionMode,
    val bookTitle: String, val lessonTitle: String, val practice: Practice,
    val originalAnswer: String, val originalGuessed: Boolean = false,
    val answer: PracticeAnswer = PracticeAnswer(), val completed: Boolean = false,
)

internal fun Practice.needsReview(mode: QuestionMode, answer: PracticeAnswer): Boolean =
    answer.revealed && when (mode) {
        // A chosen answer that matches the key still needs review when the learner marked it a guess.
        QuestionMode.CHOICE -> answer.text.isNotBlank() && (answer.guessed || answer.text != this.answer)
        QuestionMode.SHORT_ANSWER -> answer.understood == false
        QuestionMode.NONE -> false
    }
