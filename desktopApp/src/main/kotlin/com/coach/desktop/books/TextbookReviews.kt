package com.coach.desktop.books

/** A source snapshot and its original wrong answer; retry answers never overwrite the lesson draft. */
internal data class LocalBookReview(
    val book: String, val lesson: String, val mode: QuestionMode,
    val bookTitle: String, val lessonTitle: String, val practice: Practice,
    val originalAnswer: String, val answer: PracticeAnswer = PracticeAnswer(), val completed: Boolean = false,
)

internal fun Practice.needsReview(mode: QuestionMode, answer: PracticeAnswer): Boolean =
    answer.revealed && when (mode) {
        QuestionMode.CHOICE -> answer.text.isNotBlank() && answer.text != this.answer
        QuestionMode.SHORT_ANSWER -> answer.understood == false
        QuestionMode.NONE -> false
    }
