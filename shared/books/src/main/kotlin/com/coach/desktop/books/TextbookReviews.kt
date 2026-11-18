package com.coach.desktop.books

/** A source snapshot and its original wrong answer; retry answers never overwrite the lesson draft. */
internal data class LocalBookReview(
    val book: String, val lesson: String, val mode: QuestionMode,
    val bookTitle: String, val lessonTitle: String, val practice: Practice,
    val originalAnswer: String, val answer: PracticeAnswer = PracticeAnswer(), val completed: Boolean = false,
    val sources: Set<LocalReviewSource> = emptySet(), val dueDay: Long = 0,
    val history: List<LocalReviewAttempt> = emptyList(), val confidentStreak: Int = 0,
)

internal enum class LocalReviewSource(val bit: Int, val label: String) {
    WRONG(1, "答错"), GUESSED(2, "猜的"), SELF_CHECK(4, "还需复习"), DUE(8, "到期"),
}

internal data class LocalReviewAttempt(val number: Int, val kind: String, val answer: PracticeAnswer, val day: Long?)

internal fun PracticeAnswer.committed(mode: QuestionMode): Boolean = revealed && when (mode) {
    QuestionMode.CHOICE -> text.isNotBlank()
    QuestionMode.SHORT_ANSWER -> understood != null
    QuestionMode.NONE -> false
}

internal fun Practice.independentlyCorrect(mode: QuestionMode, answer: PracticeAnswer): Boolean =
    answer.committed(mode) && !answer.guessed && when (mode) {
        QuestionMode.CHOICE -> answer.text == this.answer
        QuestionMode.SHORT_ANSWER -> answer.understood == true
        QuestionMode.NONE -> false
    }

internal fun Practice.needsReview(mode: QuestionMode, answer: PracticeAnswer): Boolean =
    answer.committed(mode) && !independentlyCorrect(mode, answer)
